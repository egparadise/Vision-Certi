# PACS 최초 공동인증서 등록

PACS가 기존 ID/비밀번호를 검증한 뒤, 서버 간 HMAC 인증으로 최초 등록을 시작한다. 사용자가 관리자 초대 코드를 입력할 필요가 없다. INVIZ는 PACS 비밀번호를 받거나 보관하지 않는다.

관리자 사전등록과 초대 발급·초대 기반 등록은 기본적으로 중지되어 있다. 설정은
`visioncerti.doctor.admin-invitations-enabled` / `VISIONCERTI_ADMIN_INVITATIONS_ENABLED`이며 기본값은
`false`다. 이 상태에서는 기존 미사용 초대와 이미 발급된 초대 기반 인증 요청의 완료도
`403 ADMIN_INVITATIONS_DISABLED`로 거절한다. `/v1/doctor-activation/config`의
`adminInvitationsEnabled`가 현재 정책을 표시한다. 기존 기능을 재개할 때만 명시적으로 `true`를 설정한다.

이 스위치는 PACS 자체 등록, 공동인증서 로그인, 유효한 의사 세션을 통한 인증서 갱신,
관리자의 인증서 검토·정지·폐지에는 영향을 주지 않는다. PACS 로그인마다 등록된 공동인증서의
새로운 일회성 요청 서명을 검증한다. 최초 계정 연결에 사용하는 PACS 계정 확인과 이후의
공동인증서 로그인은 구분한다.

## API

두 API 모두 기존 `IntegrationAuthFilter`의 HMAC, 시간 및 nonce 검증과 기관 허용 목록을 적용한다.

- `POST /v1/integrations/pacs/enrollments/start`: `{tenantId,pacsUserId,name,licenseNumber,centerName}` → HTTP 201 `{doctorId,challenge}`. `challenge`는 기존 공동인증서 챌린지와 같은 형식이다.
- `POST /v1/integrations/pacs/enrollments/status`: `{tenantId,pacsUserId}` → HTTP 200 `{doctorId,registrationStatus,certificateEnrollmentId,fingerprintSha256,doctorLicenseNumber}`.

`registrationStatus`는 `NOT_REGISTERED`, `PENDING`, `ACTIVE`, `SUSPENDED`, `REVOKED` 중 하나다. `NOT_REGISTERED`에는 최초 등록을 시작했지만 인증서 증명·면허정보·동의를 마치지 못한 PACS 계정도 포함된다. 이때 `doctorId`는 이미 발급되었을 수 있으며, 같은 PACS 계정으로 재시도하면 이어서 등록한다. `PENDING`은 동의까지 저장하고 의료인 자격 검토·승인을 기다리는 상태다. `ACTIVE`는 의사 활성 상태와 현재 인증서의 신뢰체인·유효기간·폐지 상태를 다시 확인한 뒤 반환한다. 인증서 상태를 확인할 수 없으면 오류로 종료한다.

PACS 서버는 요청자 세션에서 계정과 면허 정보를 조회해야 한다. 브라우저가 제공한 사용자 ID나 면허번호를 그대로 전달해서는 안 된다. 이후 매 로그인 시 공동인증서 인증 결과를 확인하고 PACS 세션을 발급하는 절차는 PACS 서버가 담당한다.

## PACS 공동인증서 버튼의 화면 분기 (SDK 미연동)

PACS 로그인 화면의 공동인증서 버튼에서 **최초 등록**을 시작할 때는 PACS ID와 비밀번호를 입력한다. PACS가 먼저 의사 계정을 인증한 다음 `GET /api/vc/auth/pending/enrollment-status`로 현재 계정의 서버 상태를 조회한다. 조회 결과가 `NOT_REGISTERED`이면 추가 확인 버튼 없이 `POST /api/vc/auth/pending/enrollment-start`로 얻은 일회성 챌린지를 INVIZ 등록 창에 전달한다. `PENDING`이면 자격 검토 대기를 알리고 로그인을 허용하지 않는다. `ACTIVE`이면 등록된 공동인증서 로그인 화면을 연다. `SUSPENDED`·`REVOKED` 또는 조회 실패 시에는 두 경로 모두 진행하지 않는다. 상태 기준은 요청 횟수가 아니라 서버가 검증한 등록 완료 여부다.

등록 창에서 인증서 소유 증명, 면허·자격 정보 제출, 필수 동의가 끝나면 창을 닫고 PACS가 상태를 다시 조회한다. 이때 `PENDING`이면 검토·활성화를 기다리며 로그인은 허용하지 않는다. 등록 창의 완료 신호는 계정 활성화 증명이 아니다.

등록 후 PACS ID·비밀번호 칸을 비우고 같은 버튼을 누르면 INVIZ 공동인증서 로그인 창이 열린다. 새로운 일회성 챌린지에 대한 인증서 서명 검증이 성공하면 PACS가 `POST /api/vc/auth/certificate-login`으로 일회용 handoff 코드를 교환하여 해당 PACS 계정의 `vc1.` 세션을 발급한다. 비밀번호로 PACS에 로그인한 상태에서 `ACTIVE` 화면의 공동인증서 로그인 버튼을 누르면 `POST /api/vc/auth/certificate-step-up`을 사용한다. `ACTIVE` 상태만으로는 판독 화면 접근이나 세션 발급이 허용되지 않는다.

INVIZ의 PACS 등록·로그인 창은 로컬 SDK 대신 공개 인증서 PEM과 **일회성 요청 바이트에 대해 외부에서 생성한 서명값**을 받는다. 개인키·PFX·인증서 비밀번호는 서버로 보내지 않는다. 현재 제공되는 화면은 외부 서명 도구가 필요한 연결 화면이며, 브라우저만으로 PC 공동인증서 저장소의 개인키를 읽어 자동 서명하는 기능은 포함하지 않는다. 실제 운영 전에는 의료기관이 사용하는 인증서 형식에 맞는 독립 서명 수단과 인증기관 신뢰체인·폐지 정보가 준비되어야 한다.

## 등록과 확인

연결 키는 `clientId + NUL + tenantId + NUL + pacsUserId`의 SHA-256이다. `certi_rt.doctors.pacs_account_key`의 UNIQUE 제약과 트랜잭션 잠금으로 중복 생성을 방지한다. 동일한 대기 계정의 재시도는 같은 Doctor ID와 새로운 일회성 챌린지를 반환한다. 정지·폐지된 계정은 재생성하지 않으며, 다른 계정의 기존 면허번호를 가져오지 않는다. 등록 정보 변경이나 폐지 후 복구는 별도 관리자 처리가 필요하다.

자체 등록 레코드는 Google 이메일과 관리자 신원·면허 증빙을 생성하지 않는다. 실제 인증서 서명 검증, 신뢰체인 및 CRL 검증, 의사 본인의 동의와 관리자 인증서 신원·면허 연결 검토는 기존 절차를 사용한다. 동의만으로 서명키를 활성화하지 않는다. `IDENTITY_VERIFIED`/`CONSENTED`는 PACS 상태 조회에서는 `PENDING`이다.

공개 인증서 챌린지 API는 여전히 초대 또는 의사 세션을 요구한다. PACS 최초 등록 예외는 HMAC으로 인증된 서버 경로에만 있다.

## 의사면허·전문의 자격 제출

신규 PACS 인증서 등록과 갱신에는 `medicalCredentialsRequired=true`를 기록한다. 인증서 검증 후 발급받은
본인 bearer 세션으로 `PUT /v1/certificate-enrollment/enrollments/{registrationId}/medical-credentials`를 호출한다.

```json
{
  "medicalLicenseNumber": "123456",
  "specialist": true,
  "specialistQualificationNumber": "98765",
  "specialty": "영상의학과"
}
```

면허번호는 숫자 1~20자리이며 서버에 연결된 PACS 의사의 면허번호와 일치해야 한다. 전문의 여부는 명시해야 한다.
전문의이면 자격번호(1~50자)와 과목(1~100자)이 필요하고 제어문자는 허용하지 않는다. 전문의가 아니면
두 전문의 필드는 생략하거나 비워야 한다. 선언 내용을 입력하는 것만으로 면허나 전문의 자격이 검증되거나
진료 권한이 부여되는 것은 아니다. 외부 면허기관의 자동 검증 API는 연결하지 않았다.

응답과 본인/관리자 인증서 목록에는 위 네 필드, `medicalCredentialsRequired`,
`medicalCredentialsSubmittedAt`가 포함된다. 본인의 `PENDING` 등록이며 심사도 `PENDING`일 때만 수정할 수 있다.
수정마다 제출 시각을 갱신한다. 관리자 `APPROVE` 요청에는 화면에 표시된 제출 시각을
`medicalCredentialsSubmittedAt`로 그대로 전달하고 신원·면허·전문의 자격 증빙 참조를 `evidenceRef`에 기록한다.
제출 후 정보가 변경되면 승인 요청을 거절하므로 최신 정보와 증빙을 다시 검토해야 한다.

- 미제출 동의/승인: `409 MEDICAL_CREDENTIALS_REQUIRED`.
- 검토한 제출본과 불일치: `409 MEDICAL_CREDENTIALS_CHANGED`.
- 서버 면허번호와 불일치: `422 MEDICAL_LICENSE_MISMATCH`.
- 전문의 필수값 누락: `422 SPECIALIST_CREDENTIALS_REQUIRED`.
- 잘못된 형식/전문의 여부: `422 MEDICAL_CREDENTIALS_INVALID`.

서버는 미제출 등록의 활성화를 거절하며, PACS 등록은 기존 지문 일치만으로 자동 심사 승인하지 않는다.
제출 감사로그에는 자격번호 원문을 넣지 않는다. 승인 감사로그는 검토한 제출 시각과 증빙 참조를 연결한다.
`/v1/certificate-enrollment/config`는 `medicalCredentialsRequiredForPacs=true`를 제공한다.

데이터는 기존 `certi_rt.certificate_enrollments.payload` JSON에 저장한다. 새 컬럼/마이그레이션은 필요하지 않다.
과거 JSON의 누락 필드는 null로 읽으며 그 등록에는 기존 정책을 적용한다. 이후 PACS 갱신은 새 정책을 적용한다.

## 배포 버전

`V005__production_runtime.sql`에 이어 `database/migrations/V006__pacs_self_enrollment.sql`을 적용한다. 백엔드는 런타임 스키마 버전 6을 요구한다. 기존 의사/인증서 데이터는 보존된다. 기존 관리자 등록 계정을 면허번호만으로 자동 연결하지 않는다.

테스트는 합성 PKI와 개발용 키를 사용하는 API 테스트 및 실제 PostgreSQL 하위 테스트로 무초대 등록, HMAC/기관 통제, 중복 면허, 실제 서명 검증, 재사용 거절, 동의·검토 게이트, 활성 및 폐지 상태를 확인한다.
