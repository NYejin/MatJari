-- =====================================================================
-- V26: AI 호출 비용 기록(ai_call_log) — 관리자 대시보드의 예상 비용·남은 크레딧 추정
--
-- 설계: feature/ai-cost-tracking (2026-10-22, 사용자 요청: 남은 크레딧을 대략적인 비율로 보기)
--  - Anthropic API 호출 1번마다 한 행: 단계(first·locate·second·label·legend 등), 모델, 입력·출력 토큰, 그 시점 단가로 계산한 예상 비용.
--    cost_micro_usd = 입력 토큰 × 입력 단가($/100만 토큰) + 출력 토큰 × 출력 단가(마이크로 달러, 정수).
--  - 실제 청구액이 아니라 이 서버가 부른 호출만의 추정치다(같은 키를 쓰는 다른 곳은 빠진다). 프롬프트·이미지·응답·이용자 정보는 두지 않는다.
--  - 행은 추가만 한다. 대시보드는 기준 잔액(설정) − 기준 시각 이후 비용 합으로 남은 크레딧을 추정한다.
--
--  인덱스·잠금 검토
--  - FK 없음, AI 호출 뒤 단독 INSERT(다른 행을 잠그지 않음). 집계용 idx_ai_call_log_called(called_at).
--
-- 재실행 가능: INFORMATION_SCHEMA 가드 프로시저. 기존 데이터 변경 없음, 파괴적 단계 없음.
-- 되돌리기(롤포워드): 새 V 파일에서 ai_call_log 삭제.
--
-- 규칙: V1~V25 는 수정하지 않는다. 다음 마이그레이션은 V27.
-- =====================================================================

DROP PROCEDURE IF EXISTS v26_ai_call_log;

DELIMITER //
CREATE PROCEDURE v26_ai_call_log()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_call_log') THEN
        CREATE TABLE ai_call_log (
            id             BIGINT      NOT NULL AUTO_INCREMENT,
            called_at      DATETIME(6) NOT NULL,
            stage          VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
            model          VARCHAR(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
            input_tokens   INT         NOT NULL,
            output_tokens  INT         NOT NULL,
            cost_micro_usd BIGINT      NOT NULL,
            PRIMARY KEY (id),
            KEY idx_ai_call_log_called (called_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;
END//
DELIMITER ;

CALL v26_ai_call_log();
DROP PROCEDURE v26_ai_call_log;
