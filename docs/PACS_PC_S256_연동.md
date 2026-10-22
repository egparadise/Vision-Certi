# PACS SP10 공개 PC 클라이언트 연동

2026-10-22. 검토 대상: SaintView PACS 3.40.10 패키지의 `certificate-pc-s256-sp9.md`, handoff 문서, 서버 소스 패치 및 배포 명세. 이 문서는 구현된 직접 HTTPS 교환 규격이다. 초기 제안서의 JWT/JWKS 발급 방식은 구현하지 않는다.

## 접속 값

| 항목 | 값 |
| --- | --- |
| 웹 | `https://vision-certi.web.app` |
| API | `https://vision-certi.web.app/v1` |
| 공개 Client ID / audience | `vision-certi-pacs` |
| 현재 기관 | `inviz` |
| 설치형 PACS origin | `http://tauri.localhost` |
| 지원 규격 | `VC-PC-PROOF-S256-V1`, `S256` |

이전 `hahattalk-vision-certi.web.app` 사이트도 같은 웹/API 배포를 유지한다. PACS는 팝업을 열기 전에 공식 기본 주소로 정규화해야 이동 후 postMessage origin이 달라지는 문제를 피할 수 있다. 다른 PACS의 실제 origin과 기관은 별도로 설정한다. 임의 origin이나 기관에 자동 권한을 부여하지 않는다.

## 인증 순서

1. helper가 `GET /pacs-certificate-proofs/pc/capabilities`로 지원 여부를 확인한다.
2. helper가 매 시도마다 임의 verifier와 64자리 소문자 hex nonce를 생성한다. verifier는 브라우저 URL이나 메시지에 넣지 않는다. `codeChallenge = BASE64URL(SHA256(ASCII(verifier)))`, 패딩 없음. verifier는 43~128자 `[A-Za-z0-9._~-]`이며 충분한 난수로 생성해야 한다. 계산은 [RFC 7636](https://www.rfc-editor.org/rfc/rfc7636.html#section-4.2)을 따른다. 전체 서비스가 OAuth 서버라는 의미는 아니다.
3. 팝업 `/#/certificates/pacs-proof`에 `audience`, `origin`, `nonce`, `codeChallenge`, `codeChallengeMethod=S256`을 전달한다.
4. 웹은 `POST /pacs-certificate-proofs/challenges`에 `audience`, `pcNonce`, `codeChallenge`, `codeChallengeMethod`를 보낸다. 기관이 여러 개인 서버 설정에서는 허용된 `tenantId`도 필요하다. 서버 서명 대상에 PC nonce와 S256 값을 포함하고 웹에서 일치를 확인한다.
5. 각 PC의 인증서 프로그램이 인증서를 검색하고 비밀번호로 개인키 서명을 수행한다. 서버가 기존 인증서 검증 정책과 서명을 확인한다. 개인키·인증서 비밀번호·PACS 비밀번호를 서버에 보내지 않는다.
6. 서버의 60초 일회용 코드를 웹이 정확한 PACS origin으로 전송한다: `{type:"VC_CERTIFICATE_PROOF_CODE",nonce,audience,verificationCode}`. PACS는 origin, source, nonce, audience를 모두 검사한다.
7. helper가 아래 redeem API로 교환한다. code/nonce/audience/verifier/만료/미사용 조건이 모두 맞아야 성공한다. 같은 코드가 동시에 요청되어도 한 번만 교환된다.
8. SP10의 최종 `인증되었습니다.` 화면은 helper의 교환 및 PACS 로그인·계정 연결이 끝난 뒤 PACS에서 표시한다. 웹의 코드 전달 직후 화면은 `PACS 로그인 확인 중`이다. 서버는 PACS 로그인이 성공했다고 추측하지 않는다.
9. 판독 승인 전에는 아래 status API로 해당 인증의 최신 상태를 확인한다. 저장한 `authentication:Y`만으로 영구 승인하지 않는다.

## API

모든 경로는 위 API 주소 기준이며 HTTPS로 사용한다. 응답은 캐시하지 않는다. verifier와 코드를 요청 URL·로그에 남기지 않는다.

### 결과 교환

`POST /pacs-certificate-proofs/pc/redeem`

```json
{
  "audience": "vision-certi-pacs",
  "verificationCode": "<웹이 전달한 일회용 코드>",
  "codeVerifier": "<helper가 보관한 원본 verifier>",
  "nonce": "<해당 시도의 64자리 hex>",
  "clientInfo": {
    "pacsLoginId": "<로그인 ID>",
    "pacsAccountId": "<PACS 계정 번호>",
    "computerName": "<PC 이름>",
    "deviceId": "<PC별 지속 UUID>"
  }
}
```

`clientInfo`는 선택 확장이다. SP10 원래 요청처럼 없어도 교환된다. 응답 `{receipt,nonce,codeChallenge}`의 receipt에는 `verified:true`, `authentication:"Y"`, `verificationId`, `audience`, 인증서 SHA-256 지문·주체·발급기관·만료일·`verifiedAt`·`expiresAt`이 포함된다. HTTPS 서버 응답이며 오프라인 검증용 JWT/JWS 파일이 아니다.

### 상태 확인

`POST /pacs-certificate-proofs/pc/status`

```json
{"audience":"vision-certi-pacs","verificationId":"<UUID>","codeVerifier":"<원본 verifier>"}
```

현재 유효한 receipt를 반환한다. 해당 proof의 verifier로만 확인할 수 있으며 인증 연결 수명은 2시간이다. 상태 확인 시 기존 인증서 유효성/폐지 검사를 적용한다. verifier는 서버의 S256 해시와 비교하며 원문을 저장하거나 감사 기록에 넣지 않는다. PC는 verifier를 OS 보호 저장소에 암호화해 보관한다. 이 규격에는 연결 연장 API가 없으므로 만료하면 새 인증을 시작한다.

### 로그인·판독 요청 기록 — 서버 추가 확장

`POST /pacs-certificate-proofs/pc/events`

```json
{
  "audience":"vision-certi-pacs",
  "verificationId":"<UUID>",
  "codeVerifier":"<원본 verifier>",
  "event": {
    "eventId":"<이벤트별 UUID, 재시도할 때 유지>",
    "action":"APPROVAL_REQUESTED",
    "clientInfo":{"pacsLoginId":"<ID>","pacsAccountId":"<계정 번호>","computerName":"<PC 이름>","deviceId":"<PC UUID>"},
    "reportKeySha256":"<기관/PACS와 변하지 않는 판독문 ID의 SHA-256 소문자 hex>"
  }
}
```

- action: `LOGIN_ATTEMPTED`, `LOGIN_SUCCEEDED`, `LOGIN_FAILED`, `APPROVAL_REQUESTED`.
- 로그인 시도 직전/성공 응답 후/실패 시 각각 보고한다. 최초 ID/PW가 인증서 교환보다 먼저 확인되었다면 교환 후 관측한 결과를 보고한다.
- `reportKeySha256`은 승인 요청에서만 필수다. 같은 판독문의 작성·저장·재승인 동안 동일한 식별자를 유지한다. 환자정보나 판독문 원문을 전송하지 않는다.
- `pacsLoginId`, `deviceId` 필수. 네 clientInfo 값은 최대 128자이며 제어문자를 허용하지 않는다.
- 성공 응답 `{recorded:true,duplicate:false}`. 같은 eventId/내용 재전송은 `duplicate:true`로 중복 집계를 막는다. 다른 내용으로 재사용하면 409다.
- 유효한 교환 완료 proof와 verifier가 필요하다. 2시간이 지난 공개 PC verifier로 지연 이벤트를 제출할 수 없다. 만료/401/403은 무한 재전송하지 않고 로컬 실패 내역을 남긴다. 이 요청은 상태 확인이나 승인 권한을 대신하지 않는다.
- 서버는 계정·PC 정보를 `PACS_REPORTED`로 표시한다. 검증된 인증서 정보와 클라이언트가 주장한 계정·장치 값을 구분한다. IP는 직접 접속 IP와 미검증 프록시 전달 IP로 구분한다.

**제공된 SP10 패치에는 clientInfo/events 호출이 없다.** 서버 확장은 준비되어 있지만 PACS helper에서 위 호출을 추가해야 계정·PC 이름·로그인 완료·판독문 수가 채워진다. 기존 3.40.6용 기록 패치를 SP10에 설치하지 않는다. capabilities의 `clientEvents:true`, `clientEventPath`로 확장 지원을 확인한다. 미전송 과거 값은 소급 생성하지 않는다.

## 호환·오류·운영

- 기존 서버 간 HMAC redeem/status/events를 유지한다. PC용 코드를 HMAC redeem으로 바꿔 쓰거나 기존 코드를 PC redeem으로 교환할 수 없다. 서버 HMAC 키를 PC에 배포하지 않는다.
- `401 CERTIFICATE_PROOF_CODE_INVALID`: 코드/요청 결합 오류, 재사용 또는 만료. 새 인증 시도를 시작한다.
- `403 CERTIFICATE_PROOF_INACTIVE`: 연결이 없거나 만료됨. 새 인증 필요.
- `422 PROOF_PKCE_INVALID`: 잘못된 nonce, S256 challenge 또는 method. `plain`으로 내려가지 않는다.
- 인증서 정책/폐지 검사 실패 시 인증을 승인하지 않는다. 기존 운영 검증 정책을 변경하지 않았다.
- JSONB의 nullable 필드 추가로 V010 스키마를 유지한다. 신규 테이블·DB 초기화·키 재발급이 필요 없다. 기존 행에 필드가 없어도 HMAC 흐름을 유지한다.
- PC 이벤트는 관측 기록이며 PACS 계정 인증, 문서 내용 서명, 임상 사용 승인 증명이 아니다.
- 서버와 웹을 함께 배포한다. 롤백 시 두 부분을 함께 복원하고 새 PC 인증을 다시 시작한다. 새 proof를 이해하지 못하는 이전 서버와 혼용하지 않는다.
