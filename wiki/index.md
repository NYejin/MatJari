---
title: 위키 목차
type: index
tags: [index]
sources: []
updated: 2026-10-13
confidence: high
status: draft
---

# 위키 목차

SeatSwap 프로젝트의 결정 근거, 함정, 조사 결과, 용어를 모은 위키다. 사용법과 스키마는 [llm-wiki 스킬](../.claude/skills/llm-wiki/SKILL.md), 작업 기록은 [log.md](log.md)에 있다. 확정 결정 본문은 [CLAUDE.md](../CLAUDE.md)가 기준이다.

## 결정

- [교환 완료 시 티켓 처리: 안 2의 근거](decisions/exchange-complete-new-ticket.md): 교환 완료 때 기존 티켓을 `EXCHANGED`로 바꾸고 새 티켓을 만드는 안의 선택지와 이유, Q-15(기존 티켓의 요청 CLOSED, 다른 채팅 유지) 확정 근거, 구현 완료 후 flush 순서 함정.

- [알림 3종 설계 근거](decisions/notification-design.md): 동기 INSERT 대 커밋 후 이벤트 비교와 선택 이유, dedup_key, 닫힌 매칭 알림·화면 구성·실시간 분리의 사용자 결정 3개, 실시간 푸시(신호만 싣는 payload·폴링 완화).

- [채팅 설계 근거 (REST 전송 + STOMP 수신)](decisions/chat-design.md): REST 전송 대 STOMP 전송, 읽음 포인터 대 메시지별 read_at, 시스템 메시지 저장 대 상단 배너, 종료 후 읽기 전용. 사용자 결정 4개 명시.

- [링크 기반 공연정보 자동 입력 설계 근거](decisions/link-autofill-design.md): 3개 사이트 실측, 고정 템플릿 URL로 SSRF를 구조적으로 막은 이유, 회차 시각 미지원, 사용자 URL 요청+IP 고정 대안을 택하지 않은 이유, 한계.

- [관리자 페이지 설계 근거](decisions/admin-design.md): 범위 확정 과정("다"의 해석), 수정 대상 제목·공연장만, 승인=기존 티켓 내림, 사진 처리 후 삭제, 알림 테이블 미사용 이유, 택하지 않은 대안, 한계.

- [차단·신고·이용 정지 설계 근거](decisions/block-report-design.md): 차단 효과 양방향·비통지, 신고 자동 제재 없음, Q-21 확정 범위, 정지 강제를 loadUserById 한 곳에서 한 이유와 STOMP 한계, 열린 매칭 시스템 취소 설계, 택하지 않은 대안, 한계.

- [예약 제안/수락과 채팅 알림 설계 근거](decisions/reserve-propose-design.md): '한 명이 예약'을 '제안→수락'으로 뒤집은 사용자 결정, 상태 대신 부가 컬럼으로 둔 이유, 제안은 match만·수락만 티켓 잠금, 채팅·제안 알림 1행 재사용과 PK 먼저 upsert, 읽음 가드, 택하지 않은 대안, 한계.

- [소셜 로그인 설계 근거](decisions/social-login-design.md): oauth2-client 대신 직접 구현한 이유, 자동 연결 안 함, 쿠키 state·일회용 코드·프래그먼트 전달, 자리표시 이메일, 검증 지적과 수정, 실제 제공자 키 미검증 등 한계.

- [프로필 수정·소셜 계정 연결 설계 근거](decisions/profile-edit-design.md): 콜백이 저장하지 않고 로그인 세션이 확정하는 2단계를 택한 이유(강제 연결 로그인 CSRF), `password_login_enabled`로 마지막 로그인 수단을 판단한 이유, 자동 연결을 되살리지 않은 이유, 택하지 않은 대안, 한계.

- [회원탈퇴·개인정보처리방침 설계 근거](decisions/withdraw-privacy-design.md): 익명화 범위와 이유(채팅·교환 이력·신고·제재는 보존), 물리 삭제·채팅 삭제 등 대안 비교, 탈퇴 잠금 순서를 users 먼저에서 뒤로 바꾼 이유, 보관 기간 조사 요약, 한계 8개와 추가 관찰.

- [공연 포스터 표시 설계 근거](decisions/poster-display-design.md): 서버가 이미지를 저장·프록시하지 않고 주소만 읽어 인라인 링크로 보여 주는 이유, DB에 저장하지 않고 재조회한 이유, 택하지 않은 대안, 로드 실패 시 숨김과 대체 링크 없음, 저작권 조사 요약과 미확인 항목, 한계.

## 함정

- [프런트 함정](gotchas/frontend-react-pitfalls.md): StrictMode에서 중단된 요청이 가드를 풀지 못해 로딩에 머무는 문제(신고 상세·소셜 콜백), 객체 키 조회의 `__proto__` 함정.

- [링크 가져오기 함정](gotchas/link-fetch-pitfalls.md): 티켓팅 사이트 robots.txt가 오류 페이지를 돌려주는 경우, Java HttpClient는 IP 고정이 안 되는 점, 포스터 주소의 noimage 자리표시와 핫링크 차단.

- [잠금 순서와 인덱스·유니크 키 함정](gotchas/lock-order-and-index-pitfalls.md): 교환 도메인(MySQL)의 잠금 순서 규약, 인덱스·유니크 키로 겪은 교착, Hibernate flush 순서와 유니크 키, 알림 쓰기 규칙과 dedup_key 함정, 교환 완료 갭 잠금 교착 가능성, 채팅 전송 잠금·읽음 포인터 교착·조회 힌트·속도 제한 함정, 관리자 기능의 좌석 키 보조 인덱스 FOR UPDATE 교착과 PENDING 전용 생성 컬럼 유니크, 차단 후보 제외 NOT EXISTS 분리, READ_COMMITTED 활성 제재 중복 직렬화, users 잠금 금지, 예약 제안/수락의 upsert 대 삭제 교착(PK 먼저)과 벌크 JPQL의 제안 컬럼 우회, 프로필·소셜 연결(V16)의 users 잠금 순서와 확정 코드 소비 순서, 회원 탈퇴(V17)의 users 잠금 위치와 교착 창 2개.

## 조사

- [실시간 채팅(STOMP) JWT 인증·인가 조사](research/stomp-jwt-auth.md): CONNECT 인증, 토큰 만료, 구독·전송 인가, Origin·크기 제한. 조사 요약에 구현 후 확인 결과(CSRF·Origin 403·1008·RECEIPT 없음)를 덧붙였다.

## 용어집

- [용어집](glossary.md): 매칭 상태, 교환 요청·희망 조건, 추가금, 티켓과 마감, 알림, 채팅, 소셜 연결 등 고유 용어.
