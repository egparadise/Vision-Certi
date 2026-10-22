# PACS 공동인증서 확인 전용 연동

이 문서는 PACS가 **자체 ID/PW 로그인**을 담당하고 Vision-Certi는 **공동인증서 소유와 상태만 확인**하는 흐름을 설명한다. Vision-Certi는 PACS ID, PW를 수신하거나 검증하지 않는다. 기존 의사 등록·면허 검증·판독문 전자서명 API와 별도의 연동 방식이다. 인증서 확인 결과는 의사면허 검증이나 특정 판독문에 대한 전자서명 자체를 뜻하지 않는다.

## 1. 첫 로그인

1. PACS 클라이언트가 의사에게 PACS ID/PW를 받는다. PACS 서버가 계정을 인증한다. 이 단계가 실패하면 인증서 확인을 시작하지 않는다.
2. PACS 클라이언트가 `https://vision-certi.web.app/#/certificates/pacs-proof?nonce=<64자리 소문자 hex>&audience=<clientId>&origin=<정확한 PACS origin>` 팝업을 연다. `VITE_PACS_ALLOWED_ORIGINS`에 이 origin이 등록되어 있어야 한다. 설치형 Tauri PACS에서 `window.opener`/정확한 origin을 제공하지 못하면 검증된 네이티브 브리지가 필요하다.
3. 팝업은 `POST /v1/pacs-certificate-proofs/challenges` (`{"audience":"<clientId>"}`)로 일회용 서명 대상을 받고, 로컬 PC의 NPKI 또는 PFX/P12 개인키로 서명한다. 인증서 비밀번호와 개인키는 PC 밖으로 전송하지 않는다.
4. 팝업은 `POST /v1/pacs-certificate-proofs/verify`에 공개 인증서, 공개 체인, 서명, challenge ID를 제출한다. Vision-Certi는 서명, 신뢰체인, 기간, CRL을 확인하고 60초짜리 `verificationCode`를 발급한다. 사전 Vision-Certi 의사 계정 등록은 필요하지 않다.
5. 팝업이 `{ "type":"VC_CERTIFICATE_PROOF_CODE", "nonce":"...", "audience":"...", "verificationCode":"..." }`를 `postMessage`로 PACS 창에 보낸다. PACS는 `event.source`, `event.origin`, `nonce`, `audience`를 모두 검사한다. 메시지 자체는 로그인 증명이 아니다.
6. **PACS 서버**가 HMAC 인증으로 `POST /v1/integrations/pacs/certificate-proofs/redeem`에 `{ "verificationCode":"..." }`를 보낸다. 성공 시 아래 JSON을 받는다. 코드는 한 번만 교환할 수 있다. PACS는 현재 ID/PW로 인증한 계정과 인증서 지문을 로컬에 연결한 후 로그인한다.

```json
{
  "verified": true,
  "authentication": "Y",
  "verificationId": "UUID",
  "audience": "vision-certi-pacs",
  "certificateFingerprintSha256": "64자리 hex",
  "subjectDn": "인증서 주체 DN",
  "issuerDn": "발급자 DN",
  "certificateNotAfter": "ISO-8601 만료 시각",
  "verifiedAt": "ISO-8601 검증 시각",
  "expiresAt": "ISO-8601 검증 연결 만료 시각"
}
```

`verified`는 서버가 반환한 boolean이다. 사용자가 만든 `인증: Y` 문자열이나 팝업의 메시지만으로 로그인시키지 않는다. 실패·만료·통신 장애의 기본값은 `verified: false`이다.

## 2. PACS PC의 로컬 기록

PACS가 관리하는 MD/JSON 기록에는 로그인 ID, 인증서 지문, `verificationId`, 검증 시각·만료 시각, 마지막 결과 등 **감사·연결 정보만** 둔다. 예:

```json
{
  "pacsLoginId": "doctor-001",
  "certificateFingerprintSha256": "64자리 hex",
  "verificationId": "UUID",
  "verified": true,
  "verifiedAt": "ISO-8601",
  "expiresAt": "ISO-8601",
  "pacsCredentialRef": "Windows Credential Manager 항목 이름"
}
```

**PACS 비밀번호 원문을 MD/JSON 파일에 기록하지 않는다.** 재로그인에 비밀번호가 필요하다면 설치형 PACS가 Windows Credential Manager 또는 사용자별 DPAPI로 보호되는 저장소에 보관하고, 로컬 기록에는 참조만 남긴다. 웹 전용 PACS는 로컬 파일이나 `localStorage`에 ID/PW를 영속 저장하지 말고 PACS 서버의 재인증 세션 방식을 사용해야 한다. 인증서 비밀번호는 어디에도 보관하지 않는다.

## 3. 다음 로그인

의사는 공동인증서를 다시 선택하고 인증서 비밀번호만 입력한다. PACS가 위 1번의 새 challenge/서명/코드 교환을 반복해 **새** `verificationId`와 지문을 받는다. PACS는 저장된 계정의 지문과 일치하는지 확인하고 보호된 자격 증명으로 PACS 서버에 ID/PW 로그인을 요청한다. 다른 지문이면 계정 연결을 자동 변경하지 않고 최초 ID/PW 로그인부터 다시 시작한다. 기존 로컬 파일의 `verified: true`만 읽고 로그인하지 않는다.

## 4. 판독문 Approve

PACS는 현재 로그인 계정과 연결된 `verificationId`를 HMAC 인증으로 `GET /v1/integrations/pacs/certificate-proofs/{verificationId}`에서 다시 조회한다. `verified: true`, 인증서 지문·계정 연결 일치, 만료 전, 최신 인증서 상태 확인이 모두 성공하면 PACS 자체 승인 권한 검사를 계속한다. 증명이 없거나 만료·폐지·통신 장애이면 PACS가 공동인증서 확인 팝업을 다시 열고 새 증명을 받는다. **MD/JSON의 `Y` 값만으로 Approve를 허용하지 않는다.** 이 확인만으로 판독문 내용의 전자서명이 생성되는 것은 아니므로 문서 전자서명이 필요하면 최종 판독문 바이트에 대한 별도 서명 흐름을 사용한다.

## 현재 설치형 PACS 적용 범위

2026-10-22에는 설치된 SaintViewPACS **3.40.4**와 그 버전의 실제 소스(`Saintview-Web-PACS`, `기능-개선` 브랜치, `0c08163`)를 함께 점검했다. 해당 버전은 `#/certificates/pacs-proof`를 호출하고, 인증 창의 `window.opener`도 유지한다. 코드 교환은 로컬 PACS 브로커가 수행한다.

**비밀번호 확인 후 흰 창이 남는 원인은 PACS의 `popup.close()`였다.** 실제 설치형 앱에서 이 호출이 웹 내용을 종료하지만 바깥 네이티브 창을 남기는 현상을 직접 재현했다. 이는 최초 페이지 로드 실패나 브로커 설정 오류와 구별된다. 브로커 설정 진단은 [설치형 PACS 설정 패치](../infra/pacs_patch/README.md)를 참고하되, 설정 복구만으로 이 완료 화면 문제를 해결할 수는 없다.

수정본 **3.40.5**는 정상 결과를 받은 뒤 메시지 리스너와 타이머만 정리하고 인증 창을 유지한다. 그 뒤 PACS 로그인에서 오류가 발생해도 완료된 인증 화면을 닫지 않는다. 웹 화면은 **“인증되었습니다.”**와 **“PACS 화면으로 돌아가기”** 버튼을 표시한다. 사용자가 창 오른쪽 위 X로 닫을 수 있다. 기존 3.40.4 클라이언트도 업데이트해야 하며 웹 배포만으로 클라이언트의 `popup.close()`를 막을 수 없다.

Vision-Certi의 책임 범위는 **PACS의 요청 수신 → 해당 PC 인증서 표시 → 비밀번호를 이용한 로컬 서명 → 서버 검증 → 요청 PACS에 검증 결과 전달 및 완료 표시**까지다. PACS 계정 로그인·로컬 계정 보관·판독 승인은 PACS가 처리한다.

회귀 검증은 `tests/browser/native-completion.cjs`로 수행한다. 진단용 네이티브 PACS를 루프백 CDP 포트 9227로 실행한 뒤 이 스크립트를 실행한다. 테스트는 임시 인증서의 암호화된 개인키를 실제로 복호화·서명하고 공개키로 검증하되, 인증 API와 브로커 응답은 격리한다. 실제 사용자 비밀번호를 읽거나 실제 PACS 로그인을 수행하지 않는다. `PACS_USE_BUILT_ASSETS=1`은 실행 파일 내장 화면, `AUTH_USE_LIVE_ASSETS=1`은 배포된 웹 정적 파일을 사용한다.
