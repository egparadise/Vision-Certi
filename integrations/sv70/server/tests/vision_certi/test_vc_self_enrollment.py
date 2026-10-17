"""First-login enrollment uses trusted PACS records; proof remains a separate real certificate operation."""
import json
import datetime
import importlib.util
from dataclasses import replace
from pathlib import Path
import sys
import types
import unittest
import uuid
from unittest.mock import Mock, patch

from fastapi.testclient import TestClient
from fastapi import FastAPI
from sqlalchemy import select, func

from app.integrations.vision_certi.client import VcUnavailable
from app.integrations.vision_certi.orm import VcDoctorLink, VcSession
from app.integrations.vision_certi.service import VcError
from tests.vision_certi.support import Env, TENANT
from tests.vision_certi.test_vc_api_and_client import make_app


class SelfEnrollmentTest(unittest.TestCase):
    def setUp(self):
        self.env = Env()
        self.env.add_user(1, doctor=False, group_level=99)
        self.env.add_user(7)
        self.env.add_user(8)
        self.env.sql('ALTER TABLE mgr.pacs_users_view ADD COLUMN center_list TEXT')
        self.env.sql('UPDATE mgr.pacs_users_view SET center_list=:centers',
                     centers=json.dumps([{"center_name": "Trusted PACS Center"}]))
        self.doctor = str(uuid.uuid4())
        self.enrollment = str(uuid.uuid4())
        self.status = {"doctorId": self.doctor, "registrationStatus": "PENDING",
                       "certificateEnrollmentId": None, "fingerprintSha256": None,
                       "doctorLicenseNumber": "7"}
        self.env.client.enrollment_status = Mock(side_effect=lambda *args: dict(self.status))
        self.env.client.enrollment_start = Mock(return_value={"doctorId": self.doctor,
            "challenge": {"challengeId": str(uuid.uuid4()), "payloadBase64": "test-only"}})
        self.http = TestClient(make_app(self.env))
        self.auth = {"X-Test-Legacy-User": "7", "Authorization": "Bearer legacy-test"}

    def active(self):
        self.status.update(registrationStatus="ACTIVE", certificateEnrollmentId=self.enrollment,
                           fingerprintSha256="ab" * 32)

    def call(self, method, user=7):
        return self.env.call(method, user)

    def certificate_token(self):
        self.active()
        self.call(self.env.service.enrollment_status)
        self.env.fake.licenses[self.doctor] = "7"
        return self.env.login(self.doctor, enrollment_id=self.enrollment, fingerprint="ab" * 32)

    def access(self, token, user=7):
        return self.env.call(self.env.service.require_enrolled_pacs_access, user, 'Bearer ' + token)

    def test_start_only_authenticated_own_trusted_profile(self):
        self.assertEqual(self.http.post('/api/vc/auth/enrollment-start').status_code, 401)
        r = self.http.post('/api/vc/auth/enrollment-start', headers=self.auth,
                           json={"pacsUserId": "8", "name": "forged", "licenseNumber": "8"})
        self.assertEqual(r.status_code, 422)
        self.env.client.enrollment_start.assert_not_called()
        r = self.http.post('/api/vc/auth/enrollment-start', headers=self.auth, json={})
        self.assertEqual(r.status_code, 201, r.text)
        payload = self.env.client.enrollment_start.call_args.args[0]
        self.assertEqual(payload["pacsUserId"], "7")
        self.assertEqual(payload["tenantId"], TENANT)
        self.assertEqual(payload["licenseNumber"], "7")
        self.assertEqual(payload["centerName"], "Trusted PACS Center")
        self.assertNotEqual(payload["name"], "forged")
        with self.env.Session() as s:
            self.assertEqual(s.scalar(select(func.count()).select_from(VcDoctorLink)), 0)
            self.assertEqual(s.scalar(select(func.count()).select_from(VcSession)), 0)

    def test_status_does_not_accept_browser_user_id(self):
        self.http.get('/api/vc/auth/enrollment-status?pacsUserId=8', headers=self.auth)
        self.env.client.enrollment_status.assert_called_once_with(TENANT, "7")

    def test_pending_and_active_password_access_denied_while_linking_stays_idempotent(self):
        with self.assertRaises(VcError) as e:
            self.call(self.env.service.require_enrolled_pacs_access)
        self.assertEqual(e.exception.code, "VC_SESSION_REQUIRED")
        self.active()
        for _ in range(2):
            self.call(self.env.service.enrollment_status)
            with self.assertRaises(VcError) as e:
                self.access('legacy-password-token')
            self.assertEqual(e.exception.code, "VC_SESSION_REQUIRED")
        with self.env.Session() as s:
            links = s.scalars(select(VcDoctorLink)).all()
            self.assertEqual(len(links), 1)
            self.assertEqual(links[0].user_idx, 7)
            self.assertEqual(links[0].created_by_user_idx, 7)
            self.assertEqual(links[0].evidence_ref, f"pacs-self-enrollment:{self.enrollment}")
            self.assertEqual(s.scalar(select(func.count()).select_from(VcSession)), 0)

    def test_revocation_suspension_and_outage_rechecked_after_active(self):
        token = self.certificate_token()
        self.access(token)
        for state in ["REVOKED", "SUSPENDED", "PENDING", "NOT_REGISTERED"]:
            self.status["registrationStatus"] = state
            with self.assertRaises(VcError):
                self.access(token)
        self.env.client.enrollment_status.side_effect = VcUnavailable('test timeout')
        with self.assertRaises(VcError) as e:
            self.access(token)
        self.assertEqual(e.exception.status, 503)

    def test_expired_revoked_and_wrong_user_certificate_sessions_denied(self):
        token = self.certificate_token()
        self.access(token)
        with self.assertRaises(VcError):
            self.access(token, user=8)
        self.env.clock.now += datetime.timedelta(minutes=61)
        with self.assertRaises(VcError) as e:
            self.access(token)
        self.assertEqual(e.exception.code, 'VC_SESSION_REQUIRED')
        self.env.clock.now -= datetime.timedelta(minutes=61)
        self.env.sql("UPDATE pacs_vc_session SET revoked_at=:at", at=self.env.clock())
        with self.assertRaises(VcError) as e:
            self.access(token)
        self.assertEqual(e.exception.code, 'VC_SESSION_REQUIRED')

    def test_changed_certificate_requires_new_certificate_login(self):
        token = self.certificate_token()
        for key, value in [('certificateEnrollmentId', str(uuid.uuid4())), ('fingerprintSha256', 'cd' * 32)]:
            previous = self.status[key]
            self.status[key] = value
            with self.assertRaises(VcError) as e:
                self.access(token)
            self.assertEqual(e.exception.code, 'VC_SESSION_REQUIRED')
            self.status[key] = previous

    def test_legacy_step_up_proves_certificate_and_unlocks_access(self):
        self.active()
        self.call(self.env.service.enrollment_status)
        self.env.fake.licenses[self.doctor] = '7'
        handoff = self.env.fake.issue_handoff(self.doctor, enrollment_id=self.enrollment, fingerprint='ab' * 32)
        response = self.http.post('/api/vc/auth/certificate-step-up', headers=self.auth,
                                  json={'handoffCode': handoff})
        self.assertEqual(response.status_code, 200, response.text)
        self.access(response.json()['token'])

    def test_conflicting_and_revoked_local_links_not_overwritten(self):
        self.active()
        self.env.link(8, self.doctor)
        with self.assertRaises(VcError) as e:
            self.call(self.env.service.enrollment_status)
        self.assertEqual(e.exception.code, 'VC_LINK_CONFLICT')
        self.env.sql("UPDATE pacs_vc_doctor_link SET status='REVOKED',active_user_idx=NULL,active_doctor_key=NULL")
        with self.assertRaises(VcError) as e:
            self.call(self.env.service.enrollment_status)
        self.assertEqual(e.exception.code, 'VC_LINK_CONFLICT')

    def test_staff_unaffected_and_start_refused(self):
        result = self.call(self.env.service.enrollment_status, 1)
        self.assertEqual(result['registrationStatus'], 'NOT_REQUIRED')
        self.call(self.env.service.require_enrolled_pacs_access, 1)
        self.env.client.enrollment_status.assert_not_called()
        with self.assertRaises(VcError):
            self.call(self.env.service.enrollment_start, 1)
        self.env.service.config = replace(self.env.config, state='MISCONFIGURED')
        self.assertEqual(self.call(self.env.service.enrollment_status, 1)['registrationStatus'], 'NOT_REQUIRED')
        self.call(self.env.service.require_enrolled_pacs_access, 1)

    def test_inactive_profile_and_missing_license_or_center_fail(self):
        for column, value in [("user_status", "D"), ("doctor_license_number", ""), ("center_list", "[]")]:
            with self.subTest(column=column):
                previous = self.env.sql(f'SELECT {column} FROM mgr.pacs_users_view WHERE user_idx=7').scalar()
                self.env.sql(f'UPDATE mgr.pacs_users_view SET {column}=:value WHERE user_idx=7', value=value)
                with self.assertRaises(VcError):
                    self.call(self.env.service.enrollment_start)
                self.env.sql(f'UPDATE mgr.pacs_users_view SET {column}=:value WHERE user_idx=7', value=previous)
        self.env.client.enrollment_start.assert_not_called()

    def test_malformed_active_evidence_and_license_mismatch_fail(self):
        self.active()
        for key, value in [('doctorId', None), ('certificateEnrollmentId', None), ('fingerprintSha256', ''),
                           ('doctorLicenseNumber', None), ('doctorLicenseNumber', '8')]:
            with self.subTest(key=key, value=value):
                previous = self.status[key]
                self.status[key] = value
                with self.assertRaises(VcError):
                    self.call(self.env.service.enrollment_status)
                self.status[key] = previous

    def test_disabled_guard_preserves_legacy_access(self):
        disabled = Env(enabled=False)
        disabled.call(disabled.service.require_enrolled_pacs_access, 999)

    def test_actual_middleware_guards_cached_token_and_allows_bootstrap(self):
        # Load the actual ASGI middleware with isolated legacy dependencies; never load settings or .env.
        from app.integrations.vision_certi import runtime

        def db_session():
            with self.env.Session() as s:
                yield s

        async def logger(**kwargs):
            pass

        async def error_handler(exc):
            return types.SimpleNamespace(status_code=500, msg=str(exc), detail=None, code='TEST_ERROR')

        def module(name, **attrs):
            result = types.ModuleType(name)
            result.__dict__.update(attrs)
            return result

        lookup = Mock(return_value={"user_idx": 7})
        database = module('app.database', db=types.SimpleNamespace(session=db_session),
                          manageDB=types.SimpleNamespace(session=db_session))
        modules = {
            'app.database': database,
            'app.libs': module('app.libs', exception_handler=error_handler, event_logger=logger),
            'app.crud': module('app.crud', get_user=lookup),
            'app.core': module('app.core', EXCEPT_PATH_REGEX=r'^/public$', EXCEPT_PATH_LIST=[]),
            'app.errors': module('app.errors', NotAuthorized=PermissionError),
        }
        path = Path(__file__).parents[2] / 'app/middlewares/TokenHTTPMiddleware.py'
        spec = importlib.util.spec_from_file_location('isolated_vc_token_middleware', path)
        middleware = importlib.util.module_from_spec(spec)
        app = FastAPI()

        @app.get('/api/study')
        @app.get('/api/vc/auth/enrollment-status')
        @app.post('/api/vc/auth/enrollment-start')
        @app.post('/api/vc/auth/certificate-step-up')
        @app.get('/api/user/auth/logout')
        @app.get('/api/vc/config')
        def endpoint():
            return {'reached': True}

        with patch.dict(sys.modules, modules), patch.object(runtime, 'service', return_value=self.env.service):
            spec.loader.exec_module(middleware)
            app.add_middleware(middleware.TokenASGIMiddleware)
            with TestClient(app) as http:
                headers = {'Authorization': 'Bearer legacy-token'}
                first = http.get('/api/study', headers=headers)
                self.assertEqual((first.status_code, first.json()['code']), (401, 'VC_SESSION_REQUIRED'))
                self.assertEqual(http.get('/api/vc/auth/enrollment-status', headers=headers).status_code, 200)
                self.assertEqual(http.post('/api/vc/auth/enrollment-start', headers=headers).status_code, 200)
                self.assertEqual(http.post('/api/vc/auth/certificate-step-up', headers=headers).status_code, 200)
                self.assertEqual(http.get('/api/user/auth/logout', headers=headers).status_code, 200)
                self.assertEqual(http.get('/api/vc/config', headers=headers).status_code, 200)
                token = self.certificate_token()
                self.assertEqual(http.get('/api/study', headers=headers).status_code, 401)
                self.assertEqual(lookup.call_count, 1, 'Cached JWT cannot become a certificate session')
                cert_headers = {'Authorization': 'Bearer ' + token}
                self.assertEqual(http.get('/api/study', headers=cert_headers).status_code, 200)
                self.status['registrationStatus'] = 'REVOKED'
                self.assertEqual(http.get('/api/study', headers=cert_headers).status_code, 403)
                self.status['registrationStatus'] = 'ACTIVE'
                self.env.clock.now += datetime.timedelta(minutes=61)
                self.assertEqual(http.get('/api/study', headers=cert_headers).status_code, 401)


if __name__ == '__main__':
    unittest.main()
