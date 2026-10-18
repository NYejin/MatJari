-- =====================================================================
-- V21: 공연별 좌석 구역 목록 (performance_zone) + 좌석표 가로/세로 비율 (performance.seatmap_aspect)
--
-- 설계: feature/ai-extract-backend (2026-10-18)
--  - 공연 등록 때 AI 가 상세 이미지에서 구역 이름과 구역 모양을 읽고, 사용자가 확인·수정한 목록을 공연과 함께 저장한다.
--    티켓 등록·교환 조건에서 구역을 드롭다운으로 고르는 데 쓴다(목록이 없는 공연은 기존처럼 텍스트 입력).
--    원본 이미지는 저장하지 않는다. 모양은 1단계에서는 저장만 하고, 2단계에서 SVG 클릭 선택에 쓴다.
--  - 이름이 같은 구역은 한 구역으로 묶는다(사용자 결정): (performance_id, name_key) 당 1행,
--    모양은 다각형 하나가 아니라 다각형 배열(MultiPolygon)이다.
--
--  performance_zone
--  - name: 사용자에게 보이는 표기(공백 정리 후 1~30자, 공백만은 불가). 기본 collation(utf8mb4_0900_ai_ci).
--  - name_key: 정규화 키. ticket.zone_key 와 같은 타입·길이(VARCHAR(50))·collation(utf8mb4_bin).
--    정규화는 서비스(SeatKeyNormalizer.zoneKey)가 하고 DB 는 키만 저장한다. 이름이 30자여도 NFKC 로 키가 길어질 수 있어
--    키 길이는 zone_key 와 같은 50자로 둔다(ticket.zone_key 와 그대로 비교·조인할 수 있다).
--  - sort_order: 드롭다운 표시 순서(0 이상, exchange_want_range.sort_order 와 같은 규칙). 공연 안 유일성은 강제하지 않는다.
--  - shape_polygons (JSON NULL): [[[x,y],...],[[x,y],...]] (바깥 배열 = 조각, 안쪽 = 꼭짓점, 좌표 0~1000 정규화).
--    DB CHECK(MySQL 8.0.16 에서 동작하는 함수만): 배열, 조각 1~8개, 각 조각은 배열이고 꼭짓점 3~40개, 저장 크기 상한.
--    좌표가 [숫자, 숫자] 쌍인지와 0~1000 범위는 DB 가 검사하지 않는다(JSON_SCHEMA_VALID 는 8.0.17 부터) → 서비스 검증.
--  - created_at 만 둔다(행은 갱신하지 않고 목록 교체는 삭제 후 INSERT 를 전제로 한다).
--
--  인덱스·잠금 검토
--  - 유일 키는 (name_key, performance_id) 순서로 둔다. 뜻은 UNIQUE(performance_id, name_key) 와 같다.
--    performance_id 를 선두로 두면 그 유일 키가 FK 인덱스로 쓰일 수 있고, 나중에 이름 수정으로 name_key 가 UPDATE 되면
--    FK 인덱스 변경으로 보고 부모 performance 행에 공유 잠금이 걸린다(V5·V7 에서 재현·회피한 문제와 같다).
--    그래서 FK 전용 단일 컬럼 인덱스 idx_performance_zone_performance(performance_id) 를 따로 두고, 공연별 목록 조회도 이것으로 한다.
--  - FK 는 ON DELETE/UPDATE 동작 없음(삭제 전파 없음, 공연은 삭제하지 않는다).
--  - 이 테이블은 티켓 id↑ → 요청 id↑ → 매칭 잠금 사슬 밖이다. 티켓·요청·매칭은 이 테이블을 FK 로 참조하지 않고
--    (ticket.zone_* 는 텍스트이며 FK 가 아니다) 쓰기는 공연 등록(또는 관리자 승인) 트랜잭션에서 공연 행 다음에만 한다.
--    교환 경로는 잠금 없이 읽기만 한다.
--
--  performance.seatmap_aspect DECIMAL(6,3) NULL: 좌석표 가로/세로 비율(모양을 그릴 때 쓴다). 값이 있으면 0 보다 커야 한다.
--
-- 재실행 가능: INFORMATION_SCHEMA 가드 프로시저(컬럼·CHECK·테이블이 이미 있으면 건너뜀). 기존 데이터 변경 없음, 파괴적 단계 없음.
-- 되돌리기(롤포워드): 새 V 파일에서 performance_zone 삭제, seatmap_aspect 의 CHECK·컬럼 삭제.
--
-- 규칙: V1~V20 은 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상. 다음 마이그레이션은 V22.
-- =====================================================================

DROP PROCEDURE IF EXISTS v21_performance_zone;

DELIMITER //
CREATE PROCEDURE v21_performance_zone()
BEGIN
    -- 1) performance.seatmap_aspect
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance'
                     AND COLUMN_NAME = 'seatmap_aspect') THEN
        ALTER TABLE performance ADD COLUMN seatmap_aspect DECIMAL(6,3) NULL AFTER venue_name;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE()
                     AND CONSTRAINT_NAME = 'ck_performance_seatmap_aspect') THEN
        ALTER TABLE performance ADD CONSTRAINT ck_performance_seatmap_aspect
            CHECK (seatmap_aspect IS NULL OR seatmap_aspect > 0);
    END IF;

    -- 2) performance_zone
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance_zone') THEN
        CREATE TABLE performance_zone (
            id              BIGINT      NOT NULL AUTO_INCREMENT,
            performance_id  BIGINT      NOT NULL,                  -- -> performance
            name            VARCHAR(30) NOT NULL,                  -- 표시용 구역 이름
            name_key        VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,  -- ticket.zone_key 와 같은 규칙
            sort_order      SMALLINT    NOT NULL,
            shape_polygons  JSON        NULL,                      -- [[[x,y],...],...] 0~1000 정규화 좌표
            created_at      DATETIME(6) NOT NULL,
            PRIMARY KEY (id),
            CONSTRAINT uk_performance_zone_name_key UNIQUE (name_key, performance_id),
            KEY idx_performance_zone_performance (performance_id),
            CONSTRAINT fk_performance_zone_performance FOREIGN KEY (performance_id) REFERENCES performance (id),
            CONSTRAINT ck_performance_zone_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
            CONSTRAINT ck_performance_zone_name_key CHECK (CHAR_LENGTH(name_key) > 0),
            CONSTRAINT ck_performance_zone_sort CHECK (sort_order >= 0),
            CONSTRAINT ck_performance_zone_shape CHECK (
                shape_polygons IS NULL OR (
                    JSON_TYPE(shape_polygons) = 'ARRAY'
                    AND JSON_LENGTH(shape_polygons) BETWEEN 1 AND 8
                    AND JSON_STORAGE_SIZE(shape_polygons) <= 16384
                    AND (JSON_LENGTH(shape_polygons) < 1
                         OR (JSON_TYPE(JSON_EXTRACT(shape_polygons, '$[0]')) = 'ARRAY'
                             AND JSON_LENGTH(shape_polygons, '$[0]') BETWEEN 3 AND 40))
                    AND (JSON_LENGTH(shape_polygons) < 2
                         OR (JSON_TYPE(JSON_EXTRACT(shape_polygons, '$[1]')) = 'ARRAY'
                             AND JSON_LENGTH(shape_polygons, '$[1]') BETWEEN 3 AND 40))
                    AND (JSON_LENGTH(shape_polygons) < 3
                         OR (JSON_TYPE(JSON_EXTRACT(shape_polygons, '$[2]')) = 'ARRAY'
                             AND JSON_LENGTH(shape_polygons, '$[2]') BETWEEN 3 AND 40))
                    AND (JSON_LENGTH(shape_polygons) < 4
                         OR (JSON_TYPE(JSON_EXTRACT(shape_polygons, '$[3]')) = 'ARRAY'
                             AND JSON_LENGTH(shape_polygons, '$[3]') BETWEEN 3 AND 40))
                    AND (JSON_LENGTH(shape_polygons) < 5
                         OR (JSON_TYPE(JSON_EXTRACT(shape_polygons, '$[4]')) = 'ARRAY'
                             AND JSON_LENGTH(shape_polygons, '$[4]') BETWEEN 3 AND 40))
                    AND (JSON_LENGTH(shape_polygons) < 6
                         OR (JSON_TYPE(JSON_EXTRACT(shape_polygons, '$[5]')) = 'ARRAY'
                             AND JSON_LENGTH(shape_polygons, '$[5]') BETWEEN 3 AND 40))
                    AND (JSON_LENGTH(shape_polygons) < 7
                         OR (JSON_TYPE(JSON_EXTRACT(shape_polygons, '$[6]')) = 'ARRAY'
                             AND JSON_LENGTH(shape_polygons, '$[6]') BETWEEN 3 AND 40))
                    AND (JSON_LENGTH(shape_polygons) < 8
                         OR (JSON_TYPE(JSON_EXTRACT(shape_polygons, '$[7]')) = 'ARRAY'
                             AND JSON_LENGTH(shape_polygons, '$[7]') BETWEEN 3 AND 40))))
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;
END//
DELIMITER ;

CALL v21_performance_zone();
DROP PROCEDURE v21_performance_zone;
