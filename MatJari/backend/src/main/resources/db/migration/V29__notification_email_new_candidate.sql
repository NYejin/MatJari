-- =====================================================================
-- V29: 메일 알림 + 새 교환 후보 알림(NEW_CANDIDATE)
--
--  (a) notification
--    - request_id BIGINT NULL -> exchange_request(id): 새 후보 알림의 '새 후보가 생긴 내 요청'. FK 전용 단일 컬럼 인덱스 idx_notification_request.
--      FK 에는 ON DELETE/UPDATE 동작을 붙이지 않는다(CHECK 에 쓰이는 컬럼이라 MySQL 이 거부한다, V15 와 같음).
--    - emailed_at DATETIME(6) NULL: 메일로 알린(또는 메일 대상에서 제외한) 시각. NULL = 아직 메일 처리 전.
--      메일 대상 스캔용 idx_notification_email_scan(emailed_at, created_at) 은 FK 가 아니므로 갱신 컬럼을 섞어도 된다.
--    - ck_notification_type 7종(NEW_CANDIDATE 추가): 한 ALTER 문장에서 DROP CHECK + ADD CONSTRAINT.
--    - ck_notification_match_required 재작성: NEW_CANDIDATE 는 match_id NULL·request_id NOT NULL, 나머지는 match_id NOT NULL·request_id NULL.
--    - ck_notification_new_candidate_dedup: NEW_CANDIDATE 는 (수신자, 요청)당 행 1개를 재사용한다
--      (dedup_key = NEW_CANDIDATE:{request_id}:{user_id}, CHAT_MESSAGE 의 ck_notification_chat_dedup 과 같은 방식).
--    기존 행은 모두 match_id NOT NULL·request_id NULL·emailed_at NULL 이라 새 CHECK 를 만족한다.
--      (기존 행의 emailed_at 이 NULL 이라 배포 직후 최근 24시간 안의 안 읽은 알림이 한 번 메일 대상이 될 수 있다.
--       메일 스캔이 created_at 하한을 24시간으로 두고 사용자당 1통으로 묶으므로 허용한다.)
--  (b) user_notification_setting: 사용자별 알림 설정. 행이 없으면 메일 켜짐으로 본다. last_email_at 은 메일 쿨다운 기준.
--      email_verified_at: 이메일 소유가 확인된 시각(가입 인증번호 통과·소셜 제공자 검증 이메일·비밀번호 재설정 성공·내 이메일 인증 API). NULL(또는 행 없음)이면 메일을 보내지 않는다.
--  (c) candidate_notice: 이미 알린 (받는 요청, 후보 티켓) 쌍. 요청을 지웠다 다시 만들어도 같은 티켓이면 다시 알리지 않는다.
--
-- 재실행 가능: V15·V27 과 같은 INFORMATION_SCHEMA 가드 프로시저. 파괴적 단계 없음.
-- 되돌리기(롤포워드): 새 V 파일에서 NEW_CANDIDATE 행을 지운 뒤 CHECK·FK·인덱스·컬럼·테이블을 DROP 한다.
-- 규칙: V1~V28 은 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상. 다음 마이그레이션은 V30.
-- =====================================================================

DROP PROCEDURE IF EXISTS v29_notification_email_new_candidate;

DELIMITER //
CREATE PROCEDURE v29_notification_email_new_candidate()
BEGIN
    -- (a-1) 컬럼
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification' AND COLUMN_NAME = 'request_id') THEN
        ALTER TABLE notification ADD COLUMN request_id BIGINT NULL AFTER match_id;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification' AND COLUMN_NAME = 'emailed_at') THEN
        ALTER TABLE notification ADD COLUMN emailed_at DATETIME(6) NULL AFTER read_at;
    END IF;

    -- (a-2) 인덱스: FK 전용 단일 컬럼 + 메일 스캔용(FK 아님)
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification' AND INDEX_NAME = 'idx_notification_request') THEN
        ALTER TABLE notification ADD KEY idx_notification_request (request_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification' AND INDEX_NAME = 'idx_notification_email_scan') THEN
        ALTER TABLE notification ADD KEY idx_notification_email_scan (emailed_at, created_at);
    END IF;

    -- (a-3) FK
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'notification' AND CONSTRAINT_NAME = 'fk_notification_request') THEN
        ALTER TABLE notification
            ADD CONSTRAINT fk_notification_request FOREIGN KEY (request_id) REFERENCES exchange_request (id);
    END IF;

    -- (a-4) ck_notification_type 7종 (아직 NEW_CANDIDATE 가 없을 때만. 한 문장이라 CHECK 가 빠진 순간이 없다)
    IF EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_notification_type'
                 AND CHECK_CLAUSE NOT LIKE '%NEW_CANDIDATE%') THEN
        ALTER TABLE notification
            DROP CHECK ck_notification_type,
            ADD CONSTRAINT ck_notification_type CHECK (type IN (
                'PROPOSAL_RECEIVED', 'MATCH_RESERVED', 'RESERVATION_CANCELED',
                'RESERVATION_PROPOSED', 'RESERVATION_REJECTED', 'CHAT_MESSAGE', 'NEW_CANDIDATE'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_notification_type') THEN
        ALTER TABLE notification ADD CONSTRAINT ck_notification_type CHECK (type IN (
            'PROPOSAL_RECEIVED', 'MATCH_RESERVED', 'RESERVATION_CANCELED',
            'RESERVATION_PROPOSED', 'RESERVATION_REJECTED', 'CHAT_MESSAGE', 'NEW_CANDIDATE'));
    END IF;

    -- (a-5) ck_notification_match_required 재작성 (아직 request_id 를 모를 때만)
    IF EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_notification_match_required'
                 AND CHECK_CLAUSE NOT LIKE '%request_id%') THEN
        ALTER TABLE notification
            DROP CHECK ck_notification_match_required,
            ADD CONSTRAINT ck_notification_match_required CHECK (
                (type = 'NEW_CANDIDATE' AND match_id IS NULL AND request_id IS NOT NULL)
                OR (type <> 'NEW_CANDIDATE' AND match_id IS NOT NULL AND request_id IS NULL));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_notification_match_required') THEN
        ALTER TABLE notification ADD CONSTRAINT ck_notification_match_required CHECK (
            (type = 'NEW_CANDIDATE' AND match_id IS NULL AND request_id IS NOT NULL)
            OR (type <> 'NEW_CANDIDATE' AND match_id IS NOT NULL AND request_id IS NULL));
    END IF;

    -- (a-6) NEW_CANDIDATE 는 (수신자, 요청)당 1행: dedup_key 형식 강제 (IS NOT NULL 명시 — NULL 이면 CHECK 가 통과해 버린다)
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'notification'
                     AND CONSTRAINT_NAME = 'ck_notification_new_candidate_dedup') THEN
        ALTER TABLE notification ADD CONSTRAINT ck_notification_new_candidate_dedup CHECK (
            type <> 'NEW_CANDIDATE'
            OR (dedup_key IS NOT NULL AND dedup_key = CONCAT('NEW_CANDIDATE:', request_id, ':', user_id)));
    END IF;

    -- (b) user_notification_setting
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_notification_setting') THEN
        CREATE TABLE user_notification_setting (
            user_id       BIGINT      NOT NULL,
            email_enabled TINYINT(1)  NOT NULL DEFAULT 1,
            last_email_at DATETIME(6) NULL,
            email_verified_at DATETIME(6) NULL,
            updated_at    DATETIME(6) NOT NULL,
            PRIMARY KEY (user_id),
            CONSTRAINT fk_user_notification_setting_user FOREIGN KEY (user_id) REFERENCES users (id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;

    -- (c) candidate_notice
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'candidate_notice') THEN
        CREATE TABLE candidate_notice (
            recipient_request_id BIGINT      NOT NULL,   -- 새 후보를 알려 받는 요청(R')
            candidate_ticket_id  BIGINT      NOT NULL,   -- 새로 후보가 된 상대 티켓(요청이 아니라 티켓 기준)
            created_at           DATETIME(6) NOT NULL,
            PRIMARY KEY (recipient_request_id, candidate_ticket_id),
            KEY idx_candidate_notice_ticket (candidate_ticket_id),
            CONSTRAINT fk_candidate_notice_recipient FOREIGN KEY (recipient_request_id) REFERENCES exchange_request (id),
            CONSTRAINT fk_candidate_notice_ticket FOREIGN KEY (candidate_ticket_id) REFERENCES ticket (id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;
END//
DELIMITER ;

CALL v29_notification_email_new_candidate();
DROP PROCEDURE v29_notification_email_new_candidate;
