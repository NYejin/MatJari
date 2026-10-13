-- =====================================================================
-- V6: 추가금을 요청 단위에서 희망 범위 단위로 이동
--
-- 설계: 산출물/08_ERD/exchange-schema-design.md 9.1~9.5, 9.11 (2026-10-08 사용자 확정)
--  - exchange_want_range : extra_type(X/ANY/POS/NEG) NOT NULL, extra_amount NULL + CHECK 2개. 범위마다 추가금이 짝이다.
--  - exchange_want_seat  : 같은 두 컬럼 + CHECK 2개 (파생 데이터에 비정규화. PK·인덱스는 그대로. 후보 SQL 이 조인을 늘리지 않고 읽는다).
--  - exchange_match      : a/b_extra_type, a/b_extra_amount 스냅샷 4컬럼. 매칭이 성립했을 때 적용된 추가금(표시용).
--                          요청에서 추가금이 사라지고 요청 수정·삭제 때 범위·좌석 행이 사라지므로 매칭 기록 보존에 필요하다.
--  - exchange_request    : extra_type / extra_amount 와 이를 참조하는 CHECK 2개를 제거한다 (유일한 파괴 단계, 마지막).
--
-- 이관: 요청 단위였던 추가금을 그 요청의 모든 범위·좌석에 그대로 복사한다(같은 의미). 매칭 스냅샷은 a/b 각 요청의 값을 복사한다.
--       모든 범위·좌석·매칭이 FK 로 요청을 가지므로 무손실이다. 범위가 0개인 요청(서비스로는 만들 수 없음)의 추가금만 버려진다.
--
-- 재실행 가능: V2 처럼 INFORMATION_SCHEMA 로 각 단계가 이미 적용됐는지 확인하며 진행한다. 중간에 실패하면 원인을 고치고
--   `flyway repair` 로 실패 기록을 지운 뒤 다시 적용하면 남은 단계부터 이어진다. 요청 컬럼 삭제 전까지는 원본이 그대로 남아 있다.
--   (실패 시 임시 프로시저가 남을 수 있어 먼저 DROP IF EXISTS 한다.)
--
-- 적용 전 점검 쿼리 (둘 다 0 이어야 한다)
--   SELECT COUNT(*) FROM exchange_request WHERE extra_type IN ('POS','NEG') AND extra_amount IS NULL;
--   SELECT COUNT(*) FROM exchange_request WHERE (extra_type = 'POS' AND extra_amount <= 0) OR (extra_type = 'NEG' AND extra_amount >= 0)
--                                                  OR (extra_type IN ('X','ANY') AND extra_amount IS NOT NULL);
--   V4 의 요청 CHECK 는 식이 NULL 이면 통과하므로 (POS, 금액 NULL) 같은 행이 레거시로 남아 있을 수 있다. 새 CHECK 는
--   POS/NEG 에 금액이 반드시 있어야 하므로 그런 행이 있으면 아무것도 바꾸기 전에 SIGNAL 로 실패한다(원본 보존).
--   실패 시 복구: 위 쿼리로 행을 찾아 값을 고치고(예: UPDATE exchange_request SET extra_type='ANY', extra_amount=NULL WHERE ...)
--   `flyway repair` 로 실패 기록을 지운 뒤 다시 적용한다. 이 가드는 맨 앞에서 실패하므로 임시 프로시저 `v6_extra_per_range` 가
--   남을 수 있으나 재실행 시 먼저 DROP 한다.
--
-- 되돌릴 수 없는 손실: 요청 컬럼 DROP. 복원하려면 새 마이그레이션으로 범위에서 역이관해야 하며, 범위별 값이 달라졌다면 손실이므로
--   적용 전에 mysqldump 백업을 권장한다.
--
-- 규칙: V1~V5 는 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상 (CHECK 강제, DROP CHECK 문법).
-- =====================================================================

DROP PROCEDURE IF EXISTS v6_extra_per_range;

DELIMITER //
CREATE PROCEDURE v6_extra_per_range()
BEGIN
    -- 0) 레거시 가드: 요청 컬럼이 아직 있을 때만 (이미 이관이 끝난 DB 에서의 재실행은 건너뜀)
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_request' AND COLUMN_NAME = 'extra_type') THEN
        IF EXISTS (SELECT 1 FROM exchange_request
                   WHERE (extra_type IN ('POS', 'NEG') AND extra_amount IS NULL)
                      OR (extra_type = 'POS' AND extra_amount <= 0)
                      OR (extra_type = 'NEG' AND extra_amount >= 0)
                      OR (extra_type IN ('X', 'ANY') AND extra_amount IS NOT NULL)) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'V6: 추가금 유형과 금액이 맞지 않는 레거시 exchange_request 행이 있다. 고친 뒤 flyway repair';
        END IF;
    END IF;

    -- 1) 컬럼 추가 (NULL 허용으로 먼저 추가한 뒤 백필)
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_want_range' AND COLUMN_NAME = 'extra_type') THEN
        ALTER TABLE exchange_want_range
            ADD COLUMN extra_type   VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER col_to,
            ADD COLUMN extra_amount INT NULL AFTER extra_type;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_want_seat' AND COLUMN_NAME = 'extra_type') THEN
        ALTER TABLE exchange_want_seat
            ADD COLUMN extra_type   VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
            ADD COLUMN extra_amount INT NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_match' AND COLUMN_NAME = 'a_extra_type') THEN
        ALTER TABLE exchange_match
            ADD COLUMN a_extra_type   VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
            ADD COLUMN a_extra_amount INT NULL,
            ADD COLUMN b_extra_type   VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
            ADD COLUMN b_extra_amount INT NULL;
    END IF;

    -- 2) 백필: 요청 컬럼이 아직 있을 때만. 요청 컬럼이 원본이므로 항상 덮어쓴다(부분 적용 뒤 원본을 고쳐 재실행해도 반영된다)
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_request' AND COLUMN_NAME = 'extra_type') THEN
        UPDATE exchange_want_range r JOIN exchange_request q ON q.id = r.request_id
           SET r.extra_type = q.extra_type, r.extra_amount = q.extra_amount;
        UPDATE exchange_want_seat s JOIN exchange_request q ON q.id = s.request_id
           SET s.extra_type = q.extra_type, s.extra_amount = q.extra_amount;
        UPDATE exchange_match m
          JOIN exchange_request qa ON qa.id = m.request_a_id
          JOIN exchange_request qb ON qb.id = m.request_b_id
           SET m.a_extra_type = qa.extra_type, m.a_extra_amount = qa.extra_amount,
               m.b_extra_type = qb.extra_type, m.b_extra_amount = qb.extra_amount;
    END IF;

    -- 3) NOT NULL 전환 + CHECK (NOT NULL 전환이 백필 누락을 막는다).
    --    CHECK 는 식이 NULL 이면 통과하므로 POS/NEG 쪽에 `extra_amount IS NOT NULL` 을 명시한다(V4 의 요청 CHECK 는 POS + 금액 NULL 을 막지 못했다).
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_want_range'
                     AND CONSTRAINT_NAME = 'ck_exchange_want_range_amount') THEN
        ALTER TABLE exchange_want_range
            MODIFY COLUMN extra_type VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
            ADD CONSTRAINT ck_exchange_want_range_extra_type CHECK (extra_type IN ('X', 'ANY', 'POS', 'NEG')),
            ADD CONSTRAINT ck_exchange_want_range_amount CHECK (
                (extra_type IN ('X', 'ANY') AND extra_amount IS NULL)
                OR (extra_type = 'POS' AND extra_amount IS NOT NULL AND extra_amount > 0)
                OR (extra_type = 'NEG' AND extra_amount IS NOT NULL AND extra_amount < 0));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_want_seat'
                     AND CONSTRAINT_NAME = 'ck_exchange_want_seat_amount') THEN
        ALTER TABLE exchange_want_seat
            MODIFY COLUMN extra_type VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
            ADD CONSTRAINT ck_exchange_want_seat_extra_type CHECK (extra_type IN ('X', 'ANY', 'POS', 'NEG')),
            ADD CONSTRAINT ck_exchange_want_seat_amount CHECK (
                (extra_type IN ('X', 'ANY') AND extra_amount IS NULL)
                OR (extra_type = 'POS' AND extra_amount IS NOT NULL AND extra_amount > 0)
                OR (extra_type = 'NEG' AND extra_amount IS NOT NULL AND extra_amount < 0));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_match'
                     AND CONSTRAINT_NAME = 'ck_exchange_match_b_extra') THEN
        ALTER TABLE exchange_match
            MODIFY COLUMN a_extra_type VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
            MODIFY COLUMN b_extra_type VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
            ADD CONSTRAINT ck_exchange_match_a_extra CHECK (
                (a_extra_type IN ('X', 'ANY') AND a_extra_amount IS NULL)
                OR (a_extra_type = 'POS' AND a_extra_amount IS NOT NULL AND a_extra_amount > 0)
                OR (a_extra_type = 'NEG' AND a_extra_amount IS NOT NULL AND a_extra_amount < 0)),
            ADD CONSTRAINT ck_exchange_match_b_extra CHECK (
                (b_extra_type IN ('X', 'ANY') AND b_extra_amount IS NULL)
                OR (b_extra_type = 'POS' AND b_extra_amount IS NOT NULL AND b_extra_amount > 0)
                OR (b_extra_type = 'NEG' AND b_extra_amount IS NOT NULL AND b_extra_amount < 0));
    END IF;

    -- 4) 요청 컬럼 제거 (유일한 파괴 단계). MySQL 은 CHECK 가 참조하는 컬럼 삭제를 거부하므로 CHECK 를 먼저 삭제한다.
    IF EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_request'
                 AND CONSTRAINT_NAME = 'ck_exchange_request_amount') THEN
        ALTER TABLE exchange_request DROP CHECK ck_exchange_request_amount;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_request'
                 AND CONSTRAINT_NAME = 'ck_exchange_request_extra_type') THEN
        ALTER TABLE exchange_request DROP CHECK ck_exchange_request_extra_type;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_request' AND COLUMN_NAME = 'extra_type') THEN
        ALTER TABLE exchange_request DROP COLUMN extra_amount, DROP COLUMN extra_type;
    END IF;
END//
DELIMITER ;

CALL v6_extra_per_range();
DROP PROCEDURE v6_extra_per_range;
