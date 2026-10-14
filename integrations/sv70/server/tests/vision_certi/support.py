"""Isolated test fixtures: temporary SQLite databases (main + attached "mgr" manage schema) with minimal copies of the
legacy tables, and an in-process fake Vision-Certi that verifies HMAC exactly like the Java filter and signs with a
test-only EC key. No legacy settings, .env, MySQL, MinIO, SMS or network are touched.
"""
from __future__ import annotations

import base64
import datetime
import hashlib
import hmac
import json
import os
import secrets
import tempfile
import uuid

from sqlalchemy import create_engine, event, text
from sqlalchemy.orm import sessionmaker

from app.integrations.vision_certi import ecdsa_p256, hmac_auth
from app.integrations.vision_certi.client import RawResponse, VcUnavailable, VisionCertiClient
from app.integrations.vision_certi.config import load_config
from app.integrations.vision_certi.legacy_port import SqlLegacyPort
from app.integrations.vision_certi.orm import VcBase
from app.integrations.vision_certi.service import VcIntegrationService

TENANT = "demo-hospital-a"
CLIENT_ID = "pacs-sv70"
SECRET = b"test-only-secret-0123456789abcdefghijklmnop"
BASE_URL = "https://vision-certi.test"
NOW = datetime.datetime(2026, 10, 13, 12, 0, 0)

# Java-generated interop vector (public key + signature only; no private key).
JAVA_SPKI = ("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEx3l0OpRlFxknConEhep5kvG8OIsQxgLIBcwzQvO0d8cdkJzpN2TQVcSrHNnLqn+yNijfP6"
             "vXFP41MAoPZrhf/A==")
JAVA_SIG = "MEYCIQDyHi0A7ic4SpWwc07dmCcX3Pf8ZHhfSsE6pD8IQnmTagIhAIufF1lSs/1gtmDLmb5NMdOGjbxcuCrmcS5gEi7sP2yO"
JAVA_MSG = b'{"schema":"saintview-pacs-report-snapshot/v1","vector":"java-interop"}'


class Clock:
    def __init__(self, now=NOW):
        self.now = now

    def __call__(self):
        return self.now

    def epoch(self):
        return self.now.replace(tzinfo=datetime.timezone.utc).timestamp()


# ---------------------------------------------------------------------- test-only ECDSA signer

class TestSigner:
    """Random-k ECDSA P-256 signing for the fake Vision-Certi (tests only)."""

    def __init__(self):
        self.d = secrets.randbelow(ecdsa_p256._N - 1) + 1
        x, y = ecdsa_p256._mul(self.d, ecdsa_p256._G)
        self.spki_b64 = base64.b64encode(ecdsa_p256._SPKI_PREFIX + b"\x04" + x.to_bytes(32, "big")
                                         + y.to_bytes(32, "big")).decode()

    def sign(self, message: bytes) -> str:
        n = ecdsa_p256._N
        e = int.from_bytes(hashlib.sha256(message).digest(), "big")
        while True:
            k = secrets.randbelow(n - 1) + 1
            r = ecdsa_p256._mul(k, ecdsa_p256._G)[0] % n
            s = pow(k, -1, n) * (e + r * self.d) % n
            if r and s:
                break

        def der_int(v):
            b = v.to_bytes((v.bit_length() + 7) // 8, "big")
            if b[0] & 0x80:
                b = b"\x00" + b
            return b"\x02" + bytes([len(b)]) + b
        body = der_int(r) + der_int(s)
        return base64.b64encode(b"\x30" + bytes([len(body)]) + body).decode()


# ---------------------------------------------------------------------- fake Vision-Certi

class FakeVisionCerti:
    """Implements the three integration routes with Java's HMAC rules; records every call."""

    def __init__(self, clock: Clock):
        self.clock = clock
        self.down = False
        self.seen_nonces = set()
        self.handoffs = {}
        self.requests = {}
        self.idempotency = {}
        self.signer = TestSigner()
        self.calls = []
        self.tamper_signature = False
        self.tamper_document_sha = False
        self.fail_next = 0

    def issue_handoff(self, doctor_id, *, tenant=TENANT, mode="PC_CERTIFICATE", session_minutes=30,
                      enrollment_id=None, fingerprint=None):
        code = secrets.token_urlsafe(32)
        self.handoffs[code] = {
            "tenantId": tenant, "doctorId": doctor_id, "doctorStatus": "ACTIVE", "identityMode": mode,
            "certificateEnrollmentId": enrollment_id or str(uuid.uuid4()),
            "certificateFingerprintSha256": fingerprint or hashlib.sha256(doctor_id.encode()).hexdigest(),
            "authenticatedAt": (self.clock() - datetime.timedelta(minutes=1)).isoformat() + "Z",
            "sessionExpiresAt": (self.clock() + datetime.timedelta(minutes=session_minutes)).isoformat() + "Z",
            "verifiedAt": self.clock().isoformat() + "Z", "activeSigner": True}
        return code

    def approve(self, sign_request_id):
        """The physician's explicit approval at Vision-Certi (simulated)."""
        r = self.requests[sign_request_id]
        assert r["status"] == "PENDING_APPROVAL"
        content = base64.b64decode(r["payload"]["contentBase64"])
        signed = content + b"tampered" if self.tamper_signature else content
        r["status"] = "COMPLETED"
        r["signature"] = {
            "signatureId": str(uuid.uuid4()), "algorithm": "ECDSA_P256_SHA256",
            "signatureMode": "DEV_SIMULATED_NOT_VALID_FOR_CLINICAL_USE",
            "signatureValueBase64": self.signer.sign(signed), "signedAt": self.clock().isoformat() + "Z",
            "documentSha256": "0" * 64 if self.tamper_document_sha else r["payload"]["contentSha256"],
            "doctorEvidence": {
                "doctorId": r["payload"]["doctorId"], "identityMode": "PC_CERTIFICATE", "certificateId": "1a2b",
                # Protocol fixture only: this fake service is never mounted in the application.
                "signingBackend": "OPENBAO_TRANSIT", "cryptographicallyAttributable": True,
                "revocationCheckedAtSigning": True,
                "certificateKind": "X509", "keyName": "vc-doctor-x", "keyVersion": 1,
                "publicKeySpki": self.signer.spki_b64, "certificateEnrollmentId": str(uuid.uuid4()),
                "certificateEnrollmentFingerprint": "ab" * 32, "signatureFormat": "RAW_ECDSA_P256_SHA256_DER"}}

    # -- transport -----------------------------------------------------------------------------------------------

    def send(self, method, url, headers, body, timeout):
        assert url.startswith(BASE_URL + "/v1/integrations/pacs/"), url
        assert timeout <= 30
        path = url[len(BASE_URL):]
        self.calls.append((method, path))
        if self.down:
            raise VcUnavailable("URLError")
        if self.fail_next:
            self.fail_next -= 1
            raise VcUnavailable("timeout")
        raw = body or b""
        # HMAC verification exactly as IntegrationAuthFilter
        sha = hashlib.sha256(raw).hexdigest()
        if headers.get("X-VC-Content-SHA256") != sha or headers.get("X-VC-Client-Id") != CLIENT_ID:
            return self._json(401, {"code": "INTEGRATION_AUTH_INVALID"})
        if abs(int(headers["X-VC-Timestamp"]) - int(self.clock.epoch())) > 300:
            return self._json(401, {"code": "INTEGRATION_AUTH_INVALID"})
        expected = hmac_auth.sign(SECRET, hmac_auth.canonical(
            "vision-certi:pacs-integration:v1", CLIENT_ID, method, path, None, headers["X-VC-Timestamp"],
            headers["X-VC-Nonce"], sha))
        if not hmac.compare_digest(expected, headers["X-VC-Signature"]):
            return self._json(401, {"code": "INTEGRATION_AUTH_INVALID"})
        if headers["X-VC-Nonce"] in self.seen_nonces:
            return self._json(409, {"code": "INTEGRATION_NONCE_REPLAYED"})
        self.seen_nonces.add(headers["X-VC-Nonce"])
        payload = json.loads(raw) if raw else None
        if method == "POST" and path.endswith("/handoffs/redeem"):
            identity = self.handoffs.pop(payload.get("handoffCode"), None)
            return self._json(200, identity) if identity else self._json(401, {"code": "HANDOFF_INVALID"})
        if method == "POST" and path.endswith("/report-sign-requests"):
            key = headers.get("Idempotency-Key")
            fingerprint = hashlib.sha256(raw).hexdigest()
            if key in self.idempotency:
                stored_fp, response = self.idempotency[key]
                return self._json(201, response) if stored_fp == fingerprint else \
                    self._json(409, {"code": "IDEMPOTENCY_KEY_REUSED"})
            content = base64.b64decode(payload["contentBase64"])
            if hashlib.sha256(content).hexdigest() != payload["contentSha256"]:
                return self._json(409, {"code": "DOCUMENT_HASH_MISMATCH"})
            snapshot = json.loads(content)
            assert snapshot["tenantId"] == payload["tenantId"] and snapshot["doctorId"] == payload["doctorId"]
            assert snapshot["report"]["revision"] == payload["revision"]
            rid = str(uuid.uuid4())
            self.requests[rid] = {"status": "PENDING_APPROVAL", "payload": payload, "signature": None}
            response = {"signRequestId": rid, "documentId": str(uuid.uuid4()), "documentVersionId": str(uuid.uuid4()),
                        "documentSha256": payload["contentSha256"], "signerId": payload["doctorId"],
                        "status": "PENDING_APPROVAL",
                        "expiresAt": (self.clock() + datetime.timedelta(minutes=10)).isoformat() + "Z",
                        "revision": payload["revision"]}
            self.idempotency[key] = (fingerprint, response)
            return self._json(201, response)
        if method == "GET" and "/report-sign-requests/" in path:
            rid = path.rsplit("/", 1)[1]
            r = self.requests.get(rid)
            if r is None:
                return self._json(404, {"code": "SIGN_REQUEST_NOT_FOUND"})
            return self._json(200, {"signRequestId": rid, "tenantId": r["payload"]["tenantId"], "status": r["status"],
                                    "documentSha256": r["payload"]["contentSha256"],
                                    "signerId": r["payload"]["doctorId"], "signature": r["signature"]})
        return self._json(404, {"code": "NOT_FOUND"})

    @staticmethod
    def _json(status, body):
        return RawResponse(status, json.dumps(body).encode())


# ---------------------------------------------------------------------- databases with legacy tables

class Env:
    """One isolated world: SQLite DBs, config, fake Vision-Certi, service."""

    def __init__(self, *, enabled=True, extra_env=None):
        self.dir = tempfile.mkdtemp(prefix="vc-pacs-test-")
        main_path = os.path.join(self.dir, "main.db")
        self.mgr_path = os.path.join(self.dir, "mgr.db")
        self.engine = create_engine(f"sqlite:///{main_path}")

        @event.listens_for(self.engine, "connect")
        def _attach(dbapi_connection, _record):
            dbapi_connection.execute("ATTACH DATABASE ? AS mgr", (self.mgr_path,))

        with self.engine.begin() as c:
            c.execute(text("CREATE TABLE pacs_study (study_idx INTEGER PRIMARY KEY, user_idx INTEGER, "
                           "study_status TEXT, study_accession_no TEXT, patient_id TEXT, patient_name TEXT, "
                           "study_modality TEXT, study_body_part TEXT, study_description TEXT, study_date TEXT, "
                           "hospital_idx INTEGER, hospital_name TEXT)"))
            c.execute(text("CREATE TABLE pacs_study_report (report_idx INTEGER PRIMARY KEY, study_idx INTEGER UNIQUE, "
                           "report_status TEXT, report_reading TEXT, report_conclusion TEXT, "
                           "report_study_comment TEXT, report_refer_comment TEXT, report_cvr_send TEXT DEFAULT 'N', "
                           "report_create_idx INTEGER, report_open_edit TEXT DEFAULT 'N', report_approve_idx INTEGER, "
                           "report_approve_datetime TEXT, report_update_datetime TEXT)"))
            c.execute(text("CREATE TABLE mgr.pacs_users_view (user_idx INTEGER PRIMARY KEY, user_id TEXT, "
                           "user_name TEXT, user_status TEXT, user_type TEXT, group_level INTEGER, doctor_idx INTEGER, "
                           "doctor_license_number TEXT, user_email TEXT)"))
            c.execute(text("CREATE TABLE mgr.pacs_users (user_idx INTEGER PRIMARY KEY, user_report_edit_all TEXT)"))
        VcBase.metadata.create_all(self.engine)
        self.Session = sessionmaker(bind=self.engine, expire_on_commit=False)
        self.clock = Clock()
        self.fake = FakeVisionCerti(self.clock)
        secret_file = os.path.join(self.dir, "secret")
        with open(secret_file, "wb") as f:
            f.write(SECRET)
        env = {"VISION_CERTI_INTEGRATION_ENABLED": "true" if enabled else "false",
               "VISION_CERTI_BASE_URL": BASE_URL, "VISION_CERTI_CLIENT_ID": CLIENT_ID,
               "VISION_CERTI_CLIENT_SECRET_FILE": secret_file, "VISION_CERTI_TENANT_ID": TENANT,
               "VISION_CERTI_LINK_ADMIN_USER_IDX": "1"}
        env.update(extra_env or {})
        self.config = load_config(env, super_admins=[227])
        self.port = SqlLegacyPort("mgr")
        self.client = VisionCertiClient(self.config, transport=self.fake, clock=self.clock.epoch)
        self.service = VcIntegrationService(self.config, self.client, self.port, clock=self.clock,
                                            super_admins=[227])

    def sessions(self):
        return self.Session(), self.Session()

    def sql(self, statement, **params):
        with self.engine.begin() as c:
            return c.execute(text(statement), params)

    def add_user(self, user_idx, *, doctor=True, status="A", group_level=10, user_type="P", email=None,
                 edit_all="N"):
        self.sql("INSERT INTO mgr.pacs_users_view VALUES (:u, :id, :name, :st, :t, :g, :d, :lic, :e)",
                 u=user_idx, id=f"user{user_idx}", name=f"의사{user_idx}", st=status, t=user_type, g=group_level,
                 d=user_idx if doctor else None, lic=f"LIC-{user_idx}" if doctor else None, e=email)
        self.sql("INSERT INTO mgr.pacs_users VALUES (:u, :e)", u=user_idx, e=edit_all)

    def add_study(self, study_idx, assignee, *, reading="No acute abnormality.", conclusion="Normal.",
                  status="R", creator=None):
        self.sql("INSERT INTO pacs_study VALUES (:s, :u, 'R', 'ACC-1', 'P-001', '홍길동', 'CT', 'CHEST', 'CT chest', "
                 "'2026-10-12', 3, '서울병원')", s=study_idx, u=assignee)
        self.sql("INSERT INTO pacs_study_report (report_idx, study_idx, report_status, report_reading, "
                 "report_conclusion, report_create_idx) VALUES (:r, :s, :st, :rd, :c, :cr)",
                 r=study_idx * 10, s=study_idx, st=status, rd=reading, c=conclusion,
                 cr=creator if creator is not None else assignee)
        return study_idx * 10

    def report_row(self, report_idx):
        return self.sql("SELECT * FROM pacs_study_report WHERE report_idx = :r", r=report_idx).mappings().first()

    def link(self, user_idx, doctor_id):
        main, manage = self.sessions()
        try:
            return self.service.create_link(main, manage, 1, {
                "user_idx": user_idx, "vc_tenant_id": TENANT, "vc_doctor_id": doctor_id,
                "evidence_ref": "evidence://license-check", "note": "test"})
        finally:
            main.close()
            manage.close()

    def login(self, doctor_id, **handoff):
        main, manage = self.sessions()
        try:
            token, row, user = self.service.certificate_login(main, manage, self.fake.issue_handoff(doctor_id, **handoff),
                                                              "127.0.0.1")
            return token
        finally:
            main.close()
            manage.close()

    def principal(self, token):
        main, manage = self.sessions()
        try:
            return self.service.resolve_session(main, manage, "Bearer " + token)
        finally:
            main.close()
            manage.close()

    def call(self, fn, *args, **kwargs):
        main, manage = self.sessions()
        try:
            return fn(main, manage, *args, **kwargs)
        finally:
            main.close()
            manage.close()
