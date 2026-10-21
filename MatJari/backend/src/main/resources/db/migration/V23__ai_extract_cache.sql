-- =====================================================================
-- V23: AI 회차·구역 읽기 결과 보관(ai_extract_cache)
--
-- 설계: feature/ai-cache-db (2026-10-21, 사용자 결정: 링크별 결과를 168시간 보관)
--  - 같은 링크(source_key)를 일주일 안에 다시 읽으면 AI 를 부르지 않고 저장된 결과를 돌려준다(호출 횟수에도 세지 않는다).
--    메모리 캐시는 배포·재시작 때 비워지므로 DB 에 둔다.
--  - result 는 응답 JSON(회차 날짜·시각, 구역 이름·모양 좌표 0~1000, 좌석표 비율 등)뿐이다. 원본 이미지·프롬프트·이용자 정보는
--    저장하지 않는다(누가 읽었는지도 남기지 않는다).
--  - expires_at_ms: 만료 시각(epoch 밀리초). 조회 때 지난 행은 쓰지 않고, 새 결과를 저장할 때 지난 행을 조금씩 지운다(스케줄러 없음).
--
--  인덱스·잠금 검토
--  - FK 없음. 다른 테이블과 같은 트랜잭션에서 쓰지 않는다(AI 읽기 요청은 다른 행을 잠그지 않는다). 잠금 사슬과 무관하다.
--
-- 재실행 가능: INFORMATION_SCHEMA 가드 프로시저. 기존 데이터 변경 없음, 파괴적 단계 없음.
-- 되돌리기(롤포워드): 새 V 파일에서 ai_extract_cache 삭제(보관용이라 지워도 기능에는 영향 없음).
--
-- 규칙: V1~V22 는 수정하지 않는다. 다음 마이그레이션은 V24.
-- =====================================================================

DROP PROCEDURE IF EXISTS v23_ai_extract_cache;

DELIMITER //
CREATE PROCEDURE v23_ai_extract_cache()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_extract_cache') THEN
        CREATE TABLE ai_extract_cache (
            source_key    VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, -- performance.source_key 와 같은 형식
            result        JSON         NOT NULL,
            created_at    DATETIME(6)  NOT NULL,
            expires_at_ms BIGINT       NOT NULL,
            PRIMARY KEY (source_key),
            KEY idx_ai_extract_cache_expires (expires_at_ms)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;
END//
DELIMITER ;

CALL v23_ai_extract_cache();
DROP PROCEDURE v23_ai_extract_cache;
