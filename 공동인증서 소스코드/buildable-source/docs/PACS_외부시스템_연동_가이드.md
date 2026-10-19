# 외부 PACS ↔ Vision-Certi 공동인증·판독 전자서명 연동 가이드

> Current Tailscale endpoint: `https://100.68.224.44:5184` (HTTPS/TCP 5184). See [Tailscale PACS connection](TAILSCALE_PACS_CONNECTION.md) for the active network, CA, and firewall setup.

검증 기준일: 2026-10-19. 이 문서는 **다른 PACS의 개발자가 현재 구현된 API에 연결**하기 위한 계약이다. 아래 주소는 인비즈 내부망 시험 환경이며, 실제 의료기관 운영 주소와 인증기관 신뢰 설정은 별도로 배포해야 한다.

## 1. 접속 주소와 현재 지원 범위

| 용도 | 내부망 시험값 | 사용 위치 |
|---|---|---|
| 사용자 인증서 화면 및 API 공개 기준 URL | `https://100.68.224.44:5184` | 의사 PC 브라우저와 다른 PACS 서버 |
| PACS API 경로 | 위 URL의 `/v1/integrations/pacs/...` | PACS **서버**에서만 호출 |
| 인증서 로그인 팝업 | `https://100.68.224.44:5184/#/certificates/pacs-login?...` | 의사 PC 브라우저 |
| 최초 인증서 등록 팝업 | `https://100.68.224.44:5184/#/certificates/pacs-enroll?...` | 의사 PC 브라우저 |
| 관리자 화면 | `https://100.68.224.44:5184/#/admin/login` | 인비즈 관리자 |
| API 원본 | `http://127.0.0.1:18082` | 인비즈 서버 내부 전용; 다른 PC에서 직접 접속 불가 |

`5184`는 현재 HTTPS LAN 시험 프런트엔드이고 `/v1`을 내부 API로 프록시한다. 공개 LAN 시험 CA는 인비즈 서버의 `work/lan-test/public/vision-certi-lan-ca.cer`에 있다. PACS 서버와 의사 PC가 이 **공개 CA만** 신뢰하도록 배포해야 TLS 검증이 통과한다. 비공개 CA 키나 PACS HMAC 비밀값을 브라우저에 배포하면 안 된다. 다른 PC에서 `Test-NetConnection 100.68.224.44 -Port 5184`와 CA 검증을 수행하고, 방화벽은 필요한 PACS/의사 PC 대역에만 허용한다. 이 컴퓨터에서 LAN IP로 접속한 시험은 통과했지만 **별도 PC에서의 방화벽·라우팅 시험은 아직 수행하지 않았다.**

운영 환경에서는 정식 TLS 인증서를 가진 고정 DNS 주소(예: `https://cert.example-hospital.kr`)와 리버스 프록시를 제공하고 `/v1`을 내부 API로 전달한다. 개발용 Vite 서버와 시험 CA를 운영 종단점으로 사용하지 않는다. PACS의 서버→서버 호출도 TLS 검증을 유지한다.

**현재 제약:** 인비즈 서버 설정은 HMAC PACS 클라이언트를 **한 번에 한 개**만 받는다. 기존 SaintView와 다른 PACS가 서로 다른 `clientId`/비밀값으로 동시에 연결하려면 서버의 다중 클라이언트 지원을 추가해야 한다. 현재 `contentType`/`schema`도 `saintview-pacs-report-snapshot/v1`로 고정되어 있다. 다른 PACS는 아래 형식으로 스냅샷을 변환해야 하며, 독립된 범용 판독문 형식을 쓰려면 API 버전을 추가해야 한다.

## 2. 구성 및 신뢰 경계

1. 인비즈 운영자가 PACS별 `clientId`, 32바이트 이상 HMAC 비밀값, 허용 `tenantId`를 안전한 경로로 설정한다. 서버 설정은 `VISIONCERTI_PACS_CLIENT_ID`, `VISIONCERTI_PACS_CLIENT_SECRET_FILE`, `VISIONCERTI_PACS_TENANT_IDS`, `VISIONCERTI_PACS_AUDIENCE`(기본 `vision-certi:pacs-integration:v1`)이다. 미설정 상태의 PACS 경로는 `503 INTEGRATION_NOT_CONFIGURED`를 반환한다.
2. PACS 서버에는 동일한 비밀값과 공개 URL, `clientId`, `tenantId`, 필요한 경우 LAN 시험 CA 파일을 설정한다. 비밀값은 서버 파일/비밀 저장소에만 보관하고 화면·로그·URL에 남기지 않는다. PACS의 브라우저 공개 설정에는 공개 URL과 `clientId`만 넣는다.
3. 인비즈 웹 빌드/시작 시 `VITE_PACS_ALLOWED_ORIGINS`에 **정확한 PACS 브라우저 origin**(스킴+호스트+포트)을 포함한다. `postMessage`의 `origin`, `source`, 64자리 16진수 `nonce`, `audience`를 양쪽 화면에서 검증한다. 현재 실행 중인 웹 프로세스의 origin 목록은 시작 시 고정되므로 새 PACS origin을 넣으면 웹을 다시 시작해야 한다.
4. PACS는 자신의 ID/비밀번호와 계정·병원 권한을 직접 관리한다. 인비즈의 `doctorId`와 인증서 지문은 **PACS 계정과 명시적으로 연결**하고, 인증서만 보고 환자/병원 권한을 부여하지 않는다. 최초 등록 중 입력한 의사면허번호와 전문의 자격번호는 관리자 검토 대상이다. 공동인증서 자체가 면허를 증명하지 않는다.

참조 구현은 SaintView PACS의 `backend/app/integrations/vision_certi/{client,hmac_auth,snapshot}.py`와 `frontend/src/lib/visionCerti.ts`에 있다. 다른 PACS는 이 파일을 그대로 설치하는 대신 아래 계약에 맞춰 자체 서버/화면을 구현한다.

## 3. PACS 서버 인증: `VC-HMAC-SHA256-V1`

`/v1/integrations/pacs/**`에는 의사 bearer나 개발용 헤더 대신 매 요청마다 HMAC 헤더 다섯 개가 필요하다.

| 헤더 | 값 |
|---|---|
| `X-VC-Client-Id` | 설정된 PACS `clientId` |
| `X-VC-Timestamp` | 현재 Unix 초; 서버 시각과 ±300초 이내 |
| `X-VC-Nonce` | 매 요청 새 base64url 문자열, 22~64자; 10분 내 재사용 금지 |
| `X-VC-Content-SHA256` | **실제 전송 본문 바이트**의 SHA-256 소문자 hex. GET은 빈 바이트의 해시 |
| `X-VC-Signature` | 아래 문자열의 HMAC-SHA256 결과를 패딩 없는 base64url로 인코딩 |

서명 문자열은 다음 필드를 `\n` 하나로 연결한다. 마지막 필드 뒤에는 개행을 추가하지 않는다.

```text
VC-HMAC-SHA256-V1
{audience}
{clientId}
{UPPERCASE_METHOD}
{rawPath}
{rawQuery 또는 빈 문자열}
{unixSeconds}
{nonce}
{bodySha256LowerHex}
```

`rawPath`는 `/v1/integrations/pacs/...`를 포함한 실제 요청 경로다. 프록시가 경로/쿼리를 재작성하면 서명이 불일치한다. 요청 본문 상한은 512 KiB이며 서명 검증 후에도 JSON 필드별 검증이 적용된다. 동일 nonce는 `409 INTEGRATION_NONCE_REPLAYED`다.

아래 Python 표준 라이브러리 예제는 **조회만** 한다. `VC_BASE_URL`, `VC_CLIENT_ID`, `VC_TENANT_ID`, `VC_SECRET_FILE`, `VC_CA_FILE`을 PACS 서버의 환경 변수로 준비하고 실행한다. 시험 환경의 `VC_BASE_URL`은 `https://100.68.224.44:5184`다. 예제의 `pacsUserId`는 PACS 내부 사용자 ID로 바꾼다.

```python
import base64, hashlib, hmac, json, os, secrets, ssl, time, urllib.request

base = os.environ["VC_BASE_URL"].rstrip("/")
client_id = os.environ["VC_CLIENT_ID"]
tenant_id = os.environ["VC_TENANT_ID"]
secret = open(os.environ["VC_SECRET_FILE"], "rb").read().strip()
audience = os.getenv("VC_AUDIENCE", "vision-certi:pacs-integration:v1")
path = "/v1/integrations/pacs/enrollments/status"
body = json.dumps({"tenantId": tenant_id, "pacsUserId": "doctor-001"},
                  ensure_ascii=False, separators=(",", ":")).encode("utf-8")
timestamp = str(int(time.time()))
nonce = secrets.token_urlsafe(24)
body_sha = hashlib.sha256(body).hexdigest()
canonical = "\n".join(["VC-HMAC-SHA256-V1", audience, client_id, "POST",
                       path, "", timestamp, nonce, body_sha])
signature = base64.urlsafe_b64encode(
    hmac.new(secret, canonical.encode("utf-8"), hashlib.sha256).digest()
).decode("ascii").rstrip("=")
headers = {"Content-Type": "application/json", "X-VC-Client-Id": client_id,
           "X-VC-Timestamp": timestamp, "X-VC-Nonce": nonce,
           "X-VC-Content-SHA256": body_sha, "X-VC-Signature": signature}
tls = ssl.create_default_context(cafile=os.environ["VC_CA_FILE"])
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                     urllib.request.HTTPSHandler(context=tls))
request = urllib.request.Request(base + path, data=body, headers=headers, method="POST")
with opener.open(request, timeout=10) as response:
    print(response.status, json.load(response))
```

## 4. 최초 공동인증서 등록

PACS에서 의사의 **기존 PACS 계정 인증을 먼저 완료**하고, 해당 계정의 `pacsUserId`, 이름, 의사면허번호, 소속 판독센터를 서버가 확정한다. 브라우저가 임의로 보내는 이 값들을 신뢰하지 않는다.

1. PACS 서버 → 인비즈 `POST /v1/integrations/pacs/enrollments/status` (HMAC): `{"tenantId":"...","pacsUserId":"..."}`. `registrationStatus`가 `NOT_REGISTERED`면 최초 등록으로 진행한다. `PENDING`이면 관리자 검토를 기다리고, `ACTIVE`면 인증서 로그인으로 진행한다.
2. PACS 서버 → 인비즈 `POST /v1/integrations/pacs/enrollments/start` (HMAC): `{"tenantId":"...","pacsUserId":"...","name":"...","licenseNumber":"...","centerName":"..."}`. 응답 `201`에 `doctorId`와 `challenge`가 온다. `pacsUserId`는 `[A-Za-z0-9_.:@-]`의 1~128자다.
3. PACS 브라우저는 `https://<INVIZ>/#/certificates/pacs-enroll?nonce=<64자리 hex>&audience=<clientId>&origin=<PACS origin>` 팝업을 연다. 팝업이 `VC_ENROLLMENT_READY`를 보내면 PACS 브라우저가 같은 origin/source/nonce/audience를 확인한 후 `VC_ENROLLMENT_CHALLENGE`로 **서버에서 받은 challenge 원문**을 전달한다.
4. 의사는 PC의 외부 공동인증서(PFX/P12 또는 NPKI)를 선택하고 비밀번호로 **로컬에서** challenge 바이트에 서명한다. 인비즈는 공개 인증서, 서명, 허용 CA 체인, 유효기간, CRL을 검증하고 면허/전문의 자격정보 및 동의를 받는다. **개인키와 인증서 비밀번호는 인비즈/PACS 서버로 보내지 않는다.**
5. 팝업의 `VC_ENROLLMENT_COMPLETE`는 접수 알림일 뿐 활성화 증거가 아니다. PACS 서버가 1번 상태 API를 다시 조회하여 `ACTIVE`를 확인해야 한다. 실제 활성화에는 인비즈 관리자의 면허·신원/인증서 검토가 필요하다. `ACTIVE` 전에는 인증서 로그인과 판독 승인 요청을 허용하지 않는다.

## 5. 매번 공동인증서 로그인 및 PACS 세션

1. PACS 브라우저가 `https://<INVIZ>/#/certificates/pacs-login?nonce=<64자리 hex>&audience=<clientId>&origin=<PACS origin>` 팝업을 연다. 의사는 등록된 인증서를 선택하고 비밀번호를 입력한다. 인비즈는 새 일회용 challenge의 서명·신뢰체인·폐지 상태·등록 지문을 검증한다.
2. 인비즈 팝업이 `VC_HANDOFF_CODE`와 함께 `handoffCode`, `nonce`, `audience`를 PACS 창에 전달한다. PACS는 `event.origin === INVIZ origin`, `event.source === 열었던 팝업`, `nonce`/`audience` 일치를 확인한다. 브라우저의 `certificatePasswordVerified` 표시는 안내용이고 인증 근거가 아니다.
3. PACS 브라우저는 코드를 **PACS 서버**에 보낸다. 서버가 `POST /v1/integrations/pacs/handoffs/redeem` (HMAC), 본문 `{"handoffCode":"..."}`로 교환한다. 코드는 60초 유효·1회 사용이다. 응답의 `tenantId`, `doctorId`, `doctorStatus=ACTIVE`, `identityMode=PC_CERTIFICATE`, `certificateEnrollmentId`, `certificateFingerprintSha256`, `doctorLicenseNumber`, `certificatePasswordVerified=true`, `sessionExpiresAt`를 검사하고 **해당 PACS 계정/병원/면허와 연결된 사람인지** 재검증한다. 교환 실패 시 PACS 로그인 완료 처리 금지.
4. PACS가 자기 세션을 만든 뒤, **5분 안에** `POST /v1/integrations/pacs/certificate-logins/confirm` (HMAC) 호출:

   ```json
   {"handoffCodeSha256":"<코드 바이트의 SHA-256 소문자 hex>","tenantId":"demo-hospital-a","doctorId":"<UUID>","certificateEnrollmentId":"<UUID>","pacsAccountId":"doctor-001","pacsLoginId":"doctor-001","pacsSessionId":"12345"}
   ```

   현재 `pacsSessionId`는 **숫자 1~20자리**여야 한다. UUID 세션을 쓰는 PACS는 별도의 숫자 매핑 ID가 필요하다. 응답 `recorded=true`, `expiresAt`를 PACS DB에 저장한다. 인증서 세션 최초 만료는 기본 **2시간**이며, 화면 상단 카운트다운에는 서버 응답 시간을 사용한다.
5. PACS 서버는 `GET /v1/integrations/pacs/sessions/{pacsSessionId}` (HMAC)로 `active`, `certificateFingerprintSha256`, `expiresAt`, `extendedCount`를 확인한다. 연장 버튼은 만료 **전** `POST /v1/integrations/pacs/sessions/{pacsSessionId}/extend` (HMAC), 본문 `{"tenantId":"...","doctorId":"<UUID>","pacsAccountId":"doctor-001","pacsSessionId":"12345","certificateFingerprintSha256":"<64자리 hex>","extendMinutes":60}`로 호출한다. 반환된 새 `expiresAt`을 PACS 세션에도 반영한다. 이미 만료되었다면 재로그인해야 한다.

PACS의 ID/비밀번호 로그인은 현재 기본 설정상 가능하지만 **인증서 세션이 없으므로 판독 최종 승인 권한의 근거가 되지 않는다.** 의료 영상 열람·일반 권한과 서명된 판독 확정을 구분한다.

## 6. 판독문 최종 승인·전자서명

1. PACS 서버가 판독문을 수정 불가능한 **정확한 UTF-8 JSON 바이트**로 고정한다. 현재 인비즈 수용 형식은 `contentType = application/vnd.saintview.pacs-report+json;version=1`, JSON `schema = saintview-pacs-report-snapshot/v1`이다. 스냅샷에는 최소 `tenantId`, `doctorId`, `report.externalReportId`, `report.revision`을 넣고 요청 본문과 일치시킨다. 실제 판독 내용, 검사 식별자, 작성자, 버전도 포함한다. 최대 256 KiB.
2. PACS 서버가 `POST /v1/integrations/pacs/report-sign-requests` (HMAC, `Idempotency-Key` 필수)를 호출한다. 예시(실제 `contentBase64`/해시는 **동일한 스냅샷 바이트**에서 계산):

   ```json
   {"tenantId":"demo-hospital-a","doctorId":"<UUID>","externalReportId":"other-pacs:report:42","externalStudyId":"other-pacs:study:17","revision":1,"title":"흉부 판독문","contentType":"application/vnd.saintview.pacs-report+json;version=1","contentBase64":"<base64(UTF-8 JSON)>","contentSha256":"<SHA-256 lowercase hex>","pacsSessionId":"12345","certificateFingerprintSha256":"<로그인 인증서 DER의 SHA-256>"}
   ```

   동일 리비전 재시도에는 같은 `Idempotency-Key`와 **같은 바이트**를 사용한다. 다른 본문에 같은 키를 쓰면 `409`다. 인비즈는 확정된 PACS 세션과 등록 인증서 지문, 유효한 의사 세션을 다시 확인한다. 응답 `201`의 `signRequestId`, `documentVersionId`, `documentSha256`, `status`, `expiresAt`를 PACS에 저장한다.
3. 의사가 인비즈 화면 `https://<INVIZ>/#/doctor/sign-requests/<signRequestId>?audience=<clientId>&origin=<PACS origin>`에서 **동일한 판독문**을 확인한다. 의사 PC가 등록된 외부 공동인증서로 인비즈의 `certificate-payload` 바이트를 서명하고 `REVIEWED_EXACT_REPORT_CONTENT` 동의를 보낸다. 인비즈가 그 외부 인증서 서명을 검증한 후 OpenBao 의사 키로 판독 스냅샷에 서명한다. PACS가 의사 승인 API를 대신 호출해서는 안 된다.
4. 팝업의 `VC_SIGN_COMPLETED`는 조회를 시작하는 신호다. PACS 서버가 `GET /v1/integrations/pacs/report-sign-requests/{signRequestId}` (HMAC)로 **`status=COMPLETED`와 `signature`**를 확인하고, 응답의 `documentSha256`이 자신이 보관한 스냅샷 바이트의 SHA-256인지 대조한다. `doctorEvidence.publicKeySpki`와 서명값으로 **자기 스냅샷 바이트에 대한 ECDSA P-256/SHA-256 서명**을 검증한 뒤에만 판독 확정 상태를 기록한다. `PENDING`, 거절, 만료, 5xx/네트워크 오류에서는 최종 확정하지 않는다.

외부 공동인증서는 의사 로그인과 최종 승인 동작의 **개인키 소유 증명**에 사용된다. 보관되는 판독문 자체의 서버 서명은 별도의 **OpenBao 의사 키**로 생성된다. 현재 서명 형식은 CAdES/PAdES나 공인 타임스탬프가 아니다.

## 7. 오류 처리와 검증 순서

| 상황 | PACS 처리 |
|---|---|
| `401 INTEGRATION_AUTH_INVALID` | 비밀값·시계·정확한 본문/경로/쿼리·HMAC 형식 확인. 재시도마다 새 nonce 사용 |
| `409 INTEGRATION_NONCE_REPLAYED` | 같은 HTTP 요청을 재송신하지 말고 새 nonce/서명을 만든다 |
| `401 HANDOFF_INVALID` | 코드 만료·사용·오입력; 의사에게 인증서 로그인을 다시 요청 |
| `PENDING` / `DOCTOR_NOT_ACTIVE` | 관리자 검토/활성화 후 재시도; PACS 임의 활성화 금지 |
| `403 REPORT_CERTIFICATE_MISMATCH` / `REPORT_CERTIFICATE_SESSION_INVALID` | 로그인 세션·지문·의사·tenant를 다시 확인; 만료 시 재로그인 |
| `409 DOCUMENT_HASH_MISMATCH` / `IDEMPOTENCY_KEY_REUSED` | 스냅샷 바이트·버전·재시도 키를 수정한 뒤 새 승인 절차 시작 |
| `503`, 타임아웃, TLS/CRL/OpenBao 장애 | 판독 확정을 보류하고 서버 상태를 확인. 실패를 성공으로 간주하지 않음 |

최소 검증 순서는 ① 다른 PACS PC에서 HTTPS/CA 검증 ② 위 HMAC 조회가 `200`인지 확인 ③ 미등록 PACS 사용자 `NOT_REGISTERED` ④ 최초 등록 후 관리자 활성화 ⑤ 인증서 로그인 handoff 1회 교환·재사용 거부 ⑥ 2시간 세션 조회·1시간 연장 ⑦ 인증서 지문 불일치 시 판독 요청 거부 ⑧ 동일 스냅샷 서명 완료·서명값 검증 ⑨ 서비스 장애 시 판독 미확정이다.

**이번 검증 결과:** 현재 서버의 `/v1/runtime/capabilities`는 `profile=dev`, `persistence=POSTGRESQL`, 인증서 CRL `FRESH`, `signing.available=true`, `pacsIntegration.available=true`다. 이 컴퓨터에서 LAN 주소로 시험 CA를 검증하며 **이 문서의 Python 예제 그대로** HMAC 상태 조회를 실행해 `200 / NOT_REGISTERED`를 받았다. 인비즈 API 테스트 16개(`PacsIntegrationApiTest` 8개 + PostgreSQL 테스트 8개)와 SaintView PACS 연동 테스트 27개가 통과했다. 실행 중인 두 서버를 통한 합성 의사·합성 판독문 E2E도 10단계 모두 통과했다. 여기에는 최초 등록·관리자 승인, handoff 재사용 거부, 최초 2시간/연장 1시간 세션, 판독 전 인증서 재확인, OpenBao 서명 검증 후 확정이 포함된다. **별도 PACS 장비의 네트워크, 실제 인증기관 공동인증서/CRL, 운영 TLS와 법적 적합성은 아직 검증되지 않았다.**

관리자는 `GET /v1/admin/certificate-operations`(관리자 인증, tenant 범위)와 관리자 화면에서 의사명·면허번호·전문의 자격번호·인증서 발급자/만료일·PACS 로그인 ID·시도/성공·판독 요청 건수·세션 만료/연장 횟수를 조회할 수 있다. PACS HMAC 비밀값으로 이 관리자 API를 호출할 수는 없다.

구현 근거: [`PacsIntegrationController`](../apps/backend/src/main/java/com/inviz/visioncerti/integration/PacsIntegrationController.java), [`IntegrationAuthFilter`](../apps/backend/src/main/java/com/inviz/visioncerti/integration/IntegrationAuthFilter.java), [`PacsHandoffService`](../apps/backend/src/main/java/com/inviz/visioncerti/integration/PacsHandoffService.java), [`PacsReportSignService`](../apps/backend/src/main/java/com/inviz/visioncerti/integration/PacsReportSignService.java), [기존 SaintView 연동 상세](../apps/backend/docs/SV70_PACS_INTEGRATION.md).
