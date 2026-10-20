# SaintViewPACS 공동인증서 연결 설정 패치

대상은 이 PC의 설치형 SaintViewPACS 3.40-SP4가 사용하는 로컬 인증 브로커다. `Check-Connection.cmd`는 설정을 읽고 각 경로의 HTTP 상태만 확인한다. `Apply-Patch.cmd`는 `%LOCALAPPDATA%\SaintViewPACS\CertificateAuth\broker-config.json`을 백업하고 공개 설정 네 항목을 고친다.

| 설정 | 적용 값 |
|---|---|
| `VISIONCERTI_BASE_URL` | `https://vision-certi.web.app` |
| `VISIONCERTI_API_BASE_URL` | `https://vision-certi.web.app` |
| `VISIONCERTI_PACS_CLIENT_ID` | `vision-certi-pacs` |
| `VISIONCERTI_PACS_TENANT_ID` | `inviz` |

기존 PACS 서버 주소와 허용 origin, 인증서·비밀번호·HMAC 키 파일은 변경하지 않는다. 값이 다른 기관이나 다른 클라이언트 ID에 이미 연결된 PC라면 패치를 중단한다. 원본은 같은 폴더의 `broker-config.before-vision-certi-날짜.json`으로 보관한다. 복원하려면 PACS를 종료한 다음 해당 백업을 `broker-config.json`으로 되돌린다.

현재 진단에서 인증 서버 `https://vision-certi.web.app`의 API는 HTTP 200, 일회용 인증서 확인 요청은 HTTP 201이었다. PACS API `https://api70.cloudcare.life/api/health`도 HTTP 200이었다. 로컬 인증 브로커는 재시작 전 설정에서 `clientId`를 빈 문자열로 돌려줬다. PACS의 `/api/vc/config`는 로그인하지 않은 요청을 HTTP 401로 거부했으므로, 그 응답만으로 PACS 서버 쪽 연동 준비 상태를 단정할 수 없다.

패치 후 **SaintViewPACS를 완전히 종료하고 다시 실행**해야 로컬 인증 브로커가 변경된 설정을 읽는다. 재실행 뒤 `Check-Connection.cmd`의 `runningBrokerClientId`가 `vision-certi-pacs`인지 확인한다. 화면이 여전히 흰색이면 인증 창을 생성하는 설치형 Tauri 코드 또는 WebView2 내비게이션 오류를 PACS 배포본에서 수정해야 한다. 이 패치는 설치된 `saint-pacs-tauri.exe` 바이너리를 변조하지 않는다.

`needsHmacCredential: true`는 추가 조치가 필요하다는 뜻이다. 현재 브로커 설정의 HMAC 자격증명 파일은 비어 있다. **공용 PACS HMAC 키를 의사 PC에 복사하지 말고**, PACS 서버가 Vision-Certi의 일회용 코드를 서버 간 통신으로 교환하도록 설치형 PACS의 백엔드/네이티브 클라이언트를 패치해야 한다. 현재 이 저장소에는 해당 Tauri 클라이언트의 빌드 가능한 소스가 없어 로그인 완료까지 자동으로 배포할 수 없다. 이 패치는 공개 설정 복구와 연결 진단에 한정된다.
