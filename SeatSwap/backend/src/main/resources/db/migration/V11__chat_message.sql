-- =====================================================================
-- V11: 채팅 (chat_message, chat_read_state)
--
-- 설계: feature/chat 확정 설계 (사용자 승인, 2026-10-11)
--  - chat_message: append-only. 만든 뒤 수정·삭제하지 않는다(신고 근거 보관, 탈퇴는 익명화). 본문은 텍스트만 최대 1000자.
--    client_message_id: 클라이언트가 만드는 전송 id(멱등 재전송용). (client_message_id, sender_id) 유니크이며
--    ASCII 이진 비교(ascii_bin)로 대소문자까지 구분한다. 시스템 메시지는 저장하지 않는다.
--  - chat_read_state: (match_id, user_id)당 1행의 '마지막으로 읽은 메시지 id' 포인터. 단조 증가만 허용한다
--    (UPDATE ... GREATEST). last_read_message_id 는 FK 가 아니고(0 = 아무것도 안 읽음) 메시지가 절단된 값을 저장한다.
--  - FK 인덱스는 단일 컬럼(idx_chat_message_match, idx_chat_message_sender, idx_chat_read_state_user).
--    갱신 컬럼(last_read_message_id, updated_at)은 FK 인덱스에 섞지 않는다. FK 에는 ON DELETE/UPDATE 동작을 붙이지 않는다.
--    chat_read_state 의 match_id FK 는 PK(match_id, user_id) 의 앞 컬럼이 인덱스 역할을 한다.
--  - 메시지 페이징(match_id = ? AND id < ? ORDER BY id DESC)은 InnoDB 보조 인덱스가 PK 를 끝에 포함하므로
--    idx_chat_message_match 하나로 정렬 없이 처리된다.
--
-- 재실행 가능: V2·V6~V10 와 같은 INFORMATION_SCHEMA 가드 프로시저. 파괴적 단계 없음.
-- 되돌리기(롤포워드): 새 V 파일에서 두 테이블 삭제.
--
-- 규칙: V1~V10 은 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상. 다음 마이그레이션은 V12.
-- =====================================================================

DROP PROCEDURE IF EXISTS v11_chat_message;

DELIMITER //
CREATE PROCEDURE v11_chat_message()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_message') THEN
        CREATE TABLE chat_message (
            id                BIGINT        NOT NULL AUTO_INCREMENT,
            match_id          BIGINT        NOT NULL,                  -- -> exchange_match
            sender_id         BIGINT        NOT NULL,                  -- -> users
            content           VARCHAR(1000) NOT NULL,
            client_message_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            created_at        DATETIME(6)   NOT NULL,
            PRIMARY KEY (id),
            KEY idx_chat_message_match (match_id),
            KEY idx_chat_message_sender (sender_id),
            CONSTRAINT uk_chat_message_client UNIQUE (client_message_id, sender_id),
            CONSTRAINT fk_chat_message_match  FOREIGN KEY (match_id)  REFERENCES exchange_match (id),
            CONSTRAINT fk_chat_message_sender FOREIGN KEY (sender_id) REFERENCES users (id),
            CONSTRAINT ck_chat_message_content   CHECK (CHAR_LENGTH(TRIM(content)) > 0),
            CONSTRAINT ck_chat_message_client_id CHECK (CHAR_LENGTH(client_message_id) BETWEEN 8 AND 36)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_read_state') THEN
        CREATE TABLE chat_read_state (
            match_id             BIGINT      NOT NULL,                 -- -> exchange_match
            user_id              BIGINT      NOT NULL,                 -- -> users
            last_read_message_id BIGINT      NOT NULL,                 -- FK 아님, 0 = 읽은 것 없음, 단조 증가
            updated_at           DATETIME(6) NOT NULL,
            PRIMARY KEY (match_id, user_id),
            KEY idx_chat_read_state_user (user_id),
            CONSTRAINT fk_chat_read_state_match FOREIGN KEY (match_id) REFERENCES exchange_match (id),
            CONSTRAINT fk_chat_read_state_user  FOREIGN KEY (user_id)  REFERENCES users (id),
            CONSTRAINT ck_chat_read_state_pointer CHECK (last_read_message_id >= 0)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;
END//
DELIMITER ;

CALL v11_chat_message();
DROP PROCEDURE v11_chat_message;
