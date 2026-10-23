# Vision-Certi 클라우드 운영 구성

2026-10-23: PACS 4.40.12용 `VC-ACCOUNT-36H-V1`을 운영 API에 반영했다. 최초 인증 후 최대 72시간, 최초 PC의 20초 유지 신호와 90초 연결 끊김 판정을 적용한다. 두 Hosting 사이트가 같은 API를 사용하며 기존 S256/HMAC 2시간 규격도 유지한다. [계정 인증 유지 규격](../../docs/PACS_72시간_계정인증_연동.md)을 따른다.

## 두 Hosting 사이트 배포 및 확인

운영 대상은 `vision-certi`와 `hahattalk-vision-certi` 두 곳이다. 별칭인 각 `firebaseapp.com` 도메인도 같은 Hosting 버전을 제공한다. 한 곳만 배포하면 다른 곳에는 이전 화면이 남는다. 설치 파일을 `apps/web/public/downloads/`에 준비하고 웹 빌드 후 두 타깃을 함께 배포한다.

```powershell
npm --prefix apps/web run build
firebase deploy --only hosting:vision-certi,hosting:vision-certi-legacy --project hahattalk --non-interactive
node infra/cloud/Check-WebDeployment.cjs
```

검사 프로그램은 네 운영 도메인의 HTML·자바스크립트가 로컬 빌드와 일치하는지, `no-store` 헤더와 이전 JS 주소의 최신 파일 반환을 확인하며 불일치 시 실패한다. 기존 미리보기 채널까지 갱신할 때는 `firebase hosting:clone vision-certi:live hahattalk-vision-certi:vision-certi-preview --project hahattalk --non-interactive`를 실행한다.

PACS SP10은 서버 공용 HMAC 키 없이 `VC-PC-PROOF-S256-V1` PC 교환 API를 사용한다. Client ID `vision-certi-pacs`는 공개 식별자이며 매 요청의 verifier와 인증서 서명을 함께 검증한다. [PC 연동 규격](../../docs/PACS_PC_S256_연동.md)을 따른다. 클라우드의 `vision-certi-pacs-hmac-secret`은 기존 서버 간 연동용으로 `/run/secrets/pacs-hmac/value`에서만 읽으며, PC에 발급·배포하지 않는다. 이전 HMAC 전용 helper와 SP10 helper의 설정을 혼용하지 않는다.

2026-10-19 기준 `hahattalk` 프로젝트에 Vision-Certi 전용 운영 자원을 서울 리전으로 구성했다. 공개 화면과 API 주소는 **https://vision-certi.web.app** 이다. PACS에는 `https://vision-certi.web.app/v1`을 설정한다. Firebase Hosting의 `/v1/**` 요청은 서울 Cloud Run `vision-certi-api`로 전달된다. Cloud Run은 이 PC의 서버를 경유하지 않으므로 PC가 꺼져도 API 주소가 유지된다.

## 실제 자원

| 용도 | 자원 |
| --- | --- |
| 웹 | Firebase Hosting 전용 사이트 `vision-certi` (`vision-certi.web.app`), 기존 `hahattalk-vision-certi`는 이전 주소 |
| API | Cloud Run `vision-certi-api`, 서울, 최소 인스턴스 1개 |
| 데이터 | Cloud SQL PostgreSQL 17 `vision-certi-postgres-seoul`, DB `vision_certi`, 스키마 V010, 일별 백업 및 PITR 7일 |
| 서명 키 | Compute Engine `vision-certi-openbao-1`의 OpenBao Transit/Private PKI, 전용 VPC 내부 주소 `10.71.0.10:8200` |
| 인증서 신뢰 | YESKEY 공개 `yessignCA Class 3`와 서명 CRL. 기존 CRL은 VM에서 매시간 갱신하며, 다른 yessign 분배 지점은 검증 시 제한된 LDAP 조회로 확인 |
| 비밀 | Secret Manager의 `vision-certi-*` 비밀들. Cloud Run 서비스 계정은 필요한 비밀에만 접근 |
| 인증서버 백업 | OpenBao VM 디스크의 서울 일별 스냅샷, 7일 보존 |

기존 `hako-postgres`와 다른 프로젝트 자원은 이 구성에 사용하지 않는다. Cloud SQL은 전용 DB 계정 `vc_runtime`으로 접속한다. Cloud Run 서비스 계정의 Cloud SQL 접근 권한은 Vision-Certi 인스턴스로 제한했다. OpenBao의 TCP 8200은 전용 서브넷에서만 접근할 수 있고, 초기 구성에 사용한 외부 SSH/8200 방화벽 규칙과 임시 SSH 키는 제거했다. VM 외부 IP는 발급기관 LDAP CRL 수신용 출구로 남아 있다.

현재 배포 설정은 [run-service.json](run-service.json)에 비밀값 없이 기록했다. 실제 DB 비밀번호, PACS HMAC 키, OpenBao 토큰, TLS 키와 복구키는 저장소나 브라우저에 넣지 않는다. Cloud Run 이미지는 서울 Artifact Registry `vision-certi/api`에 저장된다.

## 의사 PC와 PACS의 역할

PACS와 웹 화면은 항상 공개 클라우드 API를 사용한다. 의사 PC의 인증서 프로그램은 **그 PC의 인증서 탐색·비밀번호 입력·개인키 서명**만 수행한다. 현재 PC의 로컬 프로그램은 다음 명령으로 공개 사이트 origin을 허용하도록 실행했다. 다른 판독의사 PC에도 같은 프로그램을 설치·실행해야 한다.

요청된 **PC 인증 서버 우선·장애 시 클라우드 전환은 아직 활성화되지 않았다**. 현재 PC의 `127.0.0.1:18082`는 별도 개발 DB를 쓰므로 운영 PACS의 우선 서버로 연결하면 계정·서명 증적이 분리된다. PC 운영 서버를 구성하려면 운영 Cloud SQL과 OpenBao에 인증된 전용 연결을 마련하고, 동일 DB·키·재전송 방지 상태를 공유한 뒤, 외부 PACS가 도달할 수 있는 PC HTTPS 주소와 무중단 전환 규칙을 검증해야 한다. 그 전까지 PACS에는 공개 클라우드 주소만 사용한다.

```powershell
.\infra\start_local_certificate_bridge.ps1 -Origin 'https://vision-certi.web.app'
```

인증서 개인키와 비밀번호는 의사 PC 밖으로 보내지 않는다. 공개 클라우드에는 인증서 공개 정보, 일회용 challenge의 서명, 계정·판독 승인 기록만 저장한다. PACS 서버는 `vision-certi-pacs` 클라이언트 ID와 Secret Manager의 `vision-certi-pacs-hmac-secret` 값을 안전하게 전달받아 HMAC 요청을 생성한다. 현재 허용 기관 ID는 `inviz`이며, 실제 의료기관을 등록할 때 기관별 범위와 관리자 배정을 검토·추가해야 한다. [PACS 연동 문서](../../docs/PUBLIC_PACS_CONNECTION.md)를 따른다.

## 검증 결과와 운영 경계

2026-10-22 다른 판독자의 yessign 인증서가 UNKNOWN으로 거절되는 문제에 대해 분배 지점별 CRL 조회를 추가했다. `VISIONCERTI_CERT_YESSIGN_CRL_LOOKUP_ENABLED=true`일 때 신뢰 경로 확인 후 `ds.yessign.or.kr:389`의 허용된 CRL DN만 조회한다. 다른 호스트, 포트, 자격증명, 검색 필터와 LDAP referral은 허용하지 않는다. 서명·발급기관·분배 지점·시각을 검증한 CRL만 최대 5분(또는 nextUpdate까지) 캐시하며, 조회 실패는 20초간 재호출을 제한한다. 분배 지점이 다른 CRL을 이 인증서의 증거로 사용하지 않는다. 상세 원인/검증은 [장애 처리 보고서](../../docs/obsidian/2026-10-22_yessign_UNKNOWN_원인과_처리결과.md)를 참고한다. 상태 화면의 기존 CRL FRESH는 등록 파일의 신선도를 뜻하며 모든 인증서의 폐지 조회 성공을 보증하지 않는다.

공개 `/v1/runtime/capabilities`가 HTTP 200, `prod`, PostgreSQL `READY`/V010, OpenBao `OPENBAO_TRANSIT`, PC 인증서 CRL `FRESH`를 반환했다. 공개 `POST /v1/pacs-certificate-proofs/challenges`는 HTTP 201로 PACS 확인용 서명 대상을 반환했고, HMAC 없는 `POST /v1/integrations/pacs/certificate-proofs/redeem`은 HTTP 401로 거부됐다. `/v1` 응답에는 `Cache-Control: private, no-store, max-age=0`가 적용된다. 새 확인 전용 흐름은 [PACS 연동 문서](../../docs/PACS_공동인증서_확인전용_연동.md)에 설명한다. 운영 기능 표시는 여전히 `clinicalUseAllowed: false`이며 임상 사용 적합성 승인을 뜻하지 않는다.

Firebase Google 로그인을 활성화하고 `egparadise@gmail.com`의 최초 관리자 등록을 설정했다. 운영 DB에 관리자가 없을 때 이 계정의 검증된 Google 로그인으로 한 번만 관리자 주체를 만든다. 2026-10-19 새 주소에서 해당 계정의 Google 로그인과 `ADMIN` 대시보드 표시를 확인했다. 브라우저의 기존 개발용 관리자 ID/PW와 모의 SMS 가입은 이 `prod` API에서 허용되지 않는다. 개발 DB의 기존 의사·로그인 상태는 운영 DB로 자동 이전하지 않았다. 실제 의사와 의료기관 데이터는 검증 후 별도로 등록해야 한다.

서명 제공자는 사설 OpenBao PKI이며 공인 전자서명·신뢰 타임스탬프·임상 사용 적합성이 확인된 상태가 아니다. API의 `clinicalUseAllowed`는 `false`다. Cloud SQL과 OpenBao VM은 각각 단일 영역 구성이다. PC 장애와 분리되어 있지만 클라우드 자체의 리전·영역 장애까지 자동 복구한다고 주장하지 않는다. OpenBao 초기 복구 조각은 현재 Secret Manager 한 곳에 보관되어 있어 별도 보관 책임자에게 분리·복구 훈련을 진행해야 한다.

Cloud SQL, OpenBao VM, Cloud Run 최소 인스턴스, 디스크·스냅샷·비밀 저장소는 계속 요금이 발생한다.
