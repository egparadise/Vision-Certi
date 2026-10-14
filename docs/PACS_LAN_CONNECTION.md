# 동일 네트워크 PACS 연결 정보

기준: 2026-10-14, 내부 테스트 환경. 서버 IP가 바뀌면 URL과 TLS 인증서를 함께 갱신한다.

| 항목 | 값 |
|---|---|
| INVIZ 서버 / 브라우저 기본 주소 | `https://192.168.45.160:5174` |
| 상태 확인 | `GET /v1/runtime/capabilities` |
| 개인 인증서 로그인 | `/#/login` |
| PACS 의사 인증 팝업 | `/#/certificates/pacs-login` |
| 서비스 클라이언트 ID | `pacs-sv70` |
| 테스트 기관 ID | `demo-hospital-a` |
| 서버 간 HMAC audience | `vision-certi:pacs-integration:v1` |
| 허용 네트워크 | Windows Private/Domain, 같은 서브넷, TCP 5174 |

## PACS 서버 설정

[설정 예제](../integrations/sv70/pacs-lan.env.example)의 값들을 PACS API 프로세스 환경변수로 설정한 뒤 재시작한다. 기존 `.env` 파일에 넣는 것만으로 적용된다고 가정하지 않는다. 이 연동은 `os.environ`을 직접 읽는다.

- `VISION_CERTI_BASE_URL`과 `VISION_CERTI_PUBLIC_BASE_URL`에는 `/v1`, `/api`, `/#/login`을 붙이지 않는다.
- `VISION_CERTI_CLIENT_SECRET_FILE`은 **PACS 서버 안의 파일 경로**다. 이 인증 서버의 `C:\Project\Vision-Certi\work\sv70-integration.secret`을 관리자 전용 경로로 안전하게 복사하고 PACS 실행 계정만 읽게 한다. 내용은 Git·채팅·브라우저에 넣지 않는다.
- `VISION_CERTI_CA_BUNDLE`은 이 서버의 `work/lan-test/public/vision-certi-lan-ca.cer` 공개 인증서를 PACS 서버로 복사한 경로다. 개인키는 복사하지 않는다. 서버 간 TLS 검증을 끄지 않는다.
- `VISION_CERTI_LINK_ADMIN_USER_IDX`는 해당 PACS의 실제 연동 관리자 `user_idx`로 바꾼다. 추측한 관리자 번호를 배포하지 않는다.
- 연동 코드를 적용하고 [MySQL/MariaDB 추가 테이블](../database/sv70/001_vision_certi_integration.sql)을 검토·백업 후 적용해야 한다. 설정만으로 원본 PACS에 API가 생기지 않는다.

## 판독 PC와 인증 팝업

판독 PC에도 공개 CA 인증서를 신뢰하도록 설치한다. 인증 UI의 `VITE_PACS_ALLOWED_ORIGINS`에는 실제 PACS 웹의 `window.location.origin` 값(예: `https://pacs.example.internal`)을 정확히 등록해야 한다. 경로 없이 `scheme://host[:port]` 형식을 사용하며 HTTPS 기본 포트 443은 생략한다. 변경 후 인증 UI 서버를 재시작한다. `*` 허용은 사용하지 않는다. 데스크톱 WebView의 origin이 `null`이면 일반 웹 출처 검증을 해제하지 말고 별도의 검증된 네이티브 브리지가 필요하다.

현재 실제 PACS 서버/브라우저 접속 주소는 전달받지 않았다. 그 주소를 확인한 뒤 허용 출처를 설정해야 팝업 인계 코드가 전달된다.

## 연결 검사

PACS 서버에서:

```sh
curl --cacert /etc/vision-certi/vision-certi-lan-ca.cer https://192.168.45.160:5174/v1/runtime/capabilities
```

PACS API의 `GET /api/vc/config`가 `state: ENABLED`를 반환하는지 확인한다. 관리자 화면에서 `PACS user_idx ↔ INVIZ Doctor ID`를 증빙과 함께 연결한다. 같은 면허번호가 양쪽에 등록되어 있어야 한다.

ID/비밀번호로 PACS에 로그인한 경우에도 영상 열람·판독문 초안 작성은 가능하다. 최종 승인에서는 공동인증서 본인확인을 거쳐 현재 PACS 사용자와 일치해야 하며, 서명 확인 후에만 A/RA로 확정한다. 다른 의사의 인증서, 만료·해제된 연결, 변경된 판독문은 승인할 수 없다.

현재 서버는 합성 데이터용 개발 프로파일이다. 실제 PACS 배포 및 원격 PC에서의 접속 검증은 별도로 필요하다. 개인용 ARS/TOSS/PASS 본인확인 제공자는 아직 미연결이다.
