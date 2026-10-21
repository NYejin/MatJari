-- =====================================================================
-- V24: AI 공연정보 불러오기 사용 기록(ai_extract_usage) — 사용자당 한 달 횟수 제한
--
-- 설계: feature/ai-monthly-limit (2026-10-21, 사용자 결정: [공연정보 불러오기] 버튼, 사용자당 매월 3회, 실패 시 횟수 반환)
--  - 실제로 AI 를 부른 경우만 한 행을 쓴다(보관 결과 반환은 쓰지 않는다). AI 가 읽지 못하면 refunded_at 을 채워 횟수에서 뺀다.
--  - 한 달 = KST 달력 월(매월 1일 0시 초기화). 이번 달 사용 수 = used_at >= 이번 달 1일 AND refunded_at IS NULL.
--  - 나중에 추가 횟수(BM)를 붙일 때도 이 기록으로 계산한다. 행은 지우지 않는다(탈퇴해도 남고 FK 로 사용자만 가리킨다).
--  - source_key 는 읽은 링크 키(performance.source_key 형식). 프롬프트·이미지·결과는 여기 두지 않는다.
--
--  인덱스·잠금 검토
--  - fk_ai_extract_usage_user 는 단일 컬럼 인덱스(idx_ai_extract_usage_user)를 쓴다(FK 인덱스에 갱신 컬럼을 붙이지 않는다).
--    월 집계용 (user_id, used_at) 인덱스를 따로 둔다(used_at 은 갱신하지 않는다).
--  - 예약 트랜잭션: users 행 FOR UPDATE(첫 쿼리) → COUNT → INSERT. 다른 테이블을 잠그지 않으며 AI 호출 전에 커밋한다.
--    반환(refund)은 PK 로 한 행만 갱신한다. 티켓·요청·매칭 잠금 사슬과 겹치지 않는다.
--
-- 재실행 가능: INFORMATION_SCHEMA 가드 프로시저. 기존 데이터 변경 없음, 파괴적 단계 없음.
-- 되돌리기(롤포워드): 새 V 파일에서 ai_extract_usage 삭제.
--
-- 규칙: V1~V23 은 수정하지 않는다. 다음 마이그레이션은 V25.
-- =====================================================================

DROP PROCEDURE IF EXISTS v24_ai_extract_usage;

DELIMITER //
CREATE PROCEDURE v24_ai_extract_usage()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_extract_usage') THEN
        CREATE TABLE ai_extract_usage (
            id          BIGINT       NOT NULL AUTO_INCREMENT,
            user_id     BIGINT       NOT NULL,
            source_key  VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
            used_at     DATETIME(6)  NOT NULL,
            refunded_at DATETIME(6)  NULL,
            PRIMARY KEY (id),
            KEY idx_ai_extract_usage_user (user_id),
            KEY idx_ai_extract_usage_user_month (user_id, used_at),
            CONSTRAINT fk_ai_extract_usage_user FOREIGN KEY (user_id) REFERENCES users (id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;
END//
DELIMITER ;

CALL v24_ai_extract_usage();
DROP PROCEDURE v24_ai_extract_usage;
