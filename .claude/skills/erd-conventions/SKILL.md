---
name: erd-conventions
description: DB 엔티티/ERD 관련 작업(신규 테이블, 관계 수정, JPA 엔티티 작성) 시 반드시 참고. 현재 기준선 V1~V10(12개 테이블: users·performance·performance_session·ticket, 교환 희망 4개, 매칭·잠금 2개, 교환 이력 1개, 알림 1개)과 네이밍·제약·마이그레이션 규칙을 담고 있다. 좌석표·수정 로그·제재 설계는 삭제되어 태그 archive/seatmap-track-20261007에 보관된다. 기준 다이어그램은 산출물/08_ERD/erd.dot.
---

# ERD 컨벤션

## 기준선 (12개 테이블, Flyway V1~V10)

| 테이블 | 요점 |
|---|---|
| `users` | `role`(USER/ADMIN, `ck_users_role`) |
| `performance` | `source_key`(utf8mb4_bin, `uk_performance_source_key`), `venue_name VARCHAR(100) NOT NULL`(공연장은 텍스트, 별도 테이블 없음), `registrant_id`. 등록 후 수정 불가 |
| `performance_session` | `starts_at`(분 단위, KST), `uk_performance_session_performance_starts_at` |
| `ticket` | 구역·열·번 `zone/row/col_label` + `_key`(정규화), `status`(ACTIVE/INACTIVE/EXCHANGED, `ck_ticket_status`), 생성 컬럼 `active_flag`, `uk_ticket_active_seat`(회차·zone_key·row_key·col_key·active_flag), `idx_ticket_user_status` |
| `exchange_request` | 티켓당 미삭제 요청 1개(`uk_exchange_request_live_ticket`(live_flag, ticket_id) + FK용 `idx_exchange_request_ticket`), status OPEN/CLOSED/DELETED, `deleted_at`(`ck_exchange_request_deleted`: DELETED일 때만 값). 추가금 컬럼은 없다(범위 단위) |
| `exchange_want_range` | 입력 범위(zone/row_from~to/col_from~to 정규화 키, `sort_order`) + **범위 단위 추가금** `extra_type`(X/ANY/POS/NEG)·`extra_amount` |
| `exchange_want_seat` | 범위를 펼친 개별 좌석(PK request_id+zone/row/col key, 파생 데이터) + 범위의 추가금 복사본 |
| `exchange_want_session` | 희망 회차 + `priority`(>=1, 사용자 설정 우선순위), PK request_id+performance_session_id |
| `exchange_match` | request_a/b·ticket_a/b·user_a/b, status CHATTING/RESERVED/COMPLETED/CANCELED, 시각 컬럼, `canceled_by_id`(시스템 취소는 NULL), 생성 컬럼 `request_low_id/high_id`·`open_flag` + `uk_exchange_match_open_pair`(같은 쌍의 열린 매칭 1개), 추가금 스냅샷 `a/b_extra_type`·`a/b_extra_amount`(표시용), `reserved_by_id`(FK users)·`reserved_at`, `a/b_reserved_at`은 DEPRECATED 레거시(읽지도 쓰지도 않음) |
| `exchange_ticket_lock` | PK ticket_id, match_id. 예약된 두 티켓의 잠금 |
| `exchange_history` | 교환 완료 이력(append-only, V9). `match_id`·`user_id`·`performance_id`·`old_ticket_id`·`new_ticket_id` FK(ON DELETE/UPDATE 미지정, `fk_exchange_history_*`), 공연·자리 스냅샷 컬럼(`old_/new_` 회차 시작 시각·zone/row/col_label), `old_ticket_id`·`new_ticket_id` 각각 `uk_exchange_history_*_ticket` UNIQUE, `ck_exchange_history_tickets`(old≠new). 단일 컬럼 KEY(match/user/performance) |
| `notification` | 알림(V10). `user_id`(수신자 FK)·`match_id`(FK NOT NULL), `type`(PROPOSAL_RECEIVED·MATCH_RESERVED·RESERVATION_CANCELED, `ck_notification_type`), `read_at`(NULL=안 읽음), `dedup_key` UNIQUE NULL(`uk_notification_dedup`), 생성 컬럼 `unread_user_id`(`idx_notification_unread`, FK 아님). 단일 컬럼 KEY(user/match/unread). `ck_notification_match_required` |

- 자식 3개(range/seat/session)는 request FK ON DELETE CASCADE. 요청 소프트 삭제 시 서비스가 자식 행을 삭제한다.
- 추가금 CHECK: X/ANY는 금액 NULL, POS는 `extra_amount IS NOT NULL AND > 0`, NEG는 `IS NOT NULL AND < 0`. NULL이면 CHECK가 통과하므로 `IS NOT NULL`을 명시한다.
- 겹치는 범위의 추가금이 다르면 서비스가 422 `WANT_EXTRA_CONFLICT`로 거부한다(want_seat PK 불변).
- `exchange_match` 예약 불변식: RESERVED <=> `reserved_by_id`·`reserved_at` NOT NULL(`ck_exchange_match_reserved`), 예약자는 참여자(`ck_exchange_match_reserved_by_party`), CHATTING이면 `a/b_completed_at` NULL·COMPLETED면 둘 다 NOT NULL(`ck_exchange_match_completed`). **RESERVED를 벗어나는 모든 UPDATE는 같은 문장에서 `reserved_by_id`·`reserved_at`을 NULL로 만든다.** `reserved_by_id` FK에는 ON DELETE/UPDATE 동작을 붙이지 않는다(CHECK에 쓰이는 컬럼이라 MySQL이 거부).
- 상태값 컬럼은 `status`로 통일, 문자열 저장(enum 이름).
- 시스템 취소 경로는 CHATTING 매칭만 취소한다(RESERVED는 건드리지 않는다).

### V9 (구현 완료: 교환 완료)

- 교환 이력 `exchange_history`: 마이페이지 '교환 이력'용. 자리 정보(공연·회차·구역·열·번 텍스트)를 **스냅샷**으로 저장해 `(기존 자리) -> (바꾼 자리)`로 보여주고, `old_ticket_id`·`new_ticket_id`를 둔다. 조회 화면·API는 아직 없다.
- 교환 완료(두 사람이 '교환 수락'): 한 트랜잭션에서 기존 두 티켓을 `EXCHANGED`로 바꾸고 각자 새 자리 티켓(소유자 그대로, 회차·구역·열·번은 상대의 기존 티켓 값)을 INSERT한다. `ck_ticket_status`를 V9에서 바꿨다(`active_flag`는 ACTIVE일 때만 1이라 새 티켓 INSERT와 충돌하지 않는다). 매칭 행의 좌석은 항상 교환 전 자리다. EXCHANGED 티켓은 내리기 불가, 완료된 매칭은 취소 불가. 근거: `wiki/decisions/exchange-complete-new-ticket.md`.
- 교환 완료 시 기존 티켓의 교환 요청은 CLOSED로 닫는다.
- 교환 완료된 좌석에 걸린 다른 CHATTING 매칭은 자동 취소하지 않는다(버튼 비활성 + "이미 교환된 좌석이에요" 표시).

### V10 (구현 완료: 알림)

- 알림 `notification`: 수신자 `user_id`, 유형 3종, `match_id`는 항상 채운다(`ck_notification_match_required`). `read_at`이 NULL이면 안 읽음이다.
- 안 읽은 개수 인덱스는 `read_at`을 직접 넣지 않고 생성 컬럼 `unread_user_id`(read_at이 NULL일 때만 user_id)로 만든다. 갱신 컬럼을 FK 인덱스에 섞지 않기 위해서다.
- `dedup_key`는 '새 제안'만 `PROPOSAL_RECEIVED:{matchId}`로 채운다. '상대가 예약'·'예약 취소'는 반복마다 1건이라 NULL이다. `(user_id, type, match_id)` 유니크는 재예약 때 예약이 롤백되므로 쓰지 않는다.
- 쓰기 규칙: notification INSERT는 트랜잭션 마지막에 한다. notification 행을 잠근 뒤 티켓·요청·매칭을 잠그지 않는다.
- 닫힌 매칭의 알림은 지우지 않는다(사용자 '읽은 알림 삭제'만 제거 경로).

### 다음 마이그레이션 (V11부터, 미구현)

- 채팅 메시지 `chat_message`, 사용자 차단 `user_block`(차단하면 후보 제외·채팅 불가; 재매칭 불가의 근거는 차단·신고뿐)이 예정이다. 컬럼·제약은 설계 시 결정(db-schema-architect). 차단 제외는 `ExchangeCandidateRepository.additionalExclusions()`에 추가한다.
- 후기·신뢰도 테이블은 만들지 않는다. 사용자 신고 테이블은 교환 핵심 흐름 이후 설계한다. 좌석표·수정 로그·제재(`abuse_report`·`user_sanction` 등)는 동결 상태라 설계하지 않는다.

## 관계 원칙

- 공연·회차: Performance 1:N PerformanceSession. `Performance`는 날짜 컬럼이 없고(회차로 분리) 중복 키 `source_key`는 링크 정규화 값(사이트별 `{site}:{productId}`, 미지원 사이트는 일반 URL 정규화, 계산은 서비스 책임). 공연장 이름+제목은 unique가 아니다(재공연 존재).
- `Ticket`은 `PerformanceSession`을 참조한다(`performance_session_id`). `performance_id`를 중복으로 두지 않는다. **교환 범위는 공연(Performance) 단위**라서 같은 공연의 다른 회차 티켓끼리도 교환할 수 있다. 공연은 `session.performance`로 얻고 **매칭 판정은 회차가 아니라 공연 기준**이다. 단 매칭은 사용자가 정한 희망 회차끼리만 하고 후보는 사용자가 정한 회차 우선순위로 노출한다.
- 좌석 키는 공연(회차) 단위의 **(구역, 열, 번) 텍스트**다. 공연장 단위 구역 테이블은 두지 않고 좌표·`uid`·`section` 번호에 의존하지 않는다. 구역은 필수 별도 입력(범위 펼침 대상 아님), 열·번만 숫자 범위(`3~5`)를 펼치고 문자 열은 하나씩 추가한다.
- 같은 회차·구역·열·번의 활성 티켓은 1개(`uk_ticket_active_seat`), 사용자당 활성 티켓 상한(20)은 서비스에서 검사한다. 지정석·1매 제한은 두지 않고, 연석·3자 이상 순환 교환은 구현하지 않되 데이터 모델이 막지 않게 한다.
- `ExchangeRequest`는 티켓당 미삭제 1개. `ExchangeMatch`는 두 요청(A/B)을 참조하는 매칭 레코드이며 **점수·랭킹·신뢰도 등 추천 알고리즘용 컬럼은 추가하지 않는다**. 조건 일치는 쿼리로 판정하고 저장하지 않는다.
- 매칭 흐름: 후보 목록에서 사용자가 골라 채팅(CHATTING, 한 요청에 여러 개 동시 가능, 같은 쌍의 열린 매칭은 1개) → 한 명이 예약하면 RESERVED(두 티켓 잠금, 티켓당 예약 1개) → 예약 취소 시 CHATTING 복귀(잠금 해제, 재예약 가능) → 양쪽 '교환 수락'으로 COMPLETED. 취소(CANCELED)는 양쪽 완료 전 누구든 가능하며 재매칭 불가가 아니다. RESERVED에서는 cancel·reject가 막히고 먼저 예약을 취소해야 한다.
- 회차 당일 끝(다음날 0시 KST)까지 티켓 등록·매칭을 허용하고 이후 자동 비활성한다. 티켓 내리기는 예약 중이 아니면 언제든 가능하다.

## 잠금·인덱스 규칙

- **FK 인덱스는 단일 컬럼**으로 둔다. FK에 쓰이는 인덱스에 `status` 같은 갱신 컬럼을 붙이면 UPDATE가 부모 행에 S 잠금을 걸어 교착이 난다(실제 재현). 상세: `wiki/gotchas/lock-order-and-index-pitfalls.md`.
- 행 잠금 순서는 항상 **티켓 id↑ → 요청 id↑ → 매칭**. 요청만 잠그는 update/delete 경로에서는 티켓을 잠그지 않는다.
- 생성 컬럼 + UNIQUE로 '조건부 유일'을 만든다(NULL은 UNIQUE 대상에서 제외): `active_flag`, `live_flag`, `open_flag`. 종결 뒤 재등록이 필요한 키(예: 'PENDING일 때만 키 문자열을 만드는 생성 컬럼')도 같은 방식이다.
- 로그성 테이블은 append-only(INSERT만, setter 없음, FK ON DELETE RESTRICT)로 설계한다.

## 네이밍 규칙

- 엔티티명: PascalCase 단수형(`Ticket`, not `Tickets`). 테이블은 snake_case.
- FK 컬럼: `{참조엔티티_snake}_id`(예: `performance_session_id`). 같은 엔티티를 역할로 참조하면 역할명을 쓴다(`registrant_id`, `reserved_by_id`, `canceled_by_id`, 신고·메시지라면 `reporter_id`, `sender_id`).
- 제약·인덱스 이름은 명시한다: `uk_{테이블}_{컬럼...}`, `idx_{테이블}_{컬럼...}`, `fk_{테이블}_{컬럼}`, `ck_{테이블}_{내용}`(서비스에서 DataIntegrityViolation을 제약 이름으로 구분할 수 있게). V1의 일부 FK/UNIQUE 이름은 Hibernate가 만든 임의 이름(예: `FK6p310v5n1wwgdqry9ksyx39nf`)을 기존 DB와 맞추려고 그대로 쓴 것이므로 DROP/변경할 때 그 이름을 쓴다.
- 중복 판정용 정규화 값은 원문과 별도 컬럼(`source_key`, `*_key`)에 저장하고 거기에 unique를 건다. 좌석 키 정규화: NFKC·공백 제거·대문자·앞 0 제거·끝의 '열'/'번' 제거(`SeatKeyNormalizer`).
- **collation**: 테이블 기본은 `utf8mb4_0900_ai_ci`(대소문자·악센트 무시). 구분이 필요한 키 컬럼만 `utf8mb4_bin`(`@Collate("utf8mb4_bin")`)으로 둔다: `performance.source_key`(URL 경로·쿼리는 대소문자 구분, 비ASCII가 들어올 수 있어 `ascii_bin`은 쓰지 않음, 500자×4바이트=2000바이트로 인덱스 한도 3072바이트 이내), 좌석 `*_key`, `status`/`extra_type` 등 enum 문자열 컬럼은 migration의 선언을 따른다.
- **시간 기준 KST**: 모든 `LocalDateTime` 컬럼은 Asia/Seoul 벽시계 시각으로 저장한다.
  - 현재 시각은 `ClockConfig`의 `Clock`(Asia/Seoul)에서만 얻는다. 엔티티·서비스에서 `LocalDateTime.now()`(JVM TZ 의존) 직접 호출 금지.
  - `created_at`/`updated_at`은 JPA Auditing으로 채운다: `@EntityListeners(AuditingEntityListener.class)` + `@CreatedDate`/`@LastModifiedDate`, `JpaAuditingConfig`의 DateTimeProvider가 `LocalDateTime.now(clock)` 반환. 팩토리 메서드에서 시각을 직접 넣지 않는다(저장 전에는 null).
  - 방어선: backend Dockerfile `ENV TZ=Asia/Seoul` + `-Duser.timezone=Asia/Seoul`, docker-compose backend `TZ: Asia/Seoul`. 테스트는 `Clock.fixed`로 고정(`AuditingClockTest` 참고).

## Flyway 마이그레이션 규칙

`ddl-auto: validate` — Hibernate는 스키마를 만들거나 고치지 않고 엔티티와 일치하는지 검증만 한다(불일치 시 기동 실패).

- 변경은 `SeatSwap/backend/src/main/resources/db/migration/V{n}__{snake_description}.sql`을 **새로 추가**해서만 한다. 번호는 마지막 번호 + 1(다음은 V11).
- **이미 적용된 파일(V1~V10)은 수정 금지**(체크섬 불일치로 기동 실패). 잘못됐으면 새 V 파일로 고친다. 로컬 개발 DB를 되돌릴 때는 `docker compose down -v`.
- 새 엔티티·컬럼·인덱스·길이·NOT NULL·collation 변경은 엔티티 수정과 **같은 커밋에 마이그레이션 파일을 함께 작성**한다.
- 이관이 있는 변경은 재실행 가능한 프로시저 패턴(INFORMATION_SCHEMA 가드, V2·V6·V7·V8·V9·V10)으로 쓴다. 데이터를 이관할 수 없는 변경은 파일 맨 앞에서 임시 프로시저 + `SIGNAL SQLSTATE '45000'`으로 즉시 실패시킨다(MySQL DDL은 트랜잭션에 묶이지 않아 중간 실패 시 앞선 변경이 남기 때문). 실패 후에는 데이터를 고치고 `flyway repair` 후 재시도.
- 요구 버전은 MySQL 8.0.16 이상(CHECK 강제). Flyway baseline 설정은 없다(V1은 빈 DB 전용). 이전 스키마가 남은 로컬 DB는 `docker compose down -v`로 비운다.
- 이력 확인: `SELECT * FROM flyway_schema_history;`(절차는 `SeatSwap/backend/README.md` "DB 마이그레이션").
- 통합 테스트(`SEATSWAP_IT_*` 환경변수)는 개발 DB를 보호하는 별도 DB 이름 규칙을 따른다(ops-rules 참고).

## 08_ERD 반영 규칙

`산출물/08_ERD/erd.dot`이 기준 다이어그램이다. 현재 erd.dot은 V1~V10·12개 테이블(`exchange_history`·`notification` 포함)까지 그려져 있다. 현재 테이블은 파란 헤더, 예정 테이블(채팅·차단 등 V11 이후)은 주황 헤더 노드로 둔다. 삭제된 좌석표·수정 로그·제재 테이블은 그리지 않는다(설계는 태그 `archive/seatmap-track-20261007`의 이전 erd.dot과 마이그레이션에 보관). 시각 컬럼(created_at/updated_at/starts_at)은 KST 기준이라는 주석을 유지한다. 새 V 파일이 추가되면 erd.dot도 함께 갱신하고 이 문서의 기준선 표를 갱신한다. 설계 상세는 `산출물/08_ERD/exchange-schema-design.md`.

## 변경 시 절차

1. db-schema-architect가 변경안 설계
2. `.dot` 파일 수정 → `dot -Tpng erd.dot -o ERD.png`로 재생성(한글 라벨 사용 시 `fonts-nanum` 설치 필요)
3. 영향 있으면 산출물/04_요구사항정의서도 함께 갱신
4. 엔티티 구현은 `SeatSwap/backend/src/main/java/com/seatswap/domain/`에 반영
