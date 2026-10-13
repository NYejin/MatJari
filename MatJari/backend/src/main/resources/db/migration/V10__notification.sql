-- =====================================================================
-- V10: 알림 (새 제안 / 상대가 예약 / 상대가 예약 취소)
--
-- 설계: feature/notifications 확정 설계 (사용자 승인, 2026-10-11)
--  - notification: 수신자(user_id)별 알림. type 은 PROPOSAL_RECEIVED / MATCH_RESERVED / RESERVATION_CANCELED 3종이며
--    세 종류 모두 match_id 가 필수다(ck_notification_match_required). 개인정보(닉네임·좌석)는 저장하지 않고 조회 때 조인한다.
--  - dedup_key: 한 번만 생기는 알림(PROPOSAL_RECEIVED:{matchId})만 채운다. 예약/예약 취소는 반복마다 매번 1건이라 NULL (UNIQUE 는 NULL 중복 허용).
--  - read_at: NULL = 안 읽음. unread_user_id = 안 읽은 알림의 user_id(읽으면 NULL)인 STORED 생성 컬럼으로,
--    안 읽은 개수 조회 전용 인덱스(idx_notification_unread)를 위한 것이다. FK 가 아니므로 갱신 컬럼(read_at)이 FK 인덱스에 섞이지 않는다.
--  - FK 인덱스는 단일 컬럼(idx_notification_user, idx_notification_match). FK 에는 ON DELETE/UPDATE 동작을 붙이지 않는다.
--  - 닫힌 매칭(CANCELED/COMPLETED)의 알림도 남긴다. 알림이 사라지는 경로는 사용자의 '읽은 알림 삭제'뿐이다.
--
-- 재실행 가능: V2·V6~V9 와 같은 INFORMATION_SCHEMA 가드 프로시저. 파괴적 단계 없음.
-- 되돌리기(롤포워드): 새 V 파일에서 notification 삭제.
--
-- 규칙: V1~V9 는 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상.
-- =====================================================================

DROP PROCEDURE IF EXISTS v10_notification;

DELIMITER //
CREATE PROCEDURE v10_notification()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification') THEN
        CREATE TABLE notification (
            id             BIGINT       NOT NULL AUTO_INCREMENT,
            user_id        BIGINT       NOT NULL,                 -- 수신자 -> users
            type           VARCHAR(40)  NOT NULL,
            match_id       BIGINT       NULL,                     -- -> exchange_match (3종 모두 필수, CHECK)
            dedup_key      VARCHAR(100) NULL,                     -- PROPOSAL_RECEIVED:{matchId}; 예약/예약 취소는 NULL
            read_at        DATETIME(6)  NULL,                     -- NULL = 안 읽음
            created_at     DATETIME(6)  NOT NULL,
            unread_user_id BIGINT GENERATED ALWAYS AS (IF(read_at IS NULL, user_id, NULL)) STORED,
            PRIMARY KEY (id),
            CONSTRAINT uk_notification_dedup UNIQUE (dedup_key),
            KEY idx_notification_user (user_id),
            KEY idx_notification_match (match_id),
            KEY idx_notification_unread (unread_user_id),         -- FK 아님
            CONSTRAINT fk_notification_user  FOREIGN KEY (user_id)  REFERENCES users (id),
            CONSTRAINT fk_notification_match FOREIGN KEY (match_id) REFERENCES exchange_match (id),
            CONSTRAINT ck_notification_type CHECK (type IN ('PROPOSAL_RECEIVED', 'MATCH_RESERVED', 'RESERVATION_CANCELED')),
            CONSTRAINT ck_notification_match_required CHECK (match_id IS NOT NULL)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;
END//
DELIMITER ;

CALL v10_notification();
DROP PROCEDURE v10_notification;
