-- =====================================================================
-- V13: 사용자 차단, 사용자 신고, 이용 정지(제재)
--
-- 설계: feature/block-report 확정 설계 (2026-10-12)
--  - user_block: 차단은 방향이 하나(blocker -> blocked)이고 효과는 양방향이다(후보 제외는 두 방향 NOT EXISTS).
--    행은 INSERT/DELETE 만 한다(갱신 컬럼 없음). uk_user_block_pair(blocker_id, blocked_id) 는
--      ① 중복 차단 방지(멱등 차단의 최후 방어선) ② blocker_id FK 인덱스 겸용(갱신 컬럼이 없으므로 교착 위험 없음)
--      ③ 후보 SQL 의 양방향 NOT EXISTS (blocker=a AND blocked=b / blocker=b AND blocked=a) 두 조회 모두 이 유니크로
--         등호 2개 조회(eq_ref/const)가 된다.
--    blocked_id 는 단일 인덱스(FK, '나를 차단한 사람' 조회 없음 — FK 용).
--  - abuse_report: 매칭 화면에서 상대를 신고. 사유 5종·상태 3종(utf8mb4_bin + CHECK). OTHER 는 detail 필수.
--    'OPEN 은 (신고자, 매칭)당 1건' = 생성 컬럼 open_reporter_id = IF(status='OPEN', reporter_id, NULL) 를 맨 앞에 둔
--    UNIQUE(open_reporter_id, match_id). 처리되면 NULL 이 되어 같은 매칭에 다시 신고할 수 있다. 이 유니크는
--    '사용자당 OPEN 개수'(open_reporter_id = ?) 조회 인덱스도 겸한다. 생성 컬럼을 앞에 둔 이유는 V7/V12 와 같다
--    (FK 컬럼 선두 복합 인덱스가 FK 인덱스로 쓰이고 상태 전이 갱신 컬럼이 섞이면 부모 행 공유 잠금 교착).
--    sanction_id 는 FK 가 아니다(user_sanction.report_id 가 이 테이블을 참조하므로 순환 FK 를 피한다; 감사용 값).
--    처리됐으면 handled_by_id·handled_at 이 있고, OPEN 이면 handled_*·handler_note·sanction_id 가 비어 있다.
--    제재 기록(sanction_id)은 RESOLVED 일 때만.
--  - user_sanction: 이용 정지. type 은 'SUSPEND' 하나(CHECK, 확장 여지). ends_at NULL = 무기한.
--    활성 = revoked_at IS NULL AND (ends_at IS NULL OR ends_at > now) — 스케줄러 없이 조회 시 판단.
--    해제(revoked_at)는 revoked_by_id 와 함께. 자기 자신 제재 불가(ck_user_sanction_self).
--  - users 테이블은 바꾸지 않는다.
--  - FK 인덱스는 단일 컬럼이며 갱신 컬럼을 붙이지 않는다. FK 에 ON DELETE/UPDATE 동작을 붙이지 않는다
--    (CHECK·생성 컬럼에 쓰이는 컬럼의 FK 는 동작을 붙일 수 없다).
--  - 관리자 신고 목록(status = ? ORDER BY id DESC)용 idx_abuse_report_status(status) 는 FK 인덱스가 아니다.
--  - 잠금(서비스): 차단·제재 시 열린 매칭 취소는 티켓 id↑ -> 요청 id↑ -> 매칭 id↑. 이 세 테이블의 행 잠금 뒤에
--    티켓·요청·매칭을 잠그지 않도록 INSERT 는 트랜잭션 마지막에 둔다(알림 테이블과 같은 원칙).
--
-- 재실행 가능: V2·V6~V12 와 같은 INFORMATION_SCHEMA 가드 프로시저. 파괴적 단계 없음.
-- 되돌리기(롤포워드): 새 V 파일에서 세 테이블 삭제(user_sanction -> abuse_report -> user_block 순).
--
-- 규칙: V1~V12 는 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상. 다음 마이그레이션은 V14.
-- =====================================================================

DROP PROCEDURE IF EXISTS v13_block_report_sanction;

DELIMITER //
CREATE PROCEDURE v13_block_report_sanction()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_block') THEN
        CREATE TABLE user_block (
            id          BIGINT      NOT NULL AUTO_INCREMENT,
            blocker_id  BIGINT      NOT NULL,                      -- 차단한 사람 -> users
            blocked_id  BIGINT      NOT NULL,                      -- 차단당한 사람 -> users
            created_at  DATETIME(6) NOT NULL,
            PRIMARY KEY (id),
            CONSTRAINT uk_user_block_pair UNIQUE (blocker_id, blocked_id),   -- blocker_id FK 인덱스 겸용(갱신 컬럼 없음)
            KEY idx_user_block_blocked (blocked_id),
            CONSTRAINT fk_user_block_blocker FOREIGN KEY (blocker_id) REFERENCES users (id),
            CONSTRAINT fk_user_block_blocked FOREIGN KEY (blocked_id) REFERENCES users (id),
            CONSTRAINT ck_user_block_self CHECK (blocker_id <> blocked_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'abuse_report') THEN
        CREATE TABLE abuse_report (
            id               BIGINT       NOT NULL AUTO_INCREMENT,
            reporter_id      BIGINT       NOT NULL,                -- 신고자 -> users
            reported_id      BIGINT       NOT NULL,                -- 신고 대상(매칭 상대) -> users
            match_id         BIGINT       NOT NULL,                -- 근거 매칭 -> exchange_match
            reason           VARCHAR(20)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
            detail           VARCHAR(500) NULL,                    -- OTHER 는 필수
            status           VARCHAR(20)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'OPEN',
            handled_by_id    BIGINT       NULL,                    -- 처리한 관리자 -> users
            handled_at       DATETIME(6)  NULL,
            handler_note     VARCHAR(300) NULL,
            sanction_id      BIGINT       NULL,                    -- 함께 건 제재 id (FK 아님, RESOLVED 일 때만)
            created_at       DATETIME(6)  NOT NULL,
            updated_at       DATETIME(6)  NOT NULL,
            open_reporter_id BIGINT GENERATED ALWAYS AS (IF(status = 'OPEN', reporter_id, NULL)) STORED,  -- FK 아님
            PRIMARY KEY (id),
            CONSTRAINT uk_abuse_report_open UNIQUE (open_reporter_id, match_id),
            KEY idx_abuse_report_reporter (reporter_id),
            KEY idx_abuse_report_reported (reported_id),
            KEY idx_abuse_report_match (match_id),
            KEY idx_abuse_report_handled_by (handled_by_id),
            KEY idx_abuse_report_status (status),
            CONSTRAINT fk_abuse_report_reporter   FOREIGN KEY (reporter_id)   REFERENCES users (id),
            CONSTRAINT fk_abuse_report_reported   FOREIGN KEY (reported_id)   REFERENCES users (id),
            CONSTRAINT fk_abuse_report_match      FOREIGN KEY (match_id)      REFERENCES exchange_match (id),
            CONSTRAINT fk_abuse_report_handled_by FOREIGN KEY (handled_by_id) REFERENCES users (id),
            CONSTRAINT ck_abuse_report_reason CHECK (reason IN ('ABUSE', 'FRAUD', 'NO_SHOW', 'FAKE_SEAT', 'OTHER')),
            CONSTRAINT ck_abuse_report_status CHECK (status IN ('OPEN', 'RESOLVED', 'DISMISSED')),
            CONSTRAINT ck_abuse_report_self CHECK (reporter_id <> reported_id),
            CONSTRAINT ck_abuse_report_detail CHECK (
                (detail IS NULL OR CHAR_LENGTH(TRIM(detail)) > 0)
                AND (reason <> 'OTHER' OR detail IS NOT NULL)),
            CONSTRAINT ck_abuse_report_note CHECK (handler_note IS NULL OR CHAR_LENGTH(TRIM(handler_note)) > 0),
            CONSTRAINT ck_abuse_report_handled CHECK (
                (status = 'OPEN' AND handled_by_id IS NULL AND handled_at IS NULL AND handler_note IS NULL AND sanction_id IS NULL)
                OR (status IN ('RESOLVED', 'DISMISSED') AND handled_by_id IS NOT NULL AND handled_at IS NOT NULL)),
            CONSTRAINT ck_abuse_report_sanction CHECK (sanction_id IS NULL OR status = 'RESOLVED')
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_sanction') THEN
        CREATE TABLE user_sanction (
            id             BIGINT       NOT NULL AUTO_INCREMENT,
            user_id        BIGINT       NOT NULL,                  -- 정지 대상 -> users
            report_id      BIGINT       NULL,                      -- 근거 신고 -> abuse_report
            type           VARCHAR(20)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'SUSPEND',
            note           VARCHAR(300) NOT NULL,                  -- 사유 메모(필수)
            starts_at      DATETIME(6)  NOT NULL,
            ends_at        DATETIME(6)  NULL,                      -- NULL = 무기한
            created_by_id  BIGINT       NOT NULL,                  -- 건 관리자 -> users
            revoked_at     DATETIME(6)  NULL,                      -- 관리자 해제 시각
            revoked_by_id  BIGINT       NULL,                      -- 해제한 관리자 -> users
            created_at     DATETIME(6)  NOT NULL,
            PRIMARY KEY (id),
            KEY idx_user_sanction_user (user_id),
            KEY idx_user_sanction_report (report_id),
            KEY idx_user_sanction_created_by (created_by_id),
            KEY idx_user_sanction_revoked_by (revoked_by_id),
            CONSTRAINT fk_user_sanction_user       FOREIGN KEY (user_id)       REFERENCES users (id),
            CONSTRAINT fk_user_sanction_report     FOREIGN KEY (report_id)     REFERENCES abuse_report (id),
            CONSTRAINT fk_user_sanction_created_by FOREIGN KEY (created_by_id) REFERENCES users (id),
            CONSTRAINT fk_user_sanction_revoked_by FOREIGN KEY (revoked_by_id) REFERENCES users (id),
            CONSTRAINT ck_user_sanction_type CHECK (type IN ('SUSPEND')),
            CONSTRAINT ck_user_sanction_note CHECK (CHAR_LENGTH(TRIM(note)) > 0),
            CONSTRAINT ck_user_sanction_period CHECK (ends_at IS NULL OR ends_at > starts_at),
            CONSTRAINT ck_user_sanction_revoke CHECK (
                (revoked_at IS NULL AND revoked_by_id IS NULL)
                OR (revoked_at IS NOT NULL AND revoked_by_id IS NOT NULL)),
            CONSTRAINT ck_user_sanction_self CHECK (user_id <> created_by_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;
END//
DELIMITER ;

CALL v13_block_report_sanction();
DROP PROCEDURE v13_block_report_sanction;
