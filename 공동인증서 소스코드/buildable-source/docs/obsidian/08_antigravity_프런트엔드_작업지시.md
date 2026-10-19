> 2026-10-13 설계 변경: [[12_인증서등록_설계변경]]을 우선 적용한다. 관리자 사전등록 → PC 공동인증서/모바일 인증서 검증·의사 연결 → 본인 동의 → OpenBao ECDSA P-256 키·INVIZ 자체 인증서 활성화로 변경했다. Google은 보조 연동이며 기본 인증서 필수 정책을 우회하지 않는다. 아래 원문 기반 계획의 Keycloak·RSA/HSM 초기 구성과 충돌하는 부분은 이 변경서로 대체한다.
---
title: antigravity 프런트엔드 구현 지시
project: Vision-Certi
version: 1.0
date: 2026-10-13
status: ready-for-development
owner: antigravity
---

# antigravity 프런트엔드 구현 지시

antigravity는 `apps/web`을 담당한다. 백엔드·DB·공통 계약 파일을 직접 수정하지 말고 필요한 변경을 Codex에 요청한다. 한국어 관리 화면과 의사의 서명 전용 흐름을 구현한다.

## 입력

[[00_프로젝트_허브]], [[02_아키텍처_보안경계]], [[04_API_연동계약]], [[05_화면_사용자흐름]], [[06_작업분해_의존성]], [[10_테스트_출시_운영]].

## 구현 순서

1. React·TypeScript·Vite 앱, 라우팅·레이아웃·typed API client·TanStack Query를 만든다. `build`, `typecheck`, `test` 명령을 제공한다.
2. 대시보드, 기관 전환, 의사 검증, 문서 목록·업로드·상세를 구현한다. 처음에는 명시적 mock 모드로 동작을 검증한다.
3. PDF 열람, 지정 의사·버전·기관 확인, 승인 버튼, Step-up 분기, polling·응답 유실 복구를 구현한다.
4. 인증서·전송·감사·검증 화면을 붙인다. 미지원 API의 버튼은 비활성화하고 준비 중이라고 표시한다.
5. live 모드에서 실제 백엔드 DTO와 맞춘다. tenant 전환과 세션 만료 시 캐시·진행 요청을 안전하게 정리한다.

## 개발 연결

API prefix는 `/v1`, Vite proxy는 `http://localhost:8080`이다. 목록은 `{items:[]}`, 오류는 `{code,message,correlationId,retryable}`다. 상세 계약은 [[04_API_연동계약]]을 따른다.

개발 tenant·actor 헤더는 `demo-hospital-a/demo-hospital-b`, `doctor-demo/admin-demo`를 사용한다. 운영 빌드에는 개발 역할 전환·헤더 주입을 활성화하지 않는다. 실제 API 실패를 mock 성공으로 자동 대체하지 않는다.

문서 등록은 multipart `metadata` JSON과 `file` PDF다. 서명 요청 생성 후 서버의 nonce·documentSha256으로 승인한다. 해당 문서에서 받은 값만 사용하며 화면용 계산으로 해시를 재구성하지 않는다.

## UI 완료 조건

- 개발 모드에 의료용 사용 불가 표시가 항상 보인다.
- 관리자는 서명 버튼을 볼 수 없고, 지정 의사라도 문서 열람 실패·만료·권한 없음이면 승인할 수 없다.
- 연타 방지, loading, empty, error, 재시도 상태가 모두 있다.
- 서명 완료와 병원 ACK 상태가 별도 표시된다.
- 검증 결과의 불확실과 실패·성공이 명확히 구분된다.
- 토큰·PDF·의료정보가 브라우저 영구 저장소에 남지 않는다.
- 키보드로 주요 작업을 수행하고 label·focus·오류 안내를 읽을 수 있다.

단위·컴포넌트 시험은 관리자 승인 제한, tenant 변경 시 데이터 초기화, 오류 표시, 진행 중 버튼 비활성화를 포함한다. Playwright 통합 시험은 실제 서버의 개발 모드와 연결하되 서비스 mock과 실제 API를 구분해 보고한다.

빌드·typecheck·테스트 결과와 미구현 항목을 `work/antigravity-frontend-report.md`에 기록한다. 작업 중 Orca 질문은 coordinator에게 전달한다. 실제로 전달되지 않은 작업을 실행했다고 가정하지 않는다.
