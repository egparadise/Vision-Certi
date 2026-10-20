# Vision-Certi 클라우드 운영 구성

2026-10-19 기준 `hahattalk` 프로젝트에 Vision-Certi 전용 운영 자원을 서울 리전으로 구성했다. 공개 화면과 API 주소는 **https://vision-certi.web.app** 이다. PACS에는 `https://vision-certi.web.app/v1`을 설정한다. Firebase Hosting의 `/v1/**` 요청은 서울 Cloud Run `vision-certi-api`로 전달된다. Cloud Run은 이 PC의 서버를 경유하지 않으므로 PC가 꺼져도 API 주소가 유지된다.

## 실제 자원

| 용도 | 자원 |
| --- | --- |
| 웹 | Firebase Hosting 전용 사이트 `vision-certi` (`vision-certi.web.app`), 기존 `hahattalk-vision-certi`는 이전 주소 |
| API | Cloud Run `vision-certi-api`, 서울, 최소 인스턴스 1개 |
| 데이터 | Cloud SQL PostgreSQL 17 `vision-certi-postgres-seoul`, DB `vision_certi`, 스키마 V010, 일별 백업 및 PITR 7일 |
| 서명 키 | Compute Engine `vision-certi-openbao-1`의 OpenBao Transit/Private PKI, 전용 VPC 내부 주소 `10.71.0.10:8200` |
| 인증서 신뢰 | YESKEY 공개 `yessignCA Class 3` 인증서와 발급기관 서명 CRL, VM에서 매시간 갱신 |
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

공개 `/v1/runtime/capabilities`가 HTTP 200, `prod`, PostgreSQL `READY`/V010, OpenBao `OPENBAO_TRANSIT`, PC 인증서 CRL `FRESH`를 반환했다. 공개 `POST /v1/pacs-certificate-proofs/challenges`는 HTTP 201로 PACS 확인용 서명 대상을 반환했고, HMAC 없는 `POST /v1/integrations/pacs/certificate-proofs/redeem`은 HTTP 401로 거부됐다. `/v1` 응답에는 `Cache-Control: private, no-store, max-age=0`가 적용된다. 새 확인 전용 흐름은 [PACS 연동 문서](../../docs/PACS_공동인증서_확인전용_연동.md)에 설명한다. 운영 기능 표시는 여전히 `clinicalUseAllowed: false`이며 임상 사용 적합성 승인을 뜻하지 않는다.

Firebase Google 로그인을 활성화하고 `egparadise@gmail.com`의 최초 관리자 등록을 설정했다. 운영 DB에 관리자가 없을 때 이 계정의 검증된 Google 로그인으로 한 번만 관리자 주체를 만든다. 2026-10-19 새 주소에서 해당 계정의 Google 로그인과 `ADMIN` 대시보드 표시를 확인했다. 브라우저의 기존 개발용 관리자 ID/PW와 모의 SMS 가입은 이 `prod` API에서 허용되지 않는다. 개발 DB의 기존 의사·로그인 상태는 운영 DB로 자동 이전하지 않았다. 실제 의사와 의료기관 데이터는 검증 후 별도로 등록해야 한다.

서명 제공자는 사설 OpenBao PKI이며 공인 전자서명·신뢰 타임스탬프·임상 사용 적합성이 확인된 상태가 아니다. API의 `clinicalUseAllowed`는 `false`다. Cloud SQL과 OpenBao VM은 각각 단일 영역 구성이다. PC 장애와 분리되어 있지만 클라우드 자체의 리전·영역 장애까지 자동 복구한다고 주장하지 않는다. OpenBao 초기 복구 조각은 현재 Secret Manager 한 곳에 보관되어 있어 별도 보관 책임자에게 분리·복구 훈련을 진행해야 한다.

Cloud SQL, OpenBao VM, Cloud Run 최소 인스턴스, 디스크·스냅샷·비밀 저장소는 계속 요금이 발생한다.
