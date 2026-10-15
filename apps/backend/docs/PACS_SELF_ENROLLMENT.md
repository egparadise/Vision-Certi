# PACS 최초 공동인증서 등록

PACS가 기존 ID/비밀번호를 검증한 뒤, 서버 간 HMAC 인증으로 최초 등록을 시작한다. 사용자가 관리자 초대 코드를 입력할 필요가 없다. INVIZ는 PACS 비밀번호를 받거나 보관하지 않는다.

## API

두 API 모두 기존 `IntegrationAuthFilter`의 HMAC, 시간 및 nonce 검증과 기관 허용 목록을 적용한다.

- `POST /v1/integrations/pacs/enrollments/start`: `{tenantId,pacsUserId,name,licenseNumber,centerName}` → HTTP 201 `{doctorId,challenge}`. `challenge`는 기존 공동인증서 챌린지와 같은 형식이다.
- `POST /v1/integrations/pacs/enrollments/status`: `{tenantId,pacsUserId}` → HTTP 200 `{doctorId,registrationStatus,certificateEnrollmentId,fingerprintSha256,doctorLicenseNumber}`.

`registrationStatus`는 `NOT_REGISTERED`, `PENDING`, `ACTIVE`, `SUSPENDED`, `REVOKED` 중 하나다. 등록되지 않은 계정의 나머지 값은 null이다. `ACTIVE`는 의사 활성 상태와 현재 인증서의 신뢰체인·유효기간·폐지 상태를 다시 확인한 뒤 반환한다. 인증서 상태를 확인할 수 없으면 오류로 종료한다.

PACS 서버는 요청자 세션에서 계정과 면허 정보를 조회해야 한다. 브라우저가 제공한 사용자 ID나 면허번호를 그대로 전달해서는 안 된다. 이후 비밀번호 로그인 정책과 PACS 세션 발급은 PACS 서버가 담당한다.

## 등록과 확인

연결 키는 `clientId + NUL + tenantId + NUL + pacsUserId`의 SHA-256이다. `certi_rt.doctors.pacs_account_key`의 UNIQUE 제약과 트랜잭션 잠금으로 중복 생성을 방지한다. 동일한 대기 계정의 재시도는 같은 Doctor ID와 새로운 일회성 챌린지를 반환한다. 정지·폐지된 계정은 재생성하지 않으며, 다른 계정의 기존 면허번호를 가져오지 않는다. 등록 정보 변경이나 폐지 후 복구는 별도 관리자 처리가 필요하다.

자체 등록 레코드는 Google 이메일과 관리자 신원·면허 증빙을 생성하지 않는다. 실제 인증서 서명 검증, 신뢰체인 및 CRL 검증, 의사 본인의 동의와 관리자 인증서 신원·면허 연결 검토는 기존 절차를 사용한다. 동의만으로 서명키를 활성화하지 않는다. `IDENTITY_VERIFIED`/`CONSENTED`는 PACS 상태 조회에서는 `PENDING`이다.

공개 인증서 챌린지 API는 여전히 초대 또는 의사 세션을 요구한다. PACS 최초 등록 예외는 HMAC으로 인증된 서버 경로에만 있다.

## 배포

`V005__production_runtime.sql`에 이어 `database/migrations/V006__pacs_self_enrollment.sql`을 적용한다. 백엔드는 런타임 스키마 버전 6을 요구한다. 기존 의사/인증서 데이터는 보존된다. 기존 관리자 등록 계정을 면허번호만으로 자동 연결하지 않는다.

테스트는 합성 PKI와 개발용 키를 사용하는 API 테스트 및 실제 PostgreSQL 하위 테스트로 무초대 등록, HMAC/기관 통제, 중복 면허, 실제 서명 검증, 재사용 거절, 동의·검토 게이트, 활성 및 폐지 상태를 확인한다.
