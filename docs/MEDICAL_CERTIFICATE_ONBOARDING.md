# 의료인 공동인증서 등록 — 2026-10-17

판독의사는 PACS에서 계정을 확인한 후 본인의 공동인증서를 등록한다. 관리자 초대코드는 사용하지 않는다. 등록 이후 로그인에도 공동인증서가 필요하다.

## 사용자 흐름

1. **보안프로그램 확인**: 연결 상태를 확인하고, 등록된 공급업체 설치파일이 있으면 설치 버튼을 제공한다. 설치 후 다시 확인한다.
2. **공동인증서 선택**: PC 보안프로그램이 제공한 인증서에서 본인의 인증서를 선택한다. 비밀번호 입력 및 개인키 사용은 공급업체 보안프로그램 안에서 수행한다.
3. **의료인 자격 및 동의**: 의사면허번호와 전문의 여부를 입력한다. 전문의는 자격번호와 전문과목을 추가한다. 정보를 저장한 뒤 이용 동의를 제출한다.
4. **등록 결과**: 인증서 소유자와 의료인 신원·면허·전문의 자격의 연결을 기관 담당자가 증빙과 대조한다. 검토와 동의를 마친 후 활성화한다. 번호 입력 자체로 면허가 검증되거나 판독 권한이 부여되지 않는다.

화면에서는 ‘의사면허번호’와 ‘전문의 자격번호’를 구분한다. 보건복지부도 면허와 전문의 자격을 구분하여 안내한다. [보건복지부 면허민원 안내](https://lic.mohw.go.kr/guide/guide03.do)

원문 챌린지, 바이너리 다운로드, 공개 인증서/서명 붙여넣기는 일반 화면에서 제외했다. 개발 빌드에서만 `VITE_CERTIFICATE_DIAGNOSTICS=true`로 접힌 진단 영역을 사용할 수 있다. 이 설정은 인증 검증을 완화하지 않는다.

## 보안프로그램 연결

공급업체는 미정이다. [후보 비교·연동 계획](CERTIFICATE_VENDOR_SELECTION.md)과 [기술·견적 요청서](CERTIFICATE_VENDOR_RFI.md)를 작성했으며, 평가 SDK와 라이선스 확보 후 실제 API를 연결한다.

현재 실제 공급업체 SDK와 설치파일은 제공되지 않았다. 따라서 실제 설치나 운영 공동인증서 선택이 완료된 상태가 아니다. 연결되지 않은 경우 설치 버튼을 비활성화하며 인증 성공을 생성하지 않는다.

`apps/web/.env.certificate.example`의 다음 공개 값을 배포 환경에 설정한다.

| 설정 | 값 |
|---|---|
| `VITE_CERT_INSTALLER_NAME` | 승인된 프로그램 이름 |
| `VITE_CERT_INSTALLER_URL` | 공급업체 HTTPS 설치파일 URL 또는 같은 HTTPS origin의 상대 경로 |
| `VITE_CERT_INSTALLER_SHA256` | 승인된 설치파일의 SHA-256, 64자리 16진수 |

세 값이 유효해야 다운로드 링크가 나타난다. 체크섬은 배포 메타데이터이며 브라우저가 설치파일의 서명을 검증하거나 설치를 완료했다는 의미가 아니다. 공급업체 코드서명·설치파일 검증과 계약된 배포 절차를 별도로 적용한다. 웹 환경변수에는 개인키·비밀번호·서버 비밀키를 넣지 않는다.

설치만으로 브라우저 연동이 완성되는 것은 아니다. 공급업체 SDK 로더와 실제 어댑터를 애플리케이션에 연결해야 한다. `localCertSdkAdapter.ts`의 인터페이스를 구현하여 등록하거나, 검토한 공급업체 로더가 `window.pcCertSdk`의 `isReady`, `listCerts`, `sign`을 제공해야 한다. `isReady`는 실행 상태를 실제로 확인해야 한다.

`sign`에 서버가 발급한 정확한 `dataToSignBase64`, `challengeId`, `challengeNonce`, 선택한 인증서 지문을 전달한다. 반환값은 공개 DER 인증서, 중간 체인, 서명값, 알고리즘, 인증서 지문뿐이다. 개인키/PFX/비밀번호를 웹에 전달하지 않는다. 선택 인증서와 반환 DER 지문을 대조하고 서버에서 신뢰체인·폐지 상태·서명·일회성 요청을 검증한다. 만료·다른 인증서·변경된 요청·시간 초과는 성공으로 처리하지 않는다.

## 면허·자격 API 및 저장

인증서 검증으로 받은 의사 bearer 세션을 사용한다.

```http
PUT /v1/certificate-enrollment/enrollments/{registrationId}/medical-credentials
Authorization: Bearer <doctor-session>
Content-Type: application/json
```

```json
{
  "medicalLicenseNumber": "123456",
  "specialist": true,
  "specialistQualificationNumber": "12345",
  "specialty": "영상의학과"
}
```

전문의가 아니면 `specialist=false`, 두 전문의 필드는 null로 보낸다. 면허번호는 서버의 PACS 연결 의사 정보와 일치해야 한다. 신규 PACS 등록과 갱신에서 제출은 필수다. 자신의 미심사 등록만 수정할 수 있다. 기존 JSON 기록의 누락 필드는 null로 읽으며 과거 레코드를 임의로 검증 완료 처리하지 않는다. 별도 DB migration 없이 기존 PostgreSQL 등록 payload에 저장한다.

관리자 승인 요청은 조회한 `medicalCredentialsSubmittedAt`도 전달해야 한다. 그 사이에 자격정보가 수정되면 `409 MEDICAL_CREDENTIALS_CHANGED`로 거절하며 최신 정보로 다시 검토한다. 증빙 참조는 실제 신원·면허·전문의 자격 대조 자료를 가리켜야 한다. 입력값 자동 조회를 수행하는 정부 면허 검증 API는 연결되어 있지 않다.

면허번호 불일치는 `422 MEDICAL_LICENSE_MISMATCH`, 전문의 필수항목 누락은 `422 SPECIALIST_CREDENTIALS_REQUIRED`, 미제출 동의·승인은 `409 MEDICAL_CREDENTIALS_REQUIRED`로 거절한다. 감사기록에는 자격번호 원문 대신 제출·검토 행위를 기록한다.

## 검증 범위

Java 관련 39개 테스트(실제 PostgreSQL 포함), 웹 전체 69개 테스트 및 후속 변경 관련 30개 테스트가 통과했고 웹 빌드도 성공했다. 브라우저에서 일반 화면의 수동 입력 제거, 미설정 설치버튼 비활성화, 모바일 너비를 확인했다. 로컬 Java/PostgreSQL/OpenBao/MariaDB 통합에서 시험용 CA 인증서로 면허·전문의 정보 저장→검토→활성화→인증서 로그인→판독문 서명을 확인했다. 공급업체 설치파일·실제 운영 인증서와 원격 PACS 배포는 이 검증에 포함되지 않는다.
