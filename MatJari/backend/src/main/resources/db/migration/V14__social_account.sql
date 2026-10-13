-- =====================================================================
-- V14: 소셜 로그인 계정 연결 (social_account)
--
-- 설계: feature/social-login 확정 설계 (2026-10-12)
--  - social_account: 제공자(GOOGLE/KAKAO/NAVER) 계정과 SeatSwap 사용자를 잇는다. 행은 INSERT 만 한다(갱신 컬럼 없음).
--    provider_user_id 는 제공자가 주는 고유 식별자(구글 sub / 카카오 id / 네이버 response.id).
--    이메일·프로필은 저장하지 않는다(최소 수집). users 테이블은 바꾸지 않는다(password NOT NULL 유지,
--    소셜 가입자는 무작위 값의 BCrypt 해시를 넣는다 — 서비스 몫).
--  - uk_social_account_provider_user(provider, provider_user_id): 한 소셜 계정은 한 사용자에만 연결된다.
--    동시 첫 로그인 경합의 최후 방어선(서비스는 위반 시 다시 조회해 기존 사용자로 진행).
--    provider·provider_user_id 는 utf8mb4_bin(대소문자·악센트 구분) — 제공자 식별자는 바이트 그대로 비교한다.
--  - (user_id, provider) 유니크는 두지 않는다(연결 UI 없음, 가입 시 1행만 만든다).
--  - FK 인덱스는 단일 컬럼(idx_social_account_user). FK 에 ON DELETE/UPDATE 동작을 붙이지 않는다
--    (회원탈퇴는 물리 삭제가 아니라 익명화).
--  - provider 는 대문자 3종만(ck_social_account_provider). 빈 식별자 거부(ck_social_account_provider_user_id).
--
-- 재실행 가능: V2·V6~V13 과 같은 INFORMATION_SCHEMA 가드 프로시저. 파괴적 단계 없음.
-- 되돌리기(롤포워드): 새 V 파일에서 social_account 삭제.
--
-- 규칙: V1~V13 은 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상. 다음 마이그레이션은 V15.
-- =====================================================================

DROP PROCEDURE IF EXISTS v14_social_account;

DELIMITER //
CREATE PROCEDURE v14_social_account()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'social_account') THEN
        CREATE TABLE social_account (
            id               BIGINT       NOT NULL AUTO_INCREMENT,
            user_id          BIGINT       NOT NULL,                -- 연결된 사용자 -> users
            provider         VARCHAR(20)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
            provider_user_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
            created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
            PRIMARY KEY (id),
            CONSTRAINT uk_social_account_provider_user UNIQUE (provider, provider_user_id),
            KEY idx_social_account_user (user_id),
            CONSTRAINT fk_social_account_user FOREIGN KEY (user_id) REFERENCES users (id),
            CONSTRAINT ck_social_account_provider CHECK (provider IN ('GOOGLE', 'KAKAO', 'NAVER')),
            CONSTRAINT ck_social_account_provider_user_id CHECK (CHAR_LENGTH(TRIM(provider_user_id)) > 0)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;
END//
DELIMITER ;

CALL v14_social_account();
DROP PROCEDURE v14_social_account;
