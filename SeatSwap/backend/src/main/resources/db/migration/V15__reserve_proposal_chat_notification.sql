-- =====================================================================
-- V15: 예약 제안(제안 -> 상대 수락) + 알림 3종 추가(예약 제안 / 예약 제안 거절 / 새 채팅 메시지)
--
-- 설계: feature/reserve-propose 확정안 1·2절 (2026-10-12). V8 의 "한 명이 예약하면 RESERVED" 를 뒤집는다.
--  (a) exchange_match: 예약 제안은 상태가 아니라 매칭의 부가 정보다(상태값 추가 없음, CHATTING -> RESERVED 그대로).
--    - reserve_proposed_by_id BIGINT NULL(제안자 -> users), reserve_proposed_at DATETIME(6) NULL.
--    - FK·인덱스는 reserved_by_id(V8)와 같은 방식: fk_exchange_match_reserve_proposed_by(ON DELETE/UPDATE 동작 없음,
--      CHECK 에 쓰이는 컬럼이라 MySQL 이 거부한다), FK 전용 단일 컬럼 인덱스 idx_exchange_match_reserve_proposed_by.
--    - CHECK 3개 (V8 의 ck_exchange_match_reserved 와 같은 안전망 성격):
--        ck_exchange_match_reserve_proposal_pair     : 두 컬럼은 함께 NULL 이거나 함께 NOT NULL
--        ck_exchange_match_reserve_proposal_status   : 제안은 CHATTING 에서만 존재(RESERVED·CANCELED·COMPLETED 는 반드시 NULL)
--        ck_exchange_match_reserve_proposed_by_party : 제안자는 매칭 참여자(user_a_id 또는 user_b_id)
--      => CHATTING 을 벗어나는 모든 UPDATE(수락 -> RESERVED, cancel/reject/시스템 취소 -> CANCELED, 벌크 JPQL 포함)는
--         같은 문장에서 reserve_proposed_by_id·reserve_proposed_at 을 NULL 로 만들어야 한다.
--    - 기존 행은 두 컬럼이 NULL 이라 백필 없이 세 CHECK 를 만족한다.
--  (b) notification: ck_notification_type 을 6종으로 재작성(기존 3종 + RESERVATION_PROPOSED, RESERVATION_REJECTED, CHAT_MESSAGE).
--    기존 허용 집합의 상위 집합이라 기존 행은 영향이 없다. 같은 ALTER 한 문장에서 DROP CHECK + ADD CONSTRAINT 한다.
--    - CHAT_MESSAGE 는 (수신자, 매칭)당 행 1개를 재사용한다(INSERT ... ON DUPLICATE KEY UPDATE). 이를 보장하는
--      ck_notification_chat_dedup: type = 'CHAT_MESSAGE' 이면 dedup_key = CONCAT('CHAT_MESSAGE:', match_id, ':', user_id) 필수.
--      (그 밖의 type 은 제약 없음 — PROPOSAL_RECEIVED:{matchId} 또는 NULL 그대로.)
--
-- 재실행 가능: V2·V6~V14 와 같은 INFORMATION_SCHEMA 가드 프로시저. 파괴적 단계 없음(컬럼·데이터 삭제 없음).
-- ck_notification_type 은 CHECK_CLAUSE 에 'CHAT_MESSAGE' 가 없을 때만 재작성하고, 이름이 아예 없으면(중간 실패) 새로 만든다.
-- 되돌리기(롤포워드): 새 V 파일에서 새 CHECK·FK·인덱스·컬럼을 DROP 하고 ck_notification_type 을 3종으로 되돌린다
-- (그 전에 새 type 3종 행을 지우거나 바꿔야 한다).
--
-- 규칙: V1~V14 는 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상. 다음 마이그레이션은 V16.
-- =====================================================================

DROP PROCEDURE IF EXISTS v15_reserve_proposal_chat_notification;

DELIMITER //
CREATE PROCEDURE v15_reserve_proposal_chat_notification()
BEGIN
    -- (a-1) 컬럼
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_match' AND COLUMN_NAME = 'reserve_proposed_by_id') THEN
        ALTER TABLE exchange_match
            ADD COLUMN reserve_proposed_by_id BIGINT      NULL AFTER reserved_at,
            ADD COLUMN reserve_proposed_at    DATETIME(6) NULL AFTER reserve_proposed_by_id;
    END IF;

    -- (a-2) FK 전용 단일 컬럼 인덱스 (갱신 컬럼을 붙이지 않는다)
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_match'
                     AND INDEX_NAME = 'idx_exchange_match_reserve_proposed_by') THEN
        ALTER TABLE exchange_match ADD KEY idx_exchange_match_reserve_proposed_by (reserve_proposed_by_id);
    END IF;

    -- (a-3) FK (ON DELETE/UPDATE 동작 없음)
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_match'
                     AND CONSTRAINT_NAME = 'fk_exchange_match_reserve_proposed_by') THEN
        ALTER TABLE exchange_match
            ADD CONSTRAINT fk_exchange_match_reserve_proposed_by FOREIGN KEY (reserve_proposed_by_id) REFERENCES users (id);
    END IF;

    -- (a-4) CHECK 3개
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_match'
                     AND CONSTRAINT_NAME = 'ck_exchange_match_reserve_proposal_pair') THEN
        ALTER TABLE exchange_match ADD CONSTRAINT ck_exchange_match_reserve_proposal_pair CHECK (
            (reserve_proposed_by_id IS NULL AND reserve_proposed_at IS NULL)
            OR (reserve_proposed_by_id IS NOT NULL AND reserve_proposed_at IS NOT NULL));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_match'
                     AND CONSTRAINT_NAME = 'ck_exchange_match_reserve_proposal_status') THEN
        ALTER TABLE exchange_match ADD CONSTRAINT ck_exchange_match_reserve_proposal_status CHECK (
            status = 'CHATTING' OR (reserve_proposed_by_id IS NULL AND reserve_proposed_at IS NULL));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'exchange_match'
                     AND CONSTRAINT_NAME = 'ck_exchange_match_reserve_proposed_by_party') THEN
        ALTER TABLE exchange_match ADD CONSTRAINT ck_exchange_match_reserve_proposed_by_party CHECK (
            reserve_proposed_by_id IS NULL OR reserve_proposed_by_id = user_a_id OR reserve_proposed_by_id = user_b_id);
    END IF;

    -- (b-1) ck_notification_type 6종으로 재작성 (아직 3종일 때만. 한 문장이라 CHECK 가 빠진 순간이 없다)
    IF EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_notification_type'
                 AND CHECK_CLAUSE NOT LIKE '%CHAT_MESSAGE%') THEN
        ALTER TABLE notification
            DROP CHECK ck_notification_type,
            ADD CONSTRAINT ck_notification_type CHECK (type IN (
                'PROPOSAL_RECEIVED', 'MATCH_RESERVED', 'RESERVATION_CANCELED',
                'RESERVATION_PROPOSED', 'RESERVATION_REJECTED', 'CHAT_MESSAGE'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_notification_type') THEN
        ALTER TABLE notification ADD CONSTRAINT ck_notification_type CHECK (type IN (
            'PROPOSAL_RECEIVED', 'MATCH_RESERVED', 'RESERVATION_CANCELED',
            'RESERVATION_PROPOSED', 'RESERVATION_REJECTED', 'CHAT_MESSAGE'));
    END IF;

    -- (b-2) CHAT_MESSAGE 는 (수신자, 매칭)당 1행: dedup_key 형식 강제 (IS NOT NULL 명시 — NULL 이면 CHECK 가 통과해 버린다)
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'notification'
                     AND CONSTRAINT_NAME = 'ck_notification_chat_dedup') THEN
        ALTER TABLE notification ADD CONSTRAINT ck_notification_chat_dedup CHECK (
            type <> 'CHAT_MESSAGE'
            OR (dedup_key IS NOT NULL AND dedup_key = CONCAT('CHAT_MESSAGE:', match_id, ':', user_id)));
    END IF;
END//
DELIMITER ;

CALL v15_reserve_proposal_chat_notification();
DROP PROCEDURE v15_reserve_proposal_chat_notification;
