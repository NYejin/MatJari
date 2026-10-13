-- =====================================================================
-- V9: 교환 수락 -> 교환 완료 (기존 티켓 EXCHANGED + 새 티켓 INSERT) 와 교환 이력
--
-- 설계: wiki/decisions/exchange-complete-new-ticket.md (Q-15 확정), CLAUDE.md '교환 수락·교환 완료'
--  - ticket.status 에 'EXCHANGED' 추가: ck_ticket_status 를 ('ACTIVE','INACTIVE','EXCHANGED') 로 확장한다.
--    EXCHANGED 는 active_flag(= status 가 ACTIVE 일 때만 1) 가 NULL 이므로 uk_ticket_active_seat 에서 빠진다
--    (같은 문장·같은 트랜잭션에서 기존 티켓을 EXCHANGED 로 바꾼 뒤 새 티켓을 INSERT 해도 충돌하지 않는다).
--    기존 데이터 이관은 없다(기존 행은 ACTIVE/INACTIVE 뿐).
--  - exchange_history: append-only 교환 이력. 매칭 1건이 완료되면 사용자별 1행씩 2행을 남긴다.
--    '(기존 자리) -> (바꾼 자리)' 스냅샷(공연 제목·공연장·회차 시각·구역·열·번)과 old_ticket_id/new_ticket_id 를 가진다.
--    스냅샷 컬럼이라 이후 공연·티켓이 바뀌어도 이력은 변하지 않는다(공연은 등록 후 수정 불가이지만 이력은 독립 보존).
--    uk_exchange_history_old_ticket / uk_exchange_history_new_ticket: 한 티켓은 한 번만 교환된다(이중 교환 방지).
--    복합 UNIQUE(match_id, user_id) 는 두지 않는다. FK 인덱스는 단일 컬럼이며 갱신 컬럼을 붙이지 않는다
--    (wiki/gotchas/lock-order-and-index-pitfalls.md). FK 에는 ON DELETE/UPDATE 동작을 붙이지 않는다.
--    CHECK old_ticket_id <> new_ticket_id.
--
-- 재실행 가능: V2·V6~V8 과 같은 INFORMATION_SCHEMA 가드 프로시저. 파괴적 단계 없음.
-- 같은 이름의 CHECK 를 한 문장에서 DROP/ADD 하면 충돌할 수 있어 문장을 나눈다(V7 방식).
-- 되돌리기(롤포워드): 새 V 파일에서 exchange_history 삭제, EXCHANGED 행 정리 후 CHECK 축소.
--
-- 규칙: V1~V8 는 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상.
-- =====================================================================

DROP PROCEDURE IF EXISTS v9_exchange_complete_history;

DELIMITER //
CREATE PROCEDURE v9_exchange_complete_history()
BEGIN
    -- 1) ck_ticket_status: 옛 정의(EXCHANGED 없음) 제거
    IF EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_ticket_status'
                 AND CHECK_CLAUSE NOT LIKE '%EXCHANGED%') THEN
        ALTER TABLE ticket DROP CHECK ck_ticket_status;
    END IF;

    -- 2) ck_ticket_status: 새 정의
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'ticket'
                     AND CONSTRAINT_NAME = 'ck_ticket_status') THEN
        ALTER TABLE ticket
            ADD CONSTRAINT ck_ticket_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'EXCHANGED'));
    END IF;

    -- 3) 교환 이력 테이블 (재실행 시 이미 있으면 건너뛴다)
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_history') THEN
        CREATE TABLE exchange_history (
            id                BIGINT       NOT NULL AUTO_INCREMENT,
            match_id          BIGINT       NOT NULL,                 -- -> exchange_match (완료된 매칭)
            user_id           BIGINT       NOT NULL,                 -- 이력 주인 -> users
            old_ticket_id     BIGINT       NOT NULL,                 -- 교환 전 내 티켓(EXCHANGED) -> ticket
            new_ticket_id     BIGINT       NOT NULL,                 -- 교환 후 내 티켓(새 INSERT) -> ticket
            performance_id    BIGINT       NOT NULL,                 -- -> performance
            performance_title VARCHAR(200) NOT NULL,                 -- 스냅샷
            venue_name        VARCHAR(100) NOT NULL,                 -- 스냅샷
            old_starts_at     DATETIME(6)  NOT NULL,                 -- 스냅샷: 교환 전 회차
            new_starts_at     DATETIME(6)  NOT NULL,                 -- 스냅샷: 교환 후 회차
            old_zone_label    VARCHAR(50)  NOT NULL,
            old_row_label     VARCHAR(20)  NOT NULL,
            old_col_label     VARCHAR(20)  NOT NULL,
            new_zone_label    VARCHAR(50)  NOT NULL,
            new_row_label     VARCHAR(20)  NOT NULL,
            new_col_label     VARCHAR(20)  NOT NULL,
            created_at        DATETIME(6)  NOT NULL,
            PRIMARY KEY (id),
            CONSTRAINT uk_exchange_history_old_ticket UNIQUE (old_ticket_id),
            CONSTRAINT uk_exchange_history_new_ticket UNIQUE (new_ticket_id),
            KEY idx_exchange_history_match (match_id),
            KEY idx_exchange_history_user (user_id),
            KEY idx_exchange_history_performance (performance_id),
            CONSTRAINT fk_exchange_history_match       FOREIGN KEY (match_id)       REFERENCES exchange_match (id),
            CONSTRAINT fk_exchange_history_user        FOREIGN KEY (user_id)        REFERENCES users (id),
            CONSTRAINT fk_exchange_history_old_ticket  FOREIGN KEY (old_ticket_id)  REFERENCES ticket (id),
            CONSTRAINT fk_exchange_history_new_ticket  FOREIGN KEY (new_ticket_id)  REFERENCES ticket (id),
            CONSTRAINT fk_exchange_history_performance FOREIGN KEY (performance_id) REFERENCES performance (id),
            CONSTRAINT ck_exchange_history_tickets CHECK (old_ticket_id <> new_ticket_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;
END//
DELIMITER ;

CALL v9_exchange_complete_history();
DROP PROCEDURE v9_exchange_complete_history;
