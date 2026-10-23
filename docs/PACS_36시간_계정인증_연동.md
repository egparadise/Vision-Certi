# PACS 4.40.11 계정별 공동인증 유지

확정일: 2026-10-23. 사용자 확정 규칙: **최초 인증 후 36시간 경과 또는 최초 인증 PC 종료 시 재인증**. 자정에 초기화하지 않는다.

## 사용자 흐름

1. PACS ID/PW를 입력한다. PACS 서버가 기존 방식으로 계정을 확인한다.
2. 유효한 계정 인증이 없으면 공동인증서를 확인한다. 두 확인이 성공해야 PACS 화면에 로그인 세션을 반영한다.
3. 인증 서버는 최초 인증 시각, 최대 36시간의 만료 시각, 인증서 공개 정보, 계정 ID/번호, 최초 PC 이름/식별자를 DB에 기록한다. 인증서 자체 만료가 더 빠르면 그 시각까지 유지한다.
4. 다른 PC에서는 동일한 PACS 서버·계정·비밀번호로 로그인한다. 인증 서버의 계정 인증이 유효하면 인증서 팝업을 생략한다. 다른 PC의 ID/PW도 PACS 서버에서 매번 확인한다.
5. 로그인 화면에서 ID/PW를 입력하면 인증 상태를 조회한다. 유효할 때 공동인증서 버튼이 회색으로 비활성화되고 최초 인증 이후 경과 시간과 만료까지 남은 시간을 표시한다. 계정을 바꾸면 이전 표시를 지운다.
6. 판독 승인 직전에도 서버에서 인증 상태를 다시 확인한다. 만료·최초 PC 연결 끊김·인증서 검증 실패 시 새 공동인증서 인증이 필요하다.

## 최초 PC 연결 유지

- Windows 보조 프로그램이 20초마다 유지 신호를 전송한다. PACS 창을 닫아도 보조 프로그램이 실행 중이면 신호는 유지된다.
- 마지막 신호로부터 **90초**가 지나면 종료/절전/네트워크 단절로 간주한다. 갑작스러운 전원 종료를 서버가 즉시 감지할 수 없어 이 유예 시간이 적용된다.
- 정상 보조 프로그램 종료는 종료 요청을 전송한다. 강제 종료·전원 차단은 90초 제한으로 처리한다.
- 원본 S256 verifier와 실행 중 생성한 bootId가 최초 PC의 신호를 보호한다. 다른 PC는 인증을 조회할 수 있지만 유지 신호를 대신 보내거나 36시간을 연장할 수 없다.
- 보조 프로그램을 재시작하면 bootId와 메모리의 유지 권한이 사라진다. 저장 파일만으로 종료된 인증을 되살리지 않는다. 90초가 지난 신호도 인증을 되살리지 못한다.
- 서버 통신 실패 시 유효한 인증이라고 간주하지 않는다. 기존 PACS 서버의 중복 로그인 정책은 변경하지 않는다.

## 인증 정보와 비밀번호

인증 서버는 PACS 비밀번호·인증서 비밀번호·개인키를 받지 않는다. PC가 PACS 서버의 성공 응답을 확인한 뒤 계정 연결을 제출하므로 계정·PC 정보는 기존과 같이 `PACS_REPORTED`로 기록한다. 인증 서버가 PACS 계정의 신원을 독립적으로 검증한 기록과 혼동하지 않는다.

동일 ID/PW의 PC 간 조회에는 `accountKey`를 사용한다. PC 안에서 PBKDF2-HMAC-SHA256 600,000회로 계산하며 salt는 `SHA256(canonical(["VC-ACCOUNT-36H-V1", pacsOrigin, audience, userId]))`다. canonical은 UTF-8, `ensure_ascii=false`, 구분자 `,`/`:`인 JSON이다. 출력은 32바이트 소문자 hex다. 서버에는 `SHA256("vision-certi-account-v1|" + accountKey)`만 저장한다. 비밀번호·PACS 서버·계정 변경 시 기존 인증을 조회할 수 없다.

accountKey는 인증 조회 권한이므로 HTTPS 요청 본문으로만 전달하며 URL/로그/화면에 넣지 않는다. PC MD에는 OS 보호 키로 암호화한 값만 저장한다. 이는 서버 공용 HMAC 키가 아니며, 새 공동인증서 인증이나 PACS ID/PW 검증을 대신하지 않는다. 비밀번호 기반 키 도출은 [Python hashlib PBKDF2](https://docs.python.org/3/library/hashlib.html#hashlib.pbkdf2_hmac), 기존 일회용 코드 교환은 [RFC 7636 S256](https://www.rfc-editor.org/rfc/rfc7636#section-4.2)을 사용한다.

## 추가 API

기본 주소: `https://vision-certi.web.app/v1/pacs-certificate-proofs/pc`. 이전 Hosting 사이트도 동일 API를 사용한다. 응답은 모두 `no-store`다.

| 경로 | 역할 | 필요한 권한 |
| --- | --- | --- |
| GET `/capabilities` | `accountLease: VC-ACCOUNT-36H-V1`, 36시간/20초/90초 정책 표시 | 공개 |
| POST `/account/activate` | 새 인증을 계정에 연결 | audience, verificationId, 원본 codeVerifier, accountKey, bootId, clientInfo |
| POST `/account/status` | 다른 PC에서도 현재 상태 조회 | audience, accountKey, clientInfo |
| POST `/account/heartbeat` | 최초 PC의 생존/종료 신호 | audience, verificationId, codeVerifier, bootId, deviceId, close |
| POST `/account/events` | 로그인/판독 요청 기록 | account(status 요청과 같음), event(기존 PC 이벤트 형식) |

`clientInfo`는 pacsLoginId, pacsAccountId, computerName, deviceId다. 활성화에는 ID/계정 번호/장치 ID가 필요하며 로그인 전 조회에는 계정 번호를 생략할 수 있다. 응답에는 active, reason, serverTime, verifiedAt, expiresAt, sourceOnlineUntil, originComputerName, 유효할 때 receipt가 포함된다. 이유는 ACTIVE, NOT_AUTHENTICATED, EXPIRED, SOURCE_OFFLINE이다. 웹 표시 시 서버 시각과 PC의 단조 증가 시간으로 남은 시간을 계산한다.

기존 V010 JSONB proof에 nullable lease를 추가한다. 기존 데이터와 3.40.10의 2시간 S256/HMAC 규격은 유지한다. 4.40.11의 명시적 계정 활성화에만 36시간 정책을 적용한다. 원본 검증 시각을 갱신하지 않으며 재시도는 같은 만료 시각을 반환한다. 계정 인증 종료 후에는 새 인증서 검증이 필요하다.

## 배포

서버를 먼저 업데이트한 뒤 PACS 4.40.11 설치 파일을 각 PC에 설치한다. 기존 3.40.10 PC에는 새 계정 공유 정책이 자동 적용되지 않는다. 설치 후 최초 한 PC에서 ID/PW와 공동인증서를 확인하고, 다른 PC에서 같은 ID/PW로 로그인한다. 실제 두 PC의 전원 종료·재로그인은 배포 후 현장에서 최종 확인한다.
