# Firebase 웹과 의사 PC 인증서 프로그램

Vision-Certi 공개 사이트는 **https://vision-certi.web.app** 이다. 웹 화면과 `/v1/**` API는 같은 주소로 제공된다. Firebase Hosting은 `/v1/**`을 서울 Cloud Run `vision-certi-api`에 전달하며, Cloud Run은 전용 PostgreSQL과 내부망 OpenBao를 사용한다. 이 PC의 `dev` API나 Tailscale 주소를 경유하지 않는다. 따라서 이 PC의 로컬 서버가 꺼져도 공개 API가 응답한다.

의사 인증서의 개인키는 클라우드로 복사하지 않는다. 각 의사 PC에서 로컬 인증서 프로그램이 NPKI/PFX를 찾고 비밀번호로 challenge에 서명한다. 해당 PC에서 다음 명령으로 공개 사이트를 허용한다.

```powershell
cd C:\Project\Vision-Certi
.\infra\start_local_certificate_bridge.ps1 -Origin 'https://vision-certi.web.app'
```

현재 이 PC의 프로그램은 공개 사이트 origin을 허용하도록 재시작했으며, 인증서 목록 요청에 yessign 인증서 1건을 반환함을 확인했다. 다른 의사 PC에서는 별도 설치·실행이 필요하다. `127.0.0.1:18187`은 **의사 PC 자체의** 루프백 포트다. 중앙 인증 API의 주소·포트가 아니다.

```text
웹/PACS 주소: https://vision-certi.web.app:443
API 기본 주소: https://vision-certi.web.app/v1
로컬 인증서 프로그램: http://127.0.0.1:18187 (각 의사 PC 안에서만)
```

공개 API에서 `prod`, PostgreSQL `READY`, OpenBao 서명, 인증기관 CRL `FRESH`를 확인했다. PACS HMAC 인증·nonce 재전송 차단도 공개 주소에서 검증했다. 운영 관리자 Google 로그인을 새 주소에서 실제로 확인했다. 의사 등록의 최종 승인·판독 절차는 종단 간 검증이 남아 있다. 구성과 남은 작업은 [클라우드 운영 문서](../infra/cloud/README.md)에 기록했다.
