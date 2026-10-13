-- =====================================================================
-- V20: 공연 수정 제안에 회차 변경 추가
--
-- 설계: feature/session-edit-proposal (2026-10-14)
--  - performance_edit_proposal.session_changes (JSON, NULL 가능): 제안한 회차 변경 목록.
--    원소 = {"type":"ADD|CHANGE|REMOVE", "sessionId":숫자|null, "startsAt":"YYYY-MM-DDTHH:mm:ss"|null, "oldStartsAt":...|null}.
--    ADD 는 sessionId 없음, REMOVE 는 startsAt 없음. oldStartsAt 은 승인 때 기록하는 기존 시각 스냅샷(CHANGE·REMOVE).
--    회차 id 가 FK 가 아닌 이유: 제안이 처리된 뒤 회차가 삭제될 수 있고, 처리된 제안은 감사 기록으로 남아야 한다.
--    목록의 형식·개수·중복·충돌 검증은 서비스가 한다(제안 생성과 승인 두 시점 모두).
--  - 제목·공연장 없이 회차만 바꾸는 제안도 가능하므로 ck_performance_edit_proposal_change 를 고친다:
--    (새 제목 또는 새 공연장 또는 session_changes 가 있어야 한다) + 값이 있으면 공백만은 불가. 공백 규칙은 그대로다.
--
-- 재실행 가능: session_changes 컬럼이 이미 있으면 아무것도 하지 않는다(컬럼과 CHECK 교체는 한 묶음).
-- 되돌리기(롤포워드): 새 V 파일에서 session_changes 가 NULL 이 아닌 제안이 없을 때 컬럼 삭제와 CHECK 복원.
--
-- 규칙: V1~V19 는 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상. 다음 마이그레이션은 V21.
-- =====================================================================

DROP PROCEDURE IF EXISTS v20_edit_proposal_session_changes;

DELIMITER //
CREATE PROCEDURE v20_edit_proposal_session_changes()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance_edit_proposal'
                     AND COLUMN_NAME = 'session_changes') THEN
        ALTER TABLE performance_edit_proposal DROP CHECK ck_performance_edit_proposal_change;
        ALTER TABLE performance_edit_proposal ADD COLUMN session_changes JSON NULL AFTER new_venue_name;
        ALTER TABLE performance_edit_proposal ADD CONSTRAINT ck_performance_edit_proposal_change CHECK (
            (new_title IS NOT NULL OR new_venue_name IS NOT NULL OR session_changes IS NOT NULL)
            AND (new_title IS NULL OR CHAR_LENGTH(TRIM(new_title)) > 0)
            AND (new_venue_name IS NULL OR CHAR_LENGTH(TRIM(new_venue_name)) > 0));
    END IF;
END//
DELIMITER ;

CALL v20_edit_proposal_session_changes();
DROP PROCEDURE v20_edit_proposal_session_changes;
