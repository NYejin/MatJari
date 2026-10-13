-- =====================================================================
-- V2: venue 테이블 삭제 -> performance.venue_name (공연장 이름 텍스트)
--
-- 변경 요약 (2026-10-08 사용자 확정)
--  - 공연장은 공연의 텍스트 속성일 뿐이다. 공연장 검색·추가·정식 등록(VERIFIED)·주소는 모두 없앤다.
--  - performance.venue_name VARCHAR(100) NOT NULL 추가 (title 바로 뒤).
--    기존 행은 venue.name 값으로 채운다 (표시용 이름 그대로, 정규화하지 않는다).
--  - performance.venue_id 와 이를 참조하는 FK 삭제 (FK 이름은 information_schema 에서 조회해 동적 SQL 로 지운다).
--    venue_id 단일 컬럼 인덱스(같은 이름)는 컬럼이 사라질 때 함께 사라진다.
--  - venue 테이블 삭제.
--  - 공연장 이름 중복 판정·정규화 컬럼·유일 제약은 두지 않는다.
--
-- 되돌릴 수 없는 손실
--  - venue.address, 정식 등록 정보(status, verified_by, verified_at), 정규화 이름(normalized_name)은 사라진다.
--    필요하면 적용 전에 venue 테이블을 백업한다.
--
-- 가드 / 재시도
--  - 맨 앞 단계에서 venue_name 을 채우고, 채워지지 않은 공연(venue 매칭이 없는 행)이 있으면
--    아무것도 지우기 전에 SIGNAL 로 실패한다. MySQL 은 DDL 이 트랜잭션에 묶이지 않으므로
--    실패 후에는 venue_name 컬럼만 추가된 상태일 수 있다. 이 프로시저는 각 단계가 이미 적용됐는지
--    확인하며 진행하므로, 데이터를 고친 뒤 `flyway repair` 로 실패 기록을 지우고 다시 적용하면 이어서 진행된다.
--    (가드에서 실패하면 임시 프로시저가 남을 수 있어 먼저 DROP IF EXISTS 한다.)
--
-- 적용 전 점검 쿼리
--   SELECT COUNT(*) FROM performance p LEFT JOIN venue v ON v.id = p.venue_id WHERE v.id IS NULL;  -- 0 이어야 한다
--   SELECT MAX(CHAR_LENGTH(name)) FROM venue;                                                       -- 100 이하 (컬럼 길이 동일)
--
-- 규칙: V1 은 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상. 기본 collation utf8mb4_0900_ai_ci.
-- =====================================================================

DROP PROCEDURE IF EXISTS v2_drop_venue;

DELIMITER //
CREATE PROCEDURE v2_drop_venue()
BEGIN
    DECLARE fk_name VARCHAR(64) DEFAULT NULL;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance' AND COLUMN_NAME = 'venue_name') THEN
        ALTER TABLE performance ADD COLUMN venue_name VARCHAR(100) NULL AFTER title;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance' AND COLUMN_NAME = 'venue_id') THEN
        UPDATE performance p JOIN venue v ON v.id = p.venue_id SET p.venue_name = v.name WHERE p.venue_name IS NULL;

        IF EXISTS (SELECT 1 FROM performance WHERE venue_name IS NULL) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'V2: venue_name 백필 실패 (venue 매칭 없는 공연). 데이터 확인 후 flyway repair';
        END IF;

        SELECT CONSTRAINT_NAME INTO fk_name FROM information_schema.KEY_COLUMN_USAGE
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance' AND COLUMN_NAME = 'venue_id'
           AND REFERENCED_TABLE_NAME = 'venue'
         LIMIT 1;
        IF fk_name IS NOT NULL THEN
            SET @v2_drop_fk_sql = CONCAT('ALTER TABLE performance DROP FOREIGN KEY `', fk_name, '`');
            PREPARE v2_drop_fk FROM @v2_drop_fk_sql;
            EXECUTE v2_drop_fk;
            DEALLOCATE PREPARE v2_drop_fk;
        END IF;

        ALTER TABLE performance MODIFY COLUMN venue_name VARCHAR(100) NOT NULL, DROP COLUMN venue_id;
    END IF;

    DROP TABLE IF EXISTS venue;
END//
DELIMITER ;

CALL v2_drop_venue();
DROP PROCEDURE v2_drop_venue;
