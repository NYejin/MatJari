-- =====================================================================
-- V33: 티켓·교환 희망 조건 사진 첨부 (listing_image)
--
-- 사용자 결정(2026-11-26): 티켓('내 자리' 사진)과 교환 희망 조건('원하는 자리' 좌석표 스크린샷)에 각각 최대 3장 사진을 붙인다.
-- 사진은 소유자·관리자·그 티켓(요청)과 엮인 매칭의 참여자·지금 후보 관계인 사용자만 볼 수 있다(권한은 서비스가 판정한다).
--  - listing_image.owner_id   : 올린 사람(=티켓 보유자) -> users. 탈퇴 정리에서 이 컬럼으로 한 번에 찾는다.
--  - listing_image.ticket_id  : 티켓 사진이면 값, 요청 사진이면 NULL -> ticket.
--  - listing_image.request_id : 요청 사진이면 값, 티켓 사진이면 NULL -> exchange_request.
--    둘 중 정확히 하나만 값이다(ck_listing_image_target).
--  - image_key  : 저장 파일 이름(소문자 UUID, 확장자 없음). 파일은 DB 가 아니라 서버 디스크(matjari.upload.dir/listing/)에 둔다.
--  - sort_order : 같은 티켓(요청) 안의 표시 순서(0부터).
--  - deleted_at : 정리 작업(보관 기간 경과)·회원 탈퇴가 파일을 지운 시각. NULL 이면 파일이 있다(또는 정리 전).
--                 사용자가 사진을 직접 지우면 행 자체를 지운다(하드 삭제).
--
-- FK 인덱스는 단일 컬럼이며 갱신 컬럼을 붙이지 않는다(교착 규칙). deleted_at 은 PK 로 한 번만 갱신된다(NULL -> 시각).
-- ON DELETE/UPDATE 동작은 붙이지 않는다.
-- 재실행 가능: V13 등과 같은 INFORMATION_SCHEMA 가드 프로시저. 파괴적 단계 없음.
-- 되돌리기(롤포워드): 새 V 파일에서 listing_image 를 DROP 한다(파일은 정리 작업이 고아로 지운다).
--
-- 규칙: V1~V31 은 수정하지 않는다. V32 는 다른 작업(performance_source)이 쓴다. 요구 버전 MySQL 8.0.16 이상.
-- =====================================================================

DROP PROCEDURE IF EXISTS v33_listing_image;

DELIMITER //
CREATE PROCEDURE v33_listing_image()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'listing_image') THEN
        CREATE TABLE listing_image (
            id          BIGINT      NOT NULL AUTO_INCREMENT,
            owner_id    BIGINT      NOT NULL,
            ticket_id   BIGINT      NULL,
            request_id  BIGINT      NULL,
            image_key   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            sort_order  TINYINT     NOT NULL DEFAULT 0,
            created_at  DATETIME(6) NOT NULL,
            deleted_at  DATETIME(6) NULL,
            PRIMARY KEY (id),
            KEY idx_listing_image_owner (owner_id),
            KEY idx_listing_image_ticket (ticket_id),
            KEY idx_listing_image_request (request_id),
            KEY idx_listing_image_key (image_key),
            CONSTRAINT fk_listing_image_owner   FOREIGN KEY (owner_id)   REFERENCES users (id),
            CONSTRAINT fk_listing_image_ticket  FOREIGN KEY (ticket_id)  REFERENCES ticket (id),
            CONSTRAINT fk_listing_image_request FOREIGN KEY (request_id) REFERENCES exchange_request (id),
            CONSTRAINT ck_listing_image_target CHECK ((ticket_id IS NULL) <> (request_id IS NULL)),
            CONSTRAINT ck_listing_image_order CHECK (sort_order >= 0)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;
END//
DELIMITER ;

CALL v33_listing_image();
DROP PROCEDURE v33_listing_image;
