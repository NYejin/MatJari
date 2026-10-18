-- =====================================================================
-- V22: 관리자 권한 확장 — 공연 내리기(숨김) 컬럼 + 관리자 조치 기록(admin_action_log)
--
-- 설계: feature/admin-powers (2026-10-19, 사용자 결정 2026-10-18·19)
--  - 공연 내리기: performance.hidden_at 이 NOT NULL 이면 숨김. 목록·검색에서 빠지고 새 티켓 등록·새 교환 요청·새 제안만 막는다.
--    진행 중인 매칭은 끝까지 가능하고 다시 올리기(hidden_* 를 NULL 로)가 가능하다. 삭제는 하지 않는다.
--    hidden_by_id -> users(관리자), hidden_reason 은 숨길 때 필수(1~200자). 셋은 함께 NULL 이거나 함께 값이 있다.
--  - admin_action_log: 관리자가 한 모든 직접 조치(공연 숨김·복구·수정, 이용자 정지·해제·역할 변경·강제 탈퇴)의 기록.
--    행은 추가만 한다(수정·삭제 없음). target_type/target_id 는 대상(PERFORMANCE·USER)이며 FK 가 아니다
--    (대상이 사라져도 기록은 남는다). detail(JSON NULL)은 변경 전후 값 등 보조 정보.
--
--  인덱스·잠금 검토
--  - FK 인덱스는 단일 컬럼(idx_performance_hidden_by, idx_admin_action_log_admin). 갱신되는 hidden_at 은 FK 인덱스에 붙이지 않는다.
--  - admin_action_log 는 INSERT 전용이라 잠금 사슬(티켓 → 요청 → 매칭, users 는 그 뒤)에 끼지 않는다.
--    쓰기는 각 조치 트랜잭션의 맨 끝에서 한다(notification 과 같은 규칙).
--
-- 재실행 가능: INFORMATION_SCHEMA 가드 프로시저. 기존 데이터 변경 없음, 파괴적 단계 없음.
-- 되돌리기(롤포워드): 새 V 파일에서 admin_action_log 삭제, performance 의 hidden_* 제약·컬럼 삭제.
--
-- 규칙: V1~V21 은 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상. 다음 마이그레이션은 V23.
-- =====================================================================

DROP PROCEDURE IF EXISTS v22_admin_powers;

DELIMITER //
CREATE PROCEDURE v22_admin_powers()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance' AND COLUMN_NAME = 'hidden_at') THEN
        ALTER TABLE performance
            ADD COLUMN hidden_at     DATETIME(6)  NULL,
            ADD COLUMN hidden_by_id  BIGINT       NULL,
            ADD COLUMN hidden_reason VARCHAR(200) NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance' AND INDEX_NAME = 'idx_performance_hidden_by') THEN
        ALTER TABLE performance ADD INDEX idx_performance_hidden_by (hidden_by_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance' AND CONSTRAINT_NAME = 'fk_performance_hidden_by') THEN
        ALTER TABLE performance ADD CONSTRAINT fk_performance_hidden_by FOREIGN KEY (hidden_by_id) REFERENCES users (id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_performance_hidden') THEN
        ALTER TABLE performance ADD CONSTRAINT ck_performance_hidden CHECK (
            (hidden_at IS NULL AND hidden_by_id IS NULL AND hidden_reason IS NULL)
            OR (hidden_at IS NOT NULL AND hidden_by_id IS NOT NULL
                AND hidden_reason IS NOT NULL AND CHAR_LENGTH(TRIM(hidden_reason)) > 0));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admin_action_log') THEN
        CREATE TABLE admin_action_log (
            id          BIGINT       NOT NULL AUTO_INCREMENT,
            admin_id    BIGINT       NOT NULL,                  -- 조치한 관리자 -> users
            action      VARCHAR(40)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
            target_type VARCHAR(20)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
            target_id   BIGINT       NOT NULL,                  -- FK 아님(대상이 사라져도 기록은 남는다)
            reason      VARCHAR(500) NULL,
            detail      JSON         NULL,
            created_at  DATETIME(6)  NOT NULL,
            PRIMARY KEY (id),
            KEY idx_admin_action_log_admin (admin_id),
            KEY idx_admin_action_log_target (target_type, target_id),
            KEY idx_admin_action_log_created (created_at),
            CONSTRAINT fk_admin_action_log_admin FOREIGN KEY (admin_id) REFERENCES users (id),
            CONSTRAINT ck_admin_action_log_target_type CHECK (target_type IN ('PERFORMANCE', 'USER')),
            CONSTRAINT ck_admin_action_log_action CHECK (action IN (
                'PERFORMANCE_HIDE', 'PERFORMANCE_UNHIDE', 'PERFORMANCE_EDIT', 'PERFORMANCE_SESSIONS', 'PERFORMANCE_ZONES',
                'USER_SUSPEND', 'USER_UNSUSPEND', 'USER_ROLE_CHANGE', 'USER_FORCE_WITHDRAW'))
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;
END//
DELIMITER ;

CALL v22_admin_powers();
DROP PROCEDURE v22_admin_powers;
