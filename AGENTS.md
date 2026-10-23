# Vision-Certi 공통 작업 지침

Codex, Claude, Antigravity는 이 문서를 공통 초기화 기준으로 읽는다. 사용자의 최신 요청이 과거 설계 문서보다 우선한다.

## 현재 기준

- 운영 웹: `https://vision-certi.web.app`, `https://hahattalk-vision-certi.web.app`. 두 사이트와 firebaseapp.com 별칭을 함께 검증한다.
- API: `/v1` → 서울 Cloud Run `vision-certi-api`; 운영 데이터는 Cloud SQL PostgreSQL, 서명 제공자는 OpenBao다.
- PACS 4.40.12: 최초 공동인증 후 최대 72시간. 최초 PC의 유지 신호는 20초 간격이며 90초 끊기면 재인증한다. 자정 기준 초기화나 조회에 의한 연장을 하지 않는다.
- PACS 계정 비밀번호는 PACS가 검증한다. 인증 서버는 인증서 공개 정보·서명과 계정/PC 보고값을 처리하며 PACS PW·개인키·인증서 PW를 저장하지 않는다.
- 계약: `docs/PACS_72시간_계정인증_연동.md`, `docs/PACS_PC_S256_연동.md`. 과거의 개발용 관리자·모의 기관 정책을 운영에 복원하지 않는다.

## 작업 범위

- `apps/backend`: 인증, 계정 인증 유지, 감사 기록. `apps/web`: 인증창과 관리자 UI.
- `database`, `contracts`, `infra`: 공통 계약과 운영 구성. 설치형 PACS 소스는 별도 `Saintview-Web-PACS` 저장소다.
- 시작할 때 git 상태를 확인하고 기존 변경의 의도를 보존한다. 실행 중인 다른 작업자의 변경을 되돌리지 않는다.
- 기본 분담은 Claude 백엔드, Antigravity 프런트엔드, Codex 통합·배포다. 사용자가 통합 작업을 요청하면 필요한 영역을 함께 검토한다.
- 비밀번호·키·토큰·원본 인수인계 자료·의료정보를 커밋하지 않는다. 개발 및 테스트 성공을 실제 의사 인증 성공으로 보고하지 않는다.

## 검증과 릴리스

- 웹: `npm --prefix apps/web test`, `npm --prefix apps/web run build`.
- 백엔드: `apps/backend`에서 `.\gradlew.bat test bootJar --console=plain`. 변경 범위에 맞는 테스트를 선택할 수 있다.
- 인증서 프로그램: `.\infra\bridge-package\Build-VisionCertiBridge.ps1`; Node 버전과 설치 ZIP SHA-256을 확인한다.
- Windows PowerShell 5.1에서 한글을 사용하는 스크립트는 UTF-8 BOM으로 보관한다.
- 배포 절차는 `docs/RELEASE_RUNBOOK.md`를 따른다. 웹 빌드를 고정한 뒤 동일 파일을 두 Hosting 사이트에 배포한다.
- `node infra/cloud/Check-WebDeployment.cjs`로 실제 HTML/JS·캐시·이전 JS 복구를 확인한다. 설치 파일은 다운로드 후 SHA-256을 비교한다.
- 설치 EXE/ZIP은 배포 산출물이며 git에 추가하지 않는다. 버전·소스 커밋·해시·검증 결과를 문서에 기록한다.
- `docs/obsidian`에 보고서를 저장하고 사용자 Obsidian `Vision-Certi` 폴더에 동기화한다. 실제 실행한 검사와 남은 현장 시험을 구분한다.
- 이미 Git 저장소인 경우 다시 초기화하지 않는다. 변경 검토·검증 후 명시적으로 파일을 스테이징하고 현재 브랜치를 push한다.
