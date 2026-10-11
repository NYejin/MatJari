---
title: 용어집
type: glossary
tags: [용어, 교환, 매칭]
sources: [SeatSwap/backend/src/main/resources/db/migration/V11__chat_message.sql, SeatSwap/backend/src/main/resources/db/migration/V9__exchange_complete_history.sql, CLAUDE.md, 산출물/04_요구사항정의서/후속요구사항_제안알림_교환됨_공연정보입력.md, 산출물/08_ERD/exchange-schema-design.md, SeatSwap/backend/README.md, SeatSwap/backend/src/main/resources/db/migration/V3__ticket_seat_columns.sql, SeatSwap/backend/src/main/resources/db/migration/V5__exchange_match_tables.sql, SeatSwap/backend/src/main/resources/db/migration/V7__exchange_request_soft_delete.sql, SeatSwap/backend/src/main/resources/application.yml]
updated: 2026-10-11
confidence: high
status: draft
---

# 용어집

SeatSwap 고유 용어의 짧은 정의다. 확정 결정 본문은 [CLAUDE.md](../CLAUDE.md)에 있고 여기서는 뜻과 코드 위치만 적는다. 근거와 함정은 [교환 완료 시 티켓 처리](decisions/exchange-complete-new-ticket.md), [잠금 순서와 인덱스·유니크 키 함정](gotchas/lock-order-and-index-pitfalls.md) 참고. 상세 표는 요구사항 문서 `산출물/04_요구사항정의서/후속요구사항_제안알림_교환됨_공연정보입력.md` §3.1.

## 매칭 상태

| 용어 | 내부 값 | 뜻 | 위치 |
|---|---|---|---|
| 매칭 | `CHATTING` | 후보 목록에서 한쪽이 상대를 골라 제안하면 생기는 채팅 단계. 한 요청에 여러 개 동시에 열 수 있다. 화면 라벨은 현재 '진행 중'이고 '매칭'으로 바꾸는 것은 문서의 기본안이다 | `ExchangeMatchStatus`, 테이블 `exchange_match` |
| 예약 중 | `RESERVED` | 둘 중 한 명이 예약하면 되는 상태(8차·9차 답변, V8). 두 티켓이 잠긴다. 예약을 취소하면 `CHATTING`으로 돌아간다. 구현 완료 | `reserve`/`unreserve` API, `reserved_by_id`·`reserved_at` |
| 교환 수락 | (버튼 이름) | 화면 버튼 이름만 바뀐 것이다. 내부 값·API는 `complete`, 컬럼은 `a_completed_at`/`b_completed_at` 그대로. 구현 완료(V9, 커밋 `52cfb5a`). 첫 수락은 시각만 기록하고 RESERVED를 유지하며, 화면에서 확인 단계를 한 번 거친다 | 요구사항 문서 §3, 9차 답변 |
| 교환 완료 | `COMPLETED` | 양쪽이 '교환 수락'을 모두 누른 상태. 상태·뱃지·이력 라벨은 '교환 완료'를 유지한다. 취소 불가. 두 번째 수락 때 한 트랜잭션으로 전이한다(구현 완료). 완료 시 기존 티켓의 교환 요청은 `CLOSED`가 되고 그 티켓의 다른 채팅은 자동 취소하지 않는다(Q-15 확정) | `complete` API, `exchange_history` |
| 교환됨 | `TicketStatus.EXCHANGED` | 교환 완료 때 기존 티켓에 붙는 티켓 상태. 구현 완료(V9에서 `ck_ticket_status` 확장). 이 상태인 티켓은 내릴 수 없고(409 `TICKET_EXCHANGED`) 새 예약도 막힌다. 이 상태인 티켓의 다른 채팅 카드는 버튼 비활성 + "이미 교환된 좌석이에요" 표시 | [근거 페이지](decisions/exchange-complete-new-ticket.md) |
| 취소됨 | `CANCELED` | 채팅 종료(`cancel`), 받은 쪽 거절(`reject`), 시스템 취소로 끝난 매칭. 취소는 재매칭 불가가 아니다. 단 `RESERVED`에서는 먼저 예약을 취소해야 한다 | `canceled_by_id`, `canceled_at` |
| 시스템 취소 | `canceled_by_id` NULL | 사용자가 아니라 서비스가 취소한 경우. 티켓 내리기, 요청 수정·삭제 때 `CHATTING` 매칭에 적용된다. 응답의 `canceledBy`는 `SYSTEM` | README "연동 규칙" |
| 예약 잠금 | `exchange_ticket_lock` | 예약된 두 티켓에 한 행씩 INSERT하는 테이블. PK가 `ticket_id`라 한 티켓은 동시에 한 매칭에서만 예약된다. 해제는 DELETE | V5, 설계 문서 3.2절 |

## 교환 요청과 희망 조건

- **교환 요청 / 희망 조건** (`exchange_request`): 티켓 하나의 교환 희망 1건. 상태는 `OPEN`, `CLOSED`(티켓을 내렸거나 종료), `DELETED`(소프트 삭제). 미삭제 요청은 티켓당 1개다.
- **희망 범위** (`exchange_want_range`): 사용자가 입력한 구역·열 범위·번 범위. 추가금 유형이 범위마다 붙는다(V6).
- **펼친 희망 좌석** (`exchange_want_seat`): 범위를 개별 좌석으로 펼쳐 저장한 파생 데이터. 후보 판정의 점조회 대상이다.
- **희망 회차와 우선순위** (`exchange_want_session`): 교환을 원하는 회차와 사용자가 정한 우선순위. 후보 정렬이 이 우선순위를 따른다 (점수·랭킹 없음).
- **안전 상한**: 요청당 펼친 희망 좌석 5,000석, 범위 50개. 설정 `exchange.want.max-seats`/`max-ranges` (`application.yml`), 초과 시 422 `WANT_SEAT_LIMIT_EXCEEDED`/`WANT_RANGE_LIMIT_EXCEEDED`. 상한 판정은 합집합 기준이다.

## 추가금

- 유형 4종: `X`(추가금 없음), `ANY`(상관없음), `POS`(받아야만 교환, 금액 양수), `NEG`(낼 의향, 금액 음수). 판정은 유형만 보며 금액은 계산에 쓰지 않는다.
- 부호 규약: `+`는 내가 받을 금액, `-`는 내가 낼 수 있는 금액. 불성립 조합은 POS-POS와 POS-X뿐이다. 판정 규칙 본문은 [CLAUDE.md](../CLAUDE.md)의 6차 확정, 7차 답변 참고.
- `settlementHint`: 후보 응답의 참고 표시. 한쪽 POS와 다른 쪽 NEG에 둘 다 금액이 있고 낼 수 있는 최대가 받을 최소 이상이면 `{min, max}`, 아니면 null. 매칭 여부와 무관하다.
- **후보**: 서로 조건이 맞는 상대 요청. 조회는 `GET /api/exchange/requests/{id}/candidates`이고 선택은 사용자가 한다.

## 알림

설계 근거는 [알림 설계](decisions/notification-design.md), 쓰기 규칙은 [함정 페이지 8절](gotchas/lock-order-and-index-pitfalls.md) 참고.

- **알림 종류 3종** (`notification.type`, V10 CHECK): `PROPOSAL_RECEIVED`(새 제안, 수신자는 제안받는 쪽), `MATCH_RESERVED`(상대가 예약), `RESERVATION_CANCELED`(상대가 예약 취소). 뒤의 둘은 수신자가 호출자 반대편이다.
- **dedup_key**: 중복 생성을 막는 단일 컬럼 유니크 키. 새 제안만 `PROPOSAL_RECEIVED:{matchId}` 값을 갖고, 예약·예약 취소는 NULL이라 반복마다 매번 알림 1건이 쌓인다.
- **안 읽음**: `read_at`이 NULL인 알림. 안 읽은 개수는 생성 컬럼 `unread_user_id`로 센다. 닫힌 매칭의 알림도 센다.
- **종료된 매칭 라벨**: 매칭이 CANCELED·COMPLETED가 된 알림에 붙는 회색 라벨(응답 `matchClosed`). 알림을 숨기지 않는다.

## 티켓과 마감

- **활성 티켓**: `ticket.status = 'ACTIVE'`. 생성 컬럼 `active_flag`가 1이며 같은 회차·좌석의 활성 티켓은 1개만 허용된다 (`uk_ticket_active_seat`, V3).
- **소프트 삭제**: 티켓은 사용자가 내리면 `INACTIVE`(행은 남음, `DELETE /api/tickets/{id}`). 교환 요청은 삭제하면 `DELETED` + `deleted_at` (V7). 둘 다 물리 삭제가 아니다.
- **회차 당일 마감**: 회차 당일 끝(다음날 0시 KST)까지 티켓 등록·매칭을 허용하고 이후 자동 비활성하는 정책이다. 후보 SQL은 `starts_at >= 오늘 0시`로 같은 뜻을 표현한다. 이미 시작한 채팅의 예약·취소에는 마감 검사를 하지 않는다. 자동 비활성 스케줄러는 아직 없다 (README "다음 단계").

## 채팅

설계 근거는 [채팅 설계 근거](decisions/chat-design.md), 함정은 [함정 페이지 10절](gotchas/lock-order-and-index-pitfalls.md) 참고. 구현 완료(V11, 브랜치 `feature/chat`, 미병합).

- **읽음 포인터** (`chat_read_state.last_read_message_id`): (매칭, 사용자)당 1행으로, 그 사용자가 읽은 마지막 메시지 id다. 단조 증가만 하며 FK가 아니다. 안 읽은 개수(`unreadChatCount`)와 상대의 '읽음' 표시가 이 값으로 계산된다.
- **clientMessageId**: 클라이언트가 보내는 8~36자 식별자(`^[A-Za-z0-9_-]{8,36}$`). (`clientMessageId`, 발신자) 유니크로 같은 전송의 재전송을 멱등 처리한다(같은 id 재전송은 200). 다른 매칭에서 쓴 id는 409 `CLIENT_MESSAGE_ID_REUSED`.
- **상태 배너**: 채팅 화면 상단에 매칭 상태(예약 중, 교환된 좌석, 종료 등)를 보여주는 표시. 시스템 메시지를 저장하지 않고 이 배너로 대신한다.
- **읽기 전용 채팅**: `CANCELED`·`COMPLETED` 매칭의 채팅. 참여자는 열람할 수 있고 전송은 409 `MATCH_STATE_CONFLICT`다. 입력창은 비활성이다.
- **채팅 사용자 큐**: STOMP 수신 전용 목적지 `/user/queue/chat`. 푸시 종류는 `MESSAGE`와 `READ` 두 가지다.
