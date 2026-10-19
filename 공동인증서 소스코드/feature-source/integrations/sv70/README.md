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

공동인증서 등록·로그인은 의사 인증용입니다. PACS 판독문 최종 승인에서는 등록된 **같은 공동인증서로 문서 ID·검사 ID·버전·SHA-256·일회용 nonce에 결합된 요청을 의사 PC에서 직접 서명**하고, INVIZ가 검증·보존한 뒤 OpenBao 의사별 키 서명도 수행합니다. 두 서명 증거가 저장되기 전에는 PACS 승인 상태를 변경하지 않습니다.

PC 파일 서명은 별도 SDK 없이 PFX/P12와 NPKI `signCert.der`·`signPri.key`를 지원합니다. 비밀번호와 개인키 파일은 의사 PC 브라우저에서만 열고 공개 인증서와 서명값만 보냅니다. HTTPS, 인증기관 신뢰체인·최신 CRL, 관리자 OIDC, 운영 DB·OpenBao는 배포기관의 실제 값으로 구성해야 합니다. 실제 발급기관 인증서의 암호화 OID와 CRL 배포 방식을 현장 시험한 뒤 사용하세요.
