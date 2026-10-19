# Vision-Certi

공개 웹 화면은 **https://vision-certi.web.app**, PACS API 기본 주소는 **https://vision-certi.web.app/v1**입니다. 서버는 서울 Cloud Run·Cloud SQL·OpenBao에서 구동되어 이 PC의 서버가 중단되어도 공개 주소가 유지됩니다. [클라우드 운영 구성](infra/cloud/README.md), [외부 PACS 연결 안내](docs/PUBLIC_PACS_CONNECTION.md), [의사 PC 인증서 프로그램](docs/FIREBASE_PC_CONNECTION.md)을 참고하세요.

기존 `https://100.68.224.44:5184`는 [Tailscale 내부망 시험 주소](docs/TAILSCALE_PACS_CONNECTION.md)이며 외부 PACS에 사용하지 않습니다. 운영 관리자는 승인된 Google 계정으로 로그인하며, `egparadise@gmail.com`의 최초 관리자 로그인을 확인했습니다. 실제 의사 승인과 임상 적합성은 별도 검증이 필요합니다.

INVIZ 인증·전자서명 서버와 SV70 PACS 연동 프로젝트입니다. 의사 인증과 판독문별 승인을 검증한 뒤 OpenBao로 서명하고, PACS가 서명 증거를 확인한 후 승인 상태를 저장합니다.

## 구성

- `apps/backend`: Java/Spring Boot, PostgreSQL, Google OIDC, 인증서·CRL 검증, OpenBao Transit/PKI.
- `apps/web`: React/TypeScript 인증·관리 화면, OIDC Code+PKCE 및 문서별 승인 화면.
- `database`: PostgreSQL 마이그레이션과 SV70 MariaDB/MySQL 추가 테이블.
- [SV70 배포 묶음](integrations/sv70/README.md): 검토한 변경 파일과 원본 해시 검증 적용 도구. 원본 인수인계 전체와 비밀정보는 제외합니다.
- [설계 문서](docs/obsidian/00_프로젝트_허브.md), [검증 현황](docs/DEVELOPMENT_STATUS.md), [운영 배포](infra/production/README.md).

## 내부망 시험 접속과 PACS 연결

현재 내부 테스트 주소는 `https://100.68.224.44:5184/#/login`입니다. 인증서 등록은 `/#/certificates/register`, PACS 인증 팝업은 `/#/certificates/pacs-login`을 사용합니다.

[실행·CA 설치](docs/LAN_TEST.md), [PACS 연결 값](docs/PACS_LAN_CONNECTION.md), [로그인·판독 승인 흐름](docs/PACS_EXECUTION_FLOW.md)을 따릅니다. 실제 PACS의 웹 주소를 허용 출처로 등록하고, 연동 코드와 DB 마이그레이션을 적용해야 합니다.

PACS 최초 로그인에서 공동인증서를 등록하고, 등록·활성화 이후에도 매번 공동인증서로 로그인합니다. 관리자 사전등록·초대코드 발급 API는 기본 비활성화됩니다. 관리자 일괄 사전등록이나 초대코드 입력은 필요하지 않습니다. [최초 등록 계약](apps/backend/docs/PACS_SELF_ENROLLMENT.md) 및 [의료인 등록·보안프로그램 연결](docs/MEDICAL_CERTIFICATE_ONBOARDING.md)에 따라 V006 마이그레이션을 적용합니다. 판독문 최종 승인에 필요한 문서별 서명 확인은 별도입니다.

## 개발 검증

```powershell
cd apps/backend
.\gradlew.bat test bootJar
cd ../web
npm.cmd ci
npm.cmd test
npm.cmd run build
```

[백엔드 설명](apps/backend/README.md), [실행 계약](apps/backend/docs/PRODUCTION_RUNTIME.md), [PACS API](apps/backend/docs/SV70_PACS_INTEGRATION.md)를 참고하세요. `contracts/openapi.dev.json`은 개발 API 계약이며 최신 인증서·PACS 확장 계약은 해당 문서를 함께 확인합니다.

## 적용 범위

로컬 시험은 합성 데이터용 개발 프로파일입니다. 공개 클라우드 API는 별도 `prod` 프로파일로 배포되었고, YESKEY CA·CRL 검증과 PC 공동인증서 프로그램을 사용합니다. 운영 OIDC 계정과 관리자 권한 매핑, 모바일 인증·SMS 제공자, 실제 의료기관·의사 자격 검토는 아직 완료되지 않았습니다. 미설정 인증 제공자를 성공으로 처리하지 않습니다.

의사 인증용 외부 공동인증서와 OpenBao의 판독문 서명키는 별개입니다. 내부 PKI와 ECDSA 검증만으로 공인 타임스탬프, PAdES/CAdES 또는 의료·법적 적합성을 검증했다고 주장하지 않습니다.
