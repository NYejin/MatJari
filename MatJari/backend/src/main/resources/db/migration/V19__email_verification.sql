-- =====================================================================
-- V19: 이메일 인증번호 (일반 회원가입 전 이메일 소유 확인)
--
-- 설계: feature/email-verification (2026-10-13)
--  - email_verification: 가입하려는 이메일로 보낸 6자리 인증번호의 해시. 이메일당 행 1개(PK = email)이며
--    다시 보내면 같은 행을 덮어쓴다(code_hash 교체, attempts 0, 만료 갱신). 코드 평문은 저장하지 않는다(HMAC-SHA256).
--  - 가입 성공 시 같은 트랜잭션에서 행을 지운다(1회용). 틀린 시도는 attempts 로 센다(상한을 넘으면 새 코드가 필요).
--    만료된 행은 새 코드를 보낼 때 가끔 쓸어 낸다(스케줄러 없음).
--  - users 에는 컬럼을 추가하지 않는다: 이 방식은 인증을 통과한 이메일로만 계정이 만들어지므로 새 계정은 모두 검증된 이메일이다.
--    (기존 계정은 그대로 둔다 - 소급 인증 없음.)
--  - 이 테이블은 users 와 FK 가 없다(가입 전 이메일이라 users 행이 없다). 갱신 컬럼(attempts)이 있어도 FK 교착 함정과 무관하다.
--
-- 재실행 가능: INFORMATION_SCHEMA 가드 프로시저. 파괴적 단계 없음.
-- 되돌리기(롤포워드): 새 V 파일에서 email_verification 을 DROP.
--
-- 규칙: V1~V18 은 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상. 다음 마이그레이션은 V20.
-- =====================================================================

DROP PROCEDURE IF EXISTS v19_email_verification;

DELIMITER //
CREATE PROCEDURE v19_email_verification()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'email_verification') THEN
        CREATE TABLE email_verification (
            email       VARCHAR(100) NOT NULL,                     -- 정규화된 이메일(소문자·앞뒤 공백 제거). users.email 과 같은 상한
            code_hash   CHAR(64)     NOT NULL,                     -- HMAC-SHA256(코드) 16진 문자열
            attempts    INT          NOT NULL DEFAULT 0,           -- 입력 시도 횟수(틀린 것 포함)
            sent_at     DATETIME(6)  NOT NULL,                     -- 마지막 발송 시각(재발송 대기시간 판단)
            expires_at  DATETIME(6)  NOT NULL,
            PRIMARY KEY (email),
            KEY idx_email_verification_expires (expires_at),       -- 만료 행 정리
            CONSTRAINT ck_email_verification_attempts CHECK (attempts >= 0)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;
END//
DELIMITER ;

CALL v19_email_verification();
DROP PROCEDURE v19_email_verification;
