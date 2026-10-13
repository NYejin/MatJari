-- =====================================================================
-- V7: 교환 요청 소프트 삭제 (status DELETED)
--
-- 설계: 산출물/08_ERD/exchange-schema-design.md 9.1(B), 9.6, 9.7, 9.10, 9.11 (2026-10-08 사용자 확정)
--  - exchange_request.status 에 DELETED 추가, deleted_at DATETIME(6) NULL 추가 (DELETED 일 때만 값이 있다, CHECK).
--  - live_flag (생성 컬럼, STORED) = IF(status = 'DELETED', NULL, 1). ticket 의 활성 유일 패턴과 같다.
--  - uk_exchange_request_ticket UNIQUE(ticket_id) 를 uk_exchange_request_live_ticket UNIQUE(live_flag, ticket_id) 로 교체한다.
--    NULL(DELETED) 은 유일 대상에서 빠지므로 티켓당 '미삭제(OPEN·CLOSED) 요청' 1개를 보장하고, 삭제 후 같은 티켓에 새 요청을 만들 수 있다.
--  - FK 전용 단일 컬럼 인덱스 idx_exchange_request_ticket(ticket_id) 를 둔다.
--    유일 키의 맨 앞을 live_flag 로 둔 이유(FK 인덱스 규칙, 설계 3.4): (ticket_id, live_flag) 순서였다면 그 인덱스가 FK 인덱스로 쓰여
--    DELETED 전환 때 live_flag 가 바뀌며 부모(ticket) 행에 공유 잠금이 걸려 티켓->요청 잠금 순서 밖에서 교착이 날 수 있다.
--    (live_flag, ticket_id) 는 ticket_id 가 선두가 아니라 FK 인덱스가 될 수 없다.
--  - 기존 행은 모두 live_flag = 1 이라 새 유일 키로 이관해도 위반이 없다(옛 UNIQUE(ticket_id) 와 동치). 데이터 가드 불필요.
--  - DELETED 요청의 하위 행(range·seat·session)은 서비스가 삭제 때 지운다(스키마 변경 없음).
--
-- 재실행 가능: V2·V6 와 같은 INFORMATION_SCHEMA 가드 프로시저. 중간 실패 시 `flyway repair` 후 재적용하면 남은 단계부터 이어진다.
-- 되돌리기: UNIQUE(ticket_id) 복원은 DELETED 행(같은 티켓 중복)이 있으면 실패한다. 먼저 정리해야 한다(새 마이그레이션으로 롤포워드).
--
-- 규칙: V1~V6 는 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상.
-- =====================================================================

DROP PROCEDURE IF EXISTS v7_request_soft_delete;

DELIMITER //
CREATE PROCEDURE v7_request_soft_delete()
BEGIN
    -- 1) 옛 status CHECK(OPEN, CLOSED) 제거. 같은 이름을 한 문장에서 DROP/ADD 하면 충돌할 수 있어 문장을 나눈다.
    IF EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_exchange_request_status'
                 AND CHECK_CLAUSE NOT LIKE '%DELETED%') THEN
        ALTER TABLE exchange_request DROP CHECK ck_exchange_request_status;
    END IF;

    -- 2) 컬럼
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_request' AND COLUMN_NAME = 'deleted_at') THEN
        ALTER TABLE exchange_request ADD COLUMN deleted_at DATETIME(6) NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_request' AND COLUMN_NAME = 'live_flag') THEN
        ALTER TABLE exchange_request
            ADD COLUMN live_flag TINYINT GENERATED ALWAYS AS (IF(status = 'DELETED', NULL, 1)) STORED;
    END IF;

    -- 3) 인덱스: FK 전용 단일 컬럼 -> 새 유일 키 -> 옛 유일 키 삭제 (FK 는 항상 사용 가능한 인덱스를 유지한다)
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_request' AND INDEX_NAME = 'idx_exchange_request_ticket') THEN
        ALTER TABLE exchange_request ADD KEY idx_exchange_request_ticket (ticket_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_request' AND INDEX_NAME = 'uk_exchange_request_live_ticket') THEN
        ALTER TABLE exchange_request ADD CONSTRAINT uk_exchange_request_live_ticket UNIQUE (live_flag, ticket_id);
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_request' AND INDEX_NAME = 'uk_exchange_request_ticket') THEN
        ALTER TABLE exchange_request DROP INDEX uk_exchange_request_ticket;
    END IF;

    -- 4) CHECK
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_request'
                     AND CONSTRAINT_NAME = 'ck_exchange_request_status') THEN
        ALTER TABLE exchange_request
            ADD CONSTRAINT ck_exchange_request_status CHECK (status IN ('OPEN', 'CLOSED', 'DELETED'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_request'
                     AND CONSTRAINT_NAME = 'ck_exchange_request_deleted') THEN
        ALTER TABLE exchange_request
            ADD CONSTRAINT ck_exchange_request_deleted CHECK (
                (status = 'DELETED' AND deleted_at IS NOT NULL) OR (status <> 'DELETED' AND deleted_at IS NULL));
    END IF;
END//
DELIMITER ;

CALL v7_request_soft_delete();
DROP PROCEDURE v7_request_soft_delete;
