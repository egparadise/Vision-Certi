# Certificate enrollment (PC joint certificate + mobile), certificate login, physician document view

Status: implemented and tested in `apps/backend` (dev and prod profiles; durable PostgreSQL storage in prod, see PRODUCTION_RUNTIME.md). Contract below is what the backend
implements. **Fingerprint definition (revised):** `fingerprintSha256` = lowercase hex SHA-256 of the **full DER
certificate**; `publicKeySha256` = SHA-256 of the SubjectPublicKeyInfo DER, reported separately. A certificate reissued
for the same key (new serial/expiry) has a new fingerprint and needs explicit re-enrollment (proof + consent + review);
it is never silently accepted at login. The same public key cannot be bound to two doctors.
Default activation policy is **`CERTIFICATE_REQUIRED`**: a physician can be activated (OpenBao signing key) only with an
ACTIVE certificate enrollment and a session obtained by certificate proof/login. Google (and dev MOCK_IDENTITY) remain
optional identity steps but can never activate on their own.

## 1. Flow

```
admin  POST /v1/admin/doctors (PENDING, no key; optional expectedCertificateFingerprint) ─► invitationCode
PC     POST /v1/certificate-enrollment/challenges {invitationCode}          ─► exact bytes to sign (CERTIFICATE_ENROLLMENT)
PC     signs those bytes locally with the joint certificate's private key (never uploaded)
PC     POST /v1/certificate-enrollment/verify {registrationId, certificate, chain, signature, signatureAlgorithm}
         ─► PKIX path to configured trust anchors + CRL revocation (GOOD only) + signature over the stored bytes
         ─► enrollment PENDING (review PENDING) + physician bearer session (identityMode PC_CERTIFICATE)
         ─► doctor PENDING → IDENTITY_VERIFIED (invitation consumed)
doctor POST /v1/certificate-enrollment/enrollments/{registrationId}/consent (bearer)  ─► doctor CONSENTED
admin  POST /v1/admin/doctors/{id}/certificate-reviews {registrationId, decision:APPROVE, fingerprintSha256, evidenceRef}
         (or automatic when the fingerprint equals the preregistered expectedCertificateFingerprint)
         ─► enrollment ACTIVE once BOTH doctor consent and approval exist
doctor POST /v1/doctor-activation/activate (bearer from certificate proof/login) ─► OpenBao key + certificate, ACTIVE
later  POST /v1/certificate-enrollment/login-challenges → /login (fresh proof)  ─► new bearer session
```

- Issuer/serial/SPKI fingerprint/subject claims alone never link a certificate to a doctor: an admin must compare the
  fingerprint with identity/license evidence (the review must repeat the exact fingerprint), or the fingerprint must equal
  one recorded at preregistration. Admins cannot create a proof, consent, or a session.
- Rotation: a new certificate needs a new proof (bearer `POST /challenges` or a reissued invitation), new consent and new
  review. When it becomes ACTIVE the previous ACTIVE enrollment becomes `REVOKED` with reason `SUPERSEDED`. Records are
  never overwritten. Nothing is linked by email or name.
- Unregister (bearer) revokes the enrollment, drops **all** the doctor's sessions and blocks signing until a new
  enrollment is ACTIVE. To re-enroll without any active certificate, the admin reissues an invitation
  (`POST /v1/admin/doctors/{id}/invitation` is allowed for non-blocked doctors without an ACTIVE enrollment).
- Signing (`/v1/doctor/sign-requests/{id}/approve`) additionally requires an ACTIVE enrollment whose certificate still
  validates (path + fresh CRL) at that moment.

## 2. Signed bytes

UTF-8, lines joined by `\n`, no trailing newline. The server stores the bytes and verifies against **its** copy; the client
value is never trusted.

```
vision-certi-certificate-auth-v1
purpose=CERTIFICATE_ENROLLMENT            (or CERTIFICATE_LOGIN)
challengeId=<uuid>                         (= registrationId for enrollment)
serviceId=<VISIONCERTI_CERT_SERVICE_ID>
doctorId=<uuid>                            ("-" for login)
tenantId=<tenant>                          ("-" for login)
nonce=<43 chars base64url, 32 random bytes>
issuedAt=<ISO-8601 UTC>
expiresAt=<ISO-8601 UTC>
```

Allowed signatures: `SHA256withRSA` (RSA ≥ 2048, PKCS#1 v1.5) and `SHA256withECDSA` (P-256, **DER** encoded, not raw r||s).
The algorithm must match the certificate key.

## 3. Endpoints

Errors `{code,message,correlationId,retryable}`; lists `{items,nextCursor:null}`; bearer = `Authorization: Bearer <activationToken>`.
All binary values are standard base64 (`certificate`, `chain[]`, `signature`) of DER.

### Public / physician (no dev headers)

- `GET /v1/certificate-enrollment/config` → `200`
  ```json
  {"activationPolicy":"CERTIFICATE_REQUIRED","serviceId":"vision-certi-dev","challengeTtlSeconds":300,
   "pcCertificate":{"trustConfigured":false,"trustAnchorCount":0,"crlCount":0,
                    "allowedSignatureAlgorithms":["SHA256withRSA","SHA256withECDSA"],
                    "revocation":"CRL","ocspSupported":false},
   "mobile":{"configured":false,"provider":null},
   "devMockCertificateEnabled":false,"clinicalUseAllowed":false}
  ```
- `POST /v1/certificate-enrollment/challenges` body `{"invitationCode":"…"}` **or** `{}` with bearer (renewal) → `201`
  ```json
  {"challengeId":"uuid","registrationId":"uuid","purpose":"CERTIFICATE_ENROLLMENT","doctorId":"uuid","tenantId":"demo-hospital-a",
   "serviceId":"vision-certi-dev","nonce":"…","issuedAt":"…","expiresAt":"…",
   "signedPayload":"vision-certi-certificate-auth-v1\npurpose=…","signedPayloadBase64":"…",
   "allowedSignatureAlgorithms":["SHA256withRSA","SHA256withECDSA"]}
  ```
  Unknown/expired/used code → `422 INVITATION_INVALID` (generic), `429 INVITATION_ATTEMPTS_EXCEEDED`,
  `429 CHALLENGE_RATE_LIMITED`, `503 CERTIFICATE_TRUST_NOT_CONFIGURED`. TTL 5 min, single use.
- `POST /v1/certificate-enrollment/verify`
  `{"registrationId","certificate","chain":["…"],"signature","signatureAlgorithm"}` → `201`
  `{"enrollment":Enrollment,"activationToken","expiresAt","identityMode":"PC_CERTIFICATE","doctor":Doctor}`.
  Any attempt consumes the challenge. Errors: `404 CHALLENGE_NOT_FOUND`, `409 CHALLENGE_ALREADY_USED`, `410 CHALLENGE_EXPIRED`,
  `409 CHALLENGE_PURPOSE_MISMATCH`, `422 CERTIFICATE_INVALID` (parse, size, key usage, CA cert, key size/curve),
  `422 SIGNATURE_ALGORITHM_NOT_ALLOWED`, `422 ALGORITHM_KEY_MISMATCH`, `401 CERTIFICATE_SIGNATURE_INVALID`,
  `422 CERTIFICATE_UNTRUSTED` (no path to a configured anchor, or outside validity), `403 CERTIFICATE_REVOKED`,
  `503 CERTIFICATE_REVOCATION_UNAVAILABLE` (no CRL, stale CRL, CRL signature invalid → UNKNOWN/STALE, fail closed, retryable),
  `409 CERTIFICATE_ALREADY_BOUND` (certificate or its public key pending/active for another doctor),
  `409 CERTIFICATE_ALREADY_ENROLLED` (same certificate already pending/active for this doctor), `423 INVITATION_LOCKED`.
- `POST /v1/certificate-enrollment/login-challenges` `{}` → `201` same shape, `purpose:"CERTIFICATE_LOGIN"`,
  `registrationId/doctorId/tenantId: null`.
- `POST /v1/certificate-enrollment/login`
  `{"challengeId","fingerprintSha256","certificate","chain","signature","signatureAlgorithm"}` → `200`
  `{"enrollment","activationToken","expiresAt","identityMode":"PC_CERTIFICATE","doctor"}`.
  `fingerprintSha256` = lowercase hex SHA-256 of the full DER certificate and must match the submitted certificate
  and an ACTIVE enrollment exactly.
  Re-checks path, CRL, enrollment ACTIVE and doctor not suspended/revoked. `403 CERTIFICATE_NOT_ENROLLED`,
  `422 FINGERPRINT_MISMATCH`, `403 DOCTOR_NOT_ALLOWED`, plus verify errors.
- `GET /v1/certificate-enrollment/enrollments` (bearer) → `{items:[Enrollment]}` (own, newest first, full history).
- `POST /v1/certificate-enrollment/enrollments/{registrationId}/consent` (bearer, own enrollment)
  `{"version":"2026-10-13-draft","authentication":true,"signature":true,"privacy":true}` → `200 Enrollment`.
  Also moves the doctor IDENTITY_VERIFIED → CONSENTED. `422 CONSENT_REQUIRED|CONSENT_VERSION_MISMATCH`, `409 INVALID_ENROLLMENT_STATE`.
- `POST /v1/certificate-enrollment/enrollments/{registrationId}/unregister` (bearer, own) `{"reason":"…"}` → `200 Enrollment`
  (`REVOKED`); every session of the doctor is invalidated.

### Mobile (provider-neutral)
- `POST /v1/certificate-enrollment/mobile/transactions` `{"purpose":"CERTIFICATE_ENROLLMENT","invitationCode":"…"}` or
  `{"purpose":"CERTIFICATE_LOGIN"}` → `201 {"transactionId","purpose","provider","expiresAt","transactionSecret","providerStart":{…}}`.
- `POST /v1/certificate-enrollment/mobile/transactions/{transactionId}/callback` (provider → server; raw body passed to the
  adapter) → `200 {"transactionId","status":"VERIFIED"}`; `401 MOBILE_CALLBACK_REJECTED` unless the adapter verified it
  server-side (signature/mTLS/introspection) and transaction, purpose, nonce, expiry and a stable provider subject match.
  A posted "SUCCESS" is never trusted.
- `POST /v1/certificate-enrollment/mobile/transactions/{transactionId}/consume` `{"transactionSecret"}` → same result as
  `/verify` (enrollment, `identityMode:"MOBILE_CERTIFICATE"`) or `/login`. Single use.
- **No provider adapter is bundled**: all three return `503 MOBILE_PROVIDER_NOT_CONFIGURED`. The interface and a
  deterministic fake exist only in tests.

### Dev shortcut (opt-in, conspicuous)
- `POST /v1/certificate-enrollment/dev/mock-sign` `{"challengeId","fingerprintSha256":null|"…"}` — dev profile **and**
  `VISIONCERTI_CERT_DEV_MOCK_ENABLED=true` only, else `404 DEV_MOCK_CERTIFICATE_DISABLED`. The server issues (once per doctor)
  a `MOCK_CERTIFICATE` from an in-memory synthetic CA, signs the stored challenge bytes and runs the normal verify/login
  pipeline (path + CRL + review + consent still apply). `identityMode`/`kind` = `MOCK_CERTIFICATE`. No real trust.

### Admin (dev headers, ADMIN, tenant-scoped)
- `POST /v1/admin/doctors` accepts optional `"expectedCertificateFingerprint":"<64 hex>"` (previously verified full-DER
  certificate fingerprint; colons/uppercase accepted). Doctor objects echo it as `expectedCertificateFingerprint`.
- `GET /v1/admin/doctors/{id}/certificate-enrollments` → `{items:[Enrollment]}` full history.
- `POST /v1/admin/doctors/{id}/certificate-reviews`
  `{"registrationId","decision":"APPROVE"|"REJECT","fingerprintSha256","evidenceRef","note"}` → `200 Enrollment`.
  APPROVE requires `fingerprintSha256` equal to the enrollment's, enrollment PENDING and a fresh GOOD revocation check.
  `422 FINGERPRINT_MISMATCH`, `409 INVALID_ENROLLMENT_STATE`, `403 ADMIN_ROLE_REQUIRED`, `404 ENROLLMENT_NOT_FOUND`.

### Enrollment object
```json
{"registrationId","doctorId","tenantId","kind":"PC_CERTIFICATE|MOBILE_CERTIFICATE|MOCK_CERTIFICATE",
 "registrationStatus":"PENDING|ACTIVE|REVOKED","externalStatus":"GOOD|REVOKED|UNKNOWN|STALE","externalCheckedAt",
 "reviewStatus":"PENDING|APPROVED|REJECTED","reviewMethod":null|"ADMIN_REVIEW"|"EXPECTED_FINGERPRINT","reviewedBy","reviewedAt",
 "consentedAt","fingerprintSha256","publicKeySha256","subjectDn","issuerDn","serialNumber","notBefore","notAfter",
 "signatureAlgorithm","keyAlgorithm","provider","providerSubject","createdAt","activatedAt","revokedAt",
 "revocationReason","supersedes"}
```

### Physician document view (informed approval)
- `GET /v1/doctor/sign-requests/{id}/document` (bearer, the bound signer only) → `200 application/pdf` exact bytes of the
  bound version; headers `X-Document-Sha256`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`,
  `Content-Security-Policy: sandbox`, `Content-Disposition: inline`. `404 SIGN_REQUEST_NOT_FOUND` for others,
  `500 STORED_DOCUMENT_CORRUPTED` if bytes no longer match. Audited `DOCUMENT_VIEWED_BY_SIGNER`.

### Activation gate (changed)
`POST /v1/doctor-activation/activate` under `CERTIFICATE_REQUIRED` additionally needs an ACTIVE enrollment and a session
with identityMode `PC_CERTIFICATE`/`MOBILE_CERTIFICATE`/`MOCK_CERTIFICATE`, else `409 CERTIFICATE_ENROLLMENT_REQUIRED`.
`GET /v1/doctor-activation/config` gains `activationPolicy`.

### Signing binding (security follow-up, 2026-10-13)
Before a physician signature the backend re-validates the ACTIVE enrollment (path + CRL) and records which enrollment
authorised it. After the remote OpenBao/Transit call returns, it re-checks:
- the *same* enrollment (registrationId and fingerprint) is still ACTIVE with external status GOOD;
- the bearer session still exists;
- the doctor/key/certificate state is unchanged.

A renewal, unregister or CA revocation in the meantime discards the signature:
`403 CERTIFICATE_ENROLLMENT_CHANGED`, and the request becomes FAILED.

A certificate-login session (`PC_CERTIFICATE`/`MOBILE_CERTIFICATE`/`MOCK_CERTIFICATE`) may only sign while the
certificate it proved is the ACTIVE enrollment. After a renewal, log in again with the new certificate; otherwise the
result is `403 CERTIFICATE_ENROLLMENT_CHANGED`.

Physician signature evidence gains two fields, additively: `certificateEnrollmentId` and
`certificateEnrollmentFingerprint`. Both are null when the policy is not CERTIFICATE_REQUIRED.

## 4. Configuration

| Variable | Default | Meaning |
|---|---|---|
| `VISIONCERTI_DOCTOR_ACTIVATION_POLICY` | `CERTIFICATE_REQUIRED` | `GOOGLE_PERMITTED_DEV` (dev profile only) restores Google-only activation for local UI tests |
| `VISIONCERTI_CERT_TRUST_ANCHORS_FILE` | empty → not configured | PEM bundle of trusted CA certificates (server-side only) |
| `VISIONCERTI_CERT_CRL_FILES` | empty | comma-separated CRL files (DER or PEM), re-read on every check; must be signed by the issuer and `thisUpdate ≤ now < nextUpdate` |
| `VISIONCERTI_CERT_SERVICE_ID` | `vision-certi-dev` | `serviceId` in signed bytes |
| `VISIONCERTI_CERT_CHALLENGE_TTL` | `PT5M` | challenge lifetime |
| `VISIONCERTI_CERT_DEV_MOCK_ENABLED` | `false` | dev-only MOCK_CERTIFICATE shortcut |
| `VISIONCERTI_MOBILE_PROVIDER` | empty | no adapter bundled; any value still reports not configured |

No network access is ever made from certificate contents (no CRL DP/AIA/OCSP fetch). OCSP is not implemented.

**CRL scope (fail closed).** Complete, direct base CRLs are interpreted. An `issuingDistributionPoint` with an exact
full-name match to a certificate CRL distribution point is supported when neither side restricts reasons or names a
different CRL issuer; no network fetch occurs. The status becomes
UNKNOWN (`503 CERTIFICATE_REVOCATION_UNAVAILABLE`) when any CRL that is signed by the issuer:
- is a delta CRL (`deltaCRLIndicator`);
- has an unmatched, reason-scoped or indirect `issuingDistributionPoint`;
- advertises delta CRLs (`freshestCRL`);
- has a critical extension other than CRL number, authority key identifier or a matched issuing distribution point;
- has a revoked entry with any critical extension or `certificateIssuer`.

Such a CRL is **not** skipped in favour of an older complete CRL. The certificate is REVOKED when it is listed in any
authentic, interpretable CRL. A CRL under the same issuer name that is not signed by the issuer key is ignored. If
your CA publishes only unsupported reason-scoped or indirect CRLs, enrollment fails closed.

**Trust anchors.** An anchor is used only if all of these hold:
- it is currently valid;
- it is a CA (basicConstraints);
- it has `keyCertSign` when keyUsage is present.

Its `pathLenConstraint` is enforced. The JDK path builder checks none of this for anchors. An expired or unusable
anchor gives `422 CERTIFICATE_UNTRUSTED`.

**Certificate encoding.** Each `certificate`/`chain` value must be exactly one DER certificate. Trailing bytes, such
as a second certificate or key material, give `422 CERTIFICATE_INVALID`.

## 5. Tests

`certificate/CertificateEnrollmentServiceTest` (synthetic root → issuing CA → leaf generated at run time; real RSA/EC
signatures and CRLs), `CertificateEnrollmentApiTest` (trust anchors and CRLs from files, full HTTP flow including
document view, approval, login, unregister), `DevMockCertificateApiTest`. Covered: proof + review + consent ordering,
EC P-256 and RSA, algorithm mismatch/disallowed, RSA-1024, P-384, missing digitalSignature, CA-as-leaf, revoked leaf,
revoked intermediate, missing CRL (UNKNOWN), stale CRL, forged CRL, untrusted CA even with client-supplied root,
expired leaf, no trust configured, replay, expiry, purpose mismatch, doctor transplant, duplicate binding, invitation
lock, admin role/tenant, rejection, expected fingerprint, Google session cannot activate, renewal/supersede history,
unregister invalidation and re-enrollment, DER fingerprint reissue, login fingerprint/revocation/suspension, signing
gate with stale CRL, unsigned/forged/replayed mobile callbacks, mobile single-use consume, mobile 503.
`CertificateRevocationScopeTest` covers:
- a signed fresh empty delta CRL, alone or with an older base CRL → UNKNOWN;
- matching IDP full-name CRL → GOOD or REVOKED; unmatched IDP, reason-subset and indirect CRLs, and `freshestCRL` → UNKNOWN;
- unknown critical CRL extension, and critical/`certificateIssuer` entry extensions → UNKNOWN;
- a forged delta is ignored, and a revocation in a full CRL wins over an omitting delta;
- expired, not-yet-valid and no-keyCertSign anchors → UNTRUSTED;
- the anchor's pathLen;
- trailing certificate or private-key bytes.

The service tests cover the signing races: renewal, unregister and CA revocation during the remote signing call, and
an old-certificate session after renewal.

## 6. Honest limits

- No real Korean joint-certificate client SDK/agent is bundled; the PC side must sign the exact bytes and send DER.
  Accredited CA roots and CRLs must be provisioned by root (files). No OCSP.
- No mobile provider contract/adapter; mobile is interface-only (503).
- Enrollments, challenges and mobile transactions are durable rows (`certi_rt`); in prod staff use OIDC bearer tokens. No mobile adapter is implemented. Not clinically valid.
