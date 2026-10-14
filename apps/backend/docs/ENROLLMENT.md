# Enrollment (signup + phone OTP), dev slice

Three public routes let a person sign up before any identity exists. They are the **only** routes the dev header
filter lets through without `X-Tenant-Id`/`X-Actor-Id`, and only as exact `POST` paths (trailing slashes, other
methods, non-UUID challenge ids and extra segments all get `401`).

> **Honest scope.** SMS is a mock (`MOCK_SMS`): nothing is sent and the code is returned as `devCode`, so a
> completed challenge proves nothing about who controls the number (`phoneVerification: MOCK_VERIFIED`).
> No identity check, no medical license verification, no login, and no session exist yet. A registered account gets
> no role, tenant membership or signing capability. Public signup is **PERSONAL only**: physicians are preregistered
> by an admin and activate with an invitation code (see [DOCTOR_ACTIVATION.md](DOCTOR_ACTIVATION.md)).
> Storage is in memory and is lost on restart. Personal signup is dev-only: in `prod` every route answers `503 SMS_NOT_CONFIGURED` because no SMS carrier is integrated.

All bodies are JSON (`Content-Type: application/json`). All responses carry `Cache-Control: no-store`. Errors use the
common shape `{code,message,correlationId,retryable}` with Korean messages that never echo submitted values.

## 1. `POST /v1/enrollment/phone-challenges`

Request: `{"phone":"010-1234-5678"}`. Hyphens and spaces are ignored, and the result must be `010` followed by 8 digits.

`201`:
```json
{"challengeId":"6f1c…","expiresAt":"2026-10-13T00:03:00Z","resendAfter":"2026-10-13T00:01:00Z",
 "mode":"MOCK_SMS","devCode":"042917"}
```
`devCode` appears only with the dev mock sender. A real `SmsSender` would set `revealsCodeToClient=false`, and the field would then be omitted.

Rules:
- The code is 6 digits from `SecureRandom`.
- Only an HMAC-SHA256 digest of the code is stored, using a per-process random key.
- A challenge is valid for 3 minutes.
- A new challenge **supersedes** every earlier pending challenge and **removes every unused verification token** for the same phone.

| Error | Status | When |
|---|---|---|
| `VALIDATION_FAILED` | 422 | phone is not 010+8 digits |
| `PHONE_CHALLENGE_COOLDOWN` | 429 (retryable) | the same phone was sent a code less than 60 s ago |
| `PHONE_CHALLENGE_RATE_LIMITED` | 429 (retryable) | the phone has 5 sends in the last hour, or the source address has 30 |
| `ENROLLMENT_CAPACITY_EXCEEDED` | 429 (retryable) | tracked in-memory state (challenges + rate windows) reached 10,000 entries |
| `SMS_SEND_FAILED` | 503 (retryable) | the sender threw. The new challenge is invalidated |
| `MALFORMED_REQUEST` | 400 | unparsable JSON |

The source is `HttpServletRequest#getRemoteAddr()`. `X-Forwarded-For` is ignored because no trusted proxy is configured.

## 2. `POST /v1/enrollment/phone-challenges/{challengeId}/verify`

Request: `{"code":"042917"}`. The code must be exactly 6 ASCII digits. A malformed code returns `422 VALIDATION_FAILED` and does not use up an attempt.

`200`:
```json
{"verificationToken":"<43 chars base64url, 32 random bytes>","expiresAt":"2026-10-13T00:12:00Z","mode":"MOCK_SMS"}
```
The token is bound to the normalized phone. It is valid for 10 minutes and works for one registration. Only its SHA-256 is stored.

| Error | Status | When |
|---|---|---|
| `PHONE_CODE_MISMATCH` | 422 | wrong code. The message gives the remaining attempts |
| `PHONE_CHALLENGE_LOCKED` | 429 | the 5th wrong code. Any later attempt, including with the right code, also gets this |
| `PHONE_CHALLENGE_EXPIRED` | 410 | 3 minutes have passed |
| `PHONE_CHALLENGE_ALREADY_VERIFIED` | 409 | the challenge was already verified. No second token is issued |
| `PHONE_CHALLENGE_SUPERSEDED` | 409 | a newer code was requested for that phone |
| `PHONE_CHALLENGE_NOT_FOUND` | 404 | unknown, or pruned (more than 10 min past expiry) |

Codes are compared in constant time (`MessageDigest.isEqual`).

## 3. `POST /v1/enrollment/registrations`

```json
{"accountType":"PERSONAL","username":"alice.kim","password":"correct horse battery","name":"홍길동",
 "phone":"010-1234-5678","email":null,"institution":null,"licenseNumber":null,"specialty":null,
 "verificationToken":"<token>",
 "consents":{"authentication":true,"signature":true,"privacy":true,"version":"2026-10-13-draft"}}
```

`201`:
```json
{"registrationId":"…uuid…","username":"alice.kim","accountType":"PERSONAL","status":"REGISTERED",
 "phoneVerification":"MOCK_VERIFIED","registeredAt":"2026-10-13T00:01:30Z"}
```
`status` is always `REGISTERED`. `accountType:"CLINICIAN"` returns `422 CLINICIAN_SELF_REGISTRATION_DISABLED`
without consuming the phone proof.

| Field | Rule (violation returns `422 VALIDATION_FAILED` unless noted) |
|---|---|
| `accountType` | `PERSONAL` (`CLINICIAN` → `422 CLINICIAN_SELF_REGISTRATION_DISABLED`) |
| `username` | `^[A-Za-z][A-Za-z0-9_.-]{3,31}$`, unique ignoring case. `doctor-demo` and `admin-demo` (any case) return `409 USERNAME_UNAVAILABLE` |
| `password` | 12–128 characters (code points). Not trimmed and no character-class rules |
| `name` | 2–50 characters after trimming, with no control characters |
| `phone` | same rule as step 1, and must equal the verified phone (`422 PHONE_VERIFICATION_MISMATCH`) |
| `email` | optional (`null` or blank). At most 254 characters and well-formed |
| `institution` / `licenseNumber` / `specialty` | must be null or blank |
| `consents` | all three `true` (`422 CONSENT_REQUIRED`), and `version` equal to `2026-10-13-draft` (`422 CONSENT_VERSION_MISMATCH`) |
| `verificationToken` | missing or badly formed: `422 PHONE_VERIFICATION_REQUIRED`. Unknown, used or superseded: `422 PHONE_VERIFICATION_INVALID`. Expired: `422 PHONE_VERIFICATION_EXPIRED` |

Taken usernames return `409 USERNAME_UNAVAILABLE`. That check runs after the token is validated, so callers without a proof cannot probe which usernames exist. A full account store returns `429 ENROLLMENT_CAPACITY_EXCEEDED` (10,000 accounts).

**Atomicity:**
1. Fields are validated, then the token and username are checked under the lock.
2. The password is hashed with PBKDF2WithHmacSHA256, 600,000 iterations, a 16-byte `SecureRandom` salt and a 256-bit output. This happens outside the lock.
3. A second critical section re-checks the token and username. In the same step it removes the token and stores the account with its immutable consent record (version and time) and phone-verification record.

Any failure therefore leaves the proof usable. In a concurrent race for one username, exactly one request wins and the others get `409` with their tokens intact.

## Data handling

- Plaintext passwords are never stored or returned. Only code and token digests are retained; the token is returned once after verification, and devCode is intentionally returned only by the explicit development mock.
- Nothing in the enrollment package logs anything.
- The `toString()` of every DTO and stored record redacts the password, code, token and phone.
- The password `String` itself stays on the JVM heap until GC, because Jackson creates it.
- Memory is bounded: expired challenges, tokens and rate windows are pruned on every call, with caps as above.

## Not implemented / limits

- No real SMS provider, no real proof of phone ownership, no identity or license verification, and no PASS/본인확인.
- No login, session, password reset, account activation, admin review, or linking to tenants and doctors.
- Rate limits are per JVM and in memory. Source limits use the socket address only.
- A phone number may be used for more than one account.
- The consent version is a draft placeholder. The legal texts themselves are not served by the backend.
