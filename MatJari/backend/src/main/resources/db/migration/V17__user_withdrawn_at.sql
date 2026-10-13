-- =====================================================================
-- V17: 회원탈퇴 시각(users.withdrawn_at)
--
-- 설계: feature/withdraw spec 'V17' 절 (2026-10-13). 테이블 수 20개 그대로.
--  users.withdrawn_at DATETIME(6) NULL
--      - NULL 이면 활성 회원, 값이 있으면 탈퇴(익명화)한 회원. 시각은 KST 벽시계(다른 시각 컬럼과 같다).
--      - 탈퇴는 물리 삭제가 아니라 익명화다(채팅·교환 이력·차단·신고·제재 FK 유지). 익명화 값 자체
--        (email=withdrawn.{id}@withdrawn.seatswap.invalid, nickname='탈퇴한 회원', 무작위 BCrypt,
--        password_login_enabled=0)는 서비스가 같은 트랜잭션에서 쓴다. 이 마이그레이션은 컬럼만 추가한다.
--      - 이메일 유니크(UK6dotkott2kjsp8vw4d0m25fb7)는 그대로 둔다. 익명화 이메일이 id 를 포함해 유니크가 유지되고,
--        원래 이메일이 비워지므로 같은 이메일로 재가입할 수 있다.
--      - 인덱스는 두지 않는다(검색 조건으로 쓰지 않음. 인증 시 PK 조회 후 값만 확인한다).
--      - CHECK 는 두지 않는다(익명화 값과의 정합은 서비스 몫, 기존 행은 모두 NULL = 활성).
--      - 백필 없음: 기존 사용자는 전부 활성 회원이다.
--
-- 재실행 가능: V2·V6~V16 과 같은 INFORMATION_SCHEMA 가드 프로시저. 파괴적 단계 없음.
-- 되돌리기(롤포워드): 새 V 파일에서 users.withdrawn_at 을 DROP (탈퇴 행이 있으면 익명화 상태는 남는다).
--
-- 규칙: V1~V16 은 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상. 다음 마이그레이션은 V18.
-- =====================================================================

DROP PROCEDURE IF EXISTS v17_user_withdrawn_at;

DELIMITER //
CREATE PROCEDURE v17_user_withdrawn_at()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'withdrawn_at') THEN
        ALTER TABLE users
            ADD COLUMN withdrawn_at DATETIME(6) NULL DEFAULT NULL AFTER role;
    END IF;
END//
DELIMITER ;

CALL v17_user_withdrawn_at();
DROP PROCEDURE v17_user_withdrawn_at;
