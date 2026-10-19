# 내부 네트워크 테스트

동일한 사내 Wi-Fi/LAN에서 HTTPS 5184 포트로 접속합니다. 기본 5183 개발 서버, Java API, PostgreSQL, MariaDB 및 OpenBao는 외부에 직접 공개하지 않습니다. 웹의 `/v1` 요청만 현재 로컬 검증 API `127.0.0.1:18082`로 전달합니다.

## 서버 실행

현재 검증 환경은 PostgreSQL, 영속 OpenBao, Java API, HTTPS UI 순서로 실행한다. 각 장기 실행 명령은 별도 터미널에서 유지한다. 이미 실행 중인 서비스를 중복 실행하지 않는다.

```powershell
python infra/local_database.py postgres
python infra/openbao/server_persistent_local.py
# OpenBao 준비 완료 후 실행. 서비스 토큰 갱신 시 API도 재시작한다.
python infra/openbao/configure_existing_local.py
python infra/run_backend.py --port 18082 --database --integration --certificate-trust work/certificate-fixtures/ca.pem --certificate-crl work/certificate-fixtures/ca.crl.pem
```

위 명령은 이 PC에 준비된 DB·시험용 인증서·연동 공유키가 있는 상태를 전제로 한다. PACS 로컬 통합 시험에는 별도로 `python infra/local_database.py mariadb`가 필요하다. OpenBao 데이터는 `work/openbao/persistent-data`에 보존하며 재시작 전후 공개키 일치를 확인했다. 기존 메모리형 dev 서버에서 소실된 시험용 키를 복원하는 기능은 아니다. 단일 노드·동일 호스트 unseal 자료 보관은 내부 시험용 구성이다.

PostgreSQL/OpenBao와 영속 저장을 사용하는 검증 API가 실행된 상태에서:

```powershell
work/certificate-tools/Scripts/python.exe infra/start_lan_test.py --address 100.68.224.44
```

이 IP는 현재 개발 PC의 Tailscale 주소입니다. Tailscale 주소가 변경되면 실제 IP로 다시 실행하십시오. 스크립트는 Node.js, 웹 의존성, Python cryptography가 필요합니다. 키는 Git에서 제외된 `work/lan-test/private`에 보관하고 CA 공개 인증서만 `work/lan-test/public`에 둡니다. CA 90일, 서버 인증서 최대 30일의 내부 테스트 전용 설정입니다.

### 바탕화면 아이콘

`powershell -NoProfile -File infra/New-VisionCertiShortcut.ps1`을 한 번 실행하면 바탕화면에 **INVIZ Vision-Certi** 아이콘이 생깁니다. 아이콘 파일은 `work/desktop/vision-certi.ico`에 만듭니다.

아이콘을 누르면 `infra/Start-VisionCerti.ps1`이 실행됩니다.

1. `infra/start_for_saintview.py`로 위 구성 요소를 확인하고 꺼진 것만 켭니다.
   - 실행 중인 구성 요소는 그대로 둡니다.
   - 단, OpenBao 서비스 토큰이 만료됐거나 API가 요청을 처리하지 못하면 API를 다시 시작합니다.
2. PC 인증서 프로그램을 켭니다. 이미 실행 중이면, 실행 뒤 프로그램 파일이 바뀌었거나 다른 주소용으로 실행 중일 때만 다시 시작합니다.
3. 로그인 화면을 엽니다.

이 PC에 Tailscale 주소 `100.68.224.44`가 없으면 아무것도 켜지 않고 안내만 표시합니다. 다른 Tailscale 주소를 받았다면 `-Address <실제 IP>`로 실행합니다.

## 테스트 PC 접속

1. 서버의 `work/lan-test/public/vision-certi-lan-ca.cer` **공개 인증서만** 테스트 PC로 복사합니다.
2. Windows에서 인증서 설치 → 현재 사용자 → 신뢰할 수 있는 루트 인증 기관에 설치하고 브라우저를 다시 엽니다. 사내 관리 정책에 따라 IT 관리자가 설치해야 할 수 있습니다. 개인키는 복사하지 않습니다.
3. `https://100.68.224.44:5184/#/login` 또는 `https://100.68.224.44:5184/#/certificates/register`로 접속합니다. 접속 PC도 같은 Tailscale 네트워크에 로그인되어 있어야 합니다.

HTTPS는 인증서 기능의 Web Crypto 및 OIDC PKCE가 내부 IP에서도 동작하게 합니다. HTTP만 열고 브라우저의 보안 검사를 끄는 방식으로 대체하지 않습니다.

## Windows 방화벽

관리자 PowerShell에서 다음 명령을 실행하면 해당 PC 주소의 TCP 5184만 Private/Domain 프로파일의 같은 서브넷에 허용합니다. Public 네트워크로 자동 변경하거나 전체 방화벽을 끄지 않습니다.

```powershell
& .\infra\Enable-LanTestFirewall.ps1 -Address <사내 LAN IPv4>
```

종료 후 규칙을 제거하려면 관리자 PowerShell에서 `Remove-NetFirewallRule -Name VisionCerti-LAN-Test-HTTPS-5184`를 실행합니다. 테스트 CA도 각 클라이언트의 신뢰 저장소에서 제거할 수 있습니다.

Tailscale 주소에는 같은 서브넷용 규칙 대신 관리자 PowerShell에서 `& .\infra\Enable-TailscaleFirewall.ps1 -Address 100.68.224.44`를 실행합니다. 이 규칙은 Tailscale 인터페이스의 TCP 5184만 Tailscale 대역 피어에 허용합니다. PACS 서버와 의사 PC 모두 같은 tailnet에 연결되어 있어야 합니다. PACS 서버에는 공개 CA만 신뢰하도록 설치하고 개인키는 복사하지 않습니다.

이 서버는 개발 프로파일입니다. 개발자 역할 선택과 합성 데이터 테스트용이며 실제 환자 정보·기관 운영 계정을 사용하지 않습니다. 내부망 공개가 신분증/ARS/TOSS/PASS 공급업체 연결이나 개인 인증서 최종 등록 구현을 추가하는 것은 아닙니다.
