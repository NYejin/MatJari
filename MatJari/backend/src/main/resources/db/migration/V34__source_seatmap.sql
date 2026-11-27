-- =====================================================================
-- V34: 추가 예매처 링크의 좌석표 지도 (performance_source 지도 컬럼 + performance_zone.source_id)
--
-- 배경(2026-11-27 사용자 지시): 한 공연을 멜론과 NOL 이 구역을 나눠 팔면 대표 링크(멜론) 상세 이미지에서 읽은 지도에는 멜론 구역만 색이
--   있고 NOL 구역은 회색이라 고를 수 없다. 추가로 붙은 링크의 좌석표도 읽어 예매처별 지도를 따로 둔다. 두 지도는 좌표계(원본 이미지)가 다르므로
--   합치지 않고 지도별로 저장한다. 매칭은 구역 텍스트 키로만 비교하므로 바뀌지 않는다.
--
--  performance_source (지도 정보. 대표 링크 행에는 채우지 않는다: 대표 지도는 performance 쪽 컬럼 그대로)
--  - seatmap_aspect DECIMAL(6,3): performance.seatmap_aspect 와 같은 타입. 값이 있으면 0 초과.
--  - seatmap_image_url / seatmap_crop_left·top·width·height: V31 과 같은 타입·CHECK 규칙(다섯 값이 함께 NULL 이거나 함께 값).
--  - map_status VARCHAR(16) NOT NULL DEFAULT 'NONE': NONE(지도 없음) · PENDING(읽는 중) · READY(읽음, 구역이 0개일 수 있음) · FAILED(읽지 못함).
--  - map_updated_at DATETIME(6): 마지막 상태 변경 시각.
--
--  performance_zone
--  - source_id BIGINT NULL -> performance_source: NULL = 대표 지도(기존 데이터·코드 호환), 값 = 그 링크의 지도. FK 인덱스는 단일 컬럼 idx_performance_zone_source.
--  - 이름 유일 제약: 기존 uk_performance_zone_name_key (name_key, performance_id) 는 공연 단위였다. 지도 단위가 되도록
--    생성 컬럼 source_scope = IFNULL(source_id, 0)(STORED) 을 더해 (name_key, performance_id, source_scope) 로 바꾼다.
--    NULL 은 유니크에서 서로 다른 값으로 취급되므로 생성 컬럼이 필요하다(대표 지도 안에서도 같은 이름은 여전히 하나).
--    제약 이름은 그대로 둔다(애플리케이션이 이 이름으로 위반을 구분한다). FK 인덱스(idx_performance_zone_performance)는 따로 있어 유니크 교체와 무관하다.
--
--  잠금·인덱스: 갱신되는 것은 performance_source 의 지도 컬럼(map_status 등)뿐이고 이들은 인덱스·FK 에 포함되지 않는다(FK 인덱스에 갱신 컬럼 금지 규칙).
--   쓰기는 공연 행 FOR UPDATE 뒤에만 한다. 이 테이블들은 티켓 id↑ → 요청 id↑ → 매칭 잠금 사슬 밖이다.
--
-- 재실행 가능: INFORMATION_SCHEMA 가드 프로시저. 기존 데이터 변경 없음(기존 구역은 source_id NULL), 파괴적 단계 없음.
-- 되돌리기(롤포워드): 새 V 파일에서 유니크·생성 컬럼·source_id·지도 컬럼 삭제(추가 지도 구역을 먼저 지운다).
--
-- 규칙: V1~V33 은 수정하지 않는다. 다음 마이그레이션은 V35.
-- =====================================================================

DROP PROCEDURE IF EXISTS v34_source_seatmap;

DELIMITER //
CREATE PROCEDURE v34_source_seatmap()
BEGIN
    -- 1) performance_source 지도 컬럼
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance_source'
                     AND COLUMN_NAME = 'map_status') THEN
        ALTER TABLE performance_source
            ADD COLUMN seatmap_aspect      DECIMAL(6,3)  NULL,
            ADD COLUMN seatmap_image_url   VARCHAR(2048) NULL,
            ADD COLUMN seatmap_crop_left   DECIMAL(8,6)  NULL,
            ADD COLUMN seatmap_crop_top    DECIMAL(8,6)  NULL,
            ADD COLUMN seatmap_crop_width  DECIMAL(8,6)  NULL,
            ADD COLUMN seatmap_crop_height DECIMAL(8,6)  NULL,
            ADD COLUMN map_status          VARCHAR(16)   NOT NULL DEFAULT 'NONE',
            ADD COLUMN map_updated_at      DATETIME(6)   NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_performance_source_map_status') THEN
        ALTER TABLE performance_source ADD CONSTRAINT ck_performance_source_map_status
            CHECK (map_status IN ('NONE', 'PENDING', 'READY', 'FAILED'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_performance_source_seatmap_aspect') THEN
        ALTER TABLE performance_source ADD CONSTRAINT ck_performance_source_seatmap_aspect
            CHECK (seatmap_aspect IS NULL OR seatmap_aspect > 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_performance_source_seatmap_image') THEN
        ALTER TABLE performance_source ADD CONSTRAINT ck_performance_source_seatmap_image CHECK (
            (seatmap_image_url IS NULL AND seatmap_crop_left IS NULL AND seatmap_crop_top IS NULL
                AND seatmap_crop_width IS NULL AND seatmap_crop_height IS NULL)
            OR
            (seatmap_image_url IS NOT NULL AND seatmap_crop_left IS NOT NULL AND seatmap_crop_top IS NOT NULL
                AND seatmap_crop_width IS NOT NULL AND seatmap_crop_height IS NOT NULL
                AND seatmap_crop_left >= 0 AND seatmap_crop_left < 1
                AND seatmap_crop_top >= 0 AND seatmap_crop_top < 1
                AND seatmap_crop_width > 0 AND seatmap_crop_width <= 1
                AND seatmap_crop_height > 0 AND seatmap_crop_height <= 1
                AND seatmap_crop_left + seatmap_crop_width <= 1.000001
                AND seatmap_crop_top + seatmap_crop_height <= 1.000001));
    END IF;

    -- 2) performance_zone.source_id (+ FK 단일 컬럼 인덱스)
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance_zone'
                     AND COLUMN_NAME = 'source_id') THEN
        ALTER TABLE performance_zone ADD COLUMN source_id BIGINT NULL AFTER performance_id;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance_zone'
                     AND INDEX_NAME = 'idx_performance_zone_source') THEN
        ALTER TABLE performance_zone ADD KEY idx_performance_zone_source (source_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'performance_zone'
                     AND CONSTRAINT_NAME = 'fk_performance_zone_source') THEN
        ALTER TABLE performance_zone ADD CONSTRAINT fk_performance_zone_source
            FOREIGN KEY (source_id) REFERENCES performance_source (id);
    END IF;

    -- 3) 이름 유일 제약을 지도 단위로: 생성 컬럼 추가 -> 기존 유니크 삭제 -> 새 유니크(같은 이름)
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance_zone'
                     AND COLUMN_NAME = 'source_scope') THEN
        ALTER TABLE performance_zone
            ADD COLUMN source_scope BIGINT GENERATED ALWAYS AS (IFNULL(source_id, 0)) STORED;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance_zone'
                     AND INDEX_NAME = 'uk_performance_zone_name_key' AND COLUMN_NAME = 'source_scope') THEN
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance_zone'
                     AND INDEX_NAME = 'uk_performance_zone_name_key') THEN
            ALTER TABLE performance_zone DROP INDEX uk_performance_zone_name_key;
        END IF;
        ALTER TABLE performance_zone
            ADD CONSTRAINT uk_performance_zone_name_key UNIQUE (name_key, performance_id, source_scope);
    END IF;
END//
DELIMITER ;

CALL v34_source_seatmap();
DROP PROCEDURE IF EXISTS v34_source_seatmap;
