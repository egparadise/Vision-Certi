> 2026-10-13 설계 변경: [[12_인증서등록_설계변경]]을 우선 적용한다. 관리자 사전등록 → PC 공동인증서/모바일 인증서 검증·의사 연결 → 본인 동의 → OpenBao ECDSA P-256 키·INVIZ 자체 인증서 활성화로 변경했다. Google은 보조 연동이며 기본 인증서 필수 정책을 우회하지 않는다. 아래 원문 기반 계획의 Keycloak·RSA/HSM 초기 구성과 충돌하는 부분은 이 변경서로 대체한다.
---
title: API와 외부 연동 계약
project: Vision-Certi
version: 1.0
date: 2026-10-13
status: ready-for-development
owner: Codex
---

# API와 외부 연동 계약

OpenAPI를 백엔드와 프런트엔드의 공통 기준으로 사용한다. 이 문서는 목표 운영 계약이며 초기 개발용 API의 구현 여부는 실제 OpenAPI와 개발 보고서에서 구분한다.

## 공통 규약

- prefix `/v1`, JSON lowerCamelCase, UTF-8, ID 문자열, UTC ISO 8601, 목록 `{items,nextCursor}`. 초기 개발 목록은 `items`만 지원할 수 있다.
- 운영 브라우저 인증은 BFF 쿠키, 서비스 연동은 mTLS＋OAuth scope다. tenant는 검증된 인증 컨텍스트로 결정한다.
- 개발 전용 `X-Tenant-Id`, `X-Actor-Id`는 명시적 dev profile에서만 허용한다. 운영에서 헤더를 인증 대체 수단으로 쓰지 않는다.
- 변경 요청에는 `Idempotency-Key`가 필요하다. 보관 기본 24시간, 장기 중복은 업무 유일 제약으로 차단한다. 같은 키·내용은 기존 결과, 내용 충돌은 409다.
- 오류 형식은 `{code,message,correlationId,retryable}`. 개인정보·내부 스택·토큰을 message에 넣지 않는다.
- 인증 부재 401, 권한 거부 403, 보이지 않는 리소스 404, 상태·버전 충돌 409, 입력 오류 422, 속도 제한 429, 일시 장애 503을 사용한다.

## 엔드포인트

| 경로 | 요청의 필수 내용 | 결과·권한 |
|---|---|---|
| POST /identities/proof | identityId, proofType, evidenceRef | 201 검증 근거; 신원 검증자 |
| POST /credentials | identityId, policyVersion, approvalRefs | 202 발급 작업; CA 운영자 |
| POST /credentials/{id}/revoke | reason, invalidityAt | 202 폐기·공표 작업; CA 운영자 |
| POST /documents | multipart metadata＋file | 201 고정 문서·버전·SHA-256; 연동 서비스 |
| GET /documents | cursor, limit | 기관의 문서 목록 |
| GET /documents/{id} | 없음 | 문서와 버전 메타데이터 |
| GET /documents/{id}/versions/{versionId}/content | 없음 | 권한 검사 후 PDF; Cache-Control no-store |
| POST /documents/{id}/versions | file, previousVersionId, amendmentReason | 201 새 버전; 원본 유지 |
| POST /sign-requests | documentVersionId, signerId | 201 요청·nonce·expiresAt |
| GET /sign-requests/{id} | 없음 | 상태·결과 참조 |
| POST /sign-requests/{id}/approve | documentSha256, nonce | 202 승인·작업; 지정 의사만 |
| POST /signatures/{id}/verify | 선택 policyVersion | 200 항목별 검증 결과 |
| GET /signatures/{id}/evidence | generation | 권한 있는 감사자·의사 다운로드 |
| POST /deliveries | signatureId, connectorId, eventType | 202 전송 작업 |
| GET /deliveries/{id} | 없음 | 전송 상태·업무 ACK·재시도 |
| GET /audit-events | 기간·action·target | 마스킹된 감사 목록 |
| GET /dashboard | 없음 | 기관별 집계·장애 요약 |

표의 경로에는 공통 `/v1`이 붙는다. 인증서 목록·신원 심사 목록·기관 정책 API는 P1/P3에서 같은 권한 규약으로 확장한다. `execute`는 공개 API로 제공하지 않는다.

문서 upload metadata는 `{externalDocumentId,externalStudyId,title,signerId}`로 시작한다. 파일은 PDF 20MiB 이하, MIME과 PDF 구조를 검사한다. 한 요청에서 데이터와 파일 검증 실패 시 부분 문서를 공개하지 않는다. 실제 병원 최대 크기는 EXT-001에서 조정한다.

## 핵심 응답

```json
{
  "id": "request-uuid",
  "tenantId": "tenant-uuid",
  "documentVersionId": "version-uuid",
  "documentSha256": "64-character-lowercase-hex",
  "signerId": "identity-uuid",
  "nonce": "opaque-single-use-value",
  "status": "PENDING_APPROVAL",
  "expiresAt": "2026-10-13T06:10:00Z",
  "signatureId": null
}
```

nonce는 생성·승인 화면에서만 사용하고 감사·분석 로그에 기록하지 않는다. 요청 조회에서 반환 가능한 범위도 지정 서명자로 제한한다.

```json
{
  "status": "INDETERMINATE",
  "checks": {
    "documentIntegrity": "VALID",
    "signature": "INDETERMINATE",
    "trustChain": "INDETERMINATE",
    "timestamp": "INDETERMINATE",
    "revocationAtSigning": "INDETERMINATE",
    "authorizationAtSigning": "INDETERMINATE"
  },
  "reasonCodes": ["MISSING_TRUSTED_TIMESTAMP"],
  "verifiedAt": "2026-10-13T06:00:00Z"
}
```

모의 서명의 최종 검증은 `VALID`가 될 수 없다. `MOCK_NOT_VALID_FOR_CLINICAL_USE`를 결과와 화면에 표시한다. 원본 해시가 일치하는 것과 의사 전자서명이 유효한 것은 별도 판정이다.

## 외부 어댑터

| 인터페이스 | 입력·출력 | 모의 시험 |
|---|---|---|
| IdentityProofProvider | 실명 검증 세션 → 검증 근거 참조 | 성공·불일치·만료 |
| SmsProvider | 전화번호 참조·OTP 요청 → providerMessageId | timeout·재전송·한도 |
| CaProvider | CSR·정책 → 인증서 체인; 폐기 → 공표 참조 | 발급·갱신·폐기 실패 |
| SigningProvider | keyRef·승인 digest·서명 입력·operationId → signature result | 성공·실패·결과 유실 |
| TimestampProvider | messageImprint·nonce → RFC 3161 토큰 | 잘못된 imprint·체인·timeout |
| RevocationProvider | issuer·serial → OCSP/CRL 바이트·유효기간 | UNKNOWN·stale·REVOKED |
| HospitalConnector | deliveryId·원본·증거 참조 → 접수·업무 ACK | 중복·지연·거절·유실 |

어댑터 구현은 공급자 형식을 내부 계약으로 변환한다. 외부가 넘긴 tenant·signer를 검증 없이 사용하지 않는다. 콜백은 mTLS 또는 서명 검증·재전송 방지·요청 상관관계를 요구한다.

전송은 at-least-once다. HTTP 200만으로 병원 업무 완료라 판단하지 않는다. 재시도는 30초부터 지수 증가·지터, 최대 간격 1시간, 최대 10회 또는 24시간 중 먼저 도달할 때 DEAD_LETTER로 보낸다. 영구 거절은 즉시 담당자 확인으로 보낸다.

Cloud Care가 문서를 변경하면 새 external version 또는 새 버전 요청으로 전달해야 한다. 동일 외부 버전의 다른 바이트는 409다. DICOM SR·HL7/FHIR는 실제 규격 확보 전 구현 완료로 표시하지 않는다.

관련 문서: [[02_아키텍처_보안경계]], [[05_화면_사용자흐름]], [[11_외부의존성_결정대장]].
