-- =====================================================================
-- V18: 폐기된 신뢰도 점수 컬럼 제거(users.trust_score)
--
-- 결정: 2026-10-13 사용자 결정. 신뢰도·점수화는 매칭에 쓰지 않기로 확정했고(CLAUDE.md '폐기된 안'),
-- 컬럼은 가입 때 0.0으로 저장될 뿐 어디서도 바뀌지 않았다. 테이블 수 20개 그대로.
--  - 인덱스·FK·CHECK 가 이 컬럼을 참조하지 않는다(V1 에서 DOUBLE DEFAULT NULL 로만 정의).
--  - 파괴적 단계: 컬럼과 그 값(전부 0.0 또는 NULL)이 사라진다. 되돌릴 일이 생기면 새 V 파일에서
--    users.trust_score DOUBLE DEFAULT NULL 을 다시 추가한다(값은 복구되지 않는다).
--
-- 재실행 가능: V2·V6~V17 과 같은 INFORMATION_SCHEMA 가드 프로시저(컬럼이 있을 때만 DROP).
-- 규칙: V1~V17 은 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상. 다음 마이그레이션은 V19.
-- =====================================================================

DROP PROCEDURE IF EXISTS v18_drop_user_trust_score;

DELIMITER //
CREATE PROCEDURE v18_drop_user_trust_score()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'trust_score') THEN
        ALTER TABLE users DROP COLUMN trust_score;
    END IF;
END//
DELIMITER ;

CALL v18_drop_user_trust_score();
DROP PROCEDURE v18_drop_user_trust_score;
