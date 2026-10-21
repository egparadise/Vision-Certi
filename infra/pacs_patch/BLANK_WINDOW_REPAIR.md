# 다른 PC에서 공동인증서 창이 흰 화면일 때

이 패치는 설치형 SaintView PACS의 WebView2 캐시만 백업 후 새로 만들게 합니다. PACS 계정, 쿠키, 인증서, 인증서 비밀번호, 판독문 및 로컬 저장소는 삭제하지 않습니다. 웹 페이지를 불러올 수 있는지와 인증 브로커·PC 인증서 프로그램의 응답도 확인합니다.

1. 문제가 발생하는 **해당 PC**에서 SaintView PACS와 공동인증서 창을 완전히 종료합니다.
2. 압축 해제 폴더의 **`Repair.cmd`를 더블클릭**합니다. 결과가 나온 뒤 창이 닫히지 않으므로 내용을 확인할 수 있습니다. 조직의 실행 정책 때문에 배치 파일을 실행할 수 없는 경우 PowerShell에서 다음을 실행합니다.

   ```powershell
   .\Repair-BlankCertificateWindow.ps1
   .\Repair-BlankCertificateWindow.ps1 -RepairCache
   ```

3. SaintView PACS를 다시 열고 공동인증서 로그인을 시도합니다. 두 번째 명령의 `diagnosis`가 `CACHE_BACKED_UP_RESTART_PACS`이면 캐시가 백업된 것입니다.

`WEB_CONTENT_UNREACHABLE`이면 해당 PC에서 `vision-certi.web.app` 및 표시된 JavaScript 파일로 HTTPS 통신이 되는지 방화벽·프록시를 확인합니다. `WEBVIEW2_RUNTIME_NOT_FOUND`이면 [Microsoft 공식 WebView2 Runtime](https://developer.microsoft.com/en-us/microsoft-edge/webview2/)을 설치합니다. `LOCAL_BROKER_UNREACHABLE` 또는 `PC_CERTIFICATE_BRIDGE_UNREACHABLE`이면 PACS 인증 브로커 또는 Windows 인증서 프로그램 설치·실행 상태를 확인합니다.

백업 폴더는 `%LOCALAPPDATA%\com.saint.v3.app\EBWebView` 아래 `*.before-visioncerti-날짜` 이름으로 남습니다. 문제가 해결되지 않으면 스크립트의 JSON 출력과 PACS 버전을 함께 전달해 주세요. 비밀번호나 인증서 파일은 전달하지 마세요. 사이트와 로컬 서비스가 모두 정상인데 창이 여전히 흰색이면 설치형 PACS의 WebView2 창 생성 또는 탐색 처리를 수정해야 합니다.
