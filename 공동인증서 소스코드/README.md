# INVIZ 공동인증서 로그인 소스코드·프로그램 사본

생성 시각: 2026-10-19T08:09:59.592008+00:00 · 기준 Git 커밋: `571245f384914abbb35764792b1bd6014b0d2e36`

## 폴더 구성

- `feature-source/`: 공동인증서 등록·로그인·PACS handoff·판독 승인에 관련된 파일을 원본 경로대로 뽑은 열람용 코드. 이 부분집합만으로는 단독 빌드되지 않습니다.
- `buildable-source/`: 공통 API·DB·설정 의존성까지 포함한 웹, 백엔드, SV70 연동 패키지의 빌드 가능한 전체 소스 사본. `node_modules`, `.gradle`, 실제 환경 설정과 인증서 개인키는 포함하지 않습니다.
- `program/web/dist/`: 빌드된 웹 정적 파일. HTTPS 웹 서버에서 제공하고 `/v1`을 인증 백엔드로 프록시해야 합니다.
- `program/backend/`: 빌드된 Spring Boot 실행 JAR. Java 21 이상과 PostgreSQL·신뢰 CA/CRL·OpenBao 등의 환경 구성이 필요합니다. JAR만 실행해도 운영 인증이 자동 설정되지는 않습니다.
- `program/pc-bridge/`: 판독의사 PC에서 쓰는 인증서 탐색·로컬 서명 프로그램과 런타임 `korea-forge` 의존성. Node.js를 설치한 PC에서 실행합니다.
- `COPY_MANIFEST.json`: 복사한 각 파일의 원본 상대 경로와 SHA-256.

## 개발·실행

```powershell
cd '.\buildable-source\apps\web'
npm ci
npm run build
cd '..\backend'
.\gradlew.bat bootJar
```

의사 PC 로컬 프로그램은 **이 묶음의 최상위 폴더**에서 다음처럼 실행합니다. `<인증서버주소>`는 실제 HTTPS Origin으로 바꾸세요.

```powershell
node '.\program\pc-bridge\server.mjs' --origin 'https://<인증서버주소>'
```

PACS 적용 파일은 `buildable-source/integrations/sv70/`에 있습니다. 원본 PACS 프로젝트와 해시를 대조한 뒤 해당 폴더의 `apply_overlay.py` 및 README 절차에 따라 적용합니다. 웹·API 계약은 `buildable-source/apps/backend/docs/SV70_PACS_INTEGRATION.md`를 참조하세요.

이 사본에는 환자 데이터, 실제 공동인증서 파일, 개인키, 비밀번호, 토큰, 운영용 `.env`, `work/` 런타임 상태가 없습니다. `infra/Start-VisionCerti.ps1`은 복사 당시 작업 트리의 수정본이며 기존 PC 주소와 로컬 런타임 경로가 들어 있으므로 다른 PC에서 그대로 실행하지 마세요.

## 복사본 검증

최상위 폴더에서 `python .\VERIFY.py`로 688개 복사 파일의 SHA-256을 다시 확인할 수 있습니다. PC 로컬 프로그램은 `program/pc-bridge`에서 `node --test certificates.node.mjs`로 시험할 수 있습니다. 복사 직후 두 검사가 통과했습니다.

## Tailscale server address

The copied source now defaults to `https://100.68.224.44:5184` for this PC. See `buildable-source/docs/TAILSCALE_PACS_CONNECTION.md`. The runnable stack still requires this PC's local `work/` runtime and public test CA.
