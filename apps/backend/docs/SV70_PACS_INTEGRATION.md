# SV70 PACS ↔ Vision-Certi integration (Vision-Certi side)

Status: implemented and tested in `apps/backend` (dev and prod profiles; Vision-Certi state is durable in PostgreSQL in prod). The PACS side lives in `SaintViewPACS-…/webpacs_api/app/integrations/vision_certi`, and its doc is `webpacs_api/docs/VISION_CERTI_INTEGRATION.md`. The cross-team contract, including the PACS MySQL tables, is `work/sv70-api-db-contract.md`.

**This is not production-complete.** It still lacks:
- a real accredited CA root and CRL feed;
- validation against the actual issuing CA's certificate/CRL files and the target physician PCs;
- durable Vision-Certi storage for handoffs, nonces, idempotency and evidence (the proposed tables are in §6);
- production staff authentication.

## 1. Flow

1. The physician logs in at Vision-Certi with the certificate (existing `/v1/certificate-enrollment/login`), which gives a VC bearer.
2. `POST /v1/doctor/pacs-handoffs` (VC bearer) returns a one-time handoff code. The browser POSTs it to PACS `/api/vc/auth/certificate-login`.
3. The PACS server calls `POST /v1/integrations/pacs/handoffs/redeem` (HMAC) and gets the verified identity. It maps that identity to a local user only through an admin link, and issues its own `vc1.` bearer.
4. On "approve", the PACS server builds the report snapshot from its DB and calls `POST /v1/integrations/pacs/report-sign-requests` (HMAC, `Idempotency-Key`).
5. The physician reads `GET /v1/doctor/sign-requests/{id}/document` (exact JSON bytes). The browser gets `GET /v1/doctor/sign-requests/{id}/certificate-payload`, signs those exact bytes with the locally opened PFX/P12 or NPKI private key, and calls `POST /v1/doctor/sign-requests/{id}/approve` with `reviewAttestation`, public certificate DER and signature. Signing then:
   - re-validates the certificate (path + CRL);
   - checks the external signature against the enrolled certificate fingerprint and bound report ID, study ID, version, hash, signer and nonce;
   - checks the enrollment binding before and after the OpenBao call;
   - signs with the physician's OpenBao Transit key;
   - verifies the signature over the stored bytes.
6. The PACS server calls `GET /v1/integrations/pacs/report-sign-requests/{id}` (HMAC). It checks the evidence and verifies the ECDSA signature over its own stored snapshot, and only then writes A/RA.

PACS never receives the VC bearer or the approval nonce, so it cannot approve on the physician's behalf.

## 2. Service authentication (`/v1/integrations/pacs/**`)

`IntegrationAuthFilter` handles these routes. Dev headers and doctor bearers are never accepted on them, and `DevHeaderAuthFilter` skips the prefix.

```
X-VC-Client-Id, X-VC-Timestamp (unix s, ±300 s), X-VC-Nonce ([A-Za-z0-9_-]{22,64}, single use / 10 min),
X-VC-Content-SHA256 (hex of exact body), X-VC-Signature = base64url-nopad(HMAC-SHA256(secret, canonical))
canonical = "VC-HMAC-SHA256-V1\n" + audience + "\n" + clientId + "\n" + METHOD + "\n" + rawPath + "\n"
            + rawQuery + "\n" + timestamp + "\n" + nonce + "\n" + contentSha256
```

- The body limit is 512 KiB. Unusual paths (`..`, `//`, encoded characters) are rejected.
- A shared test vector is asserted by both `PacsIntegrationApiTest` and the PACS tests.

**Errors**
- `401 INTEGRATION_AUTH_INVALID` (generic).
- `409 INTEGRATION_NONCE_REPLAYED`.
- `403 INTEGRATION_TENANT_FORBIDDEN`.
- `503 INTEGRATION_NOT_CONFIGURED`.

**Configuration**

| Variable | Default | Meaning |
|---|---|---|
| `VISIONCERTI_PACS_CLIENT_ID` | empty → not configured (503) | `[A-Za-z0-9._-]{3,64}` |
| `VISIONCERTI_PACS_CLIENT_SECRET_FILE` | empty | file holding the ≥32-byte shared secret, never logged. Set but unusable means startup fails (FAIL_CLOSED) |
| `VISIONCERTI_PACS_AUDIENCE` | `vision-certi:pacs-integration:v1` | part of the signed string |
| `VISIONCERTI_PACS_TENANT_IDS` | empty (required when a client id is set) | comma list; the tenant scope |
| `VISIONCERTI_PACS_HANDOFF_TTL` | `PT60S` | handoff code lifetime |
| `VISIONCERTI_PACS_MAX_CERTIFICATE_LOGIN_AGE` | `PT10M` | how fresh the certificate login must be to issue a handoff |

## 3. Routes

### `POST /v1/doctor/pacs-handoffs` (physician bearer)

Body: `{"audience":"<pacs clientId>"}`. Returns `201 {handoffCode, expiresAt, audience, tenantId}` with `Cache-Control: no-store`.

**Requirements**
- identityMode `PC_CERTIFICATE`, `MOBILE_CERTIFICATE` or `MOCK_CERTIFICATE`;
- authenticated ≤ 10 min ago;
- an ACTIVE doctor with a credential;
- the ACTIVE enrollment re-validates now (path + CRL), and it is the certificate this session proved;
- the audience is the configured client, and the tenant is in its scope.

**Errors**
- `401 DOCTOR_SESSION_REQUIRED`.
- `403 CERTIFICATE_SESSION_REQUIRED`.
- `403 CERTIFICATE_LOGIN_TOO_OLD`.
- `403 CERTIFICATE_ENROLLMENT_NOT_VALID` / `CERTIFICATE_ENROLLMENT_CHANGED` / `CERTIFICATE_ENROLLMENT_REQUIRED`.
- `403 DOCTOR_NOT_ACTIVE`.
- `403 INTEGRATION_TENANT_FORBIDDEN`.
- `422 HANDOFF_AUDIENCE_INVALID`.
- `503 INTEGRATION_NOT_CONFIGURED`.

The code is 32 random bytes, stored as SHA-256, single use, and never logged.

### `POST /v1/integrations/pacs/handoffs/redeem` (HMAC)

Body: `{"handoffCode"}`. Returns `200`:

```json
{tenantId, doctorId, doctorStatus:"ACTIVE", identityMode, certificateEnrollmentId, certificateFingerprintSha256,
 authenticatedAt, sessionExpiresAt, verifiedAt, activeSigner:true}
```

The code is consumed on any attempt. An unknown, expired or used code, another client, or a tenant out of scope gives `401 HANDOFF_INVALID`. At redeem time the backend re-checks that the session is live, the doctor is ACTIVE and the same enrollment is ACTIVE/GOOD; otherwise `403 HANDOFF_IDENTITY_NOT_VALID`.

### `POST /v1/integrations/pacs/report-sign-requests` (HMAC, `Idempotency-Key` required)

Body: `{tenantId, doctorId, externalReportId, externalStudyId, revision, title, contentType, contentBase64, contentSha256}`.
- `contentType` must be `application/vnd.saintview.pacs-report+json;version=1`.
- The content must be strict UTF-8 JSON of at most 256 KiB, with `schema` = `saintview-pacs-report-snapshot/v1`.
- Its `tenantId`, `doctorId`, `report.externalReportId` and `report.revision` must equal the request.

It returns `201 {signRequestId, documentId, documentVersionId, documentSha256, signerId, status, expiresAt, revision}`. An idempotent replay returns the stored `201` response. The nonce is never returned.

**Storage**
- There is one document per `externalReportId`, never a PDF.
- New bytes append an immutable version, so older pending requests become `DOCUMENT_VERSION_SUPERSEDED` at approval.
- Identical bytes return the existing PENDING request.

**Errors**
- `422 VALIDATION_FAILED`, `REPORT_SNAPSHOT_INVALID`, `SIGNER_NOT_ELIGIBLE`, `IDEMPOTENCY_KEY_REQUIRED`.
- `409 DOCUMENT_HASH_MISMATCH`, `IDEMPOTENCY_KEY_REUSED`, `DOCUMENT_METADATA_CONFLICT`.
- `403 INTEGRATION_TENANT_FORBIDDEN`.

### `GET /v1/integrations/pacs/report-sign-requests/{id}` (HMAC)

Returns `200 {signRequestId, tenantId, status, documentVersionId, documentSha256, signerId, expiresAt, approvedAt, signature}`. `signature` is either null or:

```json
{signatureId, algorithm:"ECDSA_P256_SHA256", signatureMode, signatureValueBase64 (DER), signedAt, documentSha256,
 doctorEvidence:{doctorId, identityMode, certificateId, certificateKind, keyName, keyVersion, approvalId, reportId,
   documentVersionId, documentSha256, signedAt, auditId, signatureFormat, signingBackend,
   cryptographicallyAttributable, revocationCheckedAtSigning, publicKeySpki, certificateEnrollmentId,
   certificateEnrollmentFingerprint}}
```

Only requests this client created are visible; any other id returns `404`.

### Physician routes (existing; changes are additive)

- `GET /v1/doctor/sign-requests/{id}/document` now serves the version's own `Content-Type`: PDF, or the report JSON type above.
- `GET /v1/doctor/sign-requests/{id}/certificate-payload` (physician bearer) returns `{signedPayloadBase64}` for the pending PACS report. The value is binary length-prefixed UTF-8 fields; sign the decoded bytes exactly. It includes protocol version, tenant, request/document/version IDs, report/study IDs, SHA-256, signer ID, nonce and review attestation.
- `POST /v1/doctor/sign-requests/{id}/approve` accepts `reviewAttestation`, `certificateDerBase64`, `externalSignatureBase64`, and `externalSignatureAlgorithm` (`SHA256withRSA` or `SHA256withECDSA`). For report snapshots the attestation must be `REVIEWED_EXACT_REPORT_CONTENT` and the registered external certificate signature is mandatory; PDFs ignore these extra fields.

## 4. Tests (`integration/PacsIntegrationApiTest`, 5)

1. **Cross-language vector:** the HMAC vector shared with Python.
2. **Service auth:** rejects no headers, dev headers, a wrong secret, a stale timestamp, a tampered body, and a signature for another path; it accepts a correct request and refuses a replayed nonce.
3. **Handoff:**
   - It needs the doctor's bearer; a wrong audience is refused.
   - Redeem returns the enrollment and fingerprint and is single use.
   - After unregister, an earlier code cannot be redeemed and no new code can be issued.
4. **Report snapshot:**
   - It is refused for a missing idempotency key, a hash mismatch, a foreign doctor in the snapshot, and a tenant out of scope.
   - Idempotent replay works, and the same key with a different body is refused.
   - The nonce is never exposed to the service.
   - The physician sees the exact JSON bytes with their content type. Approval without the attestation is refused (422); with it, the request reaches COMPLETED.
   - The service gets evidence that **verifies (ECDSA) over the exact snapshot bytes**.
   - A new revision gives a new version and a new request.
5. **Visibility:** the service cannot read requests it did not create, and PDFs keep `application/pdf`.

## 5. Limits

- **State is durable** (`certi_rt`, migration V005): handoffs (code hash), the HMAC nonce replay cache, idempotency records, documents, requests and signatures survive restarts and are shared by all instances (see PRODUCTION_RUNTIME.md; replay after restart is covered by `ProductionRuntimeTest`).
  - The PACS side keeps its own durable state and evidence (MySQL) and treats VC unavailability as retryable, never as approved.
- **One client:** only one integration client is configured at a time.
- **Shared secret:** HMAC uses a shared secret. Rotation means restarting with a new file, coordinated with PACS.
- **Signatures:** OpenBao signatures are raw ECDSA over SHA-256 of the snapshot bytes. There is no CAdES/PAdES, no RFC 3161 timestamp, and no accredited-CA signing certificate; the PKI is a private OpenBao CA.

## 6. Vision-Certi tables

Implemented by `database/migrations/V005__production_runtime.sql` (schema `certi_rt`): `pacs_handoffs`
(`code_hash` UNIQUE, consumed on first redeem under a row lock), `integration_nonces` (`nonce_key` UNIQUE,
10-minute expiry), `idempotency_records` (`scope_hash` UNIQUE), `documents` / `document_versions` / `blobs`
(content type kept per version), `sign_requests`, `signatures` (UNIQUE per request). See PRODUCTION_RUNTIME.md.
