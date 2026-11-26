-- =====================================================================
-- V31: 원본 좌석표 이미지 위치 (performance.seatmap_image_url + seatmap_crop_left/top/width/height)
--
-- 설계: 2026-11-26 사용자 결정. 구역 지도에 원 사이트의 좌석표 이미지를 인라인 링크(브라우저가 원 사이트에서 직접 불러옴)로
--  잘라 보여 주고 그 위에 인식한 구역 클릭 영역을 얹는다. **원본 이미지는 저장·복제·프록시하지 않는다**(포스터 표시와 같은 방침):
--  이미지 주소 문자열과 '이미지 안 어느 사각형이 좌석표인가'만 저장한다.
--
--  - seatmap_image_url VARCHAR(2048) NULL: 허용 호스트(멜론·티켓링크·인터파크 상세 이미지, https)의 원본 주소. 검증은 서비스가 한다.
--  - seatmap_crop_left/top/width/height DECIMAL(8,6) NULL: **원본 이미지 전체 크기에 대한 비율**(0~1)의 사각형.
--    이 사각형이 기존 구역 좌표(0~1000)와 seatmap_aspect 의 기준 영역과 정확히 같다.
--  - CHECK: 다섯 값이 모두 NULL 이거나 모두 NOT NULL, left·top 은 0 이상 1 미만, width·height 는 0 초과 1 이하,
--    left+width, top+height 는 1.000001 이하(DECIMAL 6자리 반올림 허용).
--
--  잠금·인덱스: 인덱스·FK 없음. 공연 행의 컬럼만 늘며 기존 잠금 순서(티켓 → 요청 → 매칭)와 무관하다. 쓰기는 공연 등록과
--  관리자 구역 교체(공연 행 FOR UPDATE) 안에서만 한다.
--
-- 재실행 가능: INFORMATION_SCHEMA 가드 프로시저. 기존 데이터 변경 없음, 파괴적 단계 없음(기존 공연은 모두 NULL).
-- 되돌리기(롤포워드): 새 V 파일에서 CHECK 와 다섯 컬럼 삭제.
--
-- 규칙: V1~V30 은 수정하지 않는다. 다음 마이그레이션은 V32.
-- =====================================================================

DROP PROCEDURE IF EXISTS v31_performance_seatmap_image;

DELIMITER //
CREATE PROCEDURE v31_performance_seatmap_image()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance'
                     AND COLUMN_NAME = 'seatmap_image_url') THEN
        ALTER TABLE performance
            ADD COLUMN seatmap_image_url   VARCHAR(2048) NULL AFTER seatmap_aspect,
            ADD COLUMN seatmap_crop_left   DECIMAL(8,6)  NULL AFTER seatmap_image_url,
            ADD COLUMN seatmap_crop_top    DECIMAL(8,6)  NULL AFTER seatmap_crop_left,
            ADD COLUMN seatmap_crop_width  DECIMAL(8,6)  NULL AFTER seatmap_crop_top,
            ADD COLUMN seatmap_crop_height DECIMAL(8,6)  NULL AFTER seatmap_crop_width;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE()
                     AND CONSTRAINT_NAME = 'ck_performance_seatmap_image') THEN
        ALTER TABLE performance ADD CONSTRAINT ck_performance_seatmap_image CHECK (
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
END //
DELIMITER ;

CALL v31_performance_seatmap_image();
DROP PROCEDURE IF EXISTS v31_performance_seatmap_image;
