> 최신 변경: 공동인증 유지 시간은 **72시간**입니다. 최초 PC 종료/연결 끊김 시 재인증은 유지합니다. [[PACS_72시간_계정인증_연동]] 및 [[2026-10-23_통합_초기화_웹_설치_배포]]를 따릅니다.

> 2026-10-23 현재 기준: 루트 AGENTS.md·CLAUDE.md·GEMINI.md 및 RELEASE_RUNBOOK을 공통 초기화 기준으로 사용한다. PACS 4.40.12는 최초 인증 후 최대 72시간 또는 최초 PC 종료 시 재인증한다. 24시간부터 주황색, 48시간부터 빨간색과 재인증 권고를 표시한다. [[PACS_72시간_계정인증_연동]]과 [[2026-10-23_통합_초기화_웹_설치_배포]]를 먼저 확인한다. 아래 10-13 내용은 초기 설계 이력이다.

> 2026-10-13 설계 변경: [[12_인증서등록_설계변경]]을 우선 적용한다. 관리자 사전등록 → PC 공동인증서/모바일 인증서 검증·의사 연결 → 본인 동의 → OpenBao ECDSA P-256 키·INVIZ 자체 인증서 활성화로 변경했다. Google은 보조 연동이며 기본 인증서 필수 정책을 우회하지 않는다. 아래 원문 기반 계획의 Keycloak·RSA/HSM 초기 구성과 충돌하는 부분은 이 변경서로 대체한다.
---
title: Claude 백엔드 구현 지시
project: Vision-Certi
version: 1.0
date: 2026-10-13
status: ready-for-development
owner: Claude
---

# Claude 백엔드 구현 지시

Claude는 Java/Spring 백엔드와 독립 승인·서명·전송 실행 코드를 담당한다. 공통 계약은 Codex가 관리한다. 백엔드 코드 소유 범위는 `apps/backend`이며 임의 DB migration·프런트엔드 변경은 금지한다.

## 먼저 읽을 문서

[[00_프로젝트_허브]], [[02_아키텍처_보안경계]], [[03_DB_데이터사전]], [[04_API_연동계약]], [[06_작업분해_의존성]], [[10_테스트_출시_운영]].

## 구현 순서

1. Java 21 호환 Spring Boot 4 프로젝트, Gradle wrapper, 환경별 profile, health와 표준 오류를 만든다. 실제 버전·checksum을 고정한다.
2. BFF·Identity·Membership·Document·Approval·Signature·Verification·Connector·Audit 모듈을 분리한다. 도메인 코드가 HSM SDK에 직접 의존하지 않게 한다.
3. 문서 등록과 정확한 원본 바이트의 불변 버전·SHA-256을 구현한다. 지정 의사·기관 권한을 조회부터 검사한다.
4. OIDC·MFA·세션과 기관 권한 철회가 실제로 연결된 승인 서비스를 구현한다. Core의 사용자 헤더만으로 승인하지 않는다.
5. 승인 journal·일회성 nonce·TTL·서명 Worker 점유·HSM 결과 조정을 구현한다. 완료는 증거 저장·검증까지 포함한다.
6. CA·CRL/OCSP·TSA·독립 검증·과거 시점 검증과 원본 보존 정정을 구현한다.
7. Outbox·병원 전송·업무 ACK·재시도·감사·운영 지표를 연결한다.

## 초기 개발 slice

실제 공급자 없이도 문서 등록→명시적 승인→모의 결과→상태·검증·전송 대기 화면을 실행할 수 있게 한다. in-memory 저장소를 사용하면 재시작 유실과 PostgreSQL 미연결을 README에 표시한다.

개발 헤더는 `X-Tenant-Id: demo-hospital-a/demo-hospital-b`, `X-Actor-Id: doctor-demo/admin-demo`로 통일한다. dev profile을 명시해야 활성화되고 기본·prod profile에서는 모의 인증으로 시작할 수 없어야 한다. mock 서명 결과는 `MOCK_NOT_VALID_FOR_CLINICAL_USE`이며 검증 성공으로 위장하지 않는다.

초기 필수 API: 문서 목록·등록·상세·PDF 다운로드, 서명 요청 생성·승인·상태, 서명 목록·검증, 전송 목록, 감사 목록, 대시보드. 상세 DTO는 실제 구현 전에 Codex에 공유한다.

## 반드시 지킬 조건

- 개인키를 DB·로그·파일로 저장하지 않는다. 개발 mock에서도 운영키를 생성·반입하지 않는다.
- 관리자·기관 서비스가 의사 대신 승인할 수 없다.
- 서명된 버전을 덮어쓰지 않는다. 정정 사유와 새 승인으로만 새 버전을 만든다.
- HSM timeout을 단순 재시도하지 않는다. provider 결과 조회 가능 여부를 계약으로 드러낸다.
- 검증에 실패하거나 증거가 부족하면 명확한 실패·불확실 상태를 반환한다.
- 요청 tenant·actor는 운영 인증 컨텍스트에서만 도출한다.
- 임의 URL fetch, unrestricted CORS, 운영 기본 비밀번호, verbose 환자정보 로그를 넣지 않는다.

## 시험과 보고

JUnit·Spring 테스트로 기관 격리, 관리자 승인 차단, 만료·nonce 재사용, 해시 바꿔치기, 멱등성 payload 충돌, 중복 경쟁, 서명 결과 불명 상태를 검증한다. 테스트 컨테이너가 없으면 DB 통합 시험 미실행을 명시한다.

기본 명령은 Windows `gradlew.bat test`, Linux `./gradlew test`; 개발 실행은 명시적 dev profile로 제공한다. 테스트 결과·수정 파일·실행 명령·운영 제한을 `work/claude-backend-report.md`에 남긴다. 실제로 실행하지 않은 테스트를 통과로 기재하지 않는다.

사용자는 프로젝트 파일 편집·빌드·테스트·의존성 설치의 일상 작업을 승인했다. 샌드박스 승인 절차는 그대로 따르며 데이터 삭제·외부 배포·보안 정책 완화는 수행하지 않는다.
