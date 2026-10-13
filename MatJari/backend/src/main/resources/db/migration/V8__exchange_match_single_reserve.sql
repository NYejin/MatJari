-- =====================================================================
-- V8: 예약 방식 변경 - 둘 중 한 명이 예약하면 RESERVED (reserved_by_id / reserved_at)
--
-- 설계: 산출물/08_ERD/exchange-schema-design.md 10절 (8차+9차 답변, 2026-10-09~10 사용자 확정)
--  - exchange_match 에 reserved_by_id(FK users, NULL)·reserved_at(NULL) 추가. RESERVED 일 때만 값이 있다(CHECK).
--  - a_reserved_at / b_reserved_at 는 삭제하지 않고 미사용(레거시)으로 둔다(COMMENT 로 폐기 표시). 새 코드는 읽지도 쓰지도 않는다.
--  - 기존 RESERVED 행은 두 시각 중 이른 쪽 사용자(동시이면 a측)를 reserved_by_id, 그 시각을 reserved_at 으로 백필한다.
--    시각이 비정상(둘 다 NULL)인 행은 updated_at 으로 CHECK 를 만족시킨다. exchange_ticket_lock 행은 그대로 유효하다.
--  - 구 모델의 반쪽 동의(CHATTING 인데 한쪽만 a/b_reserved_at 이 있는 행)는 새 모델에 없는 상태다. 변환하지 않고
--    reserved_* 를 NULL 로 둔다(즉시 예약으로 승격하면 동의 없이 상대 티켓이 잠긴다). 화면의 반쪽 동의 표시는 사라진다.
--  - CHECK 3개: ck_exchange_match_reserved(RESERVED <=> reserved_* NOT NULL), ck_exchange_match_reserved_by_party(예약자는 참여자),
--    ck_exchange_match_completed(CHATTING 이면 a/b_completed_at NULL, COMPLETED 이면 둘 다 NOT NULL). V5 CHECK 와 충돌 없음(설계 10.3).
--  - FK 전용 인덱스 idx_exchange_match_reserved_by 는 단일 컬럼이다(설계 3.4: FK 인덱스에 갱신 컬럼 금지).
--    reserved_by_id FK 에는 ON DELETE/UPDATE 동작을 붙이지 않는다(CHECK 에 쓰이는 컬럼이라 MySQL 이 거부한다).
--  - RESERVED 를 벗어나는 모든 UPDATE 는 같은 문장에서 reserved_by_id·reserved_at 을 NULL 로 만들어야 한다(CHECK 안전망).
--  - V5 의 '양쪽이 이 사람과 교환할게요를 눌러야 RESERVED' 설명은 V8 로 낡았다(V5 는 수정하지 않는다).
--
-- 재실행 가능: V2·V6·V7 과 같은 INFORMATION_SCHEMA 가드 프로시저. 파괴적 단계 없음(컬럼 삭제 없음).
-- ck_exchange_match_completed 를 위반하는 행(CHATTING 인데 완료 시각이 있거나 COMPLETED 인데 없음)이 있으면
-- 아무것도 바꾸기 전에 SIGNAL 로 실패한다. 고친 뒤 flyway repair 후 다시 적용한다.
-- 되돌리기(롤포워드): 새 V 파일에서 CHECK 3개·FK·인덱스·컬럼을 DROP. 구 코드(accept)와는 호환되지 않는다(설계 10.8).
--
-- 규칙: V1~V7 는 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상.
-- =====================================================================

DROP PROCEDURE IF EXISTS v8_single_reserve;

DELIMITER //
CREATE PROCEDURE v8_single_reserve()
BEGIN
    -- 0) 가드: 데이터가 ck_exchange_match_completed 를 만족하는지 (아무것도 바꾸기 전에 실패시켜 원본 보존)
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_match'
                     AND CONSTRAINT_NAME = 'ck_exchange_match_completed') THEN
        IF EXISTS (SELECT 1 FROM exchange_match
                   WHERE (status = 'CHATTING'  AND (a_completed_at IS NOT NULL OR b_completed_at IS NOT NULL))
                      OR (status = 'COMPLETED' AND (a_completed_at IS NULL OR b_completed_at IS NULL))) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'V8: a/b_completed_at 이 상태와 맞지 않는 exchange_match 행이 있다. 고친 뒤 flyway repair';
        END IF;
    END IF;

    -- 1) 컬럼
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_match' AND COLUMN_NAME = 'reserved_by_id') THEN
        ALTER TABLE exchange_match
            ADD COLUMN reserved_by_id BIGINT      NULL AFTER b_reserved_at,
            ADD COLUMN reserved_at    DATETIME(6) NULL AFTER reserved_by_id;
    END IF;

    -- 2) FK 전용 단일 컬럼 인덱스
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_match' AND INDEX_NAME = 'idx_exchange_match_reserved_by') THEN
        ALTER TABLE exchange_match ADD KEY idx_exchange_match_reserved_by (reserved_by_id);
    END IF;

    -- 3) 백필 (재실행 안전: reserved_by_id 가 이미 있는 행은 건드리지 않는다)
    UPDATE exchange_match
       SET reserved_by_id = CASE WHEN b_reserved_at IS NOT NULL AND (a_reserved_at IS NULL OR b_reserved_at < a_reserved_at)
                                 THEN user_b_id ELSE user_a_id END,
           reserved_at    = COALESCE(LEAST(a_reserved_at, b_reserved_at), a_reserved_at, b_reserved_at, updated_at)
     WHERE status = 'RESERVED' AND reserved_by_id IS NULL;

    -- 4) FK (백필 뒤)
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_match'
                     AND CONSTRAINT_NAME = 'fk_exchange_match_reserved_by') THEN
        ALTER TABLE exchange_match
            ADD CONSTRAINT fk_exchange_match_reserved_by FOREIGN KEY (reserved_by_id) REFERENCES users (id);
    END IF;

    -- 5) CHECK (백필 뒤. 기존 행 전체가 검증된다)
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_match'
                     AND CONSTRAINT_NAME = 'ck_exchange_match_reserved') THEN
        ALTER TABLE exchange_match ADD CONSTRAINT ck_exchange_match_reserved CHECK (
            (status = 'RESERVED' AND reserved_by_id IS NOT NULL AND reserved_at IS NOT NULL)
            OR (status <> 'RESERVED' AND reserved_by_id IS NULL AND reserved_at IS NULL));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_match'
                     AND CONSTRAINT_NAME = 'ck_exchange_match_reserved_by_party') THEN
        ALTER TABLE exchange_match ADD CONSTRAINT ck_exchange_match_reserved_by_party CHECK (
            reserved_by_id IS NULL OR reserved_by_id = user_a_id OR reserved_by_id = user_b_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_match'
                     AND CONSTRAINT_NAME = 'ck_exchange_match_completed') THEN
        ALTER TABLE exchange_match ADD CONSTRAINT ck_exchange_match_completed CHECK (
            (status <> 'CHATTING'  OR (a_completed_at IS NULL AND b_completed_at IS NULL))
            AND (status <> 'COMPLETED' OR (a_completed_at IS NOT NULL AND b_completed_at IS NOT NULL)));
    END IF;

    -- 6) 레거시 표시 (COMMENT 만 바꾼다. 타입·NULL 허용 그대로)
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_match' AND COLUMN_NAME = 'a_reserved_at'
                     AND COLUMN_COMMENT LIKE 'DEPRECATED%') THEN
        ALTER TABLE exchange_match
            MODIFY a_reserved_at DATETIME(6) NULL COMMENT 'DEPRECATED(V8): 양쪽 동의 방식 잔재. reserved_by_id/reserved_at 사용',
            MODIFY b_reserved_at DATETIME(6) NULL COMMENT 'DEPRECATED(V8): 양쪽 동의 방식 잔재. reserved_by_id/reserved_at 사용';
    END IF;
END//
DELIMITER ;

CALL v8_single_reserve();
DROP PROCEDURE v8_single_reserve;
