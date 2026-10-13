-- =====================================================================
-- V16: 비밀번호 로그인 가능 여부(users.password_login_enabled) + 사용자당 제공자 1개(social_account)
--
-- 설계: feature/profile-edit spec 'V16' 절 (2026-10-13). 테이블 수 20개 그대로.
--  (a) users.password_login_enabled TINYINT(1) NOT NULL DEFAULT 1
--      - 비밀번호로 로그인할 수 있는 계정인가. 일반 가입 = 1, 소셜 가입 = 0(비밀번호는 무작위 BCrypt 해시라 쓸 수 없다).
--      - 소셜 연결 해제 시 "마지막 로그인 수단" 판단에 쓴다(서비스 몫: users 행 FOR UPDATE 후 판단).
--      - 백필: social_account 행이 있는 사용자는 0, 나머지는 기본값 1.
--        근거: V14 이후 social_account 행은 소셜 가입으로만 생겼다(연결 UI 없음, 자동 연결 안 함).
--        백필 UPDATE 는 컬럼 추가 블록 밖에서 매번 실행한다(0 으로만 바꾸는 멱등 문장) — ALTER 후 UPDATE 전에
--        중단된 경우에도 재실행으로 마저 채워진다. 인덱스는 두지 않는다(검색 조건으로 쓰지 않음).
--  (b) social_account: uk_social_account_user_provider (user_id, provider)
--      - V14 에는 (provider, provider_user_id) 유니크만 있고 (user_id, provider) 유니크는 없었다(연결 UI 가 없어서).
--        마이페이지 명시적 연결이 생기므로 사용자당 제공자 1개를 DB 로 보장한다(동시 중복 연결의 최후 방어선,
--        서비스는 위반 시 ALREADY_LINKED 로 응답).
--      - social_account 는 INSERT/DELETE 만 하고 갱신 컬럼이 없어 FK 교착 함정과 무관하다.
--        FK 전용 단일 컬럼 인덱스 idx_social_account_user 는 그대로 둔다(규칙: FK 인덱스는 단일 컬럼).
--      - 기존 중복 (user_id, provider) 가 있으면 SIGNAL 로 중단한다(가입 경로상 없어야 정상).
--
-- 재실행 가능: V2·V6~V15 와 같은 INFORMATION_SCHEMA 가드 프로시저. 파괴적 단계 없음.
-- 되돌리기(롤포워드): 새 V 파일에서 uk_social_account_user_provider·password_login_enabled 를 DROP.
--
-- 규칙: V1~V15 는 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상. 다음 마이그레이션은 V17.
-- =====================================================================

DROP PROCEDURE IF EXISTS v16_user_password_login;

DELIMITER //
CREATE PROCEDURE v16_user_password_login()
BEGIN
    -- (a-1) 컬럼
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'password_login_enabled') THEN
        ALTER TABLE users
            ADD COLUMN password_login_enabled TINYINT(1) NOT NULL DEFAULT 1 AFTER password;
    END IF;

    -- (a-2) 백필: 소셜 가입자는 비밀번호 로그인 불가 (멱등)
    UPDATE users u
       SET u.password_login_enabled = 0
     WHERE u.password_login_enabled <> 0
       AND EXISTS (SELECT 1 FROM social_account s WHERE s.user_id = u.id);

    -- (b) 사용자당 제공자 1개
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'social_account'
                     AND INDEX_NAME = 'uk_social_account_user_provider') THEN
        IF EXISTS (SELECT 1 FROM social_account GROUP BY user_id, provider HAVING COUNT(*) > 1) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'V16: duplicate (user_id, provider) rows in social_account - resolve before migrating';
        END IF;
        ALTER TABLE social_account
            ADD CONSTRAINT uk_social_account_user_provider UNIQUE (user_id, provider);
    END IF;
END//
DELIMITER ;

CALL v16_user_password_login();
DROP PROCEDURE v16_user_password_login;
