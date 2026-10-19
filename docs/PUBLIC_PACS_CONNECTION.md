# 외부 PACS의 Vision-Certi 공동인증서 연동

운영 웹 주소는 **https://vision-certi.web.app** (HTTPS 443), API 기본 주소는 **https://vision-certi.web.app/v1** 이다. PACS와 의사 브라우저는 이 공개 주소를 사용한다. 예전 `100.68.224.44:5184`는 Tailscale 내부 시험 주소이므로 외부 PACS에 입력하지 않는다.

현재 이 PC에서 확인한 Saintview PACS Client 화면 origin은 `https://localhost:5175`다. 공개 웹 빌드의 `VITE_PACS_ALLOWED_ORIGINS`에 이 정확한 값을 등록했다. 다른 PC의 PACS 화면이 별도 도메인·포트를 사용하면 그 화면의 실제 origin을 확인해 목록에 추가하고 웹을 다시 배포해야 한다. PACS 서버의 API URL과 브라우저 팝업 URL은 동일한 운영 인증 DB를 가리켜야 한다. 현재 이 PC의 Saintview PACS 개발 설정은 별도 `dev` 서버와 데모 기관을 가리키므로 운영 API와 섞어 사용할 수 없다.

```text
PACS 서버 ── HTTPS 443 /v1/integrations/pacs/** ── Firebase Hosting ── Cloud Run API
의사 브라우저 ── HTTPS 443 /v1/** ────────────────┘                  ├─ 전용 PostgreSQL
의사 PC 인증서 프로그램 ── 127.0.0.1:18187 ── 개인키 서명              └─ 내부망 OpenBao
```

PACS API 경로는 `https://vision-certi.web.app/v1`이다. Saintview PACS의 `VISION_CERTI_BASE_URL`에는 **`https://vision-certi.web.app`**을 입력한다. 해당 클라이언트가 `/v1`을 직접 붙이므로 설정값에 중복 입력하면 안 된다. 팝업용 `VISION_CERTI_PUBLIC_BASE_URL`도 `https://vision-certi.web.app`이다. HMAC 클라이언트 ID는 `vision-certi-pacs`다. 현재 `inviz`만 시험 범위로 허용되어 있다. 다른 의료기관은 실제 기관 ID와 PACS 브라우저 origin을 등록한 뒤 웹을 다시 배포해야 한다. HMAC 공유키는 GCP Secret Manager의 `vision-certi-pacs-hmac-secret` 최신 버전에만 보관한다. PACS 서버 담당자에게 보안 채널로 전달하고 **브라우저, URL, Git, 로그에 넣지 않는다**. 이 값의 취득·교체는 권한 있는 운영자가 Secret Manager에서 수행한다. [요청 서명 및 API 계약](PACS_외부시스템_연동_가이드.md)의 `VC-HMAC-SHA256-V1`을 따른다.

PACS는 의사 첫 로그인 시 병원 ID·개별 ID·비밀번호를 먼저 검사한 뒤 `POST /v1/integrations/pacs/enrollments/start`로 본인 등록을 시작하고, 인증서 등록 후 `POST /v1/integrations/pacs/enrollments/status`로 상태를 조회한다. Saintview PACS에서는 `VISION_CERTI_PASSWORD_LOGIN_REQUIRES_CERT=true`로 설정해야 이 단계가 열린다. 로그인 화면에 PACS ID/PW를 입력하고 **공동인증서로 로그인**을 누르면 처음에는 등록 창이 자동으로 열린다. 시스템 관리자처럼 의사 역할·면허번호가 없는 계정은 자동 등록 대상이 아니다. PACS 계정과 인증서의 연결은 서버에 저장되지만 의료인 자격 검토가 끝나기 전에는 `PENDING`이며 인증서 단독 로그인과 판독 권한은 열리지 않는다. 등록 승인 후 로그인에서는 의사 PC에서 인증서 선택·비밀번호 입력·일회용 challenge 서명을 진행하고 PACS가 `handoffs/redeem`, `certificate-logins/confirm`, `sessions/{pacsSessionId}`를 사용한다. 판독 승인 요청 시에는 등록된 인증서 지문과 현재 세션을 함께 검증한다. 인증서 비밀번호나 개인키는 PACS·클라우드로 보내지 않는다.

각 판독의사 PC에는 로컬 인증서 프로그램이 필요하다. 프로그램은 해당 PC의 NPKI/PFX 인증서를 찾아 브라우저의 `http://127.0.0.1:18187` 호출에 응답한다. 사이트를 허용 origin으로 지정하여 실행한다.

```powershell
cd C:\Project\Vision-Certi
.\infra\start_local_certificate_bridge.ps1 -Origin 'https://vision-certi.web.app'
```

서버와 각 의사 PC의 시계가 정확해야 한다. PACS HMAC 타임스탬프 허용 오차는 5분, nonce는 10분 동안 재사용할 수 없다. 연결 확인은 다음과 같이 한다.

```powershell
Invoke-RestMethod https://vision-certi.web.app/v1/runtime/capabilities
```

현재 공개 API는 `prod`로 기동했고 운영 관리자 Google 로그인과 최초 관리자 등록을 실제로 확인했다. 의사 자격 검토·승인과 PACS 종단 간 판독 절차는 아직 확인해야 한다. 따라서 **실제 의사 등록 승인 및 임상 판독 서명 서비스가 준비되었다고 간주하면 안 된다**. Cloud Run의 사설 OpenBao 서명에는 공인 타임스탬프도 없다. 자세한 현재 상태와 백업은 [클라우드 운영 구성](../infra/cloud/README.md)을 참조한다.
