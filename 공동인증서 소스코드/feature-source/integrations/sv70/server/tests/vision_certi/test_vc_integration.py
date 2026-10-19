"""SV70 Vision-Certi integration tests (isolated: SQLite + fake Vision-Certi; no legacy startup, no network)."""
from __future__ import annotations

import base64
import datetime
import hashlib
import json
import os
import tempfile
import unittest
import uuid

from sqlalchemy import select

from app.integrations.vision_certi import ecdsa_p256, hmac_auth, snapshot
from app.integrations.vision_certi.config import DISABLED, ENABLED, MISCONFIGURED, load_config
from app.integrations.vision_certi.orm import VcAudit, VcReportApproval, VcSession
from app.integrations.vision_certi.service import (APPROVED, COMMIT_REFUSED, PENDING_SIGNATURE, PREPARE_RETRY,
                                                   REMOTE_FAILED, SIGNED_REMOTE, SUPERSEDED,
                                                   SUPERSEDED_AFTER_SIGNATURE, VcError, guard_legacy_report_delete,
                                                   guard_legacy_report_write, token_sha256)

from tests.vision_certi.support import (JAVA_MSG, JAVA_SIG, JAVA_SPKI, SECRET, TENANT, Env)

DOCTOR = str(uuid.UUID("11111111-2222-4333-8444-555555555555"))


def code_of(ctx) -> str:
    return ctx.exception.code


class ConfigTest(unittest.TestCase):
    def _secret_file(self, data=SECRET):
        path = os.path.join(tempfile.mkdtemp(), "s")
        with open(path, "wb") as f:
            f.write(data)
        return path

    def base_env(self, **over):
        env = {"VISION_CERTI_INTEGRATION_ENABLED": "true", "VISION_CERTI_BASE_URL": "https://vc.example",
               "VISION_CERTI_CLIENT_ID": "pacs-sv70", "VISION_CERTI_CLIENT_SECRET_FILE": self._secret_file(),
               "VISION_CERTI_TENANT_ID": TENANT}
        env.update(over)
        return env

    def test_disabled_by_default_and_enabled_when_complete(self):
        self.assertEqual(load_config({}).state, DISABLED)
        self.assertFalse(load_config({}).guard_active)
        config = load_config(self.base_env(), super_admins=[227])
        self.assertEqual(config.state, ENABLED, config.problems)
        self.assertEqual(config.link_admin_user_idx, frozenset({227}))
        self.assertNotIn("secret", repr(config).replace("secret=", ""))  # secret excluded from repr
        self.assertNotIn(SECRET.decode(), repr(config))

    def test_enabled_but_misconfigured_fails_closed(self):
        cases = [
            {"VISION_CERTI_BASE_URL": "http://vc.example"},
            {"VISION_CERTI_BASE_URL": "https://user:pw@vc.example"},
            {"VISION_CERTI_BASE_URL": "https://vc.example/v1?x=1"},
            {"VISION_CERTI_BASE_URL": "http://localhost:8080"},  # loopback http needs the explicit flag
            {"VISION_CERTI_CLIENT_SECRET_FILE": ""},
            {"VISION_CERTI_CLIENT_SECRET_FILE": self._secret_file(b"short")},
            {"VISION_CERTI_TENANT_ID": ""},
            {"VISION_CERTI_CLIENT_ID": "bad id!"},
            {"VISION_CERTI_READ_TIMEOUT": "600"},
        ]
        for over in cases:
            config = load_config(self.base_env(**over))
            self.assertEqual(config.state, MISCONFIGURED, over)
            self.assertTrue(config.guard_active)
            self.assertFalse(config.operational)
            self.assertEqual(config.secret, b"")
        local = load_config(self.base_env(VISION_CERTI_BASE_URL="http://127.0.0.1:8080",
                                          VISION_CERTI_ALLOW_LOCALHOST_HTTP="true"))
        self.assertEqual(local.state, ENABLED, local.problems)


class CryptoAndSigningTest(unittest.TestCase):
    def test_hmac_matches_the_java_cross_language_vector(self):
        sha = hashlib.sha256(b'{"handoffCode":"x"}').hexdigest()
        canonical = hmac_auth.canonical("vision-certi:pacs-integration:v1", "pacs-sv70", "POST",
                                        "/v1/integrations/pacs/handoffs/redeem", None, "1760000000",
                                        "AAAAAAAAAAAAAAAAAAAAAA", sha)
        # Same constant as Java CrossLanguageVector.EXPECTED_SIGNATURE.
        self.assertEqual(hmac_auth.sign(b"vector-secret-vector-secret-vector-secret", canonical),
                         "8wKYlq2sp3lCKkH4fUcSa3-JHzyNRgePOMIhWM0ndOc")

    def test_ecdsa_verifies_a_java_signature_and_rejects_tampering(self):
        ecdsa_p256._verify_pure(base64.b64decode(JAVA_SPKI), JAVA_MSG, base64.b64decode(JAVA_SIG))
        ecdsa_p256.verify(JAVA_SPKI, JAVA_MSG, JAVA_SIG)
        with self.assertRaises(ecdsa_p256.InvalidSignature):
            ecdsa_p256.verify(JAVA_SPKI, JAVA_MSG + b" ", JAVA_SIG)
        bad_sig = bytearray(base64.b64decode(JAVA_SIG))
        bad_sig[-1] ^= 1
        with self.assertRaises(ecdsa_p256.InvalidSignature):
            ecdsa_p256.verify(JAVA_SPKI, JAVA_MSG, base64.b64encode(bytes(bad_sig)).decode())
        with self.assertRaises(ecdsa_p256.InvalidSignature):
            ecdsa_p256.verify(base64.b64encode(b"\x30\x00").decode(), JAVA_MSG, JAVA_SIG)
        with self.assertRaises(ecdsa_p256.InvalidSignature):
            ecdsa_p256.verify("not base64!!", JAVA_MSG, JAVA_SIG)

    def test_snapshot_bytes_are_deterministic(self):
        a = snapshot.canonical_json({"b": 1, "a": "판독"})
        self.assertEqual(a, snapshot.canonical_json({"a": "판독", "b": 1}))
        self.assertEqual(a, '{"a":"판독","b":1}'.encode("utf-8"))


class LoginTest(unittest.TestCase):
    def setUp(self):
        self.env = Env()
        self.env.add_user(1, doctor=False, group_level=99)  # link admin
        self.env.add_user(7, email="kim@example.com")
        self.env.link(7, DOCTOR)

    def test_certificate_login_issues_a_short_hashed_pacs_session(self):
        token = self.env.login(DOCTOR, session_minutes=15)
        self.assertRegex(token, r"^vc1\.[A-Za-z0-9_-]{43}$")
        main, _ = self.env.sessions()
        rows = main.execute(select(VcSession)).scalars().all()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0].token_sha256, token_sha256(token))
        # Expiry capped by the Vision-Certi session (15 min) rather than the 60 min maximum.
        self.assertEqual(rows[0].expires_at, self.env.clock() + datetime.timedelta(minutes=15))
        # The token appears nowhere in the database (sessions or audit).
        dump = json.dumps([str(r.__dict__) for r in main.execute(select(VcAudit)).scalars()])
        self.assertNotIn(token, dump)
        self.assertNotIn(token[4:], rows[0].token_sha256)
        p = self.env.principal(token)
        self.assertEqual((p.user_idx, p.doctor_id, p.tenant_id), (7, DOCTOR, TENANT))

    def test_login_refusals(self):
        fake = self.env.fake
        cases = [
            (lambda: "x" * 10, 401, "VC_HANDOFF_INVALID"),                       # malformed: no network call
            (lambda: "A" * 43, 401, "VC_HANDOFF_INVALID"),                       # unknown to Vision-Certi
            (lambda: fake.issue_handoff(str(uuid.uuid4())), 403, "VC_DOCTOR_NOT_LINKED"),
            (lambda: fake.issue_handoff(DOCTOR, tenant="demo-hospital-b"), 401, "VC_HANDOFF_INVALID"),
            (lambda: fake.issue_handoff(DOCTOR, mode="GOOGLE"), 401, "VC_HANDOFF_INVALID"),
            (lambda: fake.issue_handoff(DOCTOR, mode="MOCK_CERTIFICATE"), 401, "VC_HANDOFF_INVALID"),
            (lambda: fake.issue_handoff(DOCTOR, session_minutes=-1), 401, "VC_HANDOFF_INVALID"),
        ]
        for make, status, code in cases:
            with self.assertRaises(VcError) as ctx:
                self.env.call(self.env.service.certificate_login, make(), "127.0.0.1")
            self.assertEqual((ctx.exception.status, ctx.exception.code), (status, code))
        before = len(fake.calls)
        with self.assertRaises(VcError):
            self.env.call(self.env.service.certificate_login, "bad", None)
        self.assertEqual(len(fake.calls), before)  # malformed code never leaves the server
        fake.down = True
        with self.assertRaises(VcError) as ctx:
            self.env.call(self.env.service.certificate_login, fake.issue_handoff(DOCTOR), None)
        self.assertEqual((ctx.exception.status, ctx.exception.code, ctx.exception.retryable),
                         (503, "VC_UNAVAILABLE", True))

    def test_no_email_matching_and_local_account_revocation(self):
        # A same-e-mail user with no link is never matched.
        self.env.add_user(8, email="kim@example.com")
        other = str(uuid.uuid4())
        with self.assertRaises(VcError) as ctx:
            self.env.call(self.env.service.certificate_login, self.env.fake.issue_handoff(other), None)
        self.assertEqual(code_of(ctx), "VC_DOCTOR_NOT_LINKED")
        token = self.env.login(DOCTOR)
        # Local account blocked after login → session refused on the next request.
        self.env.sql("UPDATE mgr.pacs_users_view SET user_status = 'D' WHERE user_idx = 7")
        with self.assertRaises(VcError) as ctx:
            self.env.principal(token)
        self.assertEqual(code_of(ctx), "VC_LOCAL_USER_INACTIVE")
        with self.assertRaises(VcError) as ctx:
            self.env.call(self.env.service.certificate_login, self.env.fake.issue_handoff(DOCTOR), None)
        self.assertEqual(code_of(ctx), "VC_LOCAL_USER_INACTIVE")

    def test_session_expiry_logout_link_revocation_and_tampering(self):
        token = self.env.login(DOCTOR)
        for bad in (token[:-1] + ("A" if token[-1] != "A" else "B"), token.replace("vc1.", "vc2."), "", None):
            with self.assertRaises(VcError):
                self.env.call(self.env.service.resolve_session, "Bearer " + (bad or ""))
        self.env.clock.now += datetime.timedelta(minutes=61)
        with self.assertRaises(VcError) as ctx:
            self.env.principal(token)
        self.assertEqual(code_of(ctx), "VC_SESSION_REQUIRED")
        self.env.clock.now -= datetime.timedelta(minutes=61)
        p = self.env.principal(token)
        self.env.call(lambda m, g: self.env.service.logout(m, p))
        with self.assertRaises(VcError):
            self.env.principal(token)
        token2 = self.env.login(DOCTOR)
        self.env.call(self.env.service.revoke_link, 1, 1, "license expired")
        with self.assertRaises(VcError):
            self.env.principal(token2)
        with self.assertRaises(VcError) as ctx:
            self.env.call(self.env.service.certificate_login, self.env.fake.issue_handoff(DOCTOR), None)
        self.assertEqual(code_of(ctx), "VC_DOCTOR_NOT_LINKED")

    def test_link_administration_is_explicit_allowlisted_unique_and_append_only(self):
        self.env.add_user(50, doctor=False, group_level=99)  # high level but not on the allowlist
        self.env.add_user(9)
        self.env.add_user(10, doctor=False)
        with self.assertRaises(VcError) as ctx:
            self.env.call(self.env.service.create_link, 50, {"user_idx": 9, "vc_tenant_id": TENANT,
                                                             "vc_doctor_id": str(uuid.uuid4()), "evidence_ref": "e"})
        self.assertEqual(code_of(ctx), "VC_LINK_ADMIN_REQUIRED")
        for body, code in [
            ({"user_idx": 9, "vc_tenant_id": TENANT, "vc_doctor_id": DOCTOR, "evidence_ref": "e"}, "VC_LINK_CONFLICT"),
            ({"user_idx": 7, "vc_tenant_id": TENANT, "vc_doctor_id": str(uuid.uuid4()), "evidence_ref": "e"},
             "VC_LINK_CONFLICT"),
            ({"user_idx": 10, "vc_tenant_id": TENANT, "vc_doctor_id": str(uuid.uuid4()), "evidence_ref": "e"},
             "VC_LOCAL_USER_NOT_DOCTOR"),
            ({"user_idx": 9, "vc_tenant_id": "other", "vc_doctor_id": str(uuid.uuid4()), "evidence_ref": "e"},
             "VC_TENANT_NOT_CONFIGURED"),
            ({"user_idx": 9, "vc_tenant_id": TENANT, "vc_doctor_id": "not-a-uuid", "evidence_ref": "e"},
             "VC_VALIDATION_FAILED"),
            ({"user_idx": 9, "vc_tenant_id": TENANT, "vc_doctor_id": str(uuid.uuid4()), "evidence_ref": ""},
             "VC_VALIDATION_FAILED"),
        ]:
            with self.assertRaises(VcError) as ctx:
                self.env.call(self.env.service.create_link, 1, body)
            self.assertEqual(code_of(ctx), code, body)
        self.env.call(self.env.service.revoke_link, 1, 1, "moved")
        relinked = self.env.call(self.env.service.create_link, 1, {"user_idx": 9, "vc_tenant_id": TENANT,
                                                                   "vc_doctor_id": DOCTOR, "evidence_ref": "e2"})
        history = self.env.call(self.env.service.list_links, 1)
        self.assertEqual([h["status"] for h in history], ["ACTIVE", "REVOKED"])
        self.assertEqual(relinked["userIdx"], 9)


class ReportApprovalTest(unittest.TestCase):
    def setUp(self):
        self.env = Env()
        self.env.add_user(1, doctor=False, group_level=99)
        self.env.add_user(7)
        self.env.add_user(8)
        self.env.link(7, DOCTOR)
        self.doctor8 = str(uuid.uuid4())
        self.env.link(8, self.doctor8)
        self.report_idx = self.env.add_study(100, assignee=7)
        self.token = self.env.login(DOCTOR)
        self.p = self.env.principal(self.token)

    def content_sha(self):
        main, _ = self.env.sessions()
        return snapshot.content_sha256(self.env.port.get_report_for_study(main, 100))

    def prepare(self, principal=None, expected=None):
        return self.env.call(self.env.service.prepare, principal or self.p, 100,
                             expected if expected is not None else self.content_sha())

    def finalize(self, approval_id, principal=None):
        return self.env.call(self.env.service.finalize, approval_id, principal=principal or self.p)

    def test_happy_path_signs_db_snapshot_and_commits_a_with_evidence(self):
        approval, status = self.prepare()
        self.assertEqual((status, approval.state, approval.revision, approval.target_status),
                         (201, PENDING_SIGNATURE, 1, "A"))
        doc = json.loads(approval.snapshot_json)
        self.assertEqual(doc["schema"], snapshot.SCHEMA)
        self.assertEqual((doc["tenantId"], doc["doctorId"]), (TENANT, DOCTOR))
        self.assertEqual(doc["report"]["reading"], "No acute abnormality.")  # from the DB, not the browser
        self.assertEqual(doc["study"]["patientName"], "홍길동")
        self.assertEqual(approval.snapshot_sha256, hashlib.sha256(approval.snapshot_json.encode()).hexdigest())
        sent = self.env.fake.requests[approval.vc_sign_request_id]["payload"]
        self.assertEqual(base64.b64decode(sent["contentBase64"]), approval.snapshot_json.encode("utf-8"))
        self.assertEqual(self.env.report_row(self.report_idx)["report_status"], "R")  # nothing approved yet

        self.env.fake.approve(approval.vc_sign_request_id)
        done, status = self.finalize(approval.approval_id)
        self.assertEqual((status, done.state), (200, APPROVED))
        row = self.env.report_row(self.report_idx)
        self.assertEqual((row["report_status"], row["report_approve_idx"]), ("A", 7))
        self.assertIsNotNone(done.vc_signature_value)
        self.assertEqual(done.vc_public_key_spki, self.env.fake.signer.spki_b64)
        # Idempotent re-finalize: no second remote lookup or write.
        calls = len(self.env.fake.calls)
        again, status = self.finalize(approval.approval_id)
        self.assertEqual((status, again.state, len(self.env.fake.calls)), (200, APPROVED, calls))
        view = self.env.service.approval_view(again)
        self.assertTrue(view["approved"])
        self.assertEqual(view["signature"]["certificateFingerprintSha256"], "ab" * 32)
        with self.assertRaises(VcError) as ctx:
            self.prepare()
        self.assertEqual(code_of(ctx), "VC_ALREADY_APPROVED")

    def test_simulated_signer_cannot_finalize_a_real_pacs_report(self):
        approval, _ = self.prepare()
        self.env.fake.approve(approval.vc_sign_request_id)
        evidence = self.env.fake.requests[approval.vc_sign_request_id]['signature']['doctorEvidence']
        evidence['signingBackend'] = 'DEV_SIMULATED'
        with self.assertRaises(VcError) as ctx:
            self.finalize(approval.approval_id)
        self.assertEqual(code_of(ctx), 'VC_EVIDENCE_INVALID')
        self.assertEqual(self.env.report_row(self.report_idx)['report_status'], 'R')

    def test_permission_wrong_user_and_stale_content_are_refused(self):
        p8 = self.env.principal(self.env.login(self.doctor8))
        with self.assertRaises(VcError) as ctx:
            self.prepare(principal=p8)
        self.assertEqual(code_of(ctx), "VC_NOT_ASSIGNEE")
        with self.assertRaises(VcError) as ctx:
            self.prepare(expected="0" * 64)
        self.assertEqual(code_of(ctx), "VC_REPORT_CHANGED")
        approval, _ = self.prepare()
        self.env.fake.approve(approval.vc_sign_request_id)
        with self.assertRaises(VcError) as ctx:
            self.finalize(approval.approval_id, principal=p8)
        self.assertEqual(code_of(ctx), "VC_NOT_APPROVAL_OWNER")
        with self.assertRaises(VcError) as ctx:
            self.finalize(str(uuid.uuid4()))
        self.assertEqual(code_of(ctx), "VC_APPROVAL_NOT_FOUND")
        self.assertEqual(self.env.report_row(self.report_idx)["report_status"], "R")

    def test_not_yet_signed_and_network_failures_never_approve(self):
        self.env.fake.down = True
        with self.assertRaises(VcError) as ctx:
            self.prepare()
        self.assertEqual((ctx.exception.status, ctx.exception.code), (503, "VC_UNAVAILABLE"))
        main, _ = self.env.sessions()
        pending = main.execute(select(VcReportApproval)).scalar_one()
        self.assertEqual(pending.state, PREPARE_RETRY)
        self.env.fake.down = False
        approval, _ = self.prepare()  # same content → same approval and idempotency key, no new revision
        self.assertEqual((approval.approval_id, approval.revision, approval.state),
                         (pending.approval_id, 1, PENDING_SIGNATURE))
        with self.assertRaises(VcError) as ctx:
            self.finalize(approval.approval_id)
        self.assertEqual(code_of(ctx), "VC_NOT_YET_SIGNED")
        self.env.fake.approve(approval.vc_sign_request_id)
        self.env.fake.fail_next = 1  # timeout while fetching the result
        with self.assertRaises(VcError) as ctx:
            self.finalize(approval.approval_id)
        self.assertEqual((ctx.exception.code, ctx.exception.data["approval"]["approved"]), ("VC_UNAVAILABLE", False))
        self.assertEqual(self.env.report_row(self.report_idx)["report_status"], "R")
        done, _ = self.finalize(approval.approval_id)
        self.assertEqual(done.state, APPROVED)
        self.assertEqual(len(self.env.fake.requests), 1)  # one remote sign request in total

    def test_remote_signed_but_local_commit_failure_is_retryable_and_never_shown_approved(self):
        approval, _ = self.prepare()
        self.env.fake.approve(approval.vc_sign_request_id)
        original = self.env.port.apply_approval
        failures = {"left": 1}

        def flaky(session, *args, **kwargs):
            if failures["left"]:
                failures["left"] -= 1
                raise RuntimeError("deadlock")
            return original(session, *args, **kwargs)
        self.env.port.apply_approval = flaky
        with self.assertRaises(VcError) as ctx:
            self.finalize(approval.approval_id)
        self.assertEqual((ctx.exception.code, ctx.exception.retryable), ("VC_LOCAL_COMMIT_FAILED", True))
        self.assertEqual(ctx.exception.data["approval"]["state"], SIGNED_REMOTE)
        self.assertFalse(ctx.exception.data["approval"]["approved"])
        self.assertEqual(self.env.report_row(self.report_idx)["report_status"], "R")
        # Reconciliation by the link admin completes it without a new signature.
        results = self.env.call(self.env.service.reconcile, 1)
        self.assertEqual(results, [{"approvalId": approval.approval_id, "state": APPROVED, "code": None}])
        self.assertEqual(self.env.report_row(self.report_idx)["report_status"], "A")
        self.assertEqual(len(self.env.fake.requests), 1)

    def test_report_tampered_after_signature_is_not_approved_and_evidence_is_kept(self):
        approval, _ = self.prepare()
        self.env.fake.approve(approval.vc_sign_request_id)
        self.env.sql("UPDATE pacs_study_report SET report_reading = 'edited after signing' WHERE report_idx = :r",
                     r=self.report_idx)
        with self.assertRaises(VcError) as ctx:
            self.finalize(approval.approval_id)
        self.assertEqual(code_of(ctx), "VC_REPORT_CHANGED_AFTER_SIGNATURE")
        main, _ = self.env.sessions()
        row = main.execute(select(VcReportApproval)).scalar_one()
        self.assertEqual(row.state, SUPERSEDED_AFTER_SIGNATURE)
        self.assertIsNotNone(row.vc_signature_value)
        self.assertEqual(self.env.report_row(self.report_idx)["report_status"], "R")

    def test_tampered_or_mismatched_evidence_is_rejected(self):
        for attribute in ("tamper_signature", "tamper_document_sha"):
            env = self.env
            env.sql("UPDATE pacs_study_report SET report_reading = :x WHERE report_idx = :r", x=attribute,
                    r=self.report_idx)
            approval, _ = self.prepare()
            setattr(env.fake, attribute, True)
            env.fake.approve(approval.vc_sign_request_id)
            setattr(env.fake, attribute, False)
            with self.assertRaises(VcError) as ctx:
                self.finalize(approval.approval_id)
            self.assertEqual(code_of(ctx), "VC_EVIDENCE_INVALID")
            main, _ = env.sessions()
            self.assertEqual(main.execute(select(VcReportApproval).where(
                VcReportApproval.approval_id == approval.approval_id)).scalar_one().state, REMOTE_FAILED)
            self.assertEqual(env.report_row(self.report_idx)["report_status"], "R")

    def test_revision_creates_a_new_approval_and_keeps_old_evidence(self):
        first, _ = self.prepare()
        self.env.fake.approve(first.vc_sign_request_id)
        self.finalize(first.approval_id)
        # Edit through the legacy route (guard allows R/RR only) → new revision needs a new signature.
        data = {"report_status": "RR", "report_approve_idx": 99}
        guard_legacy_report_write(self.env.config, data)
        self.assertNotIn("report_approve_idx", data)
        self.env.sql("UPDATE pacs_study_report SET report_status = 'RR', report_reading = 'Addendum: nodule.' "
                     "WHERE report_idx = :r", r=self.report_idx)
        second, _ = self.prepare()
        self.assertEqual((second.revision, second.target_status, second.supersedes_approval_id),
                         (2, "RA", first.approval_id))
        self.assertEqual(json.loads(second.snapshot_json)["report"]["priorSignedSnapshotSha256"],
                         first.snapshot_sha256)
        # A third prepare with different content supersedes the unsigned second one.
        self.env.sql("UPDATE pacs_study_report SET report_reading = 'Addendum v2.' WHERE report_idx = :r",
                     r=self.report_idx)
        third, _ = self.prepare()
        main, _ = self.env.sessions()
        states = {a.revision: a.state for a in main.execute(select(VcReportApproval)).scalars()}
        self.assertEqual(states, {1: APPROVED, 2: SUPERSEDED, 3: PENDING_SIGNATURE})
        self.env.fake.approve(third.vc_sign_request_id)
        done, _ = self.finalize(third.approval_id)
        self.assertEqual(done.state, APPROVED)
        self.assertEqual(self.env.report_row(self.report_idx)["report_status"], "RA")
        with self.assertRaises(VcError) as ctx:
            self.finalize(second.approval_id)
        self.assertEqual(code_of(ctx), "VC_APPROVAL_CLOSED")
        history = self.env.call(self.env.service.list_for_study, 7, 100)
        self.assertEqual([h["revision"] for h in history], [3, 2, 1])

    def test_link_revoked_or_account_blocked_between_signature_and_commit(self):
        approval, _ = self.prepare()
        self.env.fake.approve(approval.vc_sign_request_id)
        self.env.call(self.env.service.revoke_link, 1, 1, "suspended")
        with self.assertRaises(VcError):  # the doctor's PACS session ended with the link
            self.env.principal(self.token)
        with self.assertRaises(VcError) as ctx:
            self.env.call(self.env.service.finalize, approval.approval_id, admin_user_idx=1)
        self.assertEqual(code_of(ctx), "VC_COMMIT_REFUSED")
        main, _ = self.env.sessions()
        self.assertEqual(main.execute(select(VcReportApproval)).scalar_one().state, COMMIT_REFUSED)
        self.assertEqual(self.env.report_row(self.report_idx)["report_status"], "R")

    def test_evidence_views_require_study_access(self):
        approval, _ = self.prepare()
        self.env.add_user(30, doctor=False, group_level=10)
        with self.assertRaises(VcError) as ctx:
            self.env.call(self.env.service.get_approval_for_reader, 30, approval.approval_id)
        self.assertEqual(code_of(ctx), "VC_APPROVAL_NOT_FOUND")
        self.assertEqual(self.env.call(self.env.service.get_approval_for_reader, 7, approval.approval_id).approval_id,
                         approval.approval_id)
        self.assertEqual(len(self.env.call(self.env.service.list_for_study, 1, 100)), 1)


class LegacyGuardTest(unittest.TestCase):
    def test_disabled_integration_keeps_legacy_behaviour(self):
        env = Env(enabled=False)
        data = {"report_status": "A", "report_approve_idx": 5}
        guard_legacy_report_write(env.config, data)
        self.assertEqual(data, {"report_status": "A", "report_approve_idx": 5})
        main, _ = env.sessions()
        guard_legacy_report_delete(env.config, main, 1)

    def test_enabled_or_misconfigured_blocks_direct_a_ra_and_signed_report_delete(self):
        for env in (Env(), Env(extra_env={"VISION_CERTI_BASE_URL": "http://insecure.example"})):
            for status in ("A", "RA", "a"):
                with self.assertRaises(VcError) as ctx:
                    guard_legacy_report_write(env.config, {"report_status": status})
                self.assertEqual(code_of(ctx), "VC_SIGNATURE_REQUIRED")
            data = {"report_status": "R", "report_approve_idx": 5, "report_approve_datetime": "x"}
            guard_legacy_report_write(env.config, data)
            self.assertEqual(data, {"report_status": "R"})
        env = Env()
        env.add_user(1, doctor=False, group_level=99)
        env.add_user(7)
        env.link(7, DOCTOR)
        report_idx = env.add_study(100, assignee=7)
        p = env.principal(env.login(DOCTOR))
        main, manage = env.sessions()
        env.service.prepare(main, manage, p, 100,
                            snapshot.content_sha256(env.port.get_report_for_study(main, 100)))
        with self.assertRaises(VcError) as ctx:
            guard_legacy_report_delete(env.config, env.Session(), report_idx)
        self.assertEqual(code_of(ctx), "VC_SIGNED_REPORT_IMMUTABLE")
        guard_legacy_report_delete(env.config, env.Session(), 999)  # report without evidence: allowed


if __name__ == "__main__":
    unittest.main()
