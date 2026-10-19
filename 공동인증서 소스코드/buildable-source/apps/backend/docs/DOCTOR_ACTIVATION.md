# Physician preregistration, activation and signing (dev slice)

> **Activation policy (2026-10-13):** default `CERTIFICATE_REQUIRED` — `activate` additionally needs an ACTIVE certificate
> enrollment and a certificate-login session; Google/MOCK_IDENTITY alone cannot activate. See
> [CERTIFICATE_ENROLLMENT.md](CERTIFICATE_ENROLLMENT.md). `VISIONCERTI_DOCTOR_ACTIVATION_POLICY=GOOGLE_PERMITTED_DEV`
> (dev profile only) restores the Google-only flow described below for local UI tests.

Status: implemented and tested in `apps/backend` (dev profile only). Tests: `DoctorServiceTest`, `DoctorSigningFlowTest`
(real adapter against a loopback fake OpenBao), `DoctorActivationApiTest`, `DoctorDefaultsApiTest`,
`OpenBaoCredentialProviderTest`, `GoogleIdTokenVerifierTest`. Not yet run against a real OpenBao 2.7.1 or real Google. Storage is durable in prod (PostgreSQL, see PRODUCTION_RUNTIME.md);
dev header identities for staff, `FailClosedProfileGuard` refuses any non-dev profile. Nothing here is clinically valid.

## 1. Flow and state machine

```
admin (dev headers, ADMIN)            physician (no dev headers)
POST /v1/admin/doctors ─► PENDING ── invitationCode (shown once)
                                      POST /v1/doctor-activation/challenges {invitationCode} ─► nonce
                                      POST /v1/doctor-activation/google {challengeId,idToken}   (real Google ID token)
                                        or /dev-identity (dev opt-in, MOCK_IDENTITY)
                          IDENTITY_VERIFIED ◄─ binds iss+sub, consumes invitation, returns bearer session
                                      POST /v1/doctor-activation/consent (bearer)
                          CONSENTED   ◄─ physician's own consent event
                                      POST /v1/doctor-activation/activate (bearer)
                          ACTIVE      ◄─ only if key + certificate provisioning succeeded; else stays CONSENTED
PATCH /v1/admin/doctors/{id}/status ─► SUSPENDED | REVOKED (sessions dropped, signing refused immediately)
```

- Preregistration creates **no key and no certificate**. Keys are created only at `activate`, after consent.
- No admin route can verify identity, consent or activate for a physician. Consent/activate require the physician's bearer session.
- Three separate, immutable evidence records: `ADMIN_VERIFICATION` (admin + evidence refs, at preregistration), `LOGIN_EVIDENCE`
  (issuer, subject, mode, challenge, at), `CONSENT` (version, three flags, at). Audit events never contain codes, nonces, tokens,
  ID tokens or emails.
- Google proves control of a Google account whose verified email equals the preregistered `googleEmail` (used **only** for the
  first match). After that the canonical binding is `iss`+`sub`, globally unique among non-revoked doctors. Google does **not**
  verify real name or medical license; that rests on the admin's evidence review.
- `SUSPENDED` and `REVOKED` are terminal in this slice (no reinstate route). `REVOKED` also asks OpenBao PKI to revoke the
  certificate (best effort; local status blocks signing regardless).

## 2. Endpoints

Errors: `{code,message,correlationId,retryable}`. Lists: `{items,nextCursor:null}`. Timestamps ISO-8601 UTC. Bearer =
`Authorization: Bearer <activationToken>`. Physician routes ignore `X-Tenant-Id`/`X-Actor-Id`.

### Doctor object

```json
{"id":"uuid","tenantId":"demo-hospital-a","name":"김의사","licenseNumber":"123456","centerName":"서울영상센터",
 "googleEmail":"dr.kim@example.com","status":"PENDING|IDENTITY_VERIFIED|CONSENTED|ACTIVE|SUSPENDED|REVOKED",
 "identityMode":null|"GOOGLE"|"MOCK_IDENTITY","googleAccountLinked":false,
 "invitationState":"ISSUED|USED|EXPIRED|LOCKED|VOID","invitationExpiresAt":"…",
 "identityVerifiedAt":null,"consentedAt":null,"consentVersion":null,"activatedAt":null,
 "statusReason":null,"statusChangedAt":null,"createdAt":"…","createdBy":"admin-demo",
 "signingBackend":"NOT_CONFIGURED|OPENBAO_TRANSIT|DEV_SIMULATED","certificate":null|Certificate}
```

Certificate:
```json
{"certificateId":"<serial>","kind":"X509"|"SIMULATED_NOT_X509","backend":"OPENBAO_TRANSIT"|"DEV_SIMULATED",
 "subject":"CN=vc-doctor-<doctorId>,…","issuer":"…","serialNumber":"…","notBefore":"…","notAfter":"…",
 "publicKeySha256":"<hex of SubjectPublicKeyInfo DER>","keyName":"vc-doctor-<doctorId>","keyVersion":1,
 "status":"VALID|REVOKED|EXPIRED","certificatePem":"-----BEGIN CERTIFICATE-----…"|null}
```

### Public config
`GET /v1/doctor-activation/config` → `200`
```json
{"googleClientId":null,"googleConfigured":false,"devSimulationEnabled":false,
 "signingBackend":"NOT_CONFIGURED","signingBackendAvailable":false,"clinicalUseAllowed":false}
```

### Admin (dev headers, role ADMIN; DOCTOR gets `403 ADMIN_ROLE_REQUIRED`; tenant-scoped, other tenant → 404)
- `POST /v1/admin/doctors` body `{name,licenseNumber,centerName,googleEmail,identityEvidenceRef,licenseEvidenceRef}` →
  `201 {"doctor":Doctor,"invitationCode":"XXXXX-XXXXX-XXXXX-XXXXX-XXXXX","invitationExpiresAt":"…"}`.
  Rules: name 2–50, licenseNumber 1–20 digits, centerName 2–100, googleEmail ≤254 well-formed (stored lower-case),
  evidence refs 1–200 chars (opaque references, not documents). `409 DOCTOR_LICENSE_ALREADY_REGISTERED` (same tenant),
  `409 GOOGLE_EMAIL_ALREADY_REGISTERED` (global, non-revoked). No Idempotency-Key: the code is returned exactly once.
- `GET /v1/admin/doctors` → `{items:[Doctor]}` (never invitation secrets). `GET /v1/admin/doctors/{id}` → Doctor.
- `GET /v1/admin/doctors/{id}/evidence` → `{items:[…]}` of immutable records with `type` = `ADMIN_VERIFICATION`
  (adminActorId, evidence refs, at), `LOGIN_EVIDENCE` (identityMode, issuer, subject, purpose, challengeId, at) or
  `CONSENT` (version, three flags, identityMode, at).
- `POST /v1/admin/doctors/{id}/invitation` → `201` same shape as preregistration; only while `PENDING`; voids the old code.
- `PATCH /v1/admin/doctors/{id}/status` `{status:"SUSPENDED"|"REVOKED",reason}` (reason 2–200) → Doctor.
  `409 INVALID_DOCTOR_STATE` from REVOKED or SUSPENDED→SUSPENDED.

### Physician activation (public routes)
- `POST /v1/doctor-activation/challenges` `{invitationCode}` → `201 {"challengeId","nonce","expiresAt","purpose":"ACTIVATION"}`.
  Unknown/expired/used/locked/void code → `422 INVITATION_INVALID` (one code for all, no doctor data).
  `429 INVITATION_ATTEMPTS_EXCEEDED` after 10 failed codes per source per hour; `429 CHALLENGE_RATE_LIMITED` after 30
  challenges per source per hour; `429 DOCTOR_ACTIVATION_CAPACITY_EXCEEDED` when live challenges + sessions reach the configured bound. Challenge:
  10 min, single use (consumed by any identity attempt). Max 10 challenges per invitation. Source = socket address.
- `POST /v1/doctor-activation/login-challenges` `{}` → same shape with `"purpose":"LOGIN"` (returning doctors).
- `POST /v1/doctor-activation/google` `{challengeId,idToken}` → `200`
  `{"activationToken","expiresAt","identityMode":"GOOGLE","doctor":Doctor}`.
  Verified with Nimbus JOSE: RS256 signature against Google JWKS, `iss` ∈ {`https://accounts.google.com`,`accounts.google.com`},
  `aud` = configured client id, `exp`/`iat` (60 s skew), `nonce` = challenge nonce, `email_verified` = true.
  ACTIVATION: email must equal the invited `googleEmail` → `IDENTITY_VERIFIED`. LOGIN: finds the doctor by `iss`+`sub`.
  Errors: `503 GOOGLE_NOT_CONFIGURED`, `401 ID_TOKEN_INVALID`, `403 GOOGLE_EMAIL_MISMATCH`, `409 GOOGLE_ACCOUNT_ALREADY_BOUND`,
  `403 GOOGLE_ACCOUNT_NOT_LINKED` (login), `404 CHALLENGE_NOT_FOUND`, `410 CHALLENGE_EXPIRED`, `409 CHALLENGE_ALREADY_USED`,
  `423 INVITATION_LOCKED` (5 failed identity attempts; admin reissues), `403 DOCTOR_NOT_ALLOWED` (suspended/revoked).
- `POST /v1/doctor-activation/dev-identity` `{challengeId,email,subject}` → same result with `"identityMode":"MOCK_IDENTITY"`.
  Exists only in the dev profile **and** with `VISIONCERTI_DOCTOR_DEV_IDENTITY_ENABLED=true`, otherwise `404 DEV_IDENTITY_DISABLED`.
  Uses issuer `urn:vision-certi:mock-identity` (never Google's), so a mock binding can never satisfy the Google path.
- `POST /v1/doctor-activation/consent` (bearer) `{"version":"2026-10-13-draft","authentication":true,"signature":true,"privacy":true}`
  → `200 Doctor` (`CONSENTED`). `422 CONSENT_REQUIRED` / `CONSENT_VERSION_MISMATCH`, `409 INVALID_DOCTOR_STATE`.
- `POST /v1/doctor-activation/activate` (bearer) `{}` → `200 Doctor` (`ACTIVE`, with `certificate`). Already ACTIVE → `200` same.
  Failure leaves `CONSENTED`: `503 SIGNING_BACKEND_NOT_CONFIGURED` (retryable false), `502 SIGNING_BACKEND_UNAVAILABLE`
  (retryable true; also covers a certificate chain that does not verify), `502 CERTIFICATE_KEY_MISMATCH`,
  `502 SIGNING_KEY_POLICY_VIOLATION` (existing key not non-exportable ecdsa-p256), `502 CERTIFICATE_NOT_VALID`,
  `409 ACTIVATION_IN_PROGRESS`. The message names the cause (never secrets).
- `GET /v1/doctor-activation/me` (bearer) → `{"doctor":Doctor,"certificate":Certificate|null,"identityMode","sessionExpiresAt"}`.
- Session errors: `401 DOCTOR_SESSION_REQUIRED` (missing/unknown/expired), `403 DOCTOR_NOT_ALLOWED` (suspended/revoked; session deleted).

### Physician signing (bearer, ACTIVE only)
Admin creates documents and sign requests with the existing routes using `signerId = <doctor id>`; only ACTIVE doctors of the
same tenant are eligible (`422 SIGNER_NOT_ELIGIBLE`). Dev-header actors can never approve these requests.
- `GET /v1/doctor/sign-requests` → `{items:[SignRequest]}` (own requests; `nonce` shown while `PENDING_APPROVAL`).
- `GET /v1/doctor/sign-requests/{id}`.
- `POST /v1/doctor/sign-requests/{id}/approve` `{documentSha256,nonce}` → `202 SignRequest` (`COMPLETED`, `signatureId`).
  Before signing, every time: session valid, doctor `ACTIVE` in the request's tenant, certificate within validity, not revoked
  locally and (OpenBao) `revocation_time == 0` from `GET pki/cert/{serial}`, request bound to this doctor, not expired, nonce
  unused and equal, version still latest, hash equal and re-hashed from stored bytes. After the backend returns the
  signature the doctor/key/certificate are re-checked (a suspension or revocation during signing discards the signature and
  marks the request `FAILED`), and the signature is verified against the certified public key before it is stored.
  Errors as the legacy approve plus `403 DOCTOR_NOT_ACTIVE`, `403 CERTIFICATE_NOT_VALID`, `502 SIGNING_BACKEND_UNAVAILABLE`,
  `502 SIGNATURE_SELF_CHECK_FAILED`. Explicit approval (hash + one-time nonce) is always required; there is no auto-sign.
- The signature (`GET /v1/signatures`) gets `doctorEvidence`: `{doctorId,identityMode,identityIssuer,identitySubject,
  certificateId,certificateKind,keyName,keyVersion,approvalId,reportId,documentVersionId,documentSha256,signedAt,auditId,
  signatureFormat:"RAW_ECDSA_P256_SHA256_DER",signingBackend,cryptographicallyAttributable,revocationCheckedAtSigning,
  publicKeySpki}` — a raw ECDSA signature, **not CAdES/PAdES**. `auditId` is the `SIGN_REQUEST_APPROVED` audit event.
- `POST /v1/signatures/{id}/verify` for these: real ECDSA check over the stored bytes with the recorded key. OpenBao:
  `signature: VALID`, `revocationAtSigning: VALID`, `authorizationAtSigning: VALID` (GOOGLE identity), `trustChain` and
  `timestamp: INDETERMINATE` → overall `INDETERMINATE`. DEV_SIMULATED or MOCK_IDENTITY: `signature`/`authorization`
  `INDETERMINATE` with `SIMULATED_KEY_SIGNATURE_INTACT` / `MOCK_IDENTITY`. Overall is never `VALID`.
  Legacy synthetic signatures keep `doctorEvidence:null` and `MOCK_NOT_VALID_FOR_CLINICAL_USE`.

## 3. Configuration (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `VISIONCERTI_GOOGLE_CLIENT_ID` | empty → Google unavailable | OAuth client id = required `aud` |
| `VISIONCERTI_GOOGLE_JWKS_URI` | `https://www.googleapis.com/oauth2/v3/certs` | JWKS for signature verification |
| `VISIONCERTI_DOCTOR_DEV_IDENTITY_ENABLED` | `false` | dev-profile-only MOCK_IDENTITY route |
| `VISIONCERTI_SIGNING_BACKEND` | `NOT_CONFIGURED` | `OPENBAO_TRANSIT` or `DEV_SIMULATED` (dev profile only); no fallback between them |
| `VISIONCERTI_OPENBAO_ADDR` | empty | `https://…` (any host) or `http://` **only** to `localhost`/`127.0.0.0/8`/`::1` |
| `VISIONCERTI_OPENBAO_NAMESPACE` | empty | sent as `X-Vault-Namespace` when set |
| `VISIONCERTI_OPENBAO_PROVISIONING_TOKEN` | empty | Transit key create/read, PKI sign, PKI revoke |
| `VISIONCERTI_OPENBAO_SIGNING_TOKEN` | empty | Transit sign only; must differ from the provisioning token |
| `VISIONCERTI_OPENBAO_TRANSIT_MOUNT` | `transit` | |
| `VISIONCERTI_OPENBAO_PKI_MOUNT` | `pki` | |
| `VISIONCERTI_OPENBAO_PKI_ROLE` | `vision-certi-doctor` | role used by `pki/sign/<role>` |
| `VISIONCERTI_OPENBAO_KEY_PREFIX` | `vc-doctor` | key name = `<prefix>-<doctorId>` (deterministic) |
| `VISIONCERTI_OPENBAO_CERT_TTL` | `8760h` | requested certificate TTL |
| `VISIONCERTI_OPENBAO_CA_CERT_FILE` | empty | PEM bundle to trust for the OpenBao TLS endpoint |
| `VISIONCERTI_OPENBAO_TIMEOUT` | `PT10S` | per HTTP call |

### OpenBao 2.7.1 calls made (API docs: /api/secret/transit/, /api/secret/pki/)

| Step | Call | Token |
|---|---|---|
| key read | `GET /v1/<transit>/keys/<name>` (404 → create) | provisioning |
| key create | `POST /v1/<transit>/keys/<name>` `{"type":"ecdsa-p256","exportable":false,"allow_plaintext_backup":false}` | provisioning |
| key check | `type == ecdsa-p256`, `exportable == false`, pin `latest_version`, read `keys.<v>.public_key` | |
| CSR | PKCS#10 `CN=<keyName>, O=Vision-Certi DEV` built with BouncyCastle, signed through Transit (below) | signing |
| issue | `POST /v1/<pki>/sign/<role>` `{"csr","common_name":"<keyName>","ttl","format":"pem","exclude_cn_from_sans":true}` | provisioning |
| verify | certificate public key must equal the Transit public key (`CERTIFICATE_KEY_MISMATCH`); `issuing_ca` is required and must have signed the certificate (issuer DN + signature); each `ca_chain` element must be signed by the next; every element within validity; serial must match | |
| sign | `POST /v1/<transit>/sign/<name>` `{"input":base64(sha256),"prehashed":true,"hash_algorithm":"sha2-256","marshaling_algorithm":"asn1","key_version":<pinned>}` → `vault:v<pinned>:<b64 DER>`; verified locally before use | signing |
| status | `GET /v1/<pki>/cert/<serial>` → `revocation_time == 0` before every signature | none |
| revoke | `POST /v1/<pki>/revoke` `{"serial_number"}` on admin REVOKED | provisioning |

Suggested policies (revised 2026-10-13 after root's real OpenBao policy test):
- **Do not use a `*` glob on Transit key paths.** `path "<transit>/keys/<prefix>-*"` also matches `…/keys/<name>/config`
  (and `/rotate`, `/trim`, …), so it would let the provisioning token re-enable deletion or export. A separate
  `deny` on `<transit>/keys/+/config` does **not** fix this: it loses the OpenBao path-priority comparison to the longer
  prefix glob.
- Preferred: a **dedicated Transit mount** that holds only physician keys
  (`VISIONCERTI_OPENBAO_TRANSIT_MOUNT`). Provisioning token: `create,read,update` on `<mount>/keys/+`. The single-segment
  `+` does not match `<mount>/keys/<name>/config`; root verified that this returns 403. Signing token: `update` on
  `<mount>/sign/+` only.
- Alternative on a shared mount: exact per-doctor paths (`<transit>/keys/<prefix>-<doctorId>`, and
  `<transit>/sign/<prefix>-<doctorId>`), written when the doctor is provisioned.
- PKI: `update` on `<pki>/sign/<role>` and `<pki>/revoke` (provisioning token only). The status lookup
  `GET <pki>/cert/<serial>` needs no token.

The backend only ever calls `<transit>/keys/<name>` (read/create) and `<transit>/sign/<name>`, so single-segment rules
are sufficient. PKI role: `allow_any_name=true`,
`enforce_hostnames=false`, `key_type=ec`, `key_bits=256`, `key_usage=["DigitalSignature","ContentCommitment"]`,
`ext_key_usage=[]`, `no_store=false` (needed for status lookups). Keep Transit `deletion_allowed=false`.

Retry is idempotent for the key (same name, existing key reused, type/exportability re-checked); a retried activation issues a
new certificate (an earlier certificate whose response was lost remains in PKI unused).

## 4. Honest limits

- OpenBao Transit is software key storage unless OpenBao itself is backed by an HSM; auto-unseal is **not** an HSM. No claim of HSM.
- OpenBao PKI is a private CA: verification reports `trustChain: INDETERMINATE`. No RFC 3161 timestamp, no CAdES/PAdES, no
  OCSP/CRL publication by this backend; overall verification status is never `VALID`.
- `DEV_SIMULATED`: in-memory ECDSA key per doctor, lost on restart; its "certificate" is a labeled descriptor, **not X.509**.
- `MOCK_IDENTITY`: no proof of anything; dev opt-in only.
- Sessions (token SHA-256 only), invitations (hash only), doctors and evidence are durable rows; activation keeps a lease so a retry after a crash reuses the same Transit key. In prod staff use OIDC; see PRODUCTION_RUNTIME.md.
