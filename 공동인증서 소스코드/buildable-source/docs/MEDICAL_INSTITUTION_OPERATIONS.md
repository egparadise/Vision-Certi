# 의료기관 등록 및 인증서별 판독 현황

## 현재 로컬 실행 상태

- 관리 화면: `https://100.68.224.44:5184/#/admin` (관리자 로그인 후)
- 의료기관 등록·의사 배정: `https://100.68.224.44:5184/#/admin/institutions`
- 관리자 API는 개발 프로파일에서 로컬 관리자 세션과 `X-Tenant-Id`, `X-Actor-Id`가 필요하다. 운영 프로파일에서는 구성된 OIDC 관리자 인증이 필요하다.
- 2026-10-19 로컬 DB 조회 결과, 실제 발급기관 인증서 0건과 등록된 의료기관 0건이다. 이전 합성 인증서 및 의사 시험 기록은 삭제하지 않고 감사 보존하며, 운영 대시보드 집계에서 제외한다.
- 현재 로컬 API는 시험용 CA 신뢰 앵커와 CRL을 사용하고 임상 사용 검증 상태가 아니다. 외부 발급기관 인증서를 실제로 등록하려면 해당 발급기관 신뢰체인·폐지 확인 자료와 운영 인증 구성이 필요하다.

## 관리자 작업 순서

1. `#/admin/login`에서 관리자 인증을 완료한다.
2. `#/admin/institutions`에서 실제 의료기관의 코드와 정식 명칭을 입력한다. 이 입력만으로 기관의 법적 존재나 소속이 검증되지는 않는다.
3. 의사가 본인의 외부 인증서를 등록하고 면허 정보를 제출한 뒤, 관리자 검토를 마친 의사를 기관에 배정한다.
4. `#/admin`에서 기관을 선택하면 배정된 의사의 인증서별 PACS 로그인, 판독 승인 요청, 전자서명 완료 건수가 표시된다. 이는 해당 의사의 전체 인증서 실적 필터이며 기관별 판독 건수 분할이 아니다. 복수 기관에 배정된 의사에게는 각 기관 필터에서 동일한 전체 건수가 보인다.

## API

모든 경로는 `/v1` 기준이다. 다음 API는 관리자 전용이며 테넌트 범위와 의사 소속을 검사한다.

| 메서드 | 경로 | 기능 |
|---|---|---|
| `GET` | `/admin/medical-institutions` | 기관 목록과 배정 의사 ID |
| `POST` | `/admin/medical-institutions` | `{ "code": "기관코드", "name": "정식 기관명" }` 등록 |
| `POST` | `/admin/medical-institutions/{institutionId}/doctors/{doctorId}` | 의사 배정 |
| `DELETE` | `/admin/medical-institutions/{institutionId}/doctors/{doctorId}` | 배정 해제 |
| `GET` | `/admin/certificate-operations` | 실제 인증서별 로그인·판독 집계 |

집계 응답의 `signedReports`는 해당 인증서 지문과 연결된 서명 완료 판독문 수, `reportApprovalRequests`는 성공한 PACS 인증서 확인 요청 수다. 한 판독문에 확인 요청이 여러 번 발생할 수 있으므로 두 값은 같을 필요가 없다. `institutionNames`는 관리자 배정 목록을 나타낸다. 등록·배정·해제는 감사 로그에 남는다.

DB 변경은 `database/migrations/V009__medical_institutions.sql`이며 기존 의사·인증서·판독·감사 데이터를 삭제하지 않는다.
