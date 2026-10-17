# SV70 PACS 연동 배포 묶음

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

공동인증서 등록·로그인은 의사 인증용이며, 판독문은 의사의 명시적 최종 승인 후 OpenBao 키로 서명합니다. 판독문 서명 결과를 검증하기 전에는 PACS 승인 상태를 변경하지 않습니다.

실제 인증 SDK, 인증기관 신뢰체인·폐지목록, 관리자 OIDC, 운영 DB·TLS·OpenBao 설정은 배포기관의 값으로 구성해야 합니다. 테스트 인증서와 가짜 인증 결과를 운영에 사용하지 마세요.
