-- =====================================================================
-- V32: 한 공연을 여러 예매처 링크로 묶기 (performance_source)
--
-- 배경(2026-11-26 사용자 지시): 같은 콘서트를 멜론이 일부 구역, NOL(인터파크)이 나머지 구역을 파는 경우처럼 한 공연이 여러 예매처에
--   나뉘어 팔리면 링크 하나 = 공연 하나 규칙 때문에 공연이 둘로 갈려 두 예매처 티켓 보유자끼리 매칭이 안 된다.
--   그래서 공연에 링크를 여러 개 달 수 있게 한다(공연당 최대 6개, 서비스가 검사).
--
--  performance_source
--  - source_key: 링크 정규화 키(performance.source_key 와 같은 규칙·타입·collation utf8mb4_bin). 전체에서 유일 = 링크 키는 공연 전체에서 하나.
--  - source_url: 사용자가 입력한 링크 원문(performance.source_url 과 같은 길이).
--  - added_by_id: 링크를 단 사용자(공연 등록자 또는 추가한 사람, 관리자 추가 포함). NULL 허용(백필은 공연 등록자).
--  - 행은 갱신하지 않는다(추가·삭제만). 그래서 갱신 컬럼이 FK 인덱스에 붙는 문제가 없다.
--  - performance.source_key(대표 링크)는 그대로 둔다. 대표 링크도 이 테이블에 한 행으로 들어가며(공연 등록 때 함께 INSERT),
--    AI 보관 결과·포스터·원본 좌석표는 대표 링크만 쓴다.
--  - 기존 공연마다 (performance_id, source_key, source_url, 등록자) 한 행씩 백필한다(created_at 은 공연 생성 시각).
--
--  인덱스·잠금 검토
--  - FK 인덱스는 단일 컬럼(idx_performance_source_performance, idx_performance_source_added_by)이다.
--  - 이 테이블은 티켓 id↑ → 요청 id↑ → 매칭 사슬 밖이다. 쓰기는 링크 추가 트랜잭션(users FOR UPDATE → 공연 행 FOR UPDATE → INSERT)
--    과 공연 등록 트랜잭션(공연 INSERT 뒤)과 관리자 조치(공연 행 FOR UPDATE → INSERT/DELETE)뿐이고 교환 경로는 쓰지 않는다.
--
-- 재실행 가능: INFORMATION_SCHEMA 가드 프로시저(테이블이 이미 있으면 건너뛰고, 백필은 없는 키만 넣는다). 파괴적 단계 없음.
-- 되돌리기(롤포워드): 새 V 파일에서 performance_source 삭제.
--
-- 규칙: V1~V31 은 수정하지 않는다. 다음 마이그레이션은 V33.
-- =====================================================================

DROP PROCEDURE IF EXISTS v32_performance_source;

DELIMITER //
CREATE PROCEDURE v32_performance_source()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance_source') THEN
        CREATE TABLE performance_source (
            id              BIGINT        NOT NULL AUTO_INCREMENT,
            performance_id  BIGINT        NOT NULL,                  -- -> performance
            source_key      VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
            source_url      VARCHAR(2048) NOT NULL,
            added_by_id     BIGINT        NULL,                      -- -> users
            created_at      DATETIME(6)   NOT NULL,
            PRIMARY KEY (id),
            CONSTRAINT uk_performance_source_source_key UNIQUE (source_key),
            KEY idx_performance_source_performance (performance_id),
            KEY idx_performance_source_added_by (added_by_id),
            CONSTRAINT fk_performance_source_performance FOREIGN KEY (performance_id) REFERENCES performance (id),
            CONSTRAINT fk_performance_source_added_by FOREIGN KEY (added_by_id) REFERENCES users (id),
            CONSTRAINT ck_performance_source_key CHECK (CHAR_LENGTH(source_key) > 0)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;

    -- 기존 공연의 대표 링크 백필 (이미 있는 키는 건너뜀)
    INSERT INTO performance_source (performance_id, source_key, source_url, added_by_id, created_at)
    SELECT p.id, p.source_key, p.source_url, p.registrant_id, p.created_at
    FROM performance p
    WHERE NOT EXISTS (SELECT 1 FROM performance_source s WHERE s.source_key = p.source_key)
    ORDER BY p.id;
END//
DELIMITER ;

CALL v32_performance_source();
DROP PROCEDURE v32_performance_source;
