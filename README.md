# Vision-Certi

INVIZ 인증·전자서명 서버와 SV70 PACS 연동 프로젝트입니다. 의사 인증과 판독문별 승인을 검증한 뒤 OpenBao로 서명하고, PACS가 서명 증거를 확인한 후 승인 상태를 저장합니다.

## 구성

- `apps/backend`: Java/Spring Boot, PostgreSQL, Google OIDC, 인증서·CRL 검증, OpenBao Transit/PKI.
- `apps/web`: React/TypeScript 인증·관리 화면, OIDC Code+PKCE 및 문서별 승인 화면.
- `database`: PostgreSQL 마이그레이션과 SV70 MariaDB/MySQL 추가 테이블.
- [SV70 배포 묶음](integrations/sv70/README.md): 검토한 변경 파일과 원본 해시 검증 적용 도구. 원본 인수인계 전체와 비밀정보는 제외합니다.
- [설계 문서](docs/obsidian/00_프로젝트_허브.md), [검증 현황](docs/DEVELOPMENT_STATUS.md), [운영 배포](infra/production/README.md).

## 내부망 접속과 PACS 연결

현재 내부 테스트 주소는 `https://192.168.45.160:5174/#/login`입니다. 인증서 등록은 `/#/certificates/register`, PACS 인증 팝업은 `/#/certificates/pacs-login`을 사용합니다.

[실행·CA 설치](docs/LAN_TEST.md), [PACS 연결 값](docs/PACS_LAN_CONNECTION.md), [로그인·판독 승인 흐름](docs/PACS_EXECUTION_FLOW.md)을 따릅니다. 실제 PACS의 웹 주소를 허용 출처로 등록하고, 연동 코드와 DB 마이그레이션을 적용해야 합니다.

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

현재 실행 환경은 합성 데이터용 개발 프로파일입니다. 실제 PC 공동인증서 SDK, 모바일 인증·SMS 제공자, 운영 OIDC 계정, 기관별 신뢰 CA·폐지 검증 및 운영 인프라 설정은 배포기관과 연결해야 합니다. 미설정 인증 제공자를 성공으로 처리하지 않습니다.

의사 인증용 외부 공동인증서와 OpenBao의 판독문 서명키는 별개입니다. 내부 PKI와 ECDSA 검증만으로 공인 타임스탬프, PAdES/CAdES 또는 의료·법적 적합성을 검증했다고 주장하지 않습니다.
