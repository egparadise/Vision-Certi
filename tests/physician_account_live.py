"""Local-only, SDK-free signup -> certificate proof -> review -> real OpenBao report signing.

Uses a synthetic test CA and identity. Never sends client private keys or logs passwords/tokens.
Requires the persistent local stack on 18082 and existing work/certificate-fixtures test CA.
"""
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import secrets
import urllib.error
import urllib.request
import uuid

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID
from dev_smoke import synthetic_pdf

ROOT = Path(__file__).resolve().parents[1]
BASE = 'http://127.0.0.1:18082'
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
checks = []


def call(method, path, body=None, *, token=None, admin=False, expected=200, content_type=None, key=None):
    headers = {}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if admin:
        headers.update({'X-Actor-Id': 'admin-demo', 'X-Tenant-Id': 'demo-hospital-a'})
    if key:
        headers['Idempotency-Key'] = key
    if isinstance(body, dict):
        body = json.dumps(body).encode()
        content_type = 'application/json'
    if content_type:
        headers['Content-Type'] = content_type
    try:
        with opener.open(urllib.request.Request(BASE + path, body, headers, method=method), timeout=30) as r:
            status, raw, kind = r.status, r.read(), r.headers.get('Content-Type', '')
    except urllib.error.HTTPError as e:
        status, raw, kind = e.code, e.read(), e.headers.get('Content-Type', '')
    value = json.loads(raw) if 'json' in kind else raw
    assert status == expected, (path, status, expected, value.get('code') if isinstance(value, dict) else 'non-json')
    checks.append({'path': path, 'status': status})
    return value


caps = call('GET', '/v1/runtime/capabilities')
assert caps['profile'] == 'dev' and caps['persistence']['mode'] == 'POSTGRESQL'
assert caps['persistence']['schemaVersion'] == 7
assert call('GET', '/v1/doctor-activation/config')['signingBackend'] == 'OPENBAO_TRANSIT'
config = call('GET', '/v1/physician-accounts/config')
assert config['available']
run = uuid.uuid4().hex
license_number = str(int(run, 16))[:18]
username, password = 'direct' + run[:20], secrets.token_urlsafe(24)
receipt = call('POST', '/v1/physician-accounts/registrations', {
    'username': username, 'password': password, 'name': 'Synthetic Direct Physician', 'phone': '01000000000',
    'licenseNumber': license_number, 'centerName': 'Synthetic Test Center',
    'privacyConsent': True, 'consentVersion': config['consentVersion'],
}, expected=201)
account_token = receipt['enrollmentToken']
doctor_id = receipt['account']['doctor']['id']
assert receipt['account']['scope'] == 'ENROLLMENT_ONLY' and not receipt['account']['phoneVerified']
call('GET', '/v1/doctor/sign-requests', token=account_token, expected=401)
challenge = call('POST', '/v1/physician-accounts/certificate-challenges', token=account_token, expected=201)
fixture = ROOT / 'work/certificate-fixtures'
ca = x509.load_pem_x509_certificate((fixture / 'ca.pem').read_bytes())
ca_key = serialization.load_pem_private_key((fixture / 'ca.key.pem').read_bytes(), None)
local_key = ec.generate_private_key(ec.SECP256R1())
now = datetime.now(timezone.utc)
cert = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Synthetic direct ' + run)]))
        .issuer_name(ca.subject).public_key(local_key.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(minutes=5)).not_valid_after(now + timedelta(days=1))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(x509.KeyUsage(True, False, False, False, False, False, False, False, False), critical=True)
        .sign(ca_key, hashes.SHA256()))


def proof(challenge, login=False):
    return {('challengeId' if login else 'registrationId'): challenge['challengeId'],
            'certificate': base64.b64encode(cert.public_bytes(serialization.Encoding.DER)).decode(), 'chain': [],
            'signature': base64.b64encode(local_key.sign(base64.b64decode(challenge['signedPayloadBase64']), ec.ECDSA(hashes.SHA256()))).decode(),
            'signatureAlgorithm': 'SHA256withECDSA', 'fingerprintSha256': cert.fingerprint(hashes.SHA256()).hex()}


verified = call('POST', '/v1/certificate-enrollment/verify', proof(challenge), expected=201)
token, registration = verified['activationToken'], verified['enrollment']['registrationId']
call('POST', '/v1/certificate-enrollment/verify', proof(challenge), expected=409)
call('GET', '/v1/doctor/sign-requests', token=token, expected=403)
medical = call('PUT', f'/v1/certificate-enrollment/enrollments/{registration}/medical-credentials', {
    'medicalLicenseNumber': license_number, 'specialist': True,
    'specialistQualificationNumber': 'SYNTHETIC-SPECIALIST', 'specialty': 'Synthetic Radiology'}, token=token)
call('POST', f'/v1/certificate-enrollment/enrollments/{registration}/consent', {
    'version': '2026-10-13-draft', 'authentication': True, 'signature': True, 'privacy': True}, token=token)
call('POST', f'/v1/admin/doctors/{doctor_id}/certificate-reviews', {
    'registrationId': registration, 'decision': 'APPROVE', 'fingerprintSha256': cert.fingerprint(hashes.SHA256()).hex(),
    'medicalCredentialsSubmittedAt': medical['medicalCredentialsSubmittedAt'], 'evidenceRef': 'synthetic://direct-account-review'}, admin=True)
active = call('POST', '/v1/doctor-activation/activate', token=token)
assert active['status'] == 'ACTIVE' and active['certificate']['backend'] == 'OPENBAO_TRANSIT'
login_challenge = call('POST', '/v1/certificate-enrollment/login-challenges', expected=201)
login = call('POST', '/v1/certificate-enrollment/login', proof(login_challenge, True))
token = login['activationToken']
assert login['doctor']['id'] == doctor_id
pdf, boundary = synthetic_pdf(), 'direct-' + run
metadata = json.dumps({'externalDocumentId': 'direct-' + run, 'externalStudyId': 'synthetic-study',
                       'title': 'Synthetic direct physician report', 'signerId': doctor_id})
multipart = (f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n{metadata}\r\n'
             f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="test.pdf"\r\nContent-Type: application/pdf\r\n\r\n').encode() + pdf + f'\r\n--{boundary}--\r\n'.encode()
document = call('POST', '/v1/documents', multipart, admin=True, key='doc-' + run,
                content_type='multipart/form-data; boundary=' + boundary, expected=201)
request = call('POST', '/v1/sign-requests', {'documentVersionId': document['latestVersion']['id'], 'signerId': doctor_id},
               admin=True, key='request-' + run, expected=201)
assert call('GET', f"/v1/doctor/sign-requests/{request['id']}/document", token=token) == pdf
request = call('GET', f"/v1/doctor/sign-requests/{request['id']}", token=token)
assert request['documentSha256'] == hashlib.sha256(pdf).hexdigest()
approval = {'documentSha256': request['documentSha256'], 'nonce': request['nonce']}
call('POST', f"/v1/doctor/sign-requests/{request['id']}/approve", approval, token=account_token, expected=401)
done = call('POST', f"/v1/doctor/sign-requests/{request['id']}/approve", approval, token=token, expected=202)
assert done['status'] == 'COMPLETED'
verification = call('POST', f"/v1/signatures/{done['signatureId']}/verify", admin=True)
assert verification['status'] == 'INDETERMINATE' and not verification['clinicalUseAllowed']
call('POST', '/v1/physician-accounts/logout', token=account_token, expected=204)
call('GET', '/v1/physician-accounts/me', token=account_token, expected=401)
call('PATCH', f'/v1/admin/doctors/{doctor_id}/status', {'status': 'REVOKED', 'reason': 'Synthetic direct signup test complete'}, admin=True)
call('GET', '/v1/doctor-activation/me', token=token, expected=401)
output = {'result': 'PASS', 'synthetic': True, 'signer': 'REAL_OPENBAO_TRANSIT', 'schemaVersion': 7,
          'doctorId': doctor_id, 'signatureId': done['signatureId'], 'verification': verification, 'checks': checks}
(ROOT / 'work/physician-account-live-result.json').write_text(json.dumps(output, indent=2))
print(f'PASS: {len(checks)} HTTP checks; persistent signup, real certificate proof, review, login, OpenBao report signature, revocation')
