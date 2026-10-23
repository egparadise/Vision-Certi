# 통합 초기화·검증·배포

## 세 도구의 초기화

공통 기준은 루트 `AGENTS.md`다. Codex는 이 파일을 읽고, Claude는 `CLAUDE.md`의 `@AGENTS.md` 가져오기를 사용한다. Antigravity에는 `AGENTS.md`와 `GEMINI.md`를 제공한다. 파일을 만들었다는 사실과 외부 도구의 세션을 실제 실행했다는 사실을 구분해서 보고한다.

형식 근거: [OpenAI AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md), [Claude 프로젝트 지침](https://code.claude.com/docs/en/memory), [Antigravity 규칙](https://antigravity.google/docs/rules/).

## 릴리스 순서

1. `git status --short --branch`와 diff를 확인한다. 기존 저장소의 이력을 유지하며 변경된 소스와 관련 테스트·문서를 검토한다.
2. 변경 영역의 테스트를 수행한다. 웹은 타입 검사와 운영 빌드를 수행한다. 백엔드가 변경되면 해당 회귀 테스트와 `bootJar`를 수행한다. 이미 검증한 같은 커밋의 설치 파일은 해시와 버전을 확인해서 재사용할 수 있다.
3. PC 인증서 프로그램을 변경했으면 `infra/bridge-package/Build-VisionCertiBridge.ps1`로 ZIP을 만든다. `.env.production`의 공개 설치 해시가 일치해야 한다. 한글 PowerShell 스크립트의 BOM과 파싱 결과를 확인한다.
4. PACS 설치 파일을 `apps/web/public/downloads/saintview-pacs-<version>-setup.exe`로 복사한다. 소스 커밋·파일 크기·SHA-256을 `pacs-release.json`에 기록하고 다운로드 페이지를 갱신한다. EXE는 git에서 제외한다.
5. 웹 테스트·빌드 후 `dist`를 `work` 아래 릴리스 폴더로 복사해 고정한다. 별도 Firebase 설정의 `public`을 고정 폴더로 지정하여 동일 빌드를 두 사이트에 배포한다. API 변경이 없으면 검증된 Cloud Run 리비전을 유지한다.
6. 두 Hosting 사이트와 두 firebaseapp.com 별칭에 대해 `VISION_CERTI_RELEASE_DIR`을 지정하고 `node infra/cloud/Check-WebDeployment.cjs`를 실행한다. 이전 미리보기 채널이 사용 중이면 live 버전을 clone한다.
7. 다운로드 URL의 HTTP 상태, attachment 헤더, 본문 SHA-256을 로컬 원본과 비교한다. 브라우저에서 인증창·완료/취소 안내·설치 페이지를 확인한다. 모의 응답을 사용한 검사는 그렇게 기록한다.
8. 검토한 파일만 commit·push한다. Obsidian 보고서에는 커밋, 배포 대상, 검사 결과, 다운로드 주소, 해시, 남은 현장 시험을 남긴다.

## 운영 대상

- Firebase 프로젝트: `hahattalk`.
- Hosting 타깃: `vision-certi`, `vision-certi-legacy`(사이트 `hahattalk-vision-certi`).
- API: `vision-certi-api`, `asia-northeast3`. 자세한 구성은 [운영 문서](../infra/cloud/README.md).
- 설치 배포 폴더: `C:\Users\egpar\OneDrive - Inviz\4.Developement\3. Development\10. PACS\Web PACS\<version>`.
- Obsidian: `C:\Users\egpar\OneDrive - Inviz\15.Vibe Cording\Obsidian\Vision-Certi`.

Firebase 인증 토큰·PACS HMAC·운영 DB/서명 키는 설정 문서, 배포 manifest, 로그에 넣지 않는다. 설치 파일을 게시하는 것과 대상 PC에 실제 설치하는 것은 별개이며, 현장 두 PC 인증/종료 시험은 따로 기록한다.
