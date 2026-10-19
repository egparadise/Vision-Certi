# Tailscale PACS 연결 주소

현재 이 PC에 할당된 Tailscale IPv4는 `100.68.224.44`입니다. Vision-Certi를 기본 설정으로 실행하면 아래 주소에서 HTTPS 웹 화면과 PACS 연동 API를 함께 제공합니다.

| 용도 | 주소 |
|---|---|
| PACS 인증 서버 기본 URL | `https://100.68.224.44:5184` |
| 연결 상태 확인 | `https://100.68.224.44:5184/v1/runtime/capabilities` |
| 공동인증서 로그인 화면 | `https://100.68.224.44:5184/#/certificates/pacs-login` |
| 최초 공동인증서 등록 화면 | `https://100.68.224.44:5184/#/certificates/pacs-enroll` |
| 일반 로그인 화면 | `https://100.68.224.44:5184/#/login` |

**PACS 서버에서 입력할 IP/포트는 `100.68.224.44` / `5184`이고 프로토콜은 HTTPS입니다.** 서버 간 API는 이 기본 URL의 `/v1/integrations/pacs/...` 경로를 사용합니다. 로그인 팝업은 해당 경로만 단독 호출하는 것이 아니라, [PACS 연동 계약](PACS_외부시스템_연동_가이드.md)에 따라 `nonce`, `audience`, `origin`을 넣어 열고 서버가 결과를 교환해야 합니다.

PACS 서버와 판독의사 PC는 이 PC와 같은 Tailscale 네트워크에 연결되어 있어야 합니다. 이 PC에서 `Start-VisionCerti.ps1`을 실행하고, 관리자 PowerShell에서 `& .\infra\Enable-TailscaleFirewall.ps1 -Address 100.68.224.44`로 Tailscale 인터페이스의 TCP 5184만 허용합니다. 기존의 LAN용 `LocalSubnet` 방화벽 규칙은 Tailscale 피어 접속용이 아닙니다. Tailscale 접근 권한도 tailnet 정책에서 허용되어야 합니다.

시험용 HTTPS CA의 **공개 인증서만** `work/lan-test/public/vision-certi-lan-ca.cer`에서 PACS 서버와 의사 PC에 배포해 신뢰시킵니다. 인증서 개인키는 배포하지 않습니다. PACS의 `VISION_CERTI_BASE_URL`과 `VISION_CERTI_PUBLIC_BASE_URL`은 위 기본 URL로 지정합니다. [SV70 환경변수 예제](../integrations/sv70/pacs-lan.env.example)에 같은 값이 들어 있습니다. PACS HMAC 공유키와 허용 origin은 별도로 설정합니다.

```powershell
Test-NetConnection 100.68.224.44 -Port 5184
curl.exe --ssl-no-revoke --cacert .\vision-certi-lan-ca.cer https://100.68.224.44:5184/v1/runtime/capabilities
```

Windows `curl.exe`의 Schannel이 시험용 CA의 폐지 상태를 조회하지 못해 실패하는 경우에만 위 검증 명령의 `--ssl-no-revoke`를 사용합니다. TLS 서버 인증서의 IP와 CA 체인 검증은 유지합니다. PACS 프로그램의 HTTPS 검증을 끄지 않습니다. 이 PC의 API `127.0.0.1:18082`와 의사 PC 인증서 프로그램 `127.0.0.1:18187`은 외부에 공개하지 않습니다. Tailscale 주소가 바뀌면 `Start-VisionCerti.ps1 -Address <새 Tailscale IP>`로 실행하고 PACS URL·인증서·방화벽 규칙을 함께 갱신합니다.

## PACS PC에서 연결 오류 확인

`ERR_CONNECTION_TIMED_OUT`가 나오면 **PACS를 실행한 PC에서** 다음 명령을 실행합니다. 서버 PC에서 자기 Tailscale IP로 검사하면 원격 연결은 검증되지 않습니다.

```powershell
Test-NetConnection 100.68.224.44 -Port 5184
```

`TcpTestSucceeded: False`이면 PACS PC의 Tailscale 연결·같은 tailnet 접근 권한과 서버의 `VisionCerti-Tailscale-HTTPS-5184` 방화벽 규칙을 확인합니다. `True`인데 브라우저가 인증서 오류를 보이면 PACS PC의 **현재 사용자** 신뢰할 수 있는 루트 인증 기관에 서버의 공개 CA 파일만 등록합니다. 연결과 TLS 신뢰가 모두 정상일 때 `/v1/runtime/capabilities`가 JSON으로 열립니다.
