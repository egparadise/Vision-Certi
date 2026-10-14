"""HTTP layer (FastAPI TestClient over build_router) and the real urllib transport against a local socket server."""
from __future__ import annotations

import http.server
import json
import threading
import time
import unittest
import uuid

from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from app.integrations.vision_certi import snapshot
from app.integrations.vision_certi.api import VcApiDeps, build_router
from app.integrations.vision_certi.client import MAX_RESPONSE_BYTES, UrllibTransport, VcRejected, VcUnavailable, \
    VisionCertiClient
from app.integrations.vision_certi.config import load_config

from tests.vision_certi.support import SECRET, TENANT, Env

DOCTOR = "11111111-2222-4333-8444-555555555555"


def make_app(env: Env) -> FastAPI:
    def main_session():
        s = env.Session()
        try:
            yield s
        finally:
            s.close()

    def legacy_user_idx(request: Request):
        # Stand-in for the legacy token middleware: tests pass the authenticated user in a test-only header.
        raw = request.headers.get("X-Test-Legacy-User")
        return int(raw) if raw else None

    app = FastAPI()
    app.include_router(build_router(VcApiDeps(service=lambda: env.service, main_session=main_session,
                                              manage_session=main_session, legacy_user_idx=legacy_user_idx)),
                       prefix="/api")
    return app


class ApiTest(unittest.TestCase):
    def setUp(self):
        self.env = Env()
        self.env.add_user(1, doctor=False, group_level=99)
        self.env.add_user(7)
        self.env.add_study(100, assignee=7)
        self.http = TestClient(make_app(self.env))

    def test_full_http_flow_with_evidence_endpoints(self):
        config = self.http.get("/api/vc/config").json()
        self.assertEqual(config, {"enabled": True, "state": "ENABLED", "visionCertiBaseUrl": "https://vision-certi.test",
                                  "audience": "pacs-sv70", "tenantId": TENANT})
        self.assertNotIn(SECRET.decode(), json.dumps(config))
        # Only the configured link admin may link doctors (legacy user from the middleware).
        body = {"user_idx": 7, "vc_tenant_id": TENANT, "vc_doctor_id": DOCTOR, "evidence_ref": "evidence://x"}
        self.assertEqual(self.http.post("/api/vc/admin/doctor-links", json=body).status_code, 403)
        self.assertEqual(self.http.post("/api/vc/admin/doctor-links", json=body,
                                        headers={"X-Test-Legacy-User": "1"}).status_code, 201)
        # Browser-supplied identity is ignored: only the handoff code counts.
        r = self.http.post("/api/vc/auth/certificate-login",
                           json={"handoffCode": "A" * 43, "doctorId": DOCTOR, "authenticated": True})
        self.assertEqual((r.status_code, r.json()["code"]), (401, "VC_HANDOFF_INVALID"))
        r = self.http.post("/api/vc/auth/certificate-login",
                           json={"handoffCode": self.env.fake.issue_handoff(DOCTOR)})
        self.assertEqual(r.status_code, 200, r.text)
        self.assertEqual(r.headers["cache-control"], "no-store")
        token = r.json()["token"]
        auth = {"Authorization": f"Bearer {token}"}
        me = self.http.get("/api/vc/auth/me", headers=auth).json()
        self.assertEqual((me["userIdx"], me["doctorId"]), (7, DOCTOR))
        self.assertEqual(self.http.get("/api/vc/auth/session").status_code, 401)
        # A legacy password-login bearer is not a certificate session.
        legacy = {"Authorization": "Bearer eyJhbGciOiJIUzI1NiJ9.legacy.jwt"}
        r = self.http.post("/api/vc/study/100/report/prepare", headers=legacy, json={"expected_content_sha256": "0"})
        self.assertEqual((r.status_code, r.json()["code"]), (401, "VC_SESSION_REQUIRED"))

        main = self.env.Session()
        sha = snapshot.content_sha256(self.env.port.get_report_for_study(main, 100))
        self.assertEqual(self.http.get('/api/vc/study/100/report/current').status_code, 401)
        current = self.http.get('/api/vc/study/100/report/current', headers=auth)
        self.assertEqual(current.status_code, 200)
        self.assertEqual(current.json()['contentSha256'], sha)
        self.env.sql('UPDATE pacs_study SET user_idx=8 WHERE study_idx=100')
        self.assertEqual(self.http.get('/api/vc/study/100/report/current', headers=auth).status_code, 403)
        self.env.sql('UPDATE pacs_study SET user_idx=7 WHERE study_idx=100')
        r = self.http.post("/api/vc/study/100/report/prepare", headers=auth, json={"expected_content_sha256": sha})
        self.assertEqual(r.status_code, 201, r.text)
        prepared = r.json()
        approval_id = prepared["approval"]["approvalId"]
        self.assertEqual(prepared["approval"]["state"], "PENDING_SIGNATURE")
        self.assertFalse(prepared["approval"]["approved"])
        r = self.http.post(f"/api/vc/report-approvals/{approval_id}/finalize", headers=auth)
        self.assertEqual((r.status_code, r.json()["code"]), (409, "VC_NOT_YET_SIGNED"))
        self.env.fake.approve(prepared["visionCertiSignRequestId"])
        r = self.http.post(f"/api/vc/report-approvals/{approval_id}/finalize", headers=auth)
        self.assertEqual((r.status_code, r.json()["approval"]["state"]), (200, "APPROVED"))

        reader = {"X-Test-Legacy-User": "7"}
        view = self.http.get(f"/api/vc/report-approvals/{approval_id}", headers=reader).json()
        self.assertEqual((view["revision"], view["targetStatus"], view["approved"]), (1, "A", True))
        self.assertEqual(view["signature"]["algorithm"], "ECDSA_P256_SHA256")
        snap = self.http.get(f"/api/vc/report-approvals/{approval_id}/snapshot", headers=reader)
        self.assertEqual(snap.content.decode("utf-8"), prepared["snapshot"])
        self.assertEqual(snap.headers["x-snapshot-sha256"], prepared["snapshotSha256"])
        self.assertEqual(len(self.http.get("/api/vc/study/100/report/approvals", headers=reader).json()["items"]), 1)
        self.env.add_user(40, doctor=False)
        self.assertEqual(self.http.get(f"/api/vc/report-approvals/{approval_id}",
                                       headers={"X-Test-Legacy-User": "40"}).status_code, 404)
        self.assertEqual(self.http.post("/api/vc/auth/logout", headers=auth).status_code, 200)
        self.assertEqual(self.http.get("/api/vc/auth/me", headers=auth).status_code, 401)

    def test_misconfigured_and_disabled_states(self):
        bad = Env(extra_env={"VISION_CERTI_BASE_URL": "http://not-loopback.example"})
        http = TestClient(make_app(bad))
        self.assertEqual(http.get("/api/vc/config").json()["state"], "MISCONFIGURED")
        self.assertIsNone(http.get("/api/vc/config").json()["visionCertiBaseUrl"])
        r = http.post("/api/vc/auth/certificate-login", json={"handoffCode": "A" * 43})
        self.assertEqual((r.status_code, r.json()["code"]), (503, "VC_INTEGRATION_MISCONFIGURED"))
        off = TestClient(make_app(Env(enabled=False)))
        r = off.post("/api/vc/auth/certificate-login", json={"handoffCode": "A" * 43})
        self.assertEqual((r.status_code, r.json()["code"]), (404, "VC_INTEGRATION_DISABLED"))


class _Handler(http.server.BaseHTTPRequestHandler):
    mode = "ok"

    def log_message(self, *args):
        pass

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length") or 0))
        if _Handler.mode == "redirect":
            self.send_response(302)
            self.send_header("Location", "http://127.0.0.1:1/elsewhere")
            self.end_headers()
            return
        if _Handler.mode == "slow":
            time.sleep(3)
        if _Handler.mode == "huge":
            body = b"{" + b" " * (MAX_RESPONSE_BYTES + 10) + b"}"
        elif _Handler.mode == "error":
            self.send_response(401)
            body = b'{"code":"HANDOFF_INVALID"}'
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        elif _Handler.mode == "server":
            self.send_response(500)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        else:
            body = json.dumps({"seen": self.headers.get("X-VC-Signature") is not None}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except OSError:
            pass


class RealTransportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        port = cls.server.server_address[1]
        env = Env(extra_env={"VISION_CERTI_BASE_URL": f"http://127.0.0.1:{port}",
                             "VISION_CERTI_ALLOW_LOCALHOST_HTTP": "true", "VISION_CERTI_READ_TIMEOUT": "1",
                             "VISION_CERTI_CONNECT_TIMEOUT": "1"})
        cls.config = env.config
        cls.client = VisionCertiClient(cls.config, transport=UrllibTransport(None, 1))

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def call(self):
        return self.client.redeem_handoff("A" * 43)

    def test_signed_request_redirect_timeout_size_and_status_handling(self):
        _Handler.mode = "ok"
        self.assertEqual(self.call(), {"seen": True})
        _Handler.mode = "redirect"
        with self.assertRaises(VcUnavailable) as ctx:
            self.call()
        self.assertEqual(ctx.exception.reason, "REDIRECT_REFUSED")
        _Handler.mode = "slow"
        started = time.monotonic()
        with self.assertRaises(VcUnavailable):
            self.call()
        self.assertLess(time.monotonic() - started, 2.9)
        _Handler.mode = "huge"
        with self.assertRaises(VcUnavailable) as ctx:
            self.call()
        self.assertEqual(ctx.exception.reason, "RESPONSE_TOO_LARGE")
        _Handler.mode = "error"
        with self.assertRaises(VcRejected) as ctx:
            self.call()
        self.assertEqual((ctx.exception.status, ctx.exception.code), (401, "HANDOFF_INVALID"))
        _Handler.mode = "server"
        with self.assertRaises(VcUnavailable):
            self.call()

    def test_sign_request_ids_are_validated_before_building_a_path(self):
        with self.assertRaises(ValueError):
            self.client.get_report_sign_request("../../admin")
        self.assertEqual(str(uuid.UUID(str(uuid.uuid4()))).count("/"), 0)

    def test_unconfigured_client_never_sends(self):
        client = VisionCertiClient(load_config({}), transport=None)
        with self.assertRaises(VcUnavailable):
            client.redeem_handoff("A" * 43)


if __name__ == "__main__":
    unittest.main()


class ImportIsolationTest(unittest.TestCase):
    def test_integration_modules_do_not_load_legacy_settings(self):
        import subprocess
        import sys
        code = ("import sys, app.integrations.vision_certi.runtime, app.integrations.vision_certi.api; "
                "print('app.core' in sys.modules or 'app.database' in sys.modules)")
        out = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, timeout=60)
        self.assertEqual(out.stdout.strip(), "False", out.stderr)
