---
title: 잠금 순서와 인덱스·유니크 키 함정
type: gotcha
tags: [mysql, 교착, 인덱스, flyway, 동시성]
sources: [SeatSwap/backend/src/main/resources/db/migration/V9__exchange_complete_history.sql, 산출물/08_ERD/exchange-schema-design.md, SeatSwap/backend/README.md, SeatSwap/backend/src/main/resources/db/migration/V3__ticket_seat_columns.sql, SeatSwap/backend/src/main/resources/db/migration/V5__exchange_match_tables.sql, SeatSwap/backend/src/main/resources/db/migration/V7__exchange_request_soft_delete.sql, SeatSwap/backend/src/main/resources/db/migration/V8__exchange_match_single_reserve.sql, SeatSwap/backend/src/main/resources/db/migration/V11__chat_message.sql, CLAUDE.md]
updated: 2026-10-13
confidence: medium
status: draft
---

# 잠금 순서와 인덱스·유니크 키 함정

교환 도메인(MySQL 8, InnoDB)에서 겪었거나 설계로 막은 함정 모음이다. 코드를 읽으면 알 수 있는 세부는 생략하고 이유와 재현 조건만 둔다. 용어는 [용어집](../glossary.md), 티켓 처리 순서와 유니크 키의 관계는 [교환 완료 시 티켓 처리](../decisions/exchange-complete-new-ticket.md) 참고.

## 1. 잠금 순서 규약

- 항상 티켓(id 오름차순), 요청(id 오름차순), 매칭 순이다 (`SeatSwap/backend/README.md` "동시성" 절).
- 요청만 잠그는 update/delete 경로에서는 티켓을 잠그지 않는다 ([CLAUDE.md](../../CLAUDE.md)의 교환 희망 조건 항목에도 같은 규약이 적혀 있다).
- `users` 행은 티켓 이후에 잡는다. 새 코드에서 users 행을 X 잠금한 뒤 티켓·요청을 잠그지 않는다. `TicketService.create`는 users 행을 먼저 잡고 티켓은 INSERT만 하므로 잠금 순서 규약 안에서는 순환이 없다. 다만 교환 완료의 새 티켓 INSERT와는 갭 잠금으로 엇갈릴 수 있다(8절). 차단(`user_block`)·제재 서비스도 users X 잠금 뒤에 티켓·요청·매칭을 잠그지 않는다(12절).
- 잠금 읽기가 트랜잭션의 첫 쿼리들이 되게 한다. 불변 컬럼(티켓 id, 사용자 id)은 트랜잭션 밖에서 먼저 읽어 잠금 대상을 정한다. REPEATABLE READ 스냅샷이 잠금 뒤에 시작해야 최신 상태를 본다.

## 2. FK 인덱스에 갱신 컬럼을 붙이면 교착 (실제 재현)

- 증상: V5 작업 중 동시성 테스트에서 교착이 재현됐다. 초안의 `(ticket_a_id, status)` 같은 복합 인덱스가 FK 인덱스로 쓰이면, `status`만 바꾸는 UPDATE(티켓 내림에 의한 시스템 취소 등)도 InnoDB가 FK 인덱스 변경으로 보고 부모 행(`ticket`, `users`)에 S 잠금을 건다. 그러면 잠금 순서 밖에서 다른 티켓 행을 잡게 되어 양쪽 수락 경로와 교착이 났다.
- 해결: FK가 쓰는 인덱스는 단일 컬럼으로 둔다 (`idx_exchange_match_ticket_a (ticket_a_id)` 등, V5). 상태 필터가 필요하면 별도 인덱스로 분리한다. 규칙 출처는 설계 문서 3.4절.
- V7의 유니크 키 `uk_exchange_request_live_ticket (live_flag, ticket_id)`: 앞자리를 `live_flag`로 둔 이유가 같다. `(ticket_id, live_flag)` 순서였다면 그 인덱스가 FK 인덱스로 쓰여 DELETED 전환 때 `live_flag`가 바뀌며 부모 `ticket` 행에 S 잠금이 걸려 티켓, 요청 순서 밖에서 교착할 수 있다. `ticket_id`가 선두가 아니면 FK 인덱스가 될 수 없다. 그래서 FK 전용 `idx_exchange_request_ticket (ticket_id)`를 따로 둔다.
- V8의 `reserved_by_id` FK도 단일 컬럼 인덱스(`idx_exchange_match_reserved_by`)다. 이 FK에는 ON DELETE/UPDATE 동작을 붙이지 않았다 (CHECK에 쓰이는 컬럼이라 MySQL이 거부한다고 V8 주석에 적혀 있다).
- `reserved_by_id` 갱신은 users 행에 S 잠금을 걸지만, 잠금 순서의 맨 끝이고 users 행을 X로 잡고 매칭을 기다리는 곳이 없어 순환이 없다 (README).

## 3. 생성 컬럼 + UNIQUE로 "활성 행만 유일" 표현

생성 컬럼이 조건을 만족하면 1, 아니면 NULL이고, MySQL 유니크는 NULL을 비교에서 제외하는 성질을 쓴다. 락·갭 락 없이 DB가 보장하고 소프트 삭제와 잘 맞는다 (설계 문서 3.1절의 비교표에서 추천안).

| 생성 컬럼 | 유니크 키 | 보장 |
|---|---|---|
| `ticket.active_flag` (`status='ACTIVE'`일 때 1) | `uk_ticket_active_seat (회차, 구역키, 열키, 번키, active_flag)` (V3) | 같은 회차·좌석의 활성 티켓 1개 |
| `exchange_request.live_flag` (`status<>'DELETED'`일 때 1) | `uk_exchange_request_live_ticket (live_flag, ticket_id)` (V7) | 티켓당 미삭제 요청 1개, 삭제 후 새 요청 가능 |
| `exchange_match.open_flag` (CHATTING·RESERVED일 때 1) | `uk_exchange_match_open_pair (request_low_id, request_high_id, open_flag)` (V5) | 같은 요청 쌍의 열린 매칭 1개 (방향 무관, LEAST/GREATEST로 정규화) |

함정:
- 매칭 쌍 유니크는 a, b 순서가 달라도 같은 쌍으로 잡도록 `LEAST`/`GREATEST` 생성 컬럼을 쓴다.
- 티켓 상태에 새 값(`EXCHANGED`)을 추가하면 `active_flag`는 NULL이 되어 자동으로 슬롯을 비운다. `ck_ticket_status`(V3는 `ACTIVE`·`INACTIVE`만 허용)는 V9에서 `EXCHANGED`를 허용하도록 확장됐다.
- 한 티켓이 어떤 매칭에서는 a측, 다른 매칭에서는 b측일 수 있어 매칭 테이블의 유니크 하나로는 티켓당 예약 1개를 못 막는다. 그래서 `exchange_ticket_lock`의 PK(`ticket_id`)를 쓴다 (설계 3.2절).
- 생성 컬럼은 UPDATE 대상이 아니다. 요청 CLOSED 처리 쿼리는 `status = OPEN` 조건을 유지해야 한다. 없으면 DELETED 요청을 되살려 `live_flag`가 1이 되고 유일 키를 위반할 수 있다 (`ExchangeRequestRepository` 주석).
- RESERVED를 벗어나는 모든 UPDATE는 같은 문장에서 `reserved_by_id`·`reserved_at`을 NULL로 만들어야 `ck_exchange_match_reserved`를 통과한다 (V8 주석).

## 4. 후보 조회 SQL: STRAIGHT_JOIN

- 사정: 힌트 없이는 요청이 100~1,000건일 때도 옵티마이저가 상대 요청(`exchange_request`)을 `type=ALL`로 전체 스캔하며 시작했다. 작업량이 전체 요청 수에 비례한다.
- 해결: 조인 순서를 내 요청, 내 희망 회차·좌석, 상대 티켓, 상대 요청, 상대 희망 회차·좌석으로 고정했다. 작업량은 내 희망 좌석 수 x 희망 회차 수에 비례하고 전체 요청 수의 영향은 거의 없다.
- 트레이드오프: 희망 좌석이 아주 많은 요청은 약간 느리다 (README 실측: 희망 2,400석, 요청 1,000건에서 DB 시간 12ms에서 27ms). 희망 좌석이 적은 요청은 8ms에서 1ms로 빨라진다. 통계가 바뀌어도 계획이 흔들리지 않는 쪽을 택했다.
- 후보가 수천 건을 넘거나 서버 시간이 기준(중앙값 300ms, p95 1초)을 넘으면 keyset 페이징으로 전환을 재검토한다. 기준 전문은 README "STRAIGHT_JOIN 유지" 절.

## 5. 로컬 개발 DB(3306) 오염 방지

- `./gradlew bootRun`은 `backend/.env`를 읽어 환경변수를 주입하며, `build.gradle`의 `bootRun` 태스크가 `environment(key, value)`로 주입하므로 셸에서 직접 지정한 환경변수(DB 주소 등)보다 `.env` 값이 우선한다고 본다 (태스크 환경에 설정되는 방식이라는 점은 코드로 확인했고, 실제 덮어쓰기 재현은 하지 않았다). 임시 DB로 검증하려다 개발 DB(3306)에 붙는 사고가 이 경로로 난다.
- 그래서 임시 DB 검증은 `bootRun`을 쓰지 않는다. `./gradlew bootJar` 후 `java -jar`에 `SPRING_DATASOURCE_URL` 등 환경변수를 직접 지정하고, 임시 MySQL 컨테이너(예: 포트 13306~13310)를 쓰고, 끝나면 컨테이너를 삭제한다. 앱 종료는 해당 프로세스 PID만 한다. 절차 전문은 `SeatSwap/backend/README.md` "수동 검증 시나리오" 절.
- 실제 MySQL 통합 테스트(`ExchangeCandidateQueryTest`, `ExchangeMatchMysqlTest`)는 `SEATSWAP_IT_*` 환경변수가 있을 때만 돈다. localhost와 DB 이름 `_it` 접미사를 확인한 뒤에만 Flyway clean/TRUNCATE를 하는 안전장치가 있다 (README).
- 이전 스키마가 남은 로컬 DB는 `docker compose down -v`로 비운 뒤 적용한다 ([CLAUDE.md](../../CLAUDE.md)).

## 6. 적용된 Flyway 마이그레이션은 수정하지 않는다

- Flyway는 체크섬을 저장하므로 적용된 파일을 고치면 기동 시 체크섬 불일치로 앱이 뜨지 않는다 (CLAUDE.md의 티켓 등록 항목: 로컬 DB에 V3가 적용돼 있어 V3 파일을 수정하면 기동되지 않는다).
- 스키마 변경은 새 V 파일로만 한다 (README 규칙). 낡은 주석도 그대로 둔다. 예: V5 주석의 "양쪽이 눌러야 RESERVED" 설명은 V8로 낡았고, V8 주석에 변경 사실을 적었다.
- V9의 `exchange_history`는 `old_ticket_id`, `new_ticket_id`에 각각 단일 컬럼 UNIQUE를 둔다. 이 유니크 키가 FK 인덱스를 겸한다. 일부러 (match, user) 같은 복합 UNIQUE를 두지 않았다. 복합 UNIQUE가 match FK 인덱스로 쓰이면 2절과 같은 FK 인덱스 문제가 생길 수 있어서다. match/user/performance FK는 단일 컬럼 KEY다.
- V2·V6·V7·V8은 중간 실패 후 `flyway repair`로 이어서 재적용할 수 있게 INFORMATION_SCHEMA 가드 프로시저 패턴을 쓴다. V3·V8은 데이터가 조건에 안 맞으면 아무것도 바꾸기 전에 `SIGNAL`로 실패시킨다.
- 같은 이름의 CHECK를 한 문장에서 DROP/ADD하면 충돌할 수 있어 문장을 나눈다 (V7 주석).

## 7. Hibernate flush 순서와 유니크 키

- 증상: 한 트랜잭션에서 "기존 티켓 UPDATE, 새 티켓 INSERT" 순서로 호출해도 Hibernate는 flush 때 INSERT를 UPDATE보다 먼저 실행한다. 3절의 `uk_ticket_active_seat`는 UPDATE가 먼저 나가야 슬롯이 비므로, 순서가 뒤집히면 유니크 위반이 난다.
- 해결: UPDATE 직후 명시적으로 flush해 순서를 강제한 뒤 INSERT한다. 교환 완료가 이 경우다 ([근거 페이지](../decisions/exchange-complete-new-ticket.md)). 유니크 키가 걸린 "비우고 채우기"는 이 점을 테스트(실제 MySQL IT)로 확인한다.

## 8. 알림(notification) 쓰기 규칙 (V10)

알림 3종 구현 근거는 [알림 설계](../decisions/notification-design.md), 용어는 [용어집](../glossary.md).

- **트랜잭션 맨 끝에서 INSERT**: `NotificationService.notify`는 propose/reserve/unreserve 트랜잭션의 마지막에 일반 INSERT로 쓴다. `notification.user_id`, `match_id` FK 때문에 users 행에 S 잠금이 걸리므로, 잠금 순서는 티켓 id 오름차순, 요청 id 오름차순, 매칭, users S, notification이다. notification 행을 잠근 뒤 티켓·요청·매칭을 잠그지 않는다. 한 트랜잭션에서 수신자당 알림은 1건이다.
- **읽음·삭제는 잠금 없는 SELECT 후 IN 벌크**: 먼저 잠금 없는 SELECT로 대상 id를 모으고, 500개씩 `WHERE id IN (...)`로 UPDATE/DELETE 한다. `WHERE user_id = ? AND read_at IS NULL` 같은 범위 UPDATE/DELETE는 쓰지 않는다. 인덱스 범위에 갭 잠금이 걸려 같은 수신자에게 알림을 INSERT하는 다른 트랜잭션(propose 등)과 교착할 수 있기 때문이다. 읽은 알림 삭제에서도 안 읽은 것은 건드리지 않는다.
- **dedup_key 유니크 함정**: 요구사항 가안은 `(user_id, type, match_id)`를 유니크로 두는 것이었다. 그러면 예약, 예약 취소, 재예약을 반복할 때 같은 키가 두 번 들어가 유니크 위반이 나고 **예약 자체가 롤백**된다. 그래서 `dedup_key` 단일 컬럼 유니크(`uk_notification_dedup`)로 바꿨다. 중복을 막아야 하는 새 제안만 `PROPOSAL_RECEIVED:{matchId}` 값을 넣고, 예약·예약 취소는 `dedup_key`를 NULL로 둔다. MySQL 유니크는 NULL끼리 비교하지 않으므로(3절과 같은 성질) 예약↔취소 반복마다 매번 1건이 쌓인다.
- (V15 이후 보완) 채팅·예약 제안 알림은 위 "반복마다 1건"의 예외로 (수신자, 매칭)당 1행을 재사용하며 `dedup_key`를 채운다. 근거와 교착 사례는 13절과 [예약 제안 설계](../decisions/reserve-propose-design.md).
- 안 읽은 개수용 `unread_user_id`는 생성 컬럼이며 FK가 아니고, 갱신 컬럼 인덱스가 FK 인덱스를 겸하지 않게 user/match/unread 키를 모두 단일 컬럼으로 뒀다(2절 원칙).
- 검증: 한 수신자에게 14명이 동시에 propose·reserve/unreserve 하는 동안 수신자가 읽음·삭제 60회 반복과 `TicketService.create`를 병행하는 임시 MySQL IT에서 1213(교착) 0건. 기본 테스트 527건 통과, 임시 MySQL IT 포함 543건 통과(교착 스트레스 보강 전 수치, 보강 후 알림 테스트 클래스만 재실행) (이 수치는 구현 세션 기록이며 재실행은 하지 않았다).

## 9. 교환 완료의 갭 잠금과 users S 잠금 vs TicketService.create (교착 가능성)

- 출처: 알림 구현 코드 리뷰에서 Fable이 지적. 판정은 PLAUSIBLE(그럴듯함)이며 실제 재현은 하지 않았다. 심각도는 낮음, 코드는 수정하지 않았다.
- 시나리오: `complete`의 두 번째 수락은 새 티켓 INSERT 때 `uk_ticket_active_seat` 인덱스에 갭 잠금을 쥔다. 이어서 FK 검사(users 참조)로 users 행 S 잠금을 요구한다. 같은 참여자가 인접 좌석을 등록하는 `TicketService.create`는 users 행을 X로 먼저 잡은 뒤 같은 유니크 인덱스 범위에 INSERT하므로, 서로의 잠금을 기다리는 순환이 생길 수 있다. 1절의 "create는 INSERT만 하므로 순환 없음" 전제는 티켓 행 잠금에 대해서만 맞고 갭 잠금은 포함하지 않았다.
- 영향: MySQL이 한쪽을 희생(1213)시키고 서비스는 이를 503으로 흡수한다. 트랜잭션이 통째로 롤백되므로 데이터 손상은 없고 재시도하면 된다고 본다.
- 완화안(미적용): `complete`에서 새 티켓 INSERT 전에 users 두 행을 id 순서로 `FOR SHARE` 선행 잠금한다. 그러면 users S를 먼저 잡고 갭 잠금을 나중에 잡아 `create`(users X 선행)와 같은 순서가 된다. 적용 전에는 실제 MySQL IT로 재현을 먼저 확인한다.
- 앞선 구현 세션의 "받을 좌석을 그 순간 직접 등록하면 users S 잠금과 엇갈릴 수 있다"는 알려진 한계([작업일지](../../산출물/07_작업일지/2026-10-11.md))와 같은 건이다.

## 10. 채팅(V11) 전송·읽음·조회·연결 함정

설계 근거는 [채팅 설계 근거](../decisions/chat-design.md), STOMP 인증은 [STOMP 조사](../research/stomp-jwt-auth.md), 용어는 [용어집](../glossary.md). 아래는 구현 세션(2026-10-11, `feature/chat`)의 코드·임시 MySQL IT로 확인한 사실이다.

- **전송은 매칭 행 FOR UPDATE만 잡는다**: 전송 트랜잭션의 첫 쿼리는 `exchange_match` 행 `FOR UPDATE`(UPDATE는 하지 않음)이고, 티켓·요청은 잠그지 않는다. 한 매칭 안에서 전송이 직렬화되므로 id 순서가 커밋 순서가 된다. S 잠금(FOR SHARE)이면 두 전송이 동시에 진입해 id가 먼저 나온 쪽이 나중에 커밋될 수 있고, 클라이언트가 `after` 커서로 빈 구간을 채울 때 아직 커밋 전이던 작은 id를 건너뛰어 **메시지가 누락**된다. 종료(취소·완료) 커밋과도 같은 행을 잠그므로 종료 커밋 뒤에는 메시지가 0건이다.
- **전파는 커밋 뒤에**: `convertAndSendToUser`는 커밋 후에 호출한다. 전파가 실패해도 저장은 유지되고, 받는 쪽은 재연결 때 `after` 커서로 따라잡는다.
- **읽음 포인터 UPDATE→INSERT→재UPDATE를 트랜잭션 안에서 하면 교착(재현됨)**: `chat_read_state`는 먼저 `UPDATE ... GREATEST`, 행이 없으면 INSERT, DuplicateKey면 UPDATE를 다시 한다. 이 순서를 트랜잭션 안에서 하면 갭 잠금 교착이 재현됐다(정확한 잠금 대기 사슬은 기록하지 않았다. 없는 키에 대한 UPDATE의 갭 잠금과 INSERT가 엇갈린 것으로 추정한다). 그래서 `ChatReadStateRepository.advance`는 호출자 트랜잭션 밖(autocommit, `NOT_SUPPORTED`)에서 실행해 문장마다 잠금을 바로 풀게 했다.
- **그 부작용**: 매칭이나 users 행을 잠근 트랜잭션 안에서 `advance`를 호출하면, 호출자 트랜잭션이 쥔 잠금을 별도 연결(autocommit)이 기다리게 되어 **자기 대기로 1205(잠금 대기 초과)** 가 난다. `advance`는 잠금 트랜잭션 안에서 부르지 않는다.
- **`ON DUPLICATE KEY UPDATE`는 쓰지 않는다**: 읽음 포인터 갱신을 이 구문 하나로 줄이지 않고 위 UPDATE→INSERT→재UPDATE로 나눈다. 이 구문을 금지한 정확한 기술 이유(잠금 동작 차이 등)는 이번 입력에서 확인하지 못했다(미확인). 이유를 확인하면 이 항목에 보강한다.
- **메시지 조회는 `FORCE INDEX (idx_chat_message_match)`**: 힌트 없이는 옵티마이저가 PRIMARY 역방향 스캔, filesort, index_merge를 골라 매칭 하나의 목록 조회가 느려질 수 있었다. 4절 STRAIGHT_JOIN과 같은 이유로 계획을 고정했다. `idx_chat_message_match`는 FK용 단일 컬럼 인덱스다(2절 원칙).
- **목적지당 구독 1개**: 구독 수가 무제한이었던 점이 리뷰에서 중간 심각도로 지적됐다(Opus). 같은 큐를 반복 구독하면 푸시가 중복되고 자원을 더 쓸 수 있다. 레지스트리에 subscriptionId를 저장해 제한했고, 처음에는 세션당 1개였으나 실시간 알림 푸시 이후 허용 목적지 `/user/queue/chat`, `/user/queue/notifications` 정확 일치만 각각 1번씩 구독할 수 있다(세션당 최대 2개). 같은 목적지 중복 SUBSCRIBE나 구독 id 재사용은 거부하고 세션을 1008로 닫으며, 다른 목적지·변형과 SEND·ACK는 전부 거부한다. 사용자당 동시 연결도 5개로 제한한다. 알림 푸시는 커밋 뒤 전송이라 이 절의 "전파는 커밋 뒤에" 규칙과 같고, 알림 쓰기가 트랜잭션 맨 끝이라는 8절 규칙도 그대로다.
- **속도 제한 함정**: 제한은 5초 5건과 60초 60건 두 개다(구현은 사용자별 슬라이딩 윈도이며, 토큰 버킷 2개로 만들 때도 같은 문제가 있다). 5초 5건의 지속 속도는 분당 60건이라 60초 60건과 같다. 그래서 60초 한도는 실질적으로 따로 걸리지 않고, 효과를 내는 것은 5초 한도뿐이다. 두 한도의 지속 속도가 달라야(예: 분당 30건) 장기 연속 전송을 따로 막는다. 소모는 새 INSERT 직전에만 하며(멱등 재전송은 소모하지 않음), 단일 인스턴스 메모리라 서버가 여러 대면 공유되지 않는다. 한도를 바꾸는 것은 결정 사항이므로 여기서는 사실만 적는다.
- **검증**: 전송·예약 토글·교환 완료·티켓 등록·알림 읽음 혼합 스트레스에서 교착 0건, 동시 전송 160건 누락 0건, STOMP 통합 10건. 기본 595건 통과, 임시 MySQL IT 포함 612건 통과(구현 세션 기록, 이 위키에서 재실행하지 않았다).

## 11. 관리자 기능(V12) 잠금·유니크 함정

설계 근거는 [관리자 설계 근거](../decisions/admin-design.md). 구현 세션(2026-10-12, `feature/admin`)의 코드·임시 MySQL IT로 확인한 사실이다.

- **좌석 키 보조 유니크 인덱스로 티켓을 `FOR UPDATE` 하면 교착**: 좌석 소유 인증 승인에서 (회차, 구역, 열, 번)으로 티켓을 `FOR UPDATE` 하면 InnoDB가 보조 유니크 인덱스(`uk_ticket_active_seat`)를 먼저 잠그고 PK를 나중에 잠근다. 반면 `deactivate`는 PK(id)로 티켓을 잠가 순서가 엇갈려 교착이 났다. 해결: 좌석 키로는 **id만 잠금 없이 읽고**, 그 id로 PK 잠금을 잡은 뒤 **상태를 다시 판단**한다(그사이 내려졌거나 바뀌었을 수 있다). 이를 위해 `TicketService.deactivateLocked`를 추출했고 기존 동작과 테스트는 바뀌지 않았다. 잠금 순서는 인증 요청 행, 티켓 id 오름차순, 요청 id 오름차순, 매칭이다(1절 규약 앞에 claim 행이 붙는다). 승인+소유자 내리기+제3자 제안+예약 혼합 경합 테스트에서 교착 0건(구현 세션 기록).
- **상태 전이로 바뀌는 값은 생성 컬럼 UNIQUE로, PENDING만 유일**: "사용자(·좌석)당 대기 1건"은 `status` 자체에 유니크를 걸 수 없다(처리된 행이 쌓이므로). 생성 컬럼 `pending_proposer_id`/`pending_claimant_id` = `IF(status='PENDING', 사용자 id, NULL)`에 UNIQUE를 걸면 처리 후 NULL이 되어 슬롯이 비고 재제안이 가능하다. 3절의 `active_flag`·`live_flag`와 같은 성질(NULL은 유니크 비교 제외)이다. FK 인덱스는 이와 분리해 단일 컬럼으로 둔다(2절).
- **사용자당 상한은 사용자 행 `FOR UPDATE`로**: 수정 제안 생성(PENDING 3건)은 첫 쿼리로 사용자 행을 잠가야 동시 요청에서도 상한을 지킨다. 승인 쪽 잠금은 제안 행, 공연 행 순서다(공연을 잠그는 다른 경로가 없어 1절 규약과 충돌하지 않는다).

## 12. 차단·신고·이용 정지(V13) 함정

설계 근거는 [차단·신고·이용 정지 설계 근거](../decisions/block-report-design.md). 구현 세션(2026-10-12, `feature/block-report`)의 EXPLAIN·임시 MySQL IT로 확인한 사실이다.

- **차단 후보 제외는 `NOT EXISTS` 두 개로 나눈다**: 양방향 차단을 `(blocker=A AND blocked=B) OR (blocker=B AND blocked=A)` 하나의 NOT EXISTS로 쓰면 EXPLAIN에서 전체 인덱스 스캔이 나왔다. 방향별로 NOT EXISTS를 둘로 나누면 둘 다 `uk_user_block_pair`를 eq_ref 커버링으로 쓴다. 4절 STRAIGHT_JOIN 후보 SQL의 제외 조각에 적용된다(별칭 `ub` 사용 금지, 조각에 `?` 금지라는 기존 규칙 유지).
- **READ_COMMITTED에서 활성 제재 중복은 잠금 읽기로 못 막는다**: "사용자당 활성 제재 1건"은 DB 제약이 없다. 활성 제재를 잠금 읽기로 확인해도 READ_COMMITTED에는 갭 잠금이 없어, 행이 아직 없을 때 두 트랜잭션이 모두 통과해 둘 다 INSERT할 수 있다. 해결: 처리 트랜잭션이 **같은 대상의 신고 행 전체를 먼저 `FOR UPDATE`로 잠가 직렬화**한 뒤 ADMIN 여부 읽기, 활성 제재 잠금 읽기, `cancelAllOf`, `user_sanction` INSERT, 신고 갱신 순으로 진행한다. 같은 대상을 동시에 제재하면 한쪽이 교착으로 503(BUSY)이 되지만 중복 제재는 생기지 않는다(검증은 구현 세션 기록).
- **`users` 행은 티켓 뒤에 잠그므로 `OpenMatchCanceller` 앞에서 users X 잠금 금지**: canceller는 티켓 id 오름차순으로 잠근다(1절). 호출자가 먼저 대상 users 행을 X로 잠그면 users→티켓 순서가 되어 기존 티켓→users 경로와 순환할 수 있다. 그래서 resolve는 users 행을 잠그지 않는다. canceller는 호출 트랜잭션이 READ_COMMITTED이고 호출 전에 `ExchangeMatch` 엔티티를 로드하지 않아야 한다(티켓 잠금 뒤 매칭 목록을 다시 읽으므로).
- **쓰기 위치**: `user_block`·`abuse_report`·`user_sanction` INSERT는 트랜잭션 마지막이다(8절 notification 규칙과 같은 취지). 신고·차단 상한은 사용자 행을 잠그지 않으므로 근소 초과가 가능하다(admin의 사용자 행 잠금 상한, 11절과 대비).
- **차단·신고와 예약/내리기/제안 경합**: 20라운드 경합 테스트에서 교착 0건(구현 세션 기록).

## 13. 예약 제안/수락(V15) 함정

설계 근거는 [예약 제안/수락 설계 근거](../decisions/reserve-propose-design.md). 구현 세션(2026-10-13, `feature/reserve-propose`)의 임시 MySQL IT로 확인한 사실이다.

- **유니크 보조 인덱스를 먼저 잠그는 `INSERT ... ON DUPLICATE KEY UPDATE`는 PK를 먼저 잠그는 삭제와 교착한다(실제 MySQL 재현)**: 채팅·예약 제안 알림을 (수신자, 매칭)당 1행으로 재사용하려고 `dedup_key` 유니크(`uk_notification_dedup`)에 upsert를 걸었더니, 읽은 알림 삭제(PK 기준 `DELETE ... WHERE id IN`)와 잠금 순서가 반전되어 교착했다. upsert는 유니크 인덱스를 먼저, 삭제는 PK를 먼저 잠그기 때문이다(11절 첫 항목과 같은 계열). 해결: **먼저 PK 기준으로 UPDATE(읽음 해제, `created_at`=now)하고, 영향 행이 0일 때만 upsert**해 PK 먼저 잠그는 방식으로 통일했다. 읽은 알림이 삭제되어 행이 없어진 경우는 새로 INSERT된다. 10절의 "`ON DUPLICATE KEY UPDATE`는 쓰지 않는다"(읽음 포인터) 항목의 정확한 이유는 여전히 미확인이며, 이 항목은 알림 쪽에서 확인된 사례일 뿐 같은 원인이라는 증거는 아니다.
- **벌크 JPQL은 엔티티 로직을 우회한다**: CHATTING을 벗어나는 UPDATE는 제안 컬럼(`reserve_proposed_by_id`·`reserve_proposed_at`)을 같은 문장에서 NULL로 만들어야 CHECK를 통과한다. 엔티티 메서드(취소 등)에는 이 정리가 들어 있지만, 티켓 내리기의 벌크 JPQL 2개처럼 엔티티를 거치지 않고 상태만 바꾸는 쿼리는 그 로직을 타지 않으므로 SET 절에 직접 제안 컬럼 NULL을 넣어야 한다. 3절 마지막 항목(`reserved_by_id`)과 같은 규칙의 확장이며, CHATTING 이탈 경로(취소·거절·종료·요청 수정/삭제·티켓 내리기·차단·정지)를 새로 추가할 때 점검한다.
- **제안은 match 행만, 수락만 티켓 잠금**: 제안·철회·거절은 match `FOR UPDATE`만 잡고 알림은 맨 끝에 쓴다. 수락은 1절 순서(티켓 id↑, 요청 id↑, 매칭)를 그대로 쓴다. 동시 제안, 수락 대 철회·거절·취소·차단·정지·티켓 내리기, 같은 티켓의 두 매칭 수락 경합, 채팅 전송·읽음·삭제 혼합에서 교착 0건, 불변식(잠금 행 = 2×RESERVED) 유지(구현 세션 기록).
- **채팅 알림 읽음 가드**: 읽음 포인터가 움직일 때 CHAT_MESSAGE 알림을 읽음 처리하는 UPDATE는 READ_COMMITTED로 유니크 키 한 행만 건드리고, 포인터 뒤에 상대 메시지가 남아 있으면(NOT EXISTS) 읽음 처리하지 않는다. 매칭·users 잠금 트랜잭션 안에서는 호출하지 않는다(10절). 가드와 알림 되살림이 겹치는 극히 약한 창은 남아 있다.
