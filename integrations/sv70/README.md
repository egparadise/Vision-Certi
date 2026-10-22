# SV70 PACS 연동 배포 묶음

## SaintViewPACS 3.40.4의 인증 완료 흰 창 수정

현재 Tauri PACS에는 [3.40.5 소스 패치](patches/0001-Keep-certificate-completion-visible-after-PACS-hando.patch)를 적용한다. 기준 커밋은 `Saintview-Web-PACS` 저장소 `기능-개선` 브랜치의 `0c08163`이다. 정상 인증 결과를 받았을 때 `popup.close()`를 호출하지 않도록 수정하며 관련 테스트와 버전 변경을 포함한다. 기존 3.3.7용 파일 덮어쓰기 도구를 이 버전에 사용하지 않는다.

```powershell
git am C:\Project\Vision-Certi\integrations\sv70\patches\0001-Keep-certificate-completion-visible-after-PACS-hando.patch
```

구현·검증 범위는 [현재 인증서 확인 전용 연동 문서](../../docs/PACS_공동인증서_확인전용_연동.md)를 따른다. 아래 내용은 과거 인수인계 버전용 묶음의 설명이다.

Windows 설치 파일은 [3.40.5 다운로드](https://vision-certi.web.app/downloads/saintview-pacs-3.40.5-setup.exe)로 제공한다. [릴리스 정보](patches/saintview-pacs-3.40.5-release.json)에 크기와 SHA-256을 기록했다. 대용량 설치 파일은 Git에 포함하지 않는다. 깨끗한 체크아웃에서 Firebase를 다시 배포할 때는 이 파일을 내려받아 해시를 확인하고 `apps/web/public/downloads/`에 둔 뒤 웹을 빌드해야 기존 다운로드가 유지된다.

## 과거 3.3.7 인수인계 소스용 묶음

원본 인수인계 패키지 전체를 공개하지 않고, 이번 인증·판독문 서명 연동에서 바뀐 파일만 제공합니다. `03-secrets`, 환경별 설정, 개인키, 설치 파일, 환자 데이터는 포함하지 않습니다.

기준 소스는 2026-07-21 SaintViewPACS 3.3.7 인수인계 패키지입니다. `manifest.json`은 원본과 변경 파일의 SHA-256을 기록하며 `server.patch`, `client.patch`로 변경을 검토할 수 있습니다. 기존 파일이 수정되어 있으면 자동 덮어쓰기를 거부합니다.

```powershell
python integrations/sv70/apply_overlay.py server C:\path\to\webpacs_api
python integrations/sv70/apply_overlay.py client C:\path\to\SaintViewPACS
# 위 검증이 통과한 뒤 원본 백업과 함께 적용
python integrations/sv70/apply_overlay.py server C:\path\to\webpacs_api --apply
python integrations/sv70/apply_overlay.py client C:\path\to\SaintViewPACS --apply
```

적용 프로그램은 DB 마이그레이션이나 서버 실행을 수행하지 않습니다. 서버 소스의 `docs/VISION_CERTI_INTEGRATION.md`와 [API 계약](../../apps/backend/docs/SV70_PACS_INTEGRATION.md)을 따라 별도 검증 환경에서 먼저 점검하세요. PACS DB에는 `server/sql/2026-10-14_vision_certi_integration.sql`을 적용한 뒤 연동을 활성화합니다. 이 파일은 [검토한 마이그레이션](../../database/sv70/001_vision_certi_integration.sql)과 동일합니다.

기존 Vision-Certi 클라이언트 묶음이 적용된 PACS에는 로그인 화면과 인증서 등록 화면 두 파일의 **정확한 이전 SHA-256**을 확인한 후 갱신합니다. `apply_overlay.py client <경로>`로 먼저 검증하고 `--apply`를 사용하세요. 파일이 현장에서 수정되었으면 자동으로 덮어쓰지 않으며, 적용 시 원본을 `.vision-certi-backup-*`에 보관합니다. 등록되지 않은 `NOT_REGISTERED`·`PENDING` 계정은 PACS ID·비밀번호 확인 후 등록 화면, `ACTIVE` 계정은 공동인증서 로그인 화면으로 분기합니다. 실제 접근은 매번 인증서 소유 증명과 PACS 서버의 일회용 handoff 교환이 끝난 뒤에만 허용됩니다.

공동인증서 등록·로그인은 의사 인증용입니다. PACS 판독문 최종 승인에서는 등록된 **같은 공동인증서로 문서 ID·검사 ID·버전·SHA-256·일회용 nonce에 결합된 요청을 의사 PC에서 직접 서명**하고, INVIZ가 검증·보존한 뒤 OpenBao 의사별 키 서명도 수행합니다. 두 서명 증거가 저장되기 전에는 PACS 승인 상태를 변경하지 않습니다.

PC 파일 서명은 별도 SDK 없이 PFX/P12와 NPKI `signCert.der`·`signPri.key`를 지원합니다. 비밀번호와 개인키 파일은 의사 PC 브라우저에서만 열고 공개 인증서와 서명값만 보냅니다. HTTPS, 인증기관 신뢰체인·최신 CRL, 관리자 OIDC, 운영 DB·OpenBao는 배포기관의 실제 값으로 구성해야 합니다. 실제 발급기관 인증서의 암호화 OID와 CRL 배포 방식을 현장 시험한 뒤 사용하세요.
