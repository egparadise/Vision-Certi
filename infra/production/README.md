# 운영 배포

이 디렉터리는 실제 서비스 배포용 설정 예제다. `infra/local_database.py`, `infra/openbao/server_local.py`, `infra/run_backend.py`는 별도 로컬 검증 도구이며 운영 서비스 시작에 사용하지 않는다.

## 배포 순서

1. 운영 PostgreSQL에 [마이그레이션](../../database/migrations)을 별도 마이그레이션 계정으로 적용한다. Java 런타임은 `certi_rt`를 사용한다. 실행 계정은 `certi_app`의 로그인 멤버로 만들고, superuser·스키마 소유자·BYPASSRLS 권한을 부여하지 않는다. 비밀번호는 애플리케이션이 읽을 수 있는 제한된 파일에 저장한다.
2. OpenBao를 영속 Raft 저장소와 TLS로 배포하고 초기화·복구 키를 애플리케이션과 분리해 보관한다. Transit의 비반출 ECDSA P-256 키와 내부 PKI 역할을 구성한다. 키 생성·인증서 발급 토큰과 서명 토큰은 서로 달라야 하며, 앱에 root 토큰을 제공하지 않는다. [정책 예제](../openbao/provisioner.hcl)는 전용 Transit/PKI 마운트에 맞게 경로를 조정한다.
3. [서버 운영 설정 문서](../../apps/backend/docs/PRODUCTION_RUNTIME.md)에 따라 DB, 관리자 OIDC issuer/audience/JWKS, 관리자 subject 매핑, OpenBao TLS·토큰 파일, 공동인증기관 CA·CRL을 구성한다. 환경 파일에는 비밀값 대신 비밀파일 경로를 둔다. 미설정 기능을 모의 성공으로 대체하지 않는다.
4. `apps/backend`에서 `gradlew bootJar`, `apps/web`에서 `npm ci`, `npm run build`로 산출물을 만든다. 프런트의 허용 PACS origin과 OIDC callback URI를 실제 배포 도메인으로 제한한다. 설정의 정확한 변수 이름은 앱 문서를 따른다.
5. jar를 `/opt/vision-certi/app.jar`, 웹 산출물을 `/var/www/vision-certi`에 배포한다. 전용 OS 계정을 만들고 [systemd 예제](vision-certi.service)를 설치한다. [Nginx 예제](nginx.conf.example)에서 도메인·TLS 경로를 바꾼다. API의 8080 포트는 외부에 공개하지 않는다.
6. `/v1/runtime/capabilities`의 실제 profile·저장소·인증·서명 제공자 상태를 확인한다. 관리자 OIDC 로그인 → 의사 사전등록 → 실제 PC SDK 인증·동의 → 계정 연결 → 판독문 서명 → PACS 승인 완료를 기관 검증 환경에서 확인한다.

## SV70 적용

[연동 묶음](../../integrations/sv70)을 원본 해시 검증 후 적용한다. 기존 PACS 주 DB를 백업하고 [추가 테이블 SQL](../../database/sv70/001_vision_certi_integration.sql)을 먼저 적용한다. `VISION_CERTI_INTEGRATION_ENABLED=true`, 고정 HTTPS API 주소, 기관 ID, 서비스 계정 ID, HMAC 비밀파일, 허용 관리자 사용자 번호를 설정한다. 같은 HMAC 값을 INVIZ 서버의 해당 PACS 클라이언트에 설정한다.

관리자는 PACS 사용자와 INVIZ Doctor ID를 증빙에 따라 연결한다. 연동을 켠 뒤 설정 오류가 발생해도 기존 A/RA 직접 저장으로 우회하지 않는다. 사용자에게 재인증·저장 재시도를 안내하고, 서명 완료 후 PACS 반영 실패는 관리자 reconcile로 복구한다.

## 운영 유지

- PostgreSQL 백업과 OpenBao Raft snapshot을 각각 보관하고 함께 복구 시험한다. DB만 복구하거나 서명키만 복구하면 전체 서명 이력을 복원한 것이 아니다.
- 공동인증기관 CRL을 만료 전에 갱신하고, 서버가 새 CRL을 읽는 절차를 확인한다. 만료·unknown·신뢰 불가 상태는 인증을 중단한다. 현재 지원 범위는 전체 CRL이며 OCSP·부분/델타 CRL을 지원한다고 표시하지 않는다.
- OpenBao 서비스 토큰의 수명·갱신과 폐기를 운영 체계로 관리한다. 서명키 버전과 인증서 유효기간, 기관 이동·의사 권한 정지를 함께 관리한다. 복구키와 서비스 토큰을 로그나 Git에 저장하지 않는다.
- 관리자 계정 폐지, 의사 인증서 갱신·해제, 중복 승인, 재배정, 통신 중단, 서버 재시작, 사본 변조를 배포 전후에 검증한다.

실제 모바일 인증·SMS 공급업체와 PC 공동인증서 SDK의 계약·명세는 별도로 필요하다. 공급업체가 정해지지 않은 연결부는 사용 불가로 유지한다. 내부 PKI의 원시 ECDSA 서명은 외부 공동인증서의 판독문 직접서명, PAdES/CAdES 또는 공인 시점확인 토큰을 뜻하지 않는다.

이 예제들은 이 작업에서 원격 운영 서버에 설치되지 않았다. 적용할 기관의 도메인·계정·증빙·인증기관 설정에 맞춰 배포해야 한다.
