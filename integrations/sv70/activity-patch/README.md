# PACS 계정·PC·판독 요청 기록 패치

대상: Vision-Certi에서 배포한 **SaintView PACS 3.40.6**의 인증 도우미. 설치된 파일의 SHA-256이 배포 기준과 일치해야 적용한다. 다른 버전에는 덮어쓰지 않는다.

1. 원격 PACS PC에서 ZIP을 다운로드하고 압축을 푼다.
2. PACS와 공동인증서 창을 모두 닫는다.
3. `Install.cmd`를 더블 클릭하고 Windows 관리자 권한 요청을 허용한다.
4. 완료 후 PACS를 다시 실행해 공동인증서로 로그인한다.
5. Vision-Certi 관리자 대시보드의 **PACS 공동인증서 인증 현황**에서 계정, PC, 로그인 시도·완료와 판독 요청을 확인한다. 전송은 30초마다 재시도하며 대시보드도 30초마다 갱신한다.

설치 대상은 `C:\Program Files\SaintViewPACS\certificate-auth\SaintViewCertificateAuth.exe`이다. 기존 파일은 같은 폴더에 `.backup-날짜시간`으로 보관한다. PACS를 종료한 상태에서 백업을 원래 이름으로 복사하면 복구할 수 있다. 계정 저장 파일, 인증서, 암호화 키, 연결 설정을 수정하지 않는다. 이미 패치된 경우 재설치하지 않는다.

이 패치는 사용자 모르게 실행되는 설치가 아니다. Windows의 관리자 권한 확인을 거친다. 지원되지 않는 도우미 버전이면 먼저 공식 3.40.6 설치 파일로 업데이트한다.

## 기록 의미

- 계정 ID·계정 번호, PC 이름, PC별 UUID를 전송한다. 값의 출처는 `PACS_REPORTED`이다.
- 인증서를 확인하고 계정 로그인 요청을 시작하면 `LOGIN_ATTEMPTED`, 성공 응답과 로컬 계정 연결을 마치면 `LOGIN_SUCCEEDED`, PACS 로그인 실패는 `LOGIN_FAILED`이다. 인증서 암호를 로컬에서 잘못 입력하여 서버까지 도달하지 않은 시도는 포함하지 않는다.
- 이미 로그인한 상태에서 인증서만 갱신하는 것은 로그인 완료를 추가하지 않는다.
- Approve 요청은 `APPROVAL_REQUESTED`로 보낸다. 판독문 본문과 환자 정보는 보내지 않는다. 판독문 식별자는 PACS 주소·study 번호·report 번호를 SHA-256한 값이다. 최초 보고서로 report 번호가 아직 없으면 `primary`를 사용한다. 같은 study의 최초 보고서 후 번호가 부여되면 별도 식별자로 집계될 수 있다.
- 요청 건수는 승인 성공 건수가 아니다. 일반 상태 조회는 승인 요청으로 세지 않는다.
- `certificate-activity.json`은 `%LOCALAPPDATA%\SaintViewPACS\CertificateAuth`에 저장되며 키로 무결성을 확인한다. 통신 실패 시 대기 기록과 동일한 eventId를 유지하여 서버가 중복을 제외한다. 서버 연결을 변경하면 이전 서버의 기록을 새 서버로 보내지 않는다.
- 과거에 전송하지 않은 계정·PC·승인 기록은 소급 생성하지 않는다.

## 소스 적용과 검증

기준 서버 소스에서 `git apply --check broker-activity.patch`, `git apply broker-activity.patch` 후 `activity.py`를 `certificate_broker/activity.py`로 복사한다. 기존 `certificate_broker/build-helper.ps1`로 빌드한다. `test_activity.py`를 서버 tests에 복사하여 기존 브로커 회귀 테스트와 함께 실행한다. `manifest.json`은 이번 실행 파일 및 지원하는 이전 실행 파일의 해시다. 배포 파일은 Git 대신 Firebase Hosting downloads에 보관한다.
