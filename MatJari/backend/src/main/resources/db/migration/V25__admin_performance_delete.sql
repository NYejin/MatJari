-- =====================================================================
-- V25: 관리자 공연 삭제 조치 기록 — admin_action_log.action 에 PERFORMANCE_DELETE 추가
--
-- 설계: feature/admin-performance-delete (2026-10-21, 사용자 결정: 숨긴 공연은 걸린 것이 없을 때만 완전 삭제)
--  - 삭제는 숨긴 공연이고 티켓·교환 희망 회차·좌석 인증 요청·교환 이력이 하나도 없을 때만 한다(서비스가 검사, 아니면 409).
--    공연과 함께 회차·구역·공연 수정 제안을 지운다. 조치 기록은 대상이 FK 가 아니라 삭제 뒤에도 남는다.
--  - 이 마이그레이션은 CHECK 목록만 넓힌다(기존 값은 모두 그대로 허용). 테이블·데이터 변경 없음.
--
-- 재실행 가능: CHECK 정의에 PERFORMANCE_DELETE 가 없을 때만 다시 만든다. 파괴적 단계 없음(제약 교체만).
-- 되돌리기(롤포워드): 새 V 파일에서 PERFORMANCE_DELETE 행이 없을 때 이전 목록으로 CHECK 를 되돌린다.
--
-- 규칙: V1~V24 는 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상. 다음 마이그레이션은 V26.
-- =====================================================================

DROP PROCEDURE IF EXISTS v25_admin_performance_delete;

DELIMITER //
CREATE PROCEDURE v25_admin_performance_delete()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_admin_action_log_action'
                     AND CHECK_CLAUSE LIKE '%PERFORMANCE_DELETE%') THEN
        IF EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_admin_action_log_action') THEN
            ALTER TABLE admin_action_log DROP CHECK ck_admin_action_log_action;
        END IF;
        ALTER TABLE admin_action_log ADD CONSTRAINT ck_admin_action_log_action CHECK (action IN (
            'PERFORMANCE_HIDE', 'PERFORMANCE_UNHIDE', 'PERFORMANCE_EDIT', 'PERFORMANCE_SESSIONS', 'PERFORMANCE_ZONES',
            'PERFORMANCE_DELETE',
            'USER_SUSPEND', 'USER_UNSUSPEND', 'USER_ROLE_CHANGE', 'USER_FORCE_WITHDRAW'));
    END IF;
END//
DELIMITER ;

CALL v25_admin_performance_delete();
DROP PROCEDURE v25_admin_performance_delete;
