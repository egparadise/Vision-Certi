# Vision-Certi integration (SV70): certificate login and certificate-signed report approval

Code: `app/integrations/vision_certi/` (pure, testable). Wiring: `app/router/VisionCerti.py`. Tests: `tests/vision_certi/`.

The cross-team contract (API + MySQL DDL contract) is `work/sv70-api-db-contract.md` in the Vision-Certi workspace. The Vision-Certi side is documented in `apps/backend/docs/SV70_PACS_INTEGRATION.md`.

## 1. Default and fail-closed behaviour

| `VISION_CERTI_INTEGRATION_ENABLED` | State | Effect |
|---|---|---|
| unset / false (default) | `DISABLED` | legacy behaviour unchanged; `/api/vc/*` returns `404 VC_INTEGRATION_DISABLED` (except `/api/vc/config`) |
| true and valid | `ENABLED` | certificate login and certificate-signed approval; legacy A/RA writes blocked |
| true and invalid | `MISCONFIGURED` | `/api/vc/*` returns `503 VC_INTEGRATION_MISCONFIGURED`; legacy A/RA writes **still blocked** (fail closed) |

Configuration comes from process environment variables only. The legacy `.env*` files are not read for it, and no secret goes in an env value.

| Variable | Notes |
|---|---|
| `VISION_CERTI_BASE_URL` | fixed server-to-server origin. `https://host[:port]` only; `http://` only to localhost/127.0.0.1/::1 with `VISION_CERTI_ALLOW_LOCALHOST_HTTP=true` (dev) |
| `VISION_CERTI_PUBLIC_BASE_URL` | optional browser-facing origin returned by `/api/vc/config` (default: the base URL) |
| `VISION_CERTI_CLIENT_ID` | must equal Vision-Certi `VISIONCERTI_PACS_CLIENT_ID` |
| `VISION_CERTI_CLIENT_SECRET_FILE` | ≥ 32-byte shared HMAC secret (file only) |
| `VISION_CERTI_TENANT_ID` | the Vision-Certi tenant this PACS serves |
| `VISION_CERTI_AUDIENCE` | default `vision-certi:pacs-integration:v1` |
| `VISION_CERTI_CA_BUNDLE` | optional private CA bundle for TLS verification (verification is never disabled) |
| `VISION_CERTI_CONNECT_TIMEOUT` / `VISION_CERTI_READ_TIMEOUT` | default 3 s / 10 s (bounded 0.5–10 / 1–30) |
| `VISION_CERTI_SESSION_MAX_MINUTES` | default 60 (5–240); a PACS session also ends no later than the Vision-Certi session |
| `VISION_CERTI_LINK_ADMIN_USER_IDX` | comma list of user_idx allowed to link doctors and reconcile; default `SUPER_ADMIN_USER_IDX_LIST`. `group_level` alone is never enough |

The outbound client uses stdlib `urllib`:
- TLS 1.2+ with verification;
- redirects refused;
- a 1 MiB response cap;
- HMAC request signing (`hmac_auth.py`, identical to the Java filter, cross-checked by a shared vector).

## 2. Certificate login

1. The browser completes the Vision-Certi certificate login, then gets a handoff code from `POST {VC}/v1/doctor/pacs-handoffs` with audience = the client id.
2. The browser calls `POST /api/vc/auth/certificate-login` with body `{handoffCode}`. This path is public, listed in `EXCEPT_PATH_LIST`.
3. The server redeems the code with Vision-Certi and checks the identity: tenant, certificate identity mode, activeSigner, and session expiry.
4. It finds the **ACTIVE admin link** for (tenant, doctorId). There is never any e-mail or name matching, and any `doctorId` or "authenticated" flag sent by the browser is ignored.
5. It checks that the local user is active (`user_status='A'`), is a PACS user and is a doctor (has a `pacs_users_view.doctor_idx`).
   - **License cross-check:** when the redeem response carries `doctorLicenseNumber` (the license the Vision-Certi admin recorded with license evidence), the local `doctor_license_number` must contain the same digits. Otherwise the request gets `403 VC_LICENSE_MISMATCH`, which includes an empty local license.
   - When the field is absent (an older Vision-Certi), no comparison is made, and the audit records `licenseChecked: false`.
   - A license number is never an authenticator, and no specialist qualification is inferred.
6. It issues `token = "vc1." + 43 random chars`.
   - Only its SHA-256 is stored, in `pacs_vc_session`.
   - It expires at `min(now + 60 min, Vision-Certi session expiry)` and cannot be refreshed.
   - It is sent only in the `Authorization` header, never in a URL or log.

**On later requests**
- Every request with a `vc1.` bearer is re-validated by `crud/Token.get_user` → `runtime.resolve_vc_token_user_idx`: session, link, local user and doctor status, fresh from the DB.
- The middleware does **not** cache `vc1.` tokens, so a revocation takes effect immediately.
- Legacy password logins keep working for viewing and drafting, but can never write A/RA while the integration is on.

**Certificate step-up after a password login:** `POST /api/vc/auth/certificate-step-up` with body `{handoffCode}`.
- The path is **not** public: the legacy token middleware must authenticate the password-login bearer. A `vc1.` bearer gets `409 VC_ALREADY_CERTIFICATE_SESSION`, and without a legacy user the request gets `401 VC_LOGIN_REQUIRED`. In both cases the code is not redeemed.
- Redeem, identity and link checks are the same as steps 3–4.
- **The linked PACS user must be the logged-in legacy user**, otherwise `403 VC_STEP_UP_IDENTITY_MISMATCH`. No session row is written, and the audit records `VC_STEP_UP DENIED IDENTITY_MISMATCH`. The code is consumed either way.
- Then come the step 5 checks, including the license cross-check, and a `vc1.` session as in step 6. The response is the certificate-login body plus `"stepUp": true`.
- Reading and drafting continue with the legacy token; approval uses the `vc1.` token.
- Contract: `work/pacs-stepup-contract.md`.

**Session routes:** `GET /api/vc/auth/me` (alias `/auth/session`) and `POST /api/vc/auth/logout`.

**Admin links:** `GET/POST /api/vc/admin/doctor-links` and `POST /api/vc/admin/doctor-links/{link_idx}/revoke`.
- The body requires `evidence_ref`.
- There can be one ACTIVE link per user and per doctor; this is enforced by unique `active_*` columns.
- Revoking a link also revokes its sessions. The history is append-only.

## 3. Report approval (replaces direct A/RA)

1. `POST /api/vc/study/{study_idx}/report/prepare` with body `{expected_content_sha256}` (vc1 bearer). Requirements:
   - the report has been saved;
   - the requester may write it, by the same rule as legacy `add_report`: assignee or unassigned, group_level ≥ 98, a super admin, `user_report_edit_all`, or `report_open_edit`;
   - the content hash equals what the user is looking at;
   - it is not already approved with identical content.

   **Snapshot:** an immutable snapshot is built **from DB values**: reading, conclusion, comments, CVR, creator, study and patient identifiers, doctor, tenant, revision, and prior approval. It is canonical JSON (sorted keys, UTF-8) and is stored in `pacs_vc_report_approval` before any remote call.

   **Registration:** it is registered at Vision-Certi with `Idempotency-Key: pacs-approval-<approvalId>`.

   **Repeats:** re-preparing the same content reuses the same open approval, including after a network failure (`PREPARE_RETRY`). Different content supersedes the unsigned open approval and creates the next revision.
2. The physician reviews the exact bytes and approves at Vision-Certi, with the review attestation, using the Vision-Certi bearer.
3. `POST /api/vc/report-approvals/{approvalId}/finalize` (same doctor). The server pulls the result from Vision-Certi and verifies the evidence:
   - the request id, tenant, signer, document hash, algorithm and enrollment;
   - **ECDSA P-256 over the stored snapshot bytes** with the returned key (`ecdsa_p256.py`, which uses `cryptography` if installed, otherwise a pure verifier).
4. The evidence is persisted write-once (state `SIGNED_REMOTE`). Then, in **one transaction**, the server re-checks:
   - the local user is still active and a doctor;
   - the link is still active;
   - the certificate enrollment in the evidence is the one proved by the login/step-up session that prepared the approval (else `COMMIT_REFUSED`, reason `CERTIFICATE_CHANGED`);
   - write permission, including the current assignment;
   - the report content hash is unchanged;
   - no newer revision is approved.

   It then writes `report_status = A|RA`, `report_approve_idx` and `report_approve_datetime`, and sets the approval to `APPROVED`. The existing DB trigger updates `pacs_study.study_status`.

**Approval states**

| State | Meaning |
|---|---|
| `PREPARING` → `PENDING_SIGNATURE` → `SIGNED_REMOTE` → `APPROVED` | normal path. **Only `APPROVED` means A/RA was written**, and the API's `approved` flag is true only then |
| `PREPARE_RETRY` | Vision-Certi was unreachable at prepare; retry prepare or finalize |
| `PENDING_SIGNATURE` | the physician has not signed yet (`409 VC_NOT_YET_SIGNED`) |
| `SIGNED_REMOTE` | the signature exists but the local commit failed (`503 VC_LOCAL_COMMIT_FAILED`, retryable). `POST /api/vc/admin/report-approvals/reconcile` (link admin) completes it without re-signing |
| `EXPIRED`, `REMOTE_FAILED` | the remote request expired, failed or was rejected, or the evidence was invalid (`502 VC_EVIDENCE_INVALID`) |
| `SUPERSEDED` | a newer prepare replaced an unsigned approval |
| `SUPERSEDED_AFTER_SIGNATURE` | the report changed after signing: the evidence is kept, A/RA is not written, and a new approval is needed |
| `COMMIT_REFUSED` | the local account was blocked, the link revoked, the signing certificate enrollment differs from the session's, or permission lost between signing and commit: the evidence is kept and A/RA is not written |

**Evidence routes:** `GET /api/vc/report-approvals/{id}`, `GET …/{id}/snapshot` (exact bytes plus `X-Snapshot-Sha256`), and `GET /api/vc/study/{study_idx}/report/approvals`. They are available to the assignee, the report author, the approval's doctor, admin-level users and link admins.

## 4. Legacy route changes (minimal)

- `router/StudyReport.py`, `add_report` and `update_report`: when the state is not DISABLED, `report_status` A/RA gives `409 {detail:{code:"VC_SIGNATURE_REQUIRED"}}`, and client-supplied `report_approve_idx`/`report_approve_datetime` are dropped. Editing an approved report is allowed only as R/RR; the signed snapshot stays in `pacs_vc_report_approval`, and a new approval creates the next revision.
- `delete_report`: a report with any approval evidence gives `409 VC_SIGNED_REPORT_IMMUTABLE`.
- `crud/Token.get_user` handles `vc1.` bearers; `middlewares/TokenHTTPMiddleware.py` skips its 5-minute cache for them; `core/constants.EXCEPT_PATH_LIST` adds `/api/vc/config` and `/api/vc/auth/certificate-login`; `main.py` and `router/__init__.py` register the router.

## 5. Database

The new tables (`pacs_vc_doctor_link`, `pacs_vc_session`, `pacs_vc_report_approval`, `pacs_vc_audit`) are in the main PACS schema. The ORM is `orm.py`, and **root owns the reviewed MySQL migration**. No legacy table is altered.

If the integration is enabled before the migration has been applied, the integration routes and the guarded legacy routes fail with errors rather than falling back.

## 6. Tests (isolated)

Run:

```
python -m unittest discover -s tests/vision_certi -t .
```

This needs SQLAlchemy, FastAPI and httpx, and does not import the legacy settings.

The 40 tests use temporary SQLite databases (main plus an attached `mgr` schema), a fake Vision-Certi that verifies HMAC like Java and signs with a test key, and a local socket server for the real `urllib` transport. They cover:
- **Configuration:** fail-closed configuration states.
- **Crypto:** the HMAC vector shared with Java; ECDSA verification of a Java-generated signature and tamper rejection.
- **Login:** session expiry capped by Vision-Certi; token never stored; no e-mail matching; refusals for an unlinked doctor, a wrong tenant or a non-certificate mode.
- **Session and links:** blocked local accounts, link revocation, logout, expiry and token tampering; link admin allowlist, uniqueness and history.
- **Approval:** the happy path (A plus evidence); a non-assignee, a wrong approval owner and stale content; network failures at prepare and finalize (never approved, retry with the same idempotency key, one remote request); local commit failure followed by reconcile; content tampered after signature; tampered signature or document hash; revisions (RA, supersede, history); link revoked before commit.
- **Step-up (`test_vc_step_up.py`):**
  - unauthenticated or `vc1.` caller refused without redeeming;
  - same user succeeds and can approve end to end;
  - another user's certificate refused with no session stored, and the code is consumed;
  - replayed, stale, unknown or malformed codes; Vision-Certi down;
  - inactive or non-doctor account, Vision-Certi doctor not ACTIVE or not an active signer, revoked link;
  - disabled integration;
  - license match, mismatch, missing or absent;
  - finalization refused for a changed certificate enrollment, a reassignment, or an expired step-up session.
- **Legacy guard:** disabled, enabled and misconfigured.
- **HTTP flow:** includes evidence endpoints and the refusal of a legacy JWT for prepare.
- **Transport:** redirect refused, timeout, size cap, 4xx/5xx mapping, UUID path validation.
- **Import isolation:** importing the integration package does not load `app.core` or `app.database`.

## 7. Not done / limits

- The legacy app was not started (its `.env`, MySQL and MinIO were not touched), so the wiring in `main.py`, `crud/Token.py` and `StudyReport.py` was checked with `py_compile` only.
- `SqlLegacyPort` was tested on SQLite copies of the legacy columns, not on MySQL. The MySQL row lock (`FOR UPDATE`) is emitted only on MySQL.
- The PACS **frontend** (handoff, approve screen, evidence display) is owned by the frontend team.
- Vision-Certi has no real accredited CA, PC certificate SDK or mobile adapter configured, and its own integration state is in memory. **Not production-ready.**
- The legacy `pacs_token` table still stores password-login JWTs in plaintext (unchanged legacy behaviour). Only `vc1.` sessions are hashed.
