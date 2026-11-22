-- =====================================================================
-- V30: 채팅 사진 보내기 (예매내역 확인용)
--
-- 사용자 결정(2026-11-22): 이전의 '첨부 없음'을 바꿔 사진 1장짜리 메시지를 허용한다. 사진에는 이름·예매번호가 들어갈 수 있어 개인정보로 다룬다.
--  - chat_message.image_key         : 저장 파일 이름(소문자 UUID, 확장자 없음). NULL 이면 텍스트 메시지. 파일은 DB 가 아니라 서버 디스크
--                                    (matjari.upload.dir/chat/)에 있고 형식은 읽을 때 파일 시작 바이트로 판정한다.
--  - chat_message.image_deleted_at : 보관 기간(매칭이 닫힌 뒤 30일)이 지나 파일을 지운 시각. NULL 이면 파일이 있다(또는 아직 정리 전).
--  - ck_chat_message_content 재작성 : 본문이 비어 있지 않거나 사진이 있어야 한다. 사진 메시지의 content 는 빈 문자열('')이다.
--
-- chat_message 는 append-only(V11)였다. 이 파일부터 image_deleted_at 하나만 예외로, 정리 작업이 PK 로 한 번 갱신한다
-- (NULL -> 시각, 되돌리지 않는다). 그 밖의 컬럼은 계속 수정하지 않는다.
--
-- idx_chat_message_image_key: FK 인덱스가 아니고 갱신되지 않는 단일 컬럼(image_key 는 INSERT 뒤 불변)이라 교착 규칙(FK 인덱스에 갱신 컬럼 금지)에
-- 걸리지 않는다. 정리 작업이 '사진이 있는 행'과 '고아 파일 키가 쓰이는지'를 찾는 데 쓴다(텍스트 메시지는 NULL 이라 인덱스 행이 거의 없다).
--
-- 재실행 가능: V11·V15 와 같은 INFORMATION_SCHEMA 가드 프로시저. 파괴적 단계 없음(기존 행은 image_key NULL + 비어 있지 않은 본문이라 새 CHECK 를 만족).
-- ck_chat_message_content 는 CHECK_CLAUSE 에 image_key 가 없을 때만 한 문장(DROP CHECK + ADD)으로 바꾸고, 이름이 아예 없으면 새로 만든다.
-- 되돌리기(롤포워드): 사진 메시지 행을 지운 뒤 새 V 파일에서 CHECK 를 되돌리고 컬럼·인덱스를 DROP.
--
-- 규칙: V1~V28 은 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상. V29 는 다른 작업(notification)이 쓴다.
-- =====================================================================

DROP PROCEDURE IF EXISTS v30_chat_message_image;

DELIMITER //
CREATE PROCEDURE v30_chat_message_image()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_message' AND COLUMN_NAME = 'image_key') THEN
        ALTER TABLE chat_message
            ADD COLUMN image_key        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER client_message_id,
            ADD COLUMN image_deleted_at DATETIME(6) NULL AFTER image_key;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_message' AND INDEX_NAME = 'idx_chat_message_image_key') THEN
        ALTER TABLE chat_message ADD KEY idx_chat_message_image_key (image_key);
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_chat_message_content'
                 AND CHECK_CLAUSE NOT LIKE '%image_key%') THEN
        ALTER TABLE chat_message
            DROP CHECK ck_chat_message_content,
            ADD CONSTRAINT ck_chat_message_content CHECK (CHAR_LENGTH(TRIM(content)) > 0 OR image_key IS NOT NULL);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_chat_message_content') THEN
        ALTER TABLE chat_message
            ADD CONSTRAINT ck_chat_message_content CHECK (CHAR_LENGTH(TRIM(content)) > 0 OR image_key IS NOT NULL);
    END IF;

    -- 지운 시각은 사진이 있는 행에만 의미가 있다
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_message'
                     AND CONSTRAINT_NAME = 'ck_chat_message_image_deleted') THEN
        ALTER TABLE chat_message
            ADD CONSTRAINT ck_chat_message_image_deleted CHECK (image_deleted_at IS NULL OR image_key IS NOT NULL);
    END IF;
END//
DELIMITER ;

CALL v30_chat_message_image();
DROP PROCEDURE v30_chat_message_image;
