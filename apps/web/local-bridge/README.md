# INVIZ PC 인증서 프로그램

브라우저가 Windows `NPKI` 폴더를 시스템 파일로 분류해 접근을 거부해도, 판독의사 PC에서 실행하는 이 프로그램은 표준 사용자 인증서 위치를 검색해 인증서 목록을 제공합니다. `signCert.der`와 `signPri.key` 쌍 및 PFX/P12를 지원합니다. 인비즈가 개발한 로컬 프로그램이며 외부 금융기관 SDK를 사용하지 않습니다.

Windows에서 Node.js와 `apps/web` 의존성을 설치한 후, 이 PC의 PowerShell에서 실행합니다.

```powershell
cd C:\Project\Vision-Certi
cd apps\web
npm ci
cd ..\..
.\infra\start_local_certificate_bridge.ps1 -Origin 'https://192.168.45.160:5184'
```

실제 운영 주소로 배포할 때는 `-Origin`에 그 HTTPS 주소를 정확히 지정하고, **각 판독의사 PC에서** 프로그램을 실행합니다. 브라우저는 `127.0.0.1:18187`에만 접속합니다. 사용자 인증서의 공개 정보와 서명값만 웹 화면으로 전달합니다. 암호화된 개인키 파일은 로컬 프로그램 밖으로 내보내지 않습니다. 비밀번호는 웹 화면에서 로컬 프로그램으로만 전송되며 PACS/INVIZ 서버로 전송하지 않습니다.

기본 검색 범위는 해당 Windows 사용자 프로필의 `AppData\LocalLow\NPKI`, `AppData\Local\NPKI`, `AppData\Roaming\NPKI`, `Documents\NPKI`, `Documents`, `Desktop`, `Downloads`입니다. 다른 위치는 `node apps/web/local-bridge/server.mjs --origin <주소> --root <인증서폴더>`로 지정할 수 있습니다. 인증서 암호, 개인키, 공개 인증서 원문을 로그에 기록하지 않습니다.

프로그램은 지정된 웹 Origin만 허용하고 루프백 주소에만 바인딩합니다. 브라우저가 로컬 네트워크 접속 권한을 요청하면 해당 인비즈 사이트에 대해 허용해야 합니다. 최초 목록 검색은 공개 인증서만 읽으며 개인키는 의사가 인증서를 선택하고 암호를 입력해 서명을 요청할 때만 엽니다. 실제 로그인과 판독문 서명 허용 여부, 인증서 신뢰체인 및 폐지 상태는 INVIZ 서버가 별도로 검증합니다.

이 구현은 PC에 파일로 저장된 NPKI 공동인증서와 PFX/P12를 대상으로 합니다. 인증기관의 클라우드에만 존재하는 금융인증서는 PC 파일 검색으로 찾을 수 없으며 인증기관 연동이 필요합니다.
