# 의사 직접 가입 API

2026-10-17. 공급업체 SDK 없이 가입·인증서 소유 증명·검토·로그인·판독문 서명을 연결한다. 기존 PACS 계정과 관리자 초대코드는 직접 가입의 전제조건이 아니다.

## 계정과 세션

가입 계정은 `certi_rt.physician_accounts`, 가입 전용 세션은 `certi_rt.physician_account_sessions`에 저장한다. `Doctor ID`는 기존 의사·인증서·판독문 서명 체계와 공유한다. V005, V006 다음에 `database/migrations/V007__physician_accounts.sql`을 적용한다. 런타임 스키마 버전은 7이며 기존 데이터는 보존한다.

`VISIONCERTI_PHYSICIAN_ACCOUNT_TENANT_ID`는 가입과 자격 검토를 담당할 서버 측 기관 ID다. 클라이언트가 기관 ID나 역할을 선택해 권한을 얻을 수 없다. 운영 기본값은 비어 있어 미설정이면 가입·가입 재개를 거부한다. dev 기본값은 합성 기관 `demo-hospital-a`다. 입력한 소속 기관명은 검토할 정보이며 해당 기관의 PACS 권한을 부여하지 않는다.

비밀번호는 PBKDF2-HMAC-SHA256, 무작위 salt, 600,000회 반복으로 해시한다. 가입 전용 토큰은 `vca1.` 접두사와 256비트 난수를 사용하고 DB에는 SHA-256만 저장한다. 유효기간은 20분, 재접속하면 기존 가입 세션을 무효화한다. 브라우저는 토큰을 메모리에만 유지한다. 이 토큰은 인증서 등록 요청·본인 가입 현황·로그아웃에만 쓰며 판독·승인·서명 API에서는 거부한다.

공동인증서 검증 후의 의사 세션은 기존 정책을 따른다. 가입 정보나 비밀번호 검증만으로 인증서 소유, 휴대폰 소유, 면허 검증을 완료하지 않는다. 연락처는 미검증 상태로 저장한다. 이름·면허·인증서 연결과 전문의 자격은 기관 담당자의 증빙 검토를 거친다.

## 경로

모든 경로는 `/v1/physician-accounts`를 기준으로 한다. 민감한 응답은 `Cache-Control: no-store`다. 인증 헤더는 `Authorization: Bearer <enrollmentToken>`이며 관리자 인증 헤더를 대신 사용하지 않는다.

| 메서드·경로 | 인증 | 기능 |
|---|---|---|
| `GET /config` | 없음 | `available`, `consentVersion`, `scope=ENROLLMENT_ONLY` |
| `POST /registrations` | 없음 | 의사 계정·Doctor ID 생성과 가입 전용 세션 발급, 201 |
| `POST /sessions` | ID·비밀번호 | 가입 재개용 세션 발급, 200 |
| `GET /me` | 가입 전용 토큰 | 본인 계정과 의사 상태 조회 |
| `POST /certificate-challenges` | 가입 전용 토큰 | 본인 Doctor ID에 묶인 기존 형식의 인증서 등록 요청, 201 |
| `POST /logout` | 가입 전용 토큰 | 해당 가입 세션 폐기, 204 |

가입 예시의 동의 버전은 `/config`에서 받은 값을 사용한다.

```json
{
  "username": "doctor123",
  "password": "<12~128자 비밀번호>",
  "name": "가입 의사 성명",
  "phone": "01012345678",
  "licenseNumber": "123456",
  "centerName": "소속 의료기관",
  "privacyConsent": true,
  "consentVersion": "physician-account-2026-10-17"
}
```

ID는 영문자로 시작하는 4~32자의 영문·숫자·밑줄·마침표·하이픈이며 대소문자를 구분하지 않고 중복 검사한다. 기존 의사 계정의 면허를 새 가입 계정에 자동 연결하지 않는다. 중복 계정·면허는 기존 계정 확인 또는 기관의 별도 처리가 필요하다. 공개 가입·비밀번호 확인에는 출처별 요청 제한을 적용하고 비밀번호 확인에는 ID별 제한도 적용한다.

가입·재개 응답에는 `enrollmentToken`, `expiresAt`, `account`가 포함된다. `account`에는 `accountId`, `username`, `doctor`, `phoneVerified=false`, `scope=ENROLLMENT_ONLY`를 반환한다. 비밀번호 해시·연락처·서버 비밀키는 반환하지 않는다. `/sessions` 본문은 `username`, `password` 두 필드다.

## SDK 없는 인증서 검증

직접 가입 화면은 `/#/physician/register`, 인증서 로그인은 `/#/physician/login`이다. 이번 개발에서 PC 인증서 검색·비밀번호 입력·개인키 서명을 수행하는 공급업체 프로그램은 연결하지 않는다.

화면에서 서버의 일회성 요청 바이트를 파일로 내려받고, 외부에서 해당 바이트에 대해 생성한 서명 결과와 공개 인증서 PEM을 제출할 수 있다. 실제 원문 서명과 신뢰체인·유효기간·폐지 검증이 통과해야 다음 단계로 이동한다. 공개 인증서만 업로드하는 것은 소유 증명이 아니다. 개인키·PFX·비밀번호 입력은 받지 않는다. 이 입력 화면은 외부 서명 결과 연결용이며 PC 공동인증서를 자동으로 찾거나 서명을 생성하지 않는다.

기존 API를 그대로 사용한다.

1. `POST /v1/certificate-enrollment/verify`: 공개 DER 인증서, 중간 체인, 서명값, 알고리즘 제출. 원문은 서버에 보관된 요청 바이트를 사용한다.
2. `PUT /v1/certificate-enrollment/enrollments/{id}/medical-credentials`: 의사 세션으로 면허·전문의 여부·자격번호·전문과목 제출.
3. `POST /v1/certificate-enrollment/enrollments/{id}/consent`: 의사가 직접 필수 동의 제출.
4. 기관의 기존 검토 API로 신원·면허·자격 연결 확인, 이후 의사 활성화 API로 의사별 키 활성화.
5. `POST /v1/certificate-enrollment/login-challenges`, `POST /v1/certificate-enrollment/login`: 매번 새로운 서명으로 로그인.
6. 기존 `/v1/doctor/sign-requests/{id}`와 `/approve`: 정확한 판독문 해시·승인 요청을 확인한 뒤 의사별 키로 서명.

가입 중 화면을 닫았으면 ID·비밀번호로 가입 절차를 재개하고 새로운 인증서 소유 증명을 제출한다. 같은 직접 가입 계정의 동일 인증서가 검토 대기 중이면 기존 등록·동의·검토 기록을 유지한 채 의사 세션을 다시 발급한다. 다른 계정의 인증서를 가져오거나 이전 승인으로 자동 활성화하지 않는다.

## 운영 범위

직접 가입과 INVIZ 계정 인증·판독문 서명 경로는 연결했다. 운영에서는 기존과 마찬가지로 신뢰할 CA·폐지 자료, 기관 담당자의 검토 권한, OpenBao 키 정책과 운영 동의문을 설정해야 한다. API 인증서 검증은 SDK를 요구하지 않지만 사용자 PC에서 공동인증서 개인키로 서명값을 만드는 작업은 외부 클라이언트의 역할이다. 새로운 병원·PACS 계정 연결은 기존 서버 간 인증과 기관별 권한 확인을 거치며 면허번호만으로 자동 연결하지 않는다.

## 검증과 실행

테스트용 CA의 실제 RSA/ECDSA 서명으로 소유 증명·재사용 거부·다른 계정 인증서 연결 거부를 검증한다. 메모리 저장소와 실제 PostgreSQL에서 가입 재개·토큰 교체·만료·로그아웃, 자격 검토와 판독문 승인·서명을 검사한다. 판독 목록·상세·문서 조회도 유효한 활성 서명자 확인을 적용하여 가입 대기 또는 폐지된 인증서 세션은 거부한다.

로컬 실행 주소:

- 직접 가입·가입 이어하기: `https://100.68.224.44:5184/#/physician/register`
- 공동인증서 로그인: `https://100.68.224.44:5184/#/physician/login`
- 관리자: `https://100.68.224.44:5184/#/doctors`

`tests/physician_account_live.py`는 로컬 개발 프로파일과 스키마 7을 확인한 뒤 합성 의사·시험용 CA를 사용해 실제 HTTP, PostgreSQL, OpenBao Transit으로 26개 검사를 수행한다. 개인키는 시험 클라이언트 안에서만 사용한다. 문서 서명은 실제 OpenBao가 생성하며 외부 신뢰 여부와 임상 사용 가능 여부를 별도로 판정한다. 시험 인증서의 검증 결과를 운영 공동인증서 검증 완료로 간주하지 않는다.

`tests/browser/physician_account.mjs`는 실제 Chrome에서 가입, 모바일 레이아웃, 외부 서명 결과 입력 화면, 비밀번호 제거, 토큰의 메모리 보관, 로그아웃과 가입 재개를 확인한다. 기존 로컬 시험 CA의 HTTPS 연결에만 별도 브라우저 컨텍스트를 사용하며 시스템 인증서 설정은 변경하지 않는다. 서버 API는 별도로 CA·호스트 이름 검증을 켠 HTTPS 요청으로 확인한다.
