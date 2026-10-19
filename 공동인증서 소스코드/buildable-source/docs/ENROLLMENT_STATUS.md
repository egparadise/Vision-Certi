# 개인·의료인 가입 및 휴대폰 인증

2026-10-13 추가 구현. 가입 화면: http://127.0.0.1:5183/#/signup

## 사용 흐름

1. 개인 또는 의료인(판독의) 유형 선택.
2. 전자인증 가입, 전자서명 가입, 가입 정보 수집·이용에 각각 동의. 동의문은 개발용 초안이며 운영 약관이 아님.
3. ID, 비밀번호·확인, 이름, 휴대폰 번호 입력. 이메일은 선택. 의료인은 소속 의료기관·의사 면허번호·진료과 추가 입력.
4. 인증번호 요청 → 6자리 확인 → 가입 신청. 번호 변경 시 인증 상태 초기화.
5. 개인은 개발 계정 등록 완료, 의료인은 자격 확인 대기. 가입은 로그인이나 문서 서명 권한을 부여하지 않음.

## 현재 인증 범위

**현재는 MOCK_SMS이며 실제 문자 발송·휴대폰 소유 확인·PASS 본인확인을 수행하지 않습니다.** 개발 시험용 인증번호를 화면에 표시합니다. 실제 개인정보를 입력하지 마세요. 실제 발송을 위해서는 공급자 선정, 발신번호 및 API 자격 설정, SmsSender 실제 구현과 운영 검증이 필요합니다. 비밀키는 브라우저 코드나 채팅에 저장하지 않습니다.

API에는 3분 코드 만료, 60초 재발송 간격, 번호당 시간당 5회·접속 출처당 30회 제한, 5회 오입력 차단을 적용했습니다. 재발송은 기존 코드와 미사용 인증 토큰을 무효화합니다. 10분 유효한 인증 토큰은 확인한 번호에 묶이고 가입 시 한 번만 사용됩니다. 실제 발송·본인확인의 성공 여부는 이번 시험으로 검증하지 않았습니다.

ID는 대소문자 구분 없이 중복 검사합니다. 비밀번호는 PBKDF2-HMAC-SHA256(600,000회, 무작위 salt)으로 저장하고 평문을 반환하거나 로그에 남기지 않습니다. 구현 선택의 참고: [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html). 전체 가입 정보는 현재 메모리 저장이며 서버 재시작 시 삭제됩니다.

## 검증과 담당

- Claude: 가입·휴대폰 인증 백엔드, 신규 테스트 27개. 기존 25개 포함 52개 통과.
- Codex: 가입 화면, 서비스 동의, 개인/의료인 분기, API 연결, 계약 갱신, 실제 Chrome 검사.
- 브라우저 검사 7개 통과: 필수 동의·인증 통제, 번호 변경 시 재인증, 비밀번호 확인 불일치, 개인 가입 완료 및 비밀번호 입력 필드 제거, 의료인 필드·모바일 표시, 의료인 검토 대기, 브라우저 예외 없음.
- 프런트엔드 테스트 20개 및 TypeScript/Vite 빌드 성공. 운영 기본 빌드는 가입 화면도 차단.
- OpenAPI 17개 경로로 갱신. 문서·계약 정적 검사 2개 통과.

소스: apps/web/src/components/RegistrationView.tsx, apps/web/src/components/registration.css, apps/backend/src/main/java/com/inviz/visioncerti/enrollment/.
API 상세: apps/backend/docs/ENROLLMENT.md. 테스트: tests/browser/enrollment.mjs. 결과: work/signup-browser-result.json 및 work/signup-*.png.

재현: 실행 중인 개발 서버(8080)와 Vite(5173)를 유지한 상태에서 tests/browser 폴더에서 `node enrollment.mjs`를 실행합니다. 합성 정보만 사용합니다.