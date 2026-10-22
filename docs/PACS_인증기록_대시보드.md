# PACS 인증 기록과 관리자 대시보드

2026-10-22부터 PACS 인증 확인 전용 흐름의 요청·검증·결과 교환·유효 상태 재확인을 PostgreSQL 감사 기록에 자동 저장한다. 이 기본 기록은 기존 클라이언트에서도 수집한다. 계정·PC 이름 및 실제 PACS 로그인 결과·판독 요청은 아래 기록 연동 패치를 적용한 PC부터 추가 수집한다.

## 확인 위치

https://vision-certi.web.app/#/admin/login 에서 등록된 Google 관리자 계정으로 로그인한다. **인증서별 판독 현황 → PACS 공동인증서 인증 현황** 또는 **감사 로그**에서 확인한다. 페이지를 열어 둔 동안 30초마다 갱신하고, IP·Client ID·인증서·요청/인증 ID로 검색할 수 있다. 상세 보기에서 추적 ID와 인증서 정보를 확인한다.

## 자동 기록 항목

| 단계 | 감사 action의 접미사 | 의미 |
| --- | --- | --- |
| 요청 발급 | REQUESTED | 유효한 PACS audience의 서명 challenge 발급 |
| 검증 시도 | ATTEMPTED | 서버에 제출된 인증서 서명 검증 시작 |
| 인증 성공 | VERIFIED | 인증서 정책·서명·신뢰 경로·폐지 상태 검증 통과 |
| 결과 교환 | REDEEMED | HMAC 인증된 PACS의 일회용 코드 교환에 인증 결과 반환 |
| 상태 재확인 | CHECKED | HMAC 인증된 PACS가 기존 인증의 현재 유효 상태 확인 |
| 거절 | DENIED | 검증 오류, 만료, 재사용, audience 오류 또는 PACS HMAC 인증 거절 |

전체 action 접두사는 `PACS_CERTIFICATE_PROOF_`이다. 기록에는 서버 시각, Client ID, 요청/인증 ID, Correlation ID, 결과·오류 코드, 직접 연결 IP, 전달 IP 체인, Origin, User-Agent가 포함된다. 검증에 성공한 인증서에는 SHA-256 지문, 소유자 DN, 발급기관 DN, 유효기간, 일련번호, 서명 알고리즘과 인증 연결 만료 시각도 저장한다.

`peerIp`는 서버의 직접 연결 상대다. `forwardedFor`는 프록시가 전달한 참고 값으로 별도 저장하며 `UNVERIFIED`로 표시한다. 전달 헤더는 인증·권한 판단에 사용하지 않는다. IP만으로 개별 PC를 고유하게 식별하지 않는다. 전달 IP 체인의 형식은 [Google 요청 헤더 설명](https://docs.cloud.google.com/functions/docs/reference/headers)을 참고한다.

기존 3.40.6은 PACS ID·PC 이름을 보내지 않으므로 **PACS 기록 연동 패치**를 적용해야 한다. 패치는 계정 ID·계정 번호·컴퓨터 이름·PC 식별 UUID와 로그인 결과·승인 요청을 보낸다. `clientInfoSource=PACS_REPORTED`로 출처를 표시한다. 화면에 임의로 추정한 계정이나 장치명을 표시하지 않는다. 인증서 비밀번호·개인키·PACS 비밀번호·Bearer/HMAC 값·일회용 인증코드·서명 원문은 감사 기록에 저장하지 않는다.

## 집계 범위

- **인증 성공**은 인증서 검증 성공이며, PACS 계정 로그인 성공이나 판독문 저장 완료를 뜻하지 않는다.
- **PACS 결과 수신** 카드는 서버의 `redeem` 성공 응답 기록이다. 클라이언트에서 응답을 수신해 로그인까지 마쳤다는 별도 ACK는 현재 프로토콜에 없다.
- **상태 재확인**은 PACS가 인증 상태를 조회한 횟수다. 이를 판독 승인 완료 건수로 합산하지 않는다.
- **PACS 로그인 시도/완료/실패**는 패치된 PACS의 `LOGIN_ATTEMPTED`, `LOGIN_SUCCEEDED`, `LOGIN_FAILED` 기록이다. 서버의 인증서 검증 성공과 구별한다. **판독 승인 요청 횟수**는 `APPROVAL_REQUESTED`, **승인 요청 판독문**은 그 요청의 서로 다른 판독문 식별 해시 수다.
- PC 프로그램 안에서 발생한 비밀번호 오류는 검증 요청이 서버에 도착하기 전이므로 이 서버 통계에 포함되지 않는다.
- 검색 시 카드·인증서 집계·이벤트 목록 모두 일치하는 기록을 기준으로 표시한다. 이전 기록에는 미수집 항목을 `기록 없음`으로 표시한다.

## DB와 접근 제어

영속 저장 위치는 `vision_certi` DB의 `certi_rt.audit_events.payload`와 해시 체인 테이블 `audit_heads`이다. 새로운 테이블이나 V011 마이그레이션은 필요하지 않으며 현재 V010 스키마를 사용한다. 인증 연결이 2시간 뒤 만료되어 `pacs_certificate_proofs`의 운영 행이 정리되어도 감사 기록은 삭제하지 않는다. 이 변경에는 별도의 자동 삭제 기간을 설정하지 않았다.

신규 기록은 실제 기관 범위(`inviz`)에 저장한다. 이전 버전은 기관 대신 `vision-certi-pacs`를 감사 범위로 사용했다. 기존 체인을 수정하지 않고, 해당 클라이언트가 정확히 한 기관에 배정되어 있을 때만 그 기관의 관리자 화면에 이전 기록을 함께 투영한다. 복수 기관에 배정된 경우에는 이전 공용 범위 기록을 노출하지 않는다.

조회 API는 `GET /v1/admin/pacs-certificate-activity?q=&offset=0&limit=50`이다. 관리자 OIDC 인증과 기관 범위 검사를 거친다. 응답은 `totals`, `certificates`, `events`, `total`, `offset`, `limit`이며 캐시를 금지한다. 검색어는 128자 이하, 페이지 크기는 1~200이다. 현재 집계는 해당 기관 감사 기록을 읽어 계산하므로 대규모 기록이 누적되면 SQL 집계/인덱스 최적화를 별도 검토한다.

## 계정·PC·판독 기록 연동

[패치 다운로드](https://vision-certi.web.app/downloads/vision-certi-pacs-activity-patch.zip). PACS 3.40.6 PC에서 압축을 풀고 PACS를 종료한 다음 `Install.cmd`를 실행한다. 지원 대상과 복구 절차는 [패치 안내](../integrations/sv70/activity-patch/README.md)에 있다. 웹의 운영 대시보드와 관리자 인증서별 판독 현황 모두에 집계를 표시한다.

타사 PACS는 기존 HMAC 방식으로 다음 API를 호출한다. 이벤트는 통계용 관측 기록이며 인증 권한이나 판독 승인 권한을 발급하지 않는다.

```http
POST /v1/integrations/pacs/certificate-proofs/{verificationId}/events
Content-Type: application/json
```

```json
{
  "eventId": "704db066-1aaf-4c04-a5cb-e10f47e3ab33",
  "action": "LOGIN_SUCCEEDED",
  "clientInfo": {
    "pacsLoginId": "reader-example",
    "pacsAccountId": "42",
    "computerName": "READING-PC-EXAMPLE",
    "deviceId": "pc-installation-uuid"
  }
}
```

`action`은 `LOGIN_ATTEMPTED`, `LOGIN_SUCCEEDED`, `LOGIN_FAILED`, `APPROVAL_REQUESTED` 중 하나다. 승인 요청은 `reportKeySha256`(소문자 64자리)을 추가한다. 기관·PACS·판독문 식별자로 안정된 해시를 계산하고 동일한 판독문에 같은 값을 사용해야 한다. 환자 정보·판독문 본문은 전송하지 않는다. 필드 값은 128자 이하이며 제어문자는 허용하지 않는다. 비밀번호 필드는 없다.

서버는 HMAC 클라이언트 소유의 코드 교환이 끝난 인증 ID인지 확인한다. 동일한 `eventId`와 내용의 재전송은 `{"recorded":true,"duplicate":true}`이고 횟수에 다시 더하지 않는다. 같은 ID를 다른 내용으로 재사용하면 409다. 만료되어 운영 인증 행이 삭제된 뒤에도 같은 기관·PACS의 영구 교환 기록이 있으면 지연된 감사 기록을 수신할 수 있다. 이는 인증 세션을 연장하지 않는다.

패치는 로컬 무결성 검증 outbox에 기록한 후 30초마다 전송한다. 통신 실패 시 eventId를 유지해 재시도한다. 서버 시각은 수신 시각이며 인증서 검증 시각은 별도 `verifiedAt`이다. 소스 제공 패치의 최초 report 번호 미발급 상황에 대한 집계 범위는 패치 README를 따른다.

상단 **인증서버 ON**은 `/v1/health`의 실제 API 응답을 30초마다 확인한 결과다. 5초 내 응답이 없거나 잘못된 응답이면 **연결 안 됨**으로 표시한다. 연결 상태는 임상 사용 허가나 개별 인증서 검증 결과를 대신하지 않는다. 대시보드 상단 4개 지표는 판독문 목록, 서명 내역, 전송 큐, 감사 로그로 각각 이동한다.
