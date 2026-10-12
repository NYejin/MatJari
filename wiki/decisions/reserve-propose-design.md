---
title: 예약 제안/수락과 채팅 알림 설계 근거
type: decision
tags: [예약, 알림, 채팅, 동시성, 결정근거]
sources: [../../CLAUDE.md, chat-design.md, notification-design.md, ../gotchas/lock-order-and-index-pitfalls.md, ../glossary.md, ../../산출물/08_ERD/exchange-schema-design.md]
updated: 2026-10-13
confidence: medium
status: draft
---

# 예약 제안/수락과 채팅 알림 설계 근거

브랜치 `feature/reserve-propose`(V15, 2026-10-13 구현, 미병합)의 근거 정리다. 새 결정을 만들지 않는다. 확정 규칙 본문은 [CLAUDE.md](../../CLAUDE.md)(병합 때 갱신 예정이라 현재 서술이 낡았을 수 있다), 스키마는 [ERD 설계 문서](../../산출물/08_ERD/exchange-schema-design.md), 용어는 [용어집](../glossary.md)이 기준이다. 출처는 구현 세션이 정리한 사실 메모와 설계 원문(저장소 밖 임시 파일)이며 위키에는 요약만 둔다. 테스트 수치는 구현 세션 기록이라 재실행하지 않았다.

## 사용자가 정한 결정 (2026-10-12 지시)

1. **예약을 '제안 → 상대 수락'으로 바꾼다.** 교환 수락처럼 쌍방 합의가 돼야 RESERVED가 된다.
2. **예약하기 버튼은 채팅 화면에만 둔다.** 내 매칭 카드에서는 제안 버튼을 빼고 제안 여부 문구와 '채팅' 버튼만 둔다. RESERVED 카드의 예약 취소·교환 수락·거절/종료 제한 안내는 유지.
3. **채팅 새 메시지도 헤더 알림 벨에 표시한다.**
4. **벨 위치**: 데스크톱(가로 메뉴)은 '내 매칭' 바로 오른쪽, 햄버거 폭에서는 햄버거 왼쪽. 벨 인스턴스는 하나만 두고 폭에 따라 위치만 옮긴다.

## 이전 결정을 뒤집은 점

- 이전에는 "양쪽이 모두 눌러야 예약(`accept`)"을 폐기하고 "한 명이 예약하면 즉시 RESERVED"를 택했다(V8). 이번 지시로 흐름이 **제안 + 수락**으로 바뀌었다. 겉보기에는 폐기안으로 돌아간 것 같지만 차이가 있다. 예전 안은 양쪽 누름을 `a/b_reserved_at` 두 컬럼으로 관리하는 상태였고, 이번 안은 상태값을 늘리지 않고 한쪽 제안을 매칭의 부가 정보로 두며 수락 순간 기존 예약 로직(잠금)을 그대로 쓴다.
- 이전 [채팅 설계](chat-design.md)의 "새 메시지에 알림 행·헤더 표시 없음"도 뒤집혔다(결정 3). 해당 서술은 이 문서 기준으로 정정했다.

## 상태를 늘리지 않고 부가 정보로 둔 이유

- 새 상태값(`PROPOSED` 등)을 만들면 `open_flag`(CHATTING·RESERVED) 생성 컬럼 유니크, 후보 SQL, 취소·차단·정지 경로, 프런트 분기가 모두 영향을 받는다. 상태는 `CHATTING → RESERVED` 그대로 두고 `exchange_match.reserve_proposed_by_id`·`reserve_proposed_at`(NULL 허용)만 얹었다. (비교는 설계 원문 기준이며, 상태 추가 안을 실제로 구현해 본 것은 아니다.)
- CHECK 3개로 불변식을 DB에 둔다: 두 컬럼은 함께 NULL이거나 함께 NOT NULL, **CHATTING이 아니면 둘 다 NULL**, 제안자는 a 또는 b.
- 그 결과 **CHATTING을 벗어나는 모든 UPDATE는 같은 문장에서 제안 컬럼을 NULL로 만들어야 한다.** 안 그러면 CHECK 위반이다. 3절 `reserved_by_id` 규칙과 같은 패턴이다([함정 3절](../gotchas/lock-order-and-index-pitfalls.md)). 취소·거절·종료·요청 수정/삭제·티켓 내리기·차단·정지는 제안이 걸려 있어도 동작해야 하므로 모두 이 규칙을 따른다. 벌크 JPQL 경로는 엔티티 로직을 우회해서 따로 챙겨야 했다([함정 13절](../gotchas/lock-order-and-index-pitfalls.md)).
- FK는 `reserved_by_id`와 같은 방식(ON DELETE/UPDATE 동작 없음, 단일 컬럼 인덱스)이다. 단일 컬럼 인덱스 원칙은 [함정 2절](../gotchas/lock-order-and-index-pitfalls.md).
- `reserved_by_id`의 의미는 '예약 제안자'로 바뀐다(수락 시 제안자가 들어간다). 응답의 `reservationProposal`(`NONE`/`MINE`/`THEIRS`)은 호출자 기준이고 RESERVED·종료는 NONE이다.

## 잠금 설계: 제안은 match만, 수락만 티켓

- 제안·철회·거절은 **match 행만 `FOR UPDATE`** 로 잠그고 티켓은 잠그지 않는다. 같은 쌍의 동시 제안은 이 잠금으로 직렬화되어 한쪽만 성공한다.
- 티켓이 이미 다른 매칭에서 잠겼는지는 제안 시점에도 미리 걸러 409 `TICKET_ALREADY_RESERVED`를 낸다(잠금이 아니라 사전 점검이므로 수락 때 다시 판단한다).
- **수락만** 기존 reserve 잠금 로직(티켓 id↑ → 요청 id↑ → 매칭, `exchange_ticket_lock` 2행)을 쓴다. 기존 코드를 재사용하고 분기만 추가해 잠금 순서 규약([함정 1절](../gotchas/lock-order-and-index-pitfalls.md))을 새로 만들지 않았다.
- 알림은 트랜잭션 맨 끝에서 쓰고 푸시는 afterCommit 신호다([알림 설계](notification-design.md), [함정 8절](../gotchas/lock-order-and-index-pitfalls.md)). 상태가 실제로 바뀐 경우에만 만든다.

## 알림: 새 유형과 (수신자, 매칭)당 1행 재사용

- 새 type 3종: `RESERVATION_PROPOSED`(제안받은 쪽), `RESERVATION_REJECTED`(거절 → 제안자), `CHAT_MESSAGE`(상대). `MATCH_RESERVED`는 의미가 '상대가 예약을 수락'(제안자에게)으로 바뀐다. 철회는 알림이 없다. type CHECK는 6종이고 `ck_notification_chat_dedup`이 CHAT_MESSAGE의 `dedup_key` 형식을 강제한다.
- **채팅과 예약 제안 알림은 (수신자, 매칭)당 1행을 재사용한다**(`CHAT_MESSAGE:{matchId}:{recipientId}`, `RESERVATION_PROPOSED:{matchId}:{recipientId}`). 보낼 때마다 행을 읽지 않음·최신 시각으로 되살린다. 이유: 채팅 메시지마다, 또 제안→철회 반복마다 행이 쌓이면 무제한 증가한다(검증 지적과도 연결됨). 이전 설계의 "예약 계열은 dedup_key NULL(반복마다 1건)"은 RESERVATION_PROPOSED에 한해 바뀌었다. `MATCH_RESERVED`·`RESERVATION_REJECTED`·`RESERVATION_CANCELED`는 여전히 반복마다 1건이다.
- **PK를 먼저 잠그는 upsert로 결정**: 순수 `INSERT ... ON DUPLICATE KEY UPDATE`는 읽은 알림 삭제와 실제 MySQL에서 교착했다. upsert는 `uk_notification_dedup`을 먼저, 삭제는 PK를 먼저 잠가 순서가 반전된 탓이다. 그래서 먼저 PK 기준 UPDATE(읽지 않음으로 되돌리고 `created_at`을 now로)를 하고, 행이 없을 때만 upsert한다. 재현과 설명은 [함정 13절](../gotchas/lock-order-and-index-pitfalls.md).
- 되살린 알림이 최신순 맨 위에 오도록 목록·말풍선 정렬을 `ORDER BY created_at DESC, id DESC`로 바꿨다.
- 채팅 알림 읽음: 읽음 포인터가 앞으로 움직일 때 같은 수신자·매칭의 CHAT_MESSAGE 알림을 읽음 처리한다(READ_COMMITTED, 한 행). 포인터 advance는 매칭·users 잠금 트랜잭션 안에서 호출하지 않는 기존 규칙을 지킨다([함정 10절](../gotchas/lock-order-and-index-pitfalls.md)). **읽음 포인터 뒤에 상대 메시지가 남아 있으면 읽음 처리하지 않는 NOT EXISTS 가드**를 둔다. 읽는 사이 새 메시지가 도착해 되살린 알림을 잘못 읽음 처리하지 않기 위해서다.
- 프런트 문구와 클릭 이동(CHAT_MESSAGE·RESERVATION_PROPOSED는 채팅 화면, 나머지는 내 매칭 카드)은 코드에서 알 수 있어 생략한다. 상대의 제안·수락·거절·취소는 STOMP 알림 신호와 폴링 폴백으로 채팅 화면에 반영한다(연결을 늘리지 않고 공유 연결 사용).

## 택하지 않은 대안

| 안 | 택하지 않은 이유 |
|---|---|
| 새 매칭 상태(`PROPOSED`) 추가 | 위 '상태를 늘리지 않고' 절. 영향 범위가 크다 |
| 채팅 메시지마다 알림 1건씩 쌓기 | 무제한 증가, 벨이 메시지 수만큼 늘어남 |
| 순수 `ON DUPLICATE KEY UPDATE` upsert만 | 읽은 알림 삭제와 교착(실제 MySQL에서 재현) |
| 제안 시점에 티켓 잠금 | 제안은 가볍게 두고 잠금은 확정(수락) 시점에만. 제안·철회 반복이 티켓 경쟁에 끼지 않는다 |
| 예약-취소 남용 방지 장치 | 기존 결정대로 두지 않고 차단·신고로 다룬다 |

## 검증 지적과 반영

- 구현 세션 기록: Fable 검증에서 높음 없음, 중간 2건(되살린 알림 정렬, 제안 알림 무제한)·낮음 6건이 있었고 모두 반영했다(낮음 6건의 내용은 이번 입력에 없다). 중간 2건은 위 정렬 변경과 RESERVATION_PROPOSED 재사용으로 반영됐다.
- 동시성 IT(`ReserveProposalMysqlTest`): 동시 제안, 제안 대 철회, 수락 대 거절·철회, 같은 티켓을 쓰는 두 매칭의 수락 경합, 수락 대 취소·차단·정지·티켓 내리기·요청 삭제, 예약 취소 대 재제안, 채팅 전송·읽음·삭제 혼합에서 교착 0, 불변식(잠금 행 = 2×RESERVED, 제안은 CHATTING에만) 유지. 브라우저(Playwright)로 채팅 알림 실시간, 제안→거절→재제안→수락→예약 취소, 카드의 예약 버튼 제거, 벨 위치를 확인했다.

## 알려진 한계

- 예약-취소 남용 방지가 없다(차단·신고로 다룸).
- 알림 정렬용 `(user_id, created_at)` 인덱스가 없다. 알림이 매칭당 1행 수준이라 불필요하다고 판단했으나 실측하지 않았다(추정).
- 읽음 가드와 알림 되살림이 겹치는 극히 약한 창이 있다.
- 일부 경로는 REPEATABLE READ 패턴을 유지한다.
- 병합 전이라 `CLAUDE.md`와 일부 위키 페이지의 예약 서술이 아직 이전 흐름일 수 있다. 용어집의 '예약 중' 행은 이 문서에 맞춰 고쳤다.
