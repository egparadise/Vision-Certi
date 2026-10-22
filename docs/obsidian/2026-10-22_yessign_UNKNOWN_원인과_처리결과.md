# PACS 3.40.10 yessign UNKNOWN 오류 — 원인과 처리 결과

## 운영 증거

- 사용자 제보: 다른 판독자의 PC, PACS 3.40.10, 발급기관 yessign. 화면 오류는 `인증서 폐지 여부를 확인할 수 없습니다 (UNKNOWN)`.
- 운영 DB에서 2026-10-22 22:27:15~22:30:44 KST에 동일 인증서 지문의 `CERTIFICATE_REVOCATION_UNAVAILABLE` 4건 확인.
- 같은 시간대 `CHALLENGE_ALREADY_USED` 2건 확인.
- 운영 Secret Manager의 공개 CRL은 서명 검증에 성공했고 thisUpdate=2026-10-22 20:00:19 KST, nextUpdate=2026-10-23 20:00:19 KST였다. 단순 CRL 만료 문제로 판단하지 않았다.
- 그 CRL의 IDP와 갱신 스크립트는 `ou=dp6p47981,ou=AccreditedCA,o=yessign,c=kr` 한 지점으로 고정되어 있었다. 다른 지점의 인증서에 대한 조회 경로가 없었다.
- 기존 실패 감사에는 지문과 오류만 남아 해당 판독자의 원래 CRL DP 전체 주소는 확보하지 못했다. 다른 지점 인증서에 대한 UNKNOWN은 격리 시험 인증서로 재현했다. 배포 후 문제가 발생했던 PC의 PACS 3.40.10 로그인이 성공했다고 사용자가 확인했다.

## 원인과 처리 결과

| 문제 | 확인한 원인/근거 | 적용한 처리 | 결과와 확인 범위 |
| --- | --- | --- | --- |
| 다른 판독자 인증서의 UNKNOWN | yessign CRL 갱신이 특정 분배 지점 하나로 고정됨. 유효한 서명·신뢰 경로라도 해당 인증서에 맞는 목록이 없으면 UNKNOWN | 신뢰 경로 확인 후 인증서의 허용된 yessign 분배 지점에서 CRL 조회. 발급기관 서명·범위·유효기간 검증 | 다른 지점 시험 인증서의 UNKNOWN → GOOD 및 실제 yessign LDAP 조회·서명 검증 통과. 배포 후 원격 PC 로그인 성공을 사용자가 확인 |
| 올바른 목록을 추가해도 UNKNOWN 가능 | 같은 발급기관의 다른 지점 CRL도 ‘지원하지 않는 범위’로 처리해 전체 검증을 막음 | 명확히 다른 이름의 분배 지점은 해당 인증서의 증거에서 제외. 해당 범위의 해석 불가 목록은 계속 거절 | 다른 지점+맞는 목록 조합은 GOOD, 폐지된 인증서는 REVOKED 유지 |
| 재시도 시 이미 사용한 요청 오류 | 실패한 서명 검증 시도도 challenge를 소비하지만 웹은 같은 challenge를 유지 | 서버 오류 후 새 challenge 준비. 임시 재시도 가능 오류는 PACS helper에 종료 오류로 보내지 않음 | 동일 팝업에서 새 요청으로 재시도하는 웹 테스트 통과 |
| 원인 추적 정보 부족 | 기존 실패 기록에는 폐지 상태/분배 지점이 없음 | 신뢰 경로 확인 후 발생한 거절에 발급기관, 폐지 조회 상태, 허용된 CRL 위치 기록. 관리자 항목 이름 추가 | 다음 실패부터 DB와 관리자 상세 화면에서 확인 가능 |

인증서 비밀번호·개인키·PACS 비밀번호는 읽거나 수집하지 않았다. UNKNOWN을 GOOD으로 바꾸거나 폐지 검사를 생략한 수정이 아니다. CRL 범위와 서명 확인 원칙은 [RFC 5280 CRL 처리](https://www.rfc-editor.org/rfc/rfc5280.html#section-6.3.3)를 따른다.

## 구현 및 검증

- `YessignCrlResolver`: 고정 호스트 `ds.yessign.or.kr`, LDAP 389, 제한 DN/속성만 허용. 임의 URL·포트·LDAP 검색·referral·객체 조회 금지. 연결/읽기 제한 3초/4초, CRL 최대 2 MiB, 캐시 최대 64개. 양성 캐시 최대 5분/nextUpdate, 실패 20초. 상태 조회 시에도 CRL 유효성 재검사.
- `CertificateValidator`: 경로 검증을 통과한 인증서에서만 선택 조회 수행. 다른 지점 CRL 제외와 기존 delta/indirect/해석 불가 critical 확장 거절 유지.
- 서버 테스트 52개 통과: 범위 9, 신규 조회 7(실제 공급자 조회 포함), 인증 서비스 11, HTTP 12, PostgreSQL HTTP 12, 공개 경로 1.
- 웹 테스트 11개 통과: 인증 팝업 8, 기록 화면 3. 재시도 메시지 변경 후 팝업 8개 재확인.
- 원래 운영 DB 데이터는 조회만 수행. 시험 인증서·가상 로그인·가상 판독 자료를 운영 DB에 넣지 않았다.
- 기존 3.40.10 S256/HMAC 연동과 계정·PC·판독 요청 집계를 유지했다.

## 배포 및 사용자 확인

- API: `vision-certi-api-00016-mt9`, deploy ID `2026-10-22-crl-partition`.
- 이미지: `sha256:c78d3f4c8ecf06af259c7a0f003b4047ed106a8f14350d96e44ea0e0fe122db3`.
- 웹 번들: `index-CzcoxZCK.js`.
- `vision-certi`·`hahattalk-vision-certi` 두 Hosting 사이트 및 기존 미리보기 채널 배포 완료.
- 2026-10-22 22:54 KST: 두 사이트의 web.app/firebaseapp.com 네 호스트에서 HTML/JS 빌드 일치, HTTP 200, no-store, 이전 JS 주소 복구 확인. S256 capabilities 정상, health 모두 UP.
- Cloud Run 실제 설정에서 `VISIONCERTI_CERT_YESSIGN_CRL_LOOKUP_ENABLED=true` 확인. 기존 DB/키/기관 설정 유지.
- 공개 로그인 화면과 잘못된 직접 PACS URL의 오류 안내, 누락 JS 복구를 브라우저로 확인.

### 최종 확인: 해결 완료

- 사용자가 문제가 발생했던 원격 PC에서 PACS 3.40.10 공동인증서 **로그인 성공**을 확인했다.
- 배포 후 운영 DB 감사 기록에서도 2026-10-22 23:03:50 KST에 `PACS_CERTIFICATE_PROOF_VERIFIED` 1건, 23:03:51 KST에 `PACS_CERTIFICATE_PROOF_REDEEMED` 1건을 확인했다. 인증 검증과 PACS의 인증 결과 수령이 각각 기록되었다.
- 23:04:56 KST까지 `PACS_CERTIFICATE_PROOF_CHECKED` 10건이 기록되어 후속 상태 조회도 확인했다. 조회 시점까지 배포 후 신규 `PACS_CERTIFICATE_PROOF_DENIED` 기록은 없었다.
- 이 수정으로 PACS 재설치는 필요하지 않으며, 추가 코드 변경이나 재배포 없이 사용자 확인으로 장애 처리를 종료했다.
