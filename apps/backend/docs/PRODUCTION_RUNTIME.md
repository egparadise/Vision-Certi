# Production runtime (`prod` profile)

This document covers what the `prod` profile requires, how state is stored, how staff authenticate, and what the
runtime does and does not claim. The cross-team contract (DB, environment, frontend) is
`work/production-runtime-contract.md`.

## 1. Startup gate

`FailClosedProfileGuard` runs before any bean is created. With `prod` it calls `ProductionReadiness`, which checks
everything below and reports **all** failures at once, by variable name only. Any failure aborts startup with
`IllegalStateException: FAIL_CLOSED: the production profile is not ready: ...`.

The runtime beans repeat their own checks, so bypassing the guard still fails closed:
- `StoreConfig` verifies the schema version and refuses in-memory storage.
- `CredentialProviderConfig` requires OpenBao over https with a CA file and token files.
- `StaffAuthConfig` requires the OIDC settings.

| Area | Requirement (prod) |
|---|---|
| Profiles | `prod` only; `dev` + `prod` is refused |
| Persistence | `VISIONCERTI_PERSISTENCE_MODE` unset or `POSTGRESQL`; `VISIONCERTI_DB_URL` = `jdbc:postgresql://…`, parsed (not substring-matched): exactly `sslmode=verify-full` for any non-loopback host, no userinfo, no `user`/`password`/`service`/`options`/`sslfactory`/`sslhostnameverifier` parameter, no repeated or percent-encoded parameter names; `VISIONCERTI_DB_USERNAME`; `VISIONCERTI_DB_PASSWORD_FILE` readable; `certi_rt.schema_info` runtime = 5. At startup the **connected** role is checked: not (and unable to become) a superuser or BYPASSRLS role, not the owner of `certi_rt` (nor a member of the owner role), and a member of `certi_app` |
| Staff auth | `VISIONCERTI_STAFF_AUTH_MODE=OIDC` (the prod default); `VISIONCERTI_STAFF_OIDC_ISSUER` and `VISIONCERTI_STAFF_OIDC_JWKS_URI` over https; `VISIONCERTI_STAFF_OIDC_AUDIENCE`; `VISIONCERTI_STAFF_OIDC_CLIENT_ID` (public, echoed to the web app) |
| Signing | `VISIONCERTI_SIGNING_BACKEND=OPENBAO_TRANSIT` (the prod default) |
| OpenBao | `VISIONCERTI_OPENBAO_ADDR` over https; `VISIONCERTI_OPENBAO_CA_CERT_FILE`; `VISIONCERTI_OPENBAO_PROVISIONING_TOKEN_FILE` and `VISIONCERTI_OPENBAO_SIGNING_TOKEN_FILE`, readable, non-empty and different. The environment token variables (`…_PROVISIONING_TOKEN`, `…_SIGNING_TOKEN`) must be **unset**. Mounts, role, key prefix and TTL must be valid |
| Physician auth | `VISIONCERTI_CERT_TRUST_ANCHORS_FILE` with at least one CA. `VISIONCERTI_CERT_CRL_FILES` must hold, for every anchor, an authentic CRL issued by it whose `nextUpdate` is in the future. `VISIONCERTI_MOBILE_PROVIDER` must be empty, because no mobile adapter is implemented |
| Dev features | `visioncerti.dev.synthetic-identities-enabled`, `VISIONCERTI_DOCTOR_DEV_IDENTITY_ENABLED` and `VISIONCERTI_CERT_DEV_MOCK_ENABLED` must be false; `VISIONCERTI_DOCTOR_ACTIVATION_POLICY` must be `CERTIFICATE_REQUIRED` |
| PACS | When `VISIONCERTI_PACS_CLIENT_ID` is set, the secret comes only from `VISIONCERTI_PACS_CLIENT_SECRET_FILE`; `VISIONCERTI_PACS_CLIENT_SECRET` must be unset |

**Not present in prod.** None of these beans exist:
- dev headers, `DevActorDirectory` and synthetic actors;
- `DevIdentityController` (MOCK_IDENTITY) and `DevMockCertificateController` with the mock CA (MOCK_CERTIFICATE);
- `MockSigningProvider` and `MockSmsSender`;
- `DEV_SIMULATED` keys.

**Replacements and refusals in prod**
- `NotConfiguredSmsSender` makes personal signup (`/v1/enrollment/**`) answer **503 `SMS_NOT_CONFIGURED`**. No carrier is invented.
- `UnavailableSigningProvider` refuses legacy signing. Only registered physicians sign.
- Requests carrying `X-Tenant-Id`, `X-Actor-Id` or `X-Actor-Role` get **400 `HEADER_OVERRIDE_REJECTED`**.

**CRL freshness after startup.** CRL files are re-read on every validation. Publish new CRLs by replacing the files. A CRL that becomes stale makes certificate checks return STALE, so login and signing are refused (fail closed), and `/v1/runtime/capabilities` reports `crlState: "STALE"`.

## 2. Persistence model (`certi_rt`, migration V005)

### Rows and keys
- **One row per aggregate**, not a process snapshot: doctor, challenge, session, enrollment, document, version, sign request, signature, delivery, audit event, staff mapping, nonce, handoff, idempotency record.
- **Common columns:** `id`, `seq`, `tenant_id`, `version`, `payload jsonb`, `created_at`, `updated_at`, `expires_at`.
- **Lookup columns** are derived from the aggregate on every write. A lookup column is NULL when it does not apply, so a UNIQUE column means "unique while in that state":
  - `doctors.license_key`, `email_key` and `binding_key` (null once REVOKED);
  - `doctors.invitation_hash` (only while ISSUED);
  - `certificate_enrollments.live_fingerprint` (PENDING/ACTIVE) and `active_doctor_id` (one ACTIVE per doctor);
  - `sign_requests.pending_key` (one PENDING per version and signer);
  - `signatures.sign_request_id` (one logical signature per request);
  - `doctor_sessions.token_hash`, `pacs_handoffs.code_hash`, `integration_nonces.nonce_key`, `idempotency_records.scope_hash`, `staff_principals.subject_key`.
- **Only hashes:** raw tokens, codes, nonces and secrets are never stored, only their SHA-256.

### Transactions
- Each service operation is one transaction (`Store.tx`) with no network I/O inside. Calls to OpenBao, JWKS, the mobile provider and CRL validation run between transactions.
- Check-then-act paths take row locks (`SELECT … FOR UPDATE`, bounded by `lock_timeout`, default 5 s, `VISIONCERTI_DB_LOCK_TIMEOUT`) or transaction-scoped advisory locks (for example on a certificate fingerprint or public key).
- Every update is version-checked. A serialization failure, deadlock or version conflict retries the whole transaction (at most 3 attempts), then answers 409 `STORAGE_CONCURRENT_MODIFICATION` or 503 `STORAGE_UNAVAILABLE`.
- Any other database error rolls back and answers 503 `STORAGE_UNAVAILABLE`; there is no fallback to memory. Logs carry the SQLState and table name only.

### Commit rule
- A normal return **or a business refusal** (`ApiException`) commits. Refusals often record durable facts first: a consumed challenge, a failure counter, a DENIED audit. This matches the earlier in-memory semantics.
- Any other exception rolls back.
- A UNIQUE violation rolls back and answers the aggregate's conflict code, for example `SIGN_REQUEST_ALREADY_PENDING` or `INTEGRATION_NONCE_REPLAYED`.

### Signing
1. The nonce is consumed under the request's row lock, and the request moves to SIGNING, in transaction 1.
2. The OpenBao Transit signature is made outside any transaction.
3. Transaction 2 stores the signature only if the request is still SIGNING. The UNIQUE constraint on `sign_request_id` forbids a second logical signature.

A request left SIGNING for more than 5 minutes (crash) becomes FAILED, and a late result is then discarded with 409 `SIGNING_INTERRUPTED`.

### Audit
- Events join the business transaction and are chained right before commit. Tenants are processed in sorted order, and the per-tenant head row lock is taken last.
- The audit is durable exactly when the change it describes is.
- The chain is per tenant, with a contiguous sequence and SHA-256 links (`audit_events.chain_key` is UNIQUE).

### Immutability
- `audit_events`, `document_versions`, `signatures`, `doctor_evidence` and `blobs` reject UPDATE and DELETE by trigger, which applies even to the owner.
- `certi_app` has only SELECT and INSERT on those tables.

### Activation recovery
- `activate` persists a lease (`activationInProgress` and `activationStartedAt`) before calling OpenBao. A concurrent activation gets 409 `ACTIVATION_IN_PROGRESS` until the lease expires (2 min).
- A retry after a crash provisions again under the deterministic key name `<prefix>-<doctorId>`. The existing Transit key is read and reused, never replaced, so no new signing identity is created silently.
- The certificate is issued again for that same key. A certificate issued just before a crash is not recorded and stays valid in the PKI until it expires or is revoked; see the remaining items.

### Multiple instances
- All authoritative state is in PostgreSQL, so any number of instances may run against the same database (tested with two instances approving the same request at once). No single-instance lease is needed.
- Process-local values are non-authoritative:
  - the throttle timestamp for garbage-collecting expired rows;
  - the JWKS key cache (Nimbus, refreshed on unknown `kid`);
  - the PACS client configuration.
- Nothing in memory is authoritative.

**Planning tables (V001–V004, schema `certi`).** These are the earlier design: UUID tenants with RLS, a split sign job and approval evidence, and `doctor_*` tables. The runtime neither reads nor writes them. The mapping is:

| Planning (`certi.*`) | Runtime (`certi_rt.*`) |
|---|---|
| `documents`, `document_versions` | `documents`, `document_versions` + `blobs` (bytes) |
| `sign_requests`, `approval_evidence`, `sign_jobs`, `signatures` | `sign_requests` (approval fields in payload), `signatures` (doctor evidence in payload) |
| `deliveries`, `delivery_attempts`, `outbox_events` | `deliveries` (QUEUED only; no sender) |
| `audit_events`, `audit_checkpoints` | `audit_events` + `audit_heads` (no external checkpoints) |
| `idempotency_records` | `idempotency_records` |
| `doctor_profiles`, `doctor_reviews`, `doctor_invites`, `doctor_bindings`, `doctor_consents`, `doctor_keys`, `doctor_certificates`, `doctor_events` | `doctors` (profile, invitation hash, binding, consent, credential metadata) + `doctor_evidence` |
| `doctor_auth_certificates`, `certificate_binding_reviews`, `certificate_validation_evidence`, `certificate_challenges`, `mobile_auth_transactions` | `certificate_enrollments`, `certificate_challenges`, `mobile_transactions` |
| (none) | `doctor_sessions`, `doctor_challenges`, `rate_events`, `integration_nonces`, `pacs_handoffs`, `staff_principals` |

### Database setup
1. Apply V001…V005 with the migration account (Flyway or psql). V002 creates the NOLOGIN group `certi_app`.
2. Create a runtime login that is a member of `certi_app`, for example `CREATE ROLE vc_runtime LOGIN PASSWORD '…' IN ROLE certi_app`. It must not own the schema and must not be a superuser.
3. Point `VISIONCERTI_DB_*` at it. Prod startup verifies these role properties on the live connection and refuses to start otherwise.

The app never runs migrations.

## 3. Staff authentication (OIDC)

**Token checks**
- The frontend obtains a JWT from the configured identity provider (Authorization Code + PKCE, public client `VISIONCERTI_STAFF_OIDC_CLIENT_ID`) and sends `Authorization: Bearer <jwt>`.
- `StaffTokenVerifier` accepts only RS256, ES256 or PS256 signatures from the configured JWKS. A `typ` header, if present, must be `JWT` or `at+jwt`.
- `iss` must equal the configured issuer exactly, and `aud` must contain the configured audience.
- `exp`, `nbf` and `iat` are checked with 60 s skew, and the lifetime is at most 24 h.

**Mapping**
- `StaffDirectory` maps the verified (issuer, subject) pair to an ACTIVE mapping, which gives the tenant and role. Only `ADMIN` is accepted; physicians never use staff tokens.
- E-mail, name, group and role claims are ignored.

**Errors**

| Status | Code | When |
|---|---|---|
| 401 | `STAFF_TOKEN_REQUIRED` / `STAFF_TOKEN_INVALID` | missing or invalid token |
| 403 | `STAFF_NOT_AUTHORIZED` | unmapped or disabled |
| 503 | `STAFF_AUTH_UNAVAILABLE` | the JWKS cannot be reached |
| 400 | `HEADER_OVERRIDE_REJECTED` | identity override headers |

**Mappings**
- `VISIONCERTI_STAFF_BOOTSTRAP_FILE` is a protected local JSON array of `{tenantId, issuer, subject, role:"ADMIN", displayName, evidenceRef}`. It is read at each startup; only missing mappings are created, and existing or disabled ones are never changed.
- After bootstrap, tenant administrators use:
  - `GET /v1/admin/staff`;
  - `POST /v1/admin/staff` with `{issuer, subject, role:"ADMIN", displayName, evidenceRef}`;
  - `POST /v1/admin/staff/{principalId}/disable` with `{reason}`. Disabling your own mapping is refused.
- No unauthenticated route creates mappings. `GET /v1/staff/me` returns the caller's tenant, actor id, role, issuer and display name.

**Routes outside staff auth.** These keep their own authentication:
- public `GET /v1/health` and `GET /v1/runtime/capabilities`;
- physician routes (dedicated doctor bearer);
- personal-signup routes (503 in prod);
- HMAC integration routes.

## 4. Capabilities (`GET /v1/runtime/capabilities`, public, no-store)

This endpoint reports the live profile, persistence mode and schema version, staff auth (mode, issuer, client id, audience), physician auth (PC anchors, CRL count and state, mobile, Google, dev mock flags, activation policy), and signing (backend, availability, signature mode, `PRIVATE_OPENBAO_PKI`, timestamp `NONE`, verification label `PRIVATE_PKI_NOT_TIMESTAMPED`). It also reports whether personal signup is available, the PACS integration state, and `clinicalUseAllowed: false` with `clinicalUseReason: "LEGAL_APPROVAL_NOT_ASSERTED"`.

`warnings` are computed from the live state and must be shown as given. In prod they always include `PRIVATE_PKI_NOT_ACCREDITED`, `NO_TRUSTED_TIMESTAMP` and `RAW_ECDSA_NOT_CADES_PADES`, plus `MOBILE_PROVIDER_NOT_CONFIGURED` while no adapter exists.

**Signature mode.** In prod it is `OPENBAO_TRANSIT_ECDSA_P256_PRIVATE_PKI_NOT_TIMESTAMPED`; dev keeps `…_DEV_NOT_FOR_CLINICAL_USE`.

**Verification** of physician signatures reports `PRIVATE_CA_NOT_TRUSTED`, `PRIVATE_PKI_NOT_TIMESTAMPED`, `MISSING_TRUSTED_TIMESTAMP` and `RAW_ECDSA_NOT_CADES`. The overall status is never `VALID`. `MOCK_IDENTITY` is now reported only for `MOCK_*` identity modes; before this change PC certificate identities were mislabelled.

## 5. Tests that prove the above

- `store/PostgresStoreTest` runs against real PostgreSQL. It covers:
  - restart through a new store instance;
  - unique conflicts rolling back;
  - the commit-on-refusal and rollback-on-bug rules;
  - version CAS across two store instances, including concurrent writers (every success counted once);
  - row-lock serialization;
  - advisory-lock check-then-insert;
  - immutable tables against direct SQL (trigger and grants);
  - tenant-scoped blobs, expiry counts, an outage giving 503, and the TLS URL rules.
- `Postgres{ApiFlow,CertificateEnrollmentApi,DevMockCertificateApi,DoctorActivationApi,PacsIntegrationApi}Test` run all scenarios of the five API test classes again on PostgreSQL.
- `runtime/ProductionRuntimeTest` runs the **prod profile** against PostgreSQL, an HTTPS OpenBao fake (real ECDSA and X.509), PC anchors with CRLs, and OIDC tokens. Only the JWKS location is a test key source. It covers:
  1. startup refusals with named reasons;
  2. honest capabilities and no dev/mock surface;
  3. OIDC rules, mapping administration, and bootstrap never re-enabling;
  4. a restart: same session, credential and enrollment; no new key or certificate; nonce, handoff, challenge and idempotency replays still refused; a request created before the restart signed after it, exactly once;
  5. two instances on one database with 8 concurrent approvals: exactly one 202 and one signature row; an audit chain that is contiguous and linked;
  6. tenant isolation; an administrator can never sign;
  7. stored bytes and signatures immutable even to the owner; a tampered stored hash is never signed; a CRL revocation stops signing and login;
  8. an interrupted activation: the lease blocks, then the retry reuses the existing Transit key.
- The suites also passed against root's isolated PostgreSQL 18.6 (`VISIONCERTI_TEST_PG_*`).
