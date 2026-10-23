> 최신 변경: 공동인증 유지 시간은 **72시간**입니다. 최초 PC 종료/연결 끊김 시 재인증은 유지합니다. [[PACS_72시간_계정인증_연동]] 및 [[2026-10-23_통합_초기화_웹_설치_배포]]를 따릅니다.

> 2026-10-23 현재 기준: 루트 AGENTS.md·CLAUDE.md·GEMINI.md 및 RELEASE_RUNBOOK을 공통 초기화 기준으로 사용한다. PACS 4.40.12는 최초 인증 후 최대 72시간 또는 최초 PC 종료 시 재인증한다. 24시간부터 주황색, 48시간부터 빨간색과 재인증 권고를 표시한다. [[PACS_72시간_계정인증_연동]]과 [[2026-10-23_통합_초기화_웹_설치_배포]]를 먼저 확인한다. 아래 10-13 내용은 초기 설계 이력이다.

> 2026-10-13 설계 변경: [[12_인증서등록_설계변경]]을 우선 적용한다. 관리자 사전등록 → PC 공동인증서/모바일 인증서 검증·의사 연결 → 본인 동의 → OpenBao ECDSA P-256 키·INVIZ 자체 인증서 활성화로 변경했다. Google은 보조 연동이며 기본 인증서 필수 정책을 우회하지 않는다. 아래 원문 기반 계획의 Keycloak·RSA/HSM 초기 구성과 충돌하는 부분은 이 변경서로 대체한다.
---
title: Codex 데이터와 인프라 통합 계획
project: Vision-Certi
version: 1.0
date: 2026-10-13
status: ready-for-development
owner: Codex
---

# Codex 데이터와 인프라 통합 계획

Codex는 계약·데이터·배포·통합 검증의 기준을 관리한다. Claude와 antigravity가 병렬 개발할 수 있게 공통 응답과 fixture를 먼저 제공하고, 실제 개발 산출물을 목표 운영 아키텍처와 비교한다.

## 작업 영역

| 경로 | 산출물 |
|---|---|
| contracts | OpenAPI, 예제, 상태·오류 계약, 공급자 interface 문서 |
| database | Flyway SQL, RLS·권한, 테스트 fixture, SQL 시험 |
| infra | 개발 Compose, 환경 예제, 중앙 서버 배포·백업 설명 |
| tests/integration | 두 앱·모의 병원 연결 시험 |
| tests/security | 기관 격리·replay·권한 철회·변조 시나리오 |
| tests/load | 접수·완료 latency, 큐 적체·회복 시험 |
| docs/obsidian | 사용자 지정 Obsidian에 배포할 문서 기준본 |

## 개발 환경

DB는 PostgreSQL 18, Redis는 세션·한도용, S3 호환 개발 storage는 운영 WORM 대체로 표시하지 않는다. 실제 HSM은 개발 mock 또는 SoftHSM과 분리한다. IdP·CA·TSA의 mock/real 설정을 명시하고 운영에서 mock 옵션을 발견하면 startup을 거부한다.

Compose 포트는 localhost에만 바인딩한다. 예제 자격증명은 합성 개발용으로 표시하고 운영에 복사하지 않는다. Docker가 실행되지 않으면 in-memory 앱 시험과 SQL 정적 검사를 구분해 기록한다.

## 중앙 운영 목표

API·BFF 두 인스턴스 이상, 독립 Approval 배포 권한, 서명 Worker 전용 네트워크와 HSM 접근, 전송 Worker의 병원별 목적지 제한을 적용한다. DB Primary/Standby, 버전·보존·복제 Object Storage, 별도 backup 목적지를 둔다. 인스턴스 수는 실측 결과로 조정한다.

Secret Manager 참조로 연결하고 환경 파일에 운영키를 기록하지 않는다. HSM·CA 복구 절차는 DB 백업과 별개다. 운영 서버 시간 동기화와 TSA 인증서 신뢰정책을 모니터링한다.

## CI와 검증

backend test → frontend typecheck/test/build → OpenAPI 호환성 → migration·RLS 시험 → 통합·보안 시험 순으로 수행한다. 테스트 가능한 독립 단계는 병렬 실행한다. 의존성·컨테이너 버전과 SBOM을 기록하고 비밀값 탐지를 실행한다.

DB 변경은 expand/contract 방식으로 이전 앱과 공존하도록 배포한다. 파괴적 변경은 별도 migration과 복구 계획이 필요하다. 서명·감사·보존 데이터의 삭제형 rollback은 금지한다.

## 완료 보고

[[06_작업분해_의존성]]의 티켓별 실제 상태, 실행 명령, 확인한 결과, 남은 외부 의존성을 기록한다. 앱이 in-memory라면 SQL migration 작성 완료와 앱 DB 통합 완료를 분리한다. HSM 모의 시험 통과와 실제 HSM 검증도 분리한다.

[[10_테스트_출시_운영]]의 출시 조건과 [[11_외부의존성_결정대장]]의 차단 항목이 해결되기 전에는 운영 준비 완료라고 표시하지 않는다.
