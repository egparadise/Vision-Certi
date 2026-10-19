"""Certificate step-up after a legacy ID/password login (POST /api/vc/auth/certificate-step-up), the license
cross-check, and the certificate re-check at finalization. Isolated: SQLite + fake Vision-Certi."""
from __future__ import annotations

import datetime
import unittest
import uuid

from fastapi.testclient import TestClient
from sqlalchemy import func, select

from app.integrations.vision_certi import snapshot
from app.integrations.vision_certi.orm import VcAudit, VcReportApproval, VcSession
from app.integrations.vision_certi.service import APPROVED, COMMIT_REFUSED, VcError

from tests.vision_certi.support import Env
from tests.vision_certi.test_vc_api_and_client import make_app

DOCTOR = "11111111-2222-4333-8444-555555555555"
OTHER_DOCTOR = "99999999-2222-4333-8444-555555555555"


class StepUpTest(unittest.TestCase):
    def setUp(self):
        self.env = Env()
        self.env.add_user(1, doctor=False, group_level=99)  # link admin
        self.env.add_user(7)                                # doctor linked to DOCTOR (license LIC-7)
        self.env.add_user(8)                                # another doctor, linked to OTHER_DOCTOR
        self.env.add_user(9, doctor=False)                  # non-doctor staff
        self.env.link(7, DOCTOR)
        self.env.link(8, OTHER_DOCTOR)
        self.report_idx = self.env.add_study(100, assignee=7)
        self.http = TestClient(make_app(self.env))

    # -- helpers -------------------------------------------------------------------------------------------------

    def step_up(self, code, legacy_user=7, bearer="eyJhbGciOiJIUzI1NiJ9.legacy.jwt"):
        headers = {"Authorization": f"Bearer {bearer}"} if bearer else {}
        if legacy_user is not None:
            headers["X-Test-Legacy-User"] = str(legacy_user)
        return self.http.post("/api/vc/auth/certificate-step-up", json={"handoffCode": code}, headers=headers)

    def session_count(self):
        main, _ = self.env.sessions()
        try:
            return main.execute(select(func.count()).select_from(VcSession)).scalar()
        finally:
            main.close()

    def audits(self, action):
        main, _ = self.env.sessions()
        try:
            return [(a.outcome, a.detail_json) for a in
                    main.execute(select(VcAudit).where(VcAudit.action == action)).scalars()]
        finally:
            main.close()

    def assert_refused(self, response, status, code):
        self.assertEqual((response.status_code, response.json()["code"]), (status, code), response.text)
        self.assertNotIn("token", response.json())

    # -- authentication of the caller ---------------------------------------------------------------------------

    def test_unauthenticated_step_up_is_refused_without_redeeming_the_code(self):
        code = self.env.fake.issue_handoff(DOCTOR)
        self.assert_refused(self.step_up(code, legacy_user=None, bearer=None), 401, "VC_LOGIN_REQUIRED")
        self.assert_refused(self.step_up(code, legacy_user=None), 401, "VC_LOGIN_REQUIRED")
        self.assertIn(code, self.env.fake.handoffs)  # never sent to Vision-Certi
        self.assertEqual(self.session_count(), 0)
        # A certificate session cannot step up again (it already is one); the code stays unredeemed.
        token = self.env.login(DOCTOR)
        self.assert_refused(self.step_up(code, bearer=token), 409, "VC_ALREADY_CERTIFICATE_SESSION")
        self.assertIn(code, self.env.fake.handoffs)
        self.assertEqual(self.session_count(), 1)

    def test_same_user_step_up_issues_a_certificate_session_that_can_approve(self):
        r = self.step_up(self.env.fake.issue_handoff(DOCTOR))
        self.assertEqual(r.status_code, 200, r.text)
        self.assertEqual(r.headers["cache-control"], "no-store")
        body = r.json()
        self.assertTrue(body["stepUp"])
        self.assertEqual((body["user_data"]["user_idx"], body["vc"]["doctorId"]), (7, DOCTOR))
        token = body["token"]
        self.assertRegex(token, r"^vc1\.[A-Za-z0-9_-]{43}$")
        self.assertEqual(self.session_count(), 1)
        self.assertEqual([o for o, _ in self.audits("VC_STEP_UP")], ["SUCCESS"])
        # The step-up session works for the certificate-only approval flow, end to end.
        auth = {"Authorization": f"Bearer {token}"}
        current = self.http.get("/api/vc/study/100/report/current", headers=auth).json()
        r = self.http.post("/api/vc/study/100/report/prepare", headers=auth,
                           json={"expected_content_sha256": current["contentSha256"]})
        self.assertEqual(r.status_code, 201, r.text)
        approval_id = r.json()["approval"]["approvalId"]
        self.env.fake.approve(r.json()["visionCertiSignRequestId"])
        r = self.http.post(f"/api/vc/report-approvals/{approval_id}/finalize", headers=auth)
        self.assertEqual((r.status_code, r.json()["approval"]["state"]), (200, APPROVED), r.text)
        self.assertEqual(self.env.report_row(self.report_idx)["report_status"], "A")

    def test_certificate_of_another_user_is_rejected_and_no_session_is_stored(self):
        # User 8 is logged in with a password but presents doctor 7's certificate handoff.
        code = self.env.fake.issue_handoff(DOCTOR)
        self.assert_refused(self.step_up(code, legacy_user=8), 403, "VC_STEP_UP_IDENTITY_MISMATCH")
        self.assertEqual(self.session_count(), 0)
        self.assertEqual([o for o, _ in self.audits("VC_STEP_UP")], ["DENIED"])
        self.assertIn("IDENTITY_MISMATCH", self.audits("VC_STEP_UP")[0][1])
        # The code was consumed by the attempt: the rightful user cannot replay it either.
        self.assert_refused(self.step_up(code, legacy_user=7), 401, "VC_HANDOFF_INVALID")
        # Staff without a doctor account, and a user whose certificate doctor has no link at all.
        self.assert_refused(self.step_up(self.env.fake.issue_handoff(DOCTOR), legacy_user=9), 403,
                            "VC_STEP_UP_IDENTITY_MISMATCH")
        self.assert_refused(self.step_up(self.env.fake.issue_handoff(str(uuid.uuid4()))), 403,
                            "VC_DOCTOR_NOT_LINKED")
        # Browser-supplied identity fields are ignored.
        r = self.http.post("/api/vc/auth/certificate-step-up", headers={
            "Authorization": "Bearer eyJ.legacy", "X-Test-Legacy-User": "8"},
            json={"handoffCode": self.env.fake.issue_handoff(DOCTOR), "doctorId": OTHER_DOCTOR, "user_idx": 7})
        self.assert_refused(r, 403, "VC_STEP_UP_IDENTITY_MISMATCH")
        self.assertEqual(self.session_count(), 0)

    def test_stale_replayed_or_malformed_codes_are_refused(self):
        code = self.env.fake.issue_handoff(DOCTOR)
        self.assertEqual(self.step_up(code).status_code, 200)
        self.assert_refused(self.step_up(code), 401, "VC_HANDOFF_INVALID")             # replayed
        self.assert_refused(self.step_up(self.env.fake.issue_handoff(DOCTOR, session_minutes=-1)), 401,
                            "VC_HANDOFF_INVALID")                                       # stale VC session
        self.assert_refused(self.step_up("A" * 43), 401, "VC_HANDOFF_INVALID")         # unknown
        calls = len(self.env.fake.calls)
        self.assert_refused(self.step_up("short"), 401, "VC_HANDOFF_INVALID")          # malformed: no network
        self.assertEqual(len(self.env.fake.calls), calls)
        self.env.fake.down = True
        r = self.step_up(self.env.fake.issue_handoff(DOCTOR))
        self.assert_refused(r, 503, "VC_UNAVAILABLE")
        self.assertTrue(r.json()["retryable"])
        self.assertEqual(self.session_count(), 1)

    def test_inactive_or_non_doctor_local_account_gets_no_session(self):
        self.env.sql("UPDATE mgr.pacs_users_view SET user_status = 'D' WHERE user_idx = 7")
        self.assert_refused(self.step_up(self.env.fake.issue_handoff(DOCTOR)), 403, "VC_LOCAL_USER_INACTIVE")
        self.env.sql("UPDATE mgr.pacs_users_view SET user_status = 'A', doctor_idx = NULL WHERE user_idx = 7")
        self.assert_refused(self.step_up(self.env.fake.issue_handoff(DOCTOR)), 403, "VC_LOCAL_USER_NOT_DOCTOR")
        # Vision-Certi reports the doctor as not ACTIVE / not an active signer.
        self.env.sql("UPDATE mgr.pacs_users_view SET doctor_idx = 7 WHERE user_idx = 7")
        code = self.env.fake.issue_handoff(DOCTOR)
        self.env.fake.handoffs[code]["doctorStatus"] = "SUSPENDED"
        self.assert_refused(self.step_up(code), 401, "VC_HANDOFF_INVALID")
        code = self.env.fake.issue_handoff(DOCTOR)
        self.env.fake.handoffs[code]["activeSigner"] = False
        self.assert_refused(self.step_up(code), 401, "VC_HANDOFF_INVALID")
        # Link revoked by the admin.
        self.env.call(self.env.service.revoke_link, 1, 1, "license suspended")
        self.assert_refused(self.step_up(self.env.fake.issue_handoff(DOCTOR)), 403, "VC_DOCTOR_NOT_LINKED")
        self.assertEqual(self.session_count(), 0)

    def test_disabled_integration_answers_without_redeeming(self):
        env = Env(enabled=False)
        http = TestClient(make_app(env))
        r = http.post("/api/vc/auth/certificate-step-up", json={"handoffCode": "A" * 43},
                      headers={"Authorization": "Bearer eyJ.legacy", "X-Test-Legacy-User": "7"})
        self.assertEqual((r.status_code, r.json()["code"]), (404, "VC_INTEGRATION_DISABLED"))
        self.assertEqual(env.fake.calls, [])


class LicenseCrossCheckTest(unittest.TestCase):
    """Vision-Certi's admin-verified license number is a required deny condition, never an authenticator."""

    def setUp(self):
        self.env = Env()
        self.env.add_user(1, doctor=False, group_level=99)
        self.env.add_user(7)  # local license "LIC-7"
        self.env.link(7, DOCTOR)

    def login(self, **handoff):
        return self.env.call(self.env.service.certificate_login, self.env.fake.issue_handoff(DOCTOR, **handoff), None)

    def step_up(self, **handoff):
        return self.env.call(self.env.service.certificate_step_up, 7, self.env.fake.issue_handoff(DOCTOR, **handoff),
                             None)

    def sessions(self):
        main, _ = self.env.sessions()
        try:
            return main.execute(select(func.count()).select_from(VcSession)).scalar()
        finally:
            main.close()

    def assert_mismatch(self, flow, **handoff):
        with self.assertRaises(VcError) as ctx:
            flow(**handoff)
        self.assertEqual((ctx.exception.status, ctx.exception.code), (403, "VC_LICENSE_MISMATCH"))

    def test_matching_license_is_accepted_and_audited(self):
        self.login(license_number="7")
        self.step_up(license_number=" 7 ")
        main, _ = self.env.sessions()
        details = [a.detail_json for a in main.execute(select(VcAudit).where(VcAudit.outcome == "SUCCESS",
                                                                             VcAudit.target_type == "VC_SESSION"))
                   .scalars()]
        self.assertEqual(len(details), 2)
        self.assertTrue(all('"licenseChecked": true' in d for d in details))

    def test_mismatch_or_missing_local_license_is_refused_without_a_session(self):
        for flow in (self.login, self.step_up):
            self.assert_mismatch(flow, license_number="12345")
        self.env.sql("UPDATE mgr.pacs_users_view SET doctor_license_number = NULL WHERE user_idx = 7")
        self.assert_mismatch(self.step_up, license_number="7")
        self.assertEqual(self.sessions(), 0)
        main, _ = self.env.sessions()
        denied = main.execute(select(func.count()).select_from(VcAudit).where(
            VcAudit.outcome == "DENIED", VcAudit.detail_json.like("%LICENSE_MISMATCH%"))).scalar()
        self.assertEqual(denied, 3)

    def test_absent_or_malformed_vision_certi_license_fails_closed(self):
        # No backward bypass: an identity without the admin-verified license never gets a session.
        for flow in (self.login, self.step_up):
            self.assert_mismatch(flow, omit_license=True)
            for bad in ("", "  ", "7a", "LIC-7", "1" * 21, 7, None):
                if bad is None:
                    code = self.env.fake.issue_handoff(DOCTOR)
                    self.env.fake.handoffs[code]["doctorLicenseNumber"] = None
                    with self.assertRaises(VcError) as ctx:
                        if flow == self.login:
                            self.env.call(self.env.service.certificate_login, code, None)
                        else:
                            self.env.call(self.env.service.certificate_step_up, 7, code, None)
                    self.assertEqual(ctx.exception.code, "VC_LICENSE_MISMATCH")
                else:
                    self.assert_mismatch(flow, license_number=bad)
        self.assertEqual(self.sessions(), 0)


class FinalizationRecheckTest(unittest.TestCase):
    """A/RA after a step-up stays fail-closed: current assignment and the signing certificate are re-checked."""

    def setUp(self):
        self.env = Env()
        self.env.add_user(1, doctor=False, group_level=99)
        self.env.add_user(7)
        self.env.add_user(8)
        self.env.link(7, DOCTOR)
        self.report_idx = self.env.add_study(100, assignee=7)
        self.token, _, _ = self.env.call(self.env.service.certificate_step_up, 7,
                                         self.env.fake.issue_handoff(DOCTOR), None)
        self.p = self.env.principal(self.token)

    def prepare(self):
        main, _ = self.env.sessions()
        sha = snapshot.content_sha256(self.env.port.get_report_for_study(main, 100))
        main.close()
        approval, _ = self.env.call(self.env.service.prepare, self.p, 100, sha)
        return approval

    def finalize(self, approval_id):
        return self.env.call(self.env.service.finalize, approval_id, principal=self.p)

    def state(self):
        main, _ = self.env.sessions()
        try:
            return main.execute(select(VcReportApproval)).scalar_one().state
        finally:
            main.close()

    def test_signature_by_a_different_certificate_enrollment_is_not_committed(self):
        approval = self.prepare()
        self.env.fake.evidence_enrollment = str(uuid.uuid4())  # re-enrolled certificate signed it
        self.env.fake.approve(approval.vc_sign_request_id)
        with self.assertRaises(VcError) as ctx:
            self.finalize(approval.approval_id)
        self.assertEqual((ctx.exception.code, ctx.exception.data["reason"]), ("VC_COMMIT_REFUSED", "CERTIFICATE_CHANGED"))
        self.assertEqual(self.state(), COMMIT_REFUSED)
        self.assertEqual(self.env.report_row(self.report_idx)["report_status"], "R")

    def test_reassignment_after_signature_is_not_committed(self):
        approval = self.prepare()
        self.env.fake.approve(approval.vc_sign_request_id)
        self.env.sql("UPDATE pacs_study SET user_idx = 8 WHERE study_idx = 100")
        with self.assertRaises(VcError) as ctx:
            self.finalize(approval.approval_id)
        self.assertEqual(ctx.exception.data["reason"], "NOT_ASSIGNEE")
        self.assertEqual(self.env.report_row(self.report_idx)["report_status"], "R")

    def test_expired_step_up_session_cannot_finalize(self):
        approval = self.prepare()
        self.env.fake.approve(approval.vc_sign_request_id)
        self.env.clock.now += datetime.timedelta(minutes=61)
        with self.assertRaises(VcError) as ctx:  # every finalize request resolves the session first
            self.env.principal(self.token)
        self.assertEqual(ctx.exception.code, "VC_SESSION_REQUIRED")
        self.assertEqual(self.env.report_row(self.report_idx)["report_status"], "R")


if __name__ == "__main__":
    unittest.main()
