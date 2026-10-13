-- =====================================================================
-- V3: ticket 에 좌석(구역·열·번)·상태·시각 컬럼 추가, 활성 좌석 유일 제약
--
-- 설계: 산출물/08_ERD/exchange-schema-design.md 1.1, 4.1 (2026-10-08 확정)
--  - zone_label/zone_key, row_label/row_key, col_label/col_key: 표시용 원문(공백 정리)과 정규화 키.
--    키는 utf8mb4_bin (대소문자·악센트 구분; 정규화는 서비스가 NFKC·대문자·공백 제거 등으로 한다).
--  - status ACTIVE/INACTIVE (INACTIVE = 사용자가 내린 티켓, 소프트 삭제).
--  - active_flag: status 가 ACTIVE 일 때만 1, 아니면 NULL 인 생성 컬럼. NULL 은 UNIQUE 에서 제외되므로
--    uk_ticket_active_seat 는 "같은 회차·구역·열·번의 활성 티켓은 1개"만 강제한다.
--  - row_label/col_label 을 NOT NULL VARCHAR(20) 로 변경 (기존 VARCHAR(255) NULL).
--  - idx_ticket_user_status: 내 활성 티켓 수(상한)·내 티켓 목록.
--
-- 가드
--  - 새 NOT NULL 컬럼(구역 등)에 채울 값이 없으므로 ticket 에 행이 있으면 아무것도 바꾸기 전에 SIGNAL 로 실패한다.
--    테스트 행을 삭제한 뒤 `flyway repair` 로 실패 기록을 지우고 다시 적용한다.
--    (가드에서 실패하면 임시 프로시저가 남을 수 있어 먼저 DROP IF EXISTS 한다.)
--
-- 규칙: V1·V2 는 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상 (CHECK 강제).
-- =====================================================================

DROP PROCEDURE IF EXISTS v3_guard_ticket_empty;

DELIMITER //
CREATE PROCEDURE v3_guard_ticket_empty()
BEGIN
    IF EXISTS (SELECT 1 FROM ticket) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V3: ticket 에 행이 있어 구역 NOT NULL 컬럼을 추가할 수 없음. 테스트 행 삭제 후 flyway repair';
    END IF;
END//
DELIMITER ;

CALL v3_guard_ticket_empty();
DROP PROCEDURE v3_guard_ticket_empty;

ALTER TABLE ticket
    ADD COLUMN zone_label VARCHAR(50) NOT NULL AFTER performance_session_id,
    ADD COLUMN zone_key   VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL AFTER zone_label,
    MODIFY COLUMN row_label VARCHAR(20) NOT NULL,
    ADD COLUMN row_key    VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL AFTER row_label,
    MODIFY COLUMN col_label VARCHAR(20) NOT NULL,
    ADD COLUMN col_key    VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL AFTER col_label,
    ADD COLUMN status     VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN created_at DATETIME(6) NOT NULL,
    ADD COLUMN updated_at DATETIME(6) NOT NULL,
    ADD COLUMN active_flag TINYINT GENERATED ALWAYS AS (IF(status = 'ACTIVE', 1, NULL)) STORED,
    ADD CONSTRAINT ck_ticket_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    ADD CONSTRAINT uk_ticket_active_seat UNIQUE (performance_session_id, zone_key, row_key, col_key, active_flag),
    ADD KEY idx_ticket_user_status (user_id, status);
