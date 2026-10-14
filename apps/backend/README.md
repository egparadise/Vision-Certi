# Vision-Certi backend

Spring Boot backend for the Vision-Certi remote-reading signature server: tenant-scoped document registration with
immutable versions, sign requests bound to an exact SHA-256, explicit physician approval with a one-time nonce,
physician signing with OpenBao Transit keys and OpenBao PKI certificates, PC certificate login, the SV70 PACS
integration, verification, a delivery queue, a hash-chained audit log and dashboard counters.

Two runnable profiles:

- **`prod`**: PostgreSQL persistence (schema `certi_rt`, migration `database/migrations/V005__production_runtime.sql`),
  OIDC JWT staff authentication with explicit (issuer, subject) to tenant/role mappings, OpenBao Transit + PKI over TLS
  with separate token files, PC certificate trust anchors with fresh CRLs. Startup is refused unless all of that is
  configured; dev/mock features do not exist. See [docs/PRODUCTION_RUNTIME.md](docs/PRODUCTION_RUNTIME.md).
- **`dev`**: synthetic header identities, mock signing/SMS/certificates, in-memory store by default (PostgreSQL
  optional).

> **No legal approval is asserted.** Physician signatures are raw ECDSA P-256 from OpenBao Transit with a certificate
> from a **private** OpenBao PKI: no accredited CA, no RFC 3161 timestamp, no CAdES/PAdES, no HSM claim (OpenBao is
> software key storage unless itself HSM-backed). `clinicalUseAllowed` is always `false`; verification never reports
> an overall `VALID`. No mobile certificate adapter and no SMS carrier are integrated.

## Stack

| Item | Version |
|---|---|
| Spring Boot | 4.1.1 (Spring Framework 7, Jackson 3) |
| Java | bytecode `--release 21`; built and tested with JDK 25.0.1 |
| Gradle | wrapper 9.8.1 (distribution SHA-256 pinned in `gradle/wrapper/gradle-wrapper.properties`) |

## Run

From `apps/backend` (PowerShell: use `.\gradlew.bat`):

```sh
./gradlew test                                              # 178 tests (real PostgreSQL included, see below)
./gradlew bootRun --args='--spring.profiles.active=dev'     # http://localhost:8080
# or
./gradlew bootJar
java -jar build/libs/vision-certi-backend-0.1.0-dev.jar --spring.profiles.active=dev
```

Any JDK 21+ can run Gradle and the jar. With no profile, or with `prod` but incomplete production configuration,
startup aborts with `IllegalStateException: FAIL_CLOSED ...` listing every missing setting by name — that is
intentional. Production configuration: [docs/PRODUCTION_RUNTIME.md](docs/PRODUCTION_RUNTIME.md).

Persistence tests run against a real PostgreSQL server: by default the zonky embedded PostgreSQL binaries (an actual
`postgres` process in a temporary directory, connected as a non-owner `certi_app` login), or an isolated disposable
database given by `VISIONCERTI_TEST_PG_URL`, `VISIONCERTI_TEST_PG_USERNAME`, `VISIONCERTI_TEST_PG_PASSWORD_FILE`.

### Try it

```sh
curl localhost:8080/v1/health

H='-H X-Tenant-Id:demo-hospital-a'
curl $H -H 'X-Actor-Id: admin-demo' -H 'Idempotency-Key: demo-doc-0001' \
  -F 'metadata={"externalDocumentId":"r-1","externalStudyId":"s-1","title":"Demo","signerId":"doctor-demo"}' \
  -F 'file=@report.pdf;type=application/pdf' localhost:8080/v1/documents
curl $H -H 'X-Actor-Id: admin-demo' -H 'Idempotency-Key: demo-req-0001' -H 'Content-Type: application/json' \
  -d '{"documentVersionId":"<latestVersion.id>","signerId":"doctor-demo"}' localhost:8080/v1/sign-requests
curl $H -H 'X-Actor-Id: doctor-demo' localhost:8080/v1/sign-requests/<id>            # signer sees nonce
curl $H -H 'X-Actor-Id: doctor-demo' -H 'Idempotency-Key: demo-apr-0001' -H 'Content-Type: application/json' \
  -d '{"documentSha256":"<sha>","nonce":"<nonce>"}' localhost:8080/v1/sign-requests/<id>/approve
curl -X POST $H -H 'X-Actor-Id: doctor-demo' localhost:8080/v1/signatures/<signatureId>/verify
```

Exact request/response shapes: [docs/API.md](docs/API.md). Public signup with mock phone OTP:
[docs/ENROLLMENT.md](docs/ENROLLMENT.md). Physician preregistration, Google/OpenBao activation and signing:
[docs/DOCTOR_ACTIVATION.md](docs/DOCTOR_ACTIVATION.md). PC/mobile certificate enrollment (required for activation by
default) and the physician document view: [docs/CERTIFICATE_ENROLLMENT.md](docs/CERTIFICATE_ENROLLMENT.md). SV70 PACS integration (certificate login handoff, signed report snapshots): [docs/SV70_PACS_INTEGRATION.md](docs/SV70_PACS_INTEGRATION.md).

## Profiles

| Profile | Behaviour |
|---|---|
| `dev` (+ `visioncerti.dev.synthetic-identities-enabled=true`, set in `application-dev.yml`) | Synthetic tenants `demo-hospital-a`, `demo-hospital-b`; actors `doctor-demo` (DOCTOR), `admin-demo` (ADMIN) selected by `X-Tenant-Id` / `X-Actor-Id`; mock signer; in-memory data by default (`VISIONCERTI_PERSISTENCE_MODE=POSTGRESQL` optional); `VISIONCERTI_STAFF_AUTH_MODE=OIDC` replaces the dev headers |
| `prod` | Runs only with the complete production configuration checked by `ProductionReadiness` (PostgreSQL, OIDC, OpenBao TLS + token files, PC anchors + fresh CRLs). Header overrides are rejected (400), personal signup answers 503 `SMS_NOT_CONFIGURED` |
| none, anything else, or `dev`+`prod` | Refuses to start (`FailClosedProfileGuard`, registered via `META-INF/spring.factories` so it runs before any bean). The dev header filter, dev directory and mock signer are additionally `@Profile("dev")` |

## Layout

```
src/main/java/com/inviz/visioncerti/
  common/     errors {code,message,correlationId,retryable}, list envelope, correlation id, durable idempotency, hashing, clock
  store/      Store/Tx (per-aggregate rows, version CAS, row/advisory locks), PostgresStore, MemoryStore (dev/tests)
  runtime/    ProductionReadiness (prod gate), GET /v1/runtime/capabilities
  security/   fail-closed guard, OIDC staff filter + verifier + staff directory, dev header filter, RequestActor resolver
  document/   documents + append-only versions, PDF checks, content download
  signing/    sign requests, approval rules, MockSigningProvider, signatures, verification
  delivery/   outbox entries (QUEUED only)
  audit/      per-tenant SHA-256 hash-chained audit log
  dashboard/  tenant counters
  enrollment/ public PERSONAL signup + mock phone OTP (no roles, no login); see docs/ENROLLMENT.md
  certificate/ PC joint-certificate enrollment/login (PKIX + CRL), admin review, mobile interface, dev MOCK_CERTIFICATE
  doctor/     admin preregistration, invitation, Google ID-token / dev MOCK_IDENTITY, consent, activation,
              bearer sessions, physician signing; credential/ = OpenBao Transit+PKI, DEV_SIMULATED, NOT_CONFIGURED
  health/     /v1/health
src/test/java/com/inviz/visioncerti/
  ApiFlowTest                 MockMvc: tenant isolation, idempotency replay/mismatch, nonce replay, admin refusal, versioning, error shape
  signing/SigningFlowTest     service rules with a controllable clock: expiry, nonce, hash binding, version immutability, verification, audit chain
  security/FailClosedProfileGuardTest  prod/no-profile/dev-without-flag refuse to start
  enrollment/EnrollmentServiceTest     OTP expiry/lock/cooldown/limits/resend, token binding+replay, validation, race
  enrollment/EnrollmentApiTest         public routes without dev headers, exact-route bypass, error shapes
  doctor/DoctorServiceTest             state machine, invitation/challenge/session rules, roles, tenants, suspension
  doctor/DoctorSigningFlowTest         OpenBao adapter end to end (fake server): exact digest, evidence, revoke race
  doctor/DoctorActivationApiTest       HTTP flow: bearer-only physician, no dev-header impersonation, replay
  doctor/credential/OpenBaoCredentialProviderTest  Transit/PKI contract, key/cert match, chain, tokens, TLS rule
  doctor/identity/GoogleIdTokenVerifierTest        signature, iss, aud, exp, iat, nonce, email_verified
  certificate/CertificateEnrollmentServiceTest     synthetic CA chain, CRL revoked/unknown/stale, review, renewal, signing races, mobile
  certificate/CertificateRevocationScopeTest       delta/IDP/critical CRLs fail closed, anchor validity/pathLen, trailing DER
  integration/PacsIntegrationApiTest              SV70 PACS: HMAC service auth, handoff, report snapshot signing
  certificate/CertificateEnrollmentApiTest         HTTP certificate flow, document view, approval, unregister
  certificate/DevMockCertificateApiTest            opt-in MOCK_CERTIFICATE shortcut
  store/PostgresStoreTest          real PostgreSQL: CAS across instances, unique keys, rollback/commit rules, grants+triggers, outage
  Postgres*ApiTest / PostgresApiFlowTest           the five API test classes again on real PostgreSQL
  runtime/ProductionRuntimeTest    prod profile running: startup gate, capabilities, OIDC staff auth, restart durability,
                                   replay after restart, two instances racing (one signature), tenant isolation,
                                   tampering, CRL revocation, interrupted activation recovery
  security/StaffTokenVerifierTest  token rules, identity provider outage (503)
```

## What is enforced

- Only the three exact `POST /v1/enrollment/...` routes and the exact physician routes (`/v1/doctor-activation/...`,
  `/v1/doctor/sign-requests...`) skip the dev identity headers; physician routes use a bearer session instead.
- Registered physicians sign only through their own bearer session; dev headers can never act as them.
- Every lookup is tenant-scoped; another tenant's resources return 404, not 403.
- Version bytes are copied on write and on read; versions are append-only, and a new version supersedes
  pending requests for older versions (`409 DOCUMENT_VERSION_SUPERSEDED`).
- Sign requests bind tenant, signer, version id and SHA-256; approval re-checks all of them, compares hash and
  nonce in constant time, re-hashes the stored bytes, and consumes the nonce exactly once.
- Admins can create requests but can never approve or sign; every refusal is audited `DENIED`.
- Requests expire after 10 minutes (`410 SIGN_REQUEST_EXPIRED`).
- `Idempotency-Key` replays the stored response or returns `409` for a different payload (durable, all instances).
- Verification never returns overall `VALID`.
- No private key is persisted by this backend: the mock HMAC key and DEV_SIMULATED keys live only in memory;
  OpenBao keys are created non-exportable and only referenced by name + pinned version.
- Secrets are stored only as SHA-256 (session tokens, invitation codes, handoff codes, mobile secrets, HMAC nonces,
  idempotency scopes); in prod, database passwords and OpenBao/PACS secrets come from files.

## Limitations

- **Authentication**: staff OIDC is bearer-JWT verification only. MFA, step-up and session management are left to
  the identity provider and are not verified here (no `acr`/`amr` policy). Dev headers (dev only) are not
  authentication.
- **Signing**: OpenBao is software key storage unless itself HSM-backed; the CA is a private OpenBao PKI; no
  CAdES/PAdES, no RFC 3161 timestamp, no OCSP, no long-term evidence package. `MockSigningProvider` (dev only) is not
  a signature.
- **Trust boundary**: approval and signing run in the same service as the core API (separate transactions). The
  design's separate Approval origin and isolated signing worker do not exist.
- **Persistence**: PostgreSQL rows are tenant-scoped in application queries; no RLS on `certi_rt`, no object storage
  (document bytes are `bytea`), no WORM audit checkpoints. Personal signup accounts (`/v1/enrollment`, dev only) are
  still in memory.
- **Documents**: PDF check is the `%PDF-` header and size only — no malware scan or structural validation.
- **Delivery**: entries are queued but never sent; no connector, retry or ACK handling.
- **API gaps**: no pagination or filters, no identity/credential/evidence endpoints; idempotency records are not
  pruned yet.
- Validation messages for `@Valid` bodies follow the JVM/request locale.
