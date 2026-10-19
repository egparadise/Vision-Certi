# 관리자 모드 (Admin mode)

웹 화면 `/#/admin`에서 관리자(ADMIN)가 플랫폼을 한곳에서 관리합니다. 관리자가 아니면 화면에 "관리자 권한이 필요합니다"가 표시되고, API는 `403 ADMIN_ROLE_REQUIRED`를 반환합니다. 상단 메뉴의 **관리자 모드** 버튼은 관리자에게만 보입니다.

## 화면 구성

| 메뉴 | 내용 | API |
|---|---|---|
| 개요 | 임상 사용 가능 여부, 저장소·인증·서명·인증서·PACS 구성 상태, 구성 경고, 감사·전송·서명 요청 지표 | `GET /v1/runtime/capabilities`, `GET /v1/dashboard` |
| 의사 관리 | 의사 가입 현황, 인증서 검토, 정지·폐기 (기존 의사 관리 화면) | `/v1/admin/doctors/*` |
| 관리자 계정 | OIDC issuer+subject를 관리자 권한에 연결하거나 비활성화합니다. 등록은 OIDC 모드에서만 할 수 있습니다 | `GET/POST /v1/admin/staff`, `POST /v1/admin/staff/{id}/disable` |
| 운영 정책 | 실행 중에 바꿀 수 있는 운영 값 (아래 표) | `GET /v1/admin/settings`, `PUT /v1/admin/settings/{key}`, `POST /v1/admin/settings/{key}/reset` |
| 시스템 구성 | 배포 구성을 읽기 전용으로 보여 줍니다. 비밀키와 파일 경로는 설정 여부만 표시합니다 | `GET /v1/admin/system/configuration` |
| 감사 로그 / 전송 큐 | 기존 화면 | `/v1/audit-events`, `/v1/deliveries` |

## 운영 정책 (실행 중 변경)

배포 설정(application.yml과 환경변수)이 기본값입니다. 관리자가 값을 바꾸면 그 값이 기본값보다 우선합니다.

- 변경은 허용 범위 안에서만 할 수 있습니다.
- 변경할 때는 사유(2~200자)와 `expectedRevision`이 필요합니다. 다른 관리자가 먼저 바꿨다면 `409 SETTING_CHANGED_CONCURRENTLY`가 반환됩니다.
- 변경은 `SYSTEM_SETTING_CHANGED` 또는 `SYSTEM_SETTING_RESET` 감사 이벤트로 기록됩니다.
- 변경은 이 서버에 즉시 적용되고, 다른 서버 인스턴스에는 5초 안에 적용됩니다.
- 정책은 모든 기관에 공통입니다. 그래서 `VISIONCERTI_ADMIN_SETTINGS_TENANT_IDS`에 지정한 기관(쉼표로 구분)의 관리자만 바꿀 수 있습니다.
  - 다른 기관의 관리자에게는 읽기 전용으로 보입니다.
  - 값이 비어 있으면 모든 관리자에게 읽기 전용입니다. 이것이 운영(prod) 기본값입니다.
  - dev 프로파일의 기본값은 `demo-hospital-a`입니다.

| 키 | 의미 | 범위 |
|---|---|---|
| `documents.max-bytes` | 판독문 PDF 최대 크기 | 1 MB ~ 20 MB |
| `sign-requests.ttl` | 서명 요청 유효 시간 (새로 만드는 요청부터 적용) | 1분 ~ 1시간 |
| `pacs.handoff-ttl` | PACS 핸드오프 코드 유효 시간 | 15초 ~ 5분 |
| `pacs.max-certificate-login-age` | 핸드오프를 발급할 수 있는 인증서 로그인의 최대 경과 시간 | 1분 ~ 1시간 |
| `doctor.invitation-ttl` | 의사 초대 코드 유효 시간 | 1시간 ~ 7일 |
| `doctor.session-ttl` | 의사 인증서 로그인 세션 시간 | 5분 ~ 8시간 |
| `doctor.max-identity-failures-per-invitation` | 초대 코드당 본인 확인 실패 허용 횟수 | 1 ~ 20 |
| `doctor.max-challenges-per-invitation` | 초대 코드당 인증 요청 허용 횟수 | 1 ~ 50 |
| `doctor.source-failure-hourly-limit` | 접속지별 시간당 인증 실패 한도 | 1 ~ 1000 |
| `doctor.source-challenge-hourly-limit` | 접속지별 시간당 인증 요청 한도 | 1 ~ 1000 |
| `enrollment.phone-hourly-limit` | 전화번호별 시간당 인증 문자 한도 | 1 ~ 50 |
| `enrollment.source-hourly-limit` | 접속지별 시간당 인증 문자 한도 | 1 ~ 1000 |

**화면에서 바꿀 수 없는 항목**은 보안 경계, 외부 연결, 비밀키입니다. 예를 들면 저장소 모드·DB, OIDC, 활성화 정책, 관리자 사전 등록 여부, 서명 백엔드·OpenBao, CA·CRL, PACS 연동 클라이언트입니다. 이 항목들은 계속 환경변수로만 바꿀 수 있고, 바꾼 뒤 서버를 다시 시작해야 적용됩니다. 시스템 구성 화면에서 현재 값과 해당 환경변수 이름을 확인할 수 있습니다.

## 저장소

`database/migrations/V008__admin_system_settings.sql`은 `certi_rt.system_settings` 테이블을 추가합니다.

- 설정 키마다 한 행입니다. 행을 삭제하면 기본값으로 돌아갑니다.
- 런타임 스키마 버전은 8입니다.
- V007 다음에 적용합니다. 기존 데이터는 바뀌지 않습니다.
