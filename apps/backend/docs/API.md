# Vision-Certi backend API — development slice (v1)

This file describes what `apps/backend` implements **today**. The target contract is
`docs/obsidian/04_API_연동계약.md` (owned by Codex); differences are listed at the end.
The examples below use the `dev` profile (dev headers, mock signing; in-memory storage unless PostgreSQL is configured). In `prod`, staff routes take an OIDC bearer token instead of dev headers and storage is PostgreSQL; see [PRODUCTION_RUNTIME.md](PRODUCTION_RUNTIME.md).

## Conventions

| Item | Value |
|---|---|
| Base path | `/v1` |
| JSON | lowerCamelCase, UTC ISO-8601 instants (`2026-10-13T05:36:27.325864Z`), `null` fields are included |
| IDs | Resource IDs are UUID strings. **Dev** `tenantId` / actor IDs are slugs (`demo-hospital-a`, `doctor-demo`) |
| Dev identity | `X-Tenant-Id`: `demo-hospital-a` or `demo-hospital-b`; `X-Actor-Id`: `doctor-demo` (DOCTOR) or `admin-demo` (ADMIN). Both actors belong to both tenants. **Not authentication**, only exists in the `dev` profile |
| Correlation | Optional `X-Correlation-Id` (`[A-Za-z0-9-]{1,64}`); otherwise generated. Echoed in the response header and in error bodies |
| Idempotency | `Idempotency-Key` (`[A-Za-z0-9._:-]{8,128}`) **required** on `POST /documents`, `POST /documents/{id}/versions`, `POST /sign-requests`, `POST /sign-requests/{id}/approve`. Same key + same payload → stored response replayed (same status and body). Same key + different payload → `409 IDEMPOTENCY_KEY_REUSED`. Failed calls are not stored. Scope: tenant + actor + operation (stored as SHA-256). Durable, shared by all instances; a concurrent duplicate gets `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` (retryable). Records are not pruned yet |
| List envelope | `{"items":[...],"nextCursor":null}` — no pagination yet, newest first |
| Error body | `{"code":"...","message":"...","correlationId":"...","retryable":false}` |

### Status codes

| Code | Used for |
|---|---|
| 200 / 201 / 202 | read / created / approval accepted |
| 400 `MALFORMED_REQUEST` | unparseable JSON, multipart or path UUID |
| 401 `DEV_IDENTITY_REQUIRED`, `DEV_IDENTITY_UNKNOWN` | missing/unknown dev headers |
| 403 | `SIGNING_NOT_PERMITTED_FOR_ROLE`, `SIGNER_MISMATCH` |
| 404 | resource missing **or belonging to another tenant** (`DOCUMENT_NOT_FOUND`, `DOCUMENT_VERSION_NOT_FOUND`, `SIGN_REQUEST_NOT_FOUND`, `SIGNATURE_NOT_FOUND`, `DELIVERY_NOT_FOUND`, `NOT_FOUND`) |
| 409 | `IDEMPOTENCY_KEY_REUSED`, `DOCUMENT_VERSION_CONFLICT`, `DOCUMENT_METADATA_CONFLICT`, `DOCUMENT_VERSION_SUPERSEDED`, `DOCUMENT_VERSION_UNCHANGED`, `SIGN_REQUEST_ALREADY_PENDING`, `NONCE_ALREADY_USED`, `NONCE_INVALID`, `DOCUMENT_HASH_MISMATCH`, `INVALID_STATE` |
| 410 | `SIGN_REQUEST_EXPIRED` |
| 413 | `DOCUMENT_TOO_LARGE` (> 20 MiB) |
| 415 | `INVALID_PDF` (no `%PDF-` header), `UNSUPPORTED_MEDIA_TYPE` |
| 422 | `VALIDATION_FAILED`, `IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_KEY_INVALID`, `SIGNER_NOT_ELIGIBLE`, `SIGNER_NOT_ASSIGNED`, `INVALID_PDF` (empty file) |
| 500 | `INTERNAL_ERROR` (`retryable: true`), `STORED_DOCUMENT_CORRUPTED` |

## Endpoints

### `GET /v1/health` (no dev headers needed)

```json
{"status":"UP","service":"vision-certi-backend","activeProfiles":["dev"],"persistence":"IN_MEMORY",
 "signatureMode":"MOCK_NOT_VALID_FOR_CLINICAL_USE","devSyntheticIdentities":true,"clinicalUseAllowed":false}
```

### `POST /v1/documents` — register a document (multipart/form-data)

Parts:
- `metadata` — JSON text (part content type may be `application/json` or absent):
  `{"externalDocumentId":"...","externalStudyId":"...","title":"...","signerId":"doctor-demo"}`
  (lengths: ids/signer ≤ 128, title ≤ 256; `signerId` must be a DOCTOR of the tenant)
- `file` — PDF bytes (must start with `%PDF-`, ≤ 20 MiB). The server computes SHA-256 over the exact bytes.

Responses: `201` new document; `200` identical registration already exists (same bytes + metadata);
`409 DOCUMENT_VERSION_CONFLICT` same `externalDocumentId` with different bytes (use the versions endpoint);
`409 DOCUMENT_METADATA_CONFLICT` same bytes, different metadata.

`DocumentResponse`:

```json
{
  "id": "uuid", "tenantId": "demo-hospital-a",
  "externalDocumentId": "smoke-1", "externalStudyId": "st-1", "title": "Smoke", "signerId": "doctor-demo",
  "createdAt": "...", "updatedAt": "...", "createdBy": "admin-demo",
  "latestVersion": { DocumentVersion },
  "versions": [ DocumentVersion, ... ]          // ascending versionNumber, never rewritten
}
```

`DocumentVersion`:

```json
{"id":"uuid","documentId":"uuid","versionNumber":1,"sha256":"64 lowercase hex","sizeBytes":20,
 "contentType":"application/pdf","createdAt":"...","createdBy":"admin-demo",
 "previousVersionId":null,"amendmentReason":null}
```

### `POST /v1/documents/{id}/versions` — append a version (multipart/form-data)

Parts/fields: `file` (PDF), `previousVersionId` (UUID, must be the current latest version), `amendmentReason` (1–1000 chars).
`201` → full `DocumentResponse`. `409 DOCUMENT_VERSION_SUPERSEDED` if `previousVersionId` is stale,
`409 DOCUMENT_VERSION_UNCHANGED` if bytes equal the latest version. Earlier versions are untouched;
pending sign requests for an older version can no longer be approved.

### `GET /v1/documents` → `{items:[DocumentResponse], nextCursor:null}`
### `GET /v1/documents/{id}` → `DocumentResponse`
### `GET /v1/documents/{id}/versions/{versionId}/content`

Exact stored bytes, `Content-Type: application/pdf`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`,
`Content-Security-Policy: sandbox; default-src 'none'`, `X-Document-Sha256: <hex>`.

### `POST /v1/sign-requests`

Body `{"documentVersionId":"uuid","signerId":"doctor-demo"}`. Version must be the latest of its document,
`signerId` must be a DOCTOR of the tenant and equal the document's `signerId`. One pending request per
version+signer (`409 SIGN_REQUEST_ALREADY_PENDING`). TTL 10 min (`visioncerti.sign-requests.ttl`). → `201 SignRequest`.

`SignRequest`:

```json
{
  "id": "uuid", "tenantId": "demo-hospital-a", "documentId": "uuid", "documentVersionId": "uuid",
  "documentSha256": "64 lowercase hex", "signerId": "doctor-demo",
  "nonce": "base64url(32 random bytes) | null",
  "status": "PENDING_APPROVAL",
  "signatureMode": "MOCK_NOT_VALID_FOR_CLINICAL_USE",
  "createdAt": "...", "createdBy": "admin-demo", "expiresAt": "...",
  "approvedAt": null, "approvedBy": null, "signatureId": null
}
```

`nonce` is returned **only** to the bound signer (DOCTOR whose id equals `signerId`) and only while
`PENDING_APPROVAL`; it is `null` for everyone else, and never written to audit events.
`status` ∈ `PENDING_APPROVAL, APPROVED, SIGNING, EVIDENCE_PENDING, COMPLETED, EXPIRED, REJECTED, FAILED, RECONCILIATION_REQUIRED`
(this slice produces `PENDING_APPROVAL`, `COMPLETED`, `EXPIRED`, `FAILED`). A pending request past `expiresAt`
is moved to `EXPIRED` on the next read or approval attempt.

### `GET /v1/sign-requests` → `{items:[SignRequest]}` / `GET /v1/sign-requests/{id}` → `SignRequest`

### `POST /v1/sign-requests/{id}/approve`

Body `{"documentSha256":"64 lowercase hex","nonce":"..."}`. Checks, in order: tenant (404) → role DOCTOR
(`403 SIGNING_NOT_PERMITTED_FOR_ROLE`; admins can never sign) → caller is the bound signer (`403 SIGNER_MISMATCH`)
→ not expired (`410`) → nonce not yet used (`409 NONCE_ALREADY_USED`) → status pending (`409 INVALID_STATE`)
→ version still latest (`409 DOCUMENT_VERSION_SUPERSEDED`) → hash equals bound hash (`409 DOCUMENT_HASH_MISMATCH`)
→ nonce matches (`409 NONCE_INVALID`) → stored bytes still hash to the bound value. Every refusal is audited
with outcome `DENIED`.

On success → `202 SignRequest`. Mock signing runs synchronously, so the body already shows `status: "COMPLETED"`,
`signatureId`, `approvedAt`, `approvedBy`, and a `QUEUED` delivery exists. `COMPLETED` here means "mock
signature recorded", **not** a clinically valid signature — see `signatureMode`.

### `GET /v1/signatures` → `{items:[Signature]}`

```json
{"id":"uuid","tenantId":"demo-hospital-a","signRequestId":"uuid","documentId":"uuid","documentVersionId":"uuid",
 "documentSha256":"hex","signerId":"doctor-demo","signatureMode":"MOCK_NOT_VALID_FOR_CLINICAL_USE",
 "algorithm":"MOCK-HMAC-SHA256","signatureValue":"base64","clinicalUseAllowed":false,"createdAt":"..."}
```

### `POST /v1/signatures/{id}/verify` (no body, no Idempotency-Key)

```json
{
  "signatureId": "uuid",
  "status": "INDETERMINATE",
  "signatureMode": "MOCK_NOT_VALID_FOR_CLINICAL_USE",
  "clinicalUseAllowed": false,
  "mockSignatureIntact": true,
  "checks": {
    "documentIntegrity": "VALID",
    "signature": "INDETERMINATE",
    "trustChain": "INDETERMINATE",
    "timestamp": "INDETERMINATE",
    "revocationAtSigning": "INDETERMINATE",
    "authorizationAtSigning": "INDETERMINATE"
  },
  "reasonCodes": ["MOCK_SIGNATURE_INTEGRITY_OK","MOCK_NOT_VALID_FOR_CLINICAL_USE","TRUST_CHAIN_NOT_AVAILABLE",
                  "MISSING_TRUSTED_TIMESTAMP","REVOCATION_EVIDENCE_NOT_AVAILABLE","AUTHORIZATION_EVIDENCE_NOT_VERIFIABLE"],
  "verifiedAt": "..."
}
```

`status` / check values ∈ `VALID | INVALID | INDETERMINATE`. Overall `status` is never `VALID` in this slice;
it is `INVALID` when the stored bytes no longer match (`DOCUMENT_HASH_MISMATCH`) or the mock value does not
verify (`SIGNATURE_VALUE_MISMATCH`), otherwise `INDETERMINATE`. `documentIntegrity: VALID` only means the stored
bytes still match the bound SHA-256.

### `GET /v1/deliveries` → `{items:[Delivery]}` / `GET /v1/deliveries/{id}` → `Delivery`

```json
{"id":"uuid","tenantId":"demo-hospital-a","signatureId":"uuid","signRequestId":"uuid","documentId":"uuid",
 "connectorId":"mock-connector","eventType":"SIGNATURE_COMPLETED","destination":"mock://demo-hospital-a/report-receiver",
 "status":"QUEUED","attempts":0,"createdAt":"...","updatedAt":"..."}
```

`status` ∈ `QUEUED, SENDING, ACKED, RETRY_WAIT, DEAD_LETTER`; no sender exists, so only `QUEUED` occurs.

### `GET /v1/audit-events` → `{items:[AuditEvent]}` (newest first)

```json
{"id":"uuid","tenantId":"demo-hospital-a","sequence":7,"occurredAt":"...","actorId":"admin-demo",
 "action":"SIGN_REQUEST_APPROVAL_DENIED","targetType":"SIGN_REQUEST","targetId":"uuid","outcome":"DENIED",
 "correlationId":"...","details":{"code":"SIGNING_NOT_PERMITTED_FOR_ROLE","role":"ADMIN"},
 "previousHash":"hex","hash":"hex"}
```

Actions: `DOCUMENT_VERSION_REGISTERED`, `SIGN_REQUEST_CREATED`, `SIGN_REQUEST_APPROVED`, `SIGN_REQUEST_APPROVAL_DENIED`,
`SIGN_REQUEST_EXPIRED` (actor `system`), `SIGNATURE_CREATED`, `SIGNATURE_VERIFIED`, `DELIVERY_QUEUED`.
`hash` = SHA-256 chain per tenant (first `previousHash` is 64 zeros). In-process only, not WORM.

### `GET /v1/dashboard`

```json
{"tenantId":"demo-hospital-a","generatedAt":"...","signatureMode":"MOCK_NOT_VALID_FOR_CLINICAL_USE",
 "persistence":"IN_MEMORY","documents":1,"documentVersions":1,
 "signRequestsByStatus":{"PENDING_APPROVAL":0,"APPROVED":0,"SIGNING":0,"EVIDENCE_PENDING":0,"COMPLETED":1,
                         "EXPIRED":0,"REJECTED":0,"FAILED":0,"RECONCILIATION_REQUIRED":0},
 "signatures":1,
 "deliveriesByStatus":{"QUEUED":1,"SENDING":0,"ACKED":0,"RETRY_WAIT":0,"DEAD_LETTER":0},
 "auditEvents":8}
```

## Differences from `04_API_연동계약.md`

| Topic | Contract | This slice |
|---|---|---|
| Tenant / signer IDs | UUID examples | dev slugs (`demo-hospital-a`, `doctor-demo`); approved for dev and typed as strings in `contracts/openapi.dev.json` |
| Pagination | `cursor`, `limit`, `nextCursor` | `nextCursor` always `null`, no query params |
| Approval | 202 + async worker | 202, but signing completes synchronously in-process |
| Identity, credential, evidence APIs | `/identities/proof`, `/credentials`, `/signatures/{id}/evidence`, `POST /deliveries` | not implemented |
| Extra endpoints | — | `GET /v1/health`, `GET /v1/sign-requests`, `GET /v1/deliveries` |
| Idempotency retention | 24 h | process lifetime |
| Audit filters / masking | period, action, target; masked | no filters; synthetic data only |
