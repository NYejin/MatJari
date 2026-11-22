-- =====================================================================
-- V28: 비밀번호 재설정 토큰(password_reset_token) + refresh_token 폐기 사유 PASSWORD_RESET
--
-- 설계: feature/password-reset (2026-11-22)
--  - "비밀번호를 잊었어요"에서 이메일로 받은 일회용 링크로 새 비밀번호를 정한다. 링크 토큰은 무작위 32바이트이고
--    DB 에는 SHA-256(토큰) 소문자 16진 64자(token_hash, ck_password_reset_token_hash)만 둔다. 평문은 저장하지 않는다.
--  - 상태는 두 시각으로 나눈다(상태 문자열 컬럼 없음).
--      used_at        : 이 링크로 비밀번호를 실제로 바꾼 시각
--      invalidated_at : 쓰이지 않고 무효가 된 시각(새 재설정 요청이 이전 링크를 대체 / 다른 경로로 비밀번호가 바뀜 등)
--    둘은 동시에 값일 수 없다(ck_password_reset_token_state). '이 링크로 바꿨다'와 '대체돼서 못 쓴다'를 감사·안내에서 구분하려고
--    used_at 하나로 합치지 않았다. 둘 다 NULL 이고 expires_at 전이면 쓸 수 있다.
--  - 사용자당 살아 있는(미사용·미무효) 행은 1개(uk_password_reset_token_live, 생성 컬럼 live_user_id + UNIQUE — V12·V13 의
--    'PENDING/OPEN 만 유니크'와 같은 방식). 만료됐어도 used_at·invalidated_at 이 NULL 이면 살아 있는 것으로 센다 → 새 요청은
--    INSERT 전에 그 사용자의 살아 있는 행을 invalidated_at 으로 일괄 무효화한다(서비스, users 잠금 안).
--  - request_ip: 요청 IP(감사·속도 제한 보조, 형식·길이만 검사). 개인정보라 만료 정리·탈퇴 때 행과 함께 지운다. 인덱스는 두지 않는다
--    (IP 기준 속도 제한은 지금처럼 메모리에서 한다).
--  - refresh_token.revoke_reason 에 PASSWORD_RESET 을 더한다: 비밀번호를 재설정하면 그 사용자의 리프레시 토큰을 모두 폐기한다
--    (도난된 세션 차단). 기존 5종은 그대로 허용한다.
--
--  인덱스·잠금 검토
--  - fk_password_reset_token_user 는 단일 컬럼 인덱스(idx_password_reset_token_user). ON DELETE/UPDATE 동작 없음(탈퇴는 익명화).
--    used_at/invalidated_at UPDATE 는 FK 인덱스 값을 바꾸지 않아 부모 users 행에 공유 잠금을 걸지 않는다. live_user_id 는 FK 가 아니다.
--  - INSERT 의 FK 검사가 users 행에 공유 잠금을 건다 → 요청(INSERT)·확정 경로는 users 행을 먼저 FOR UPDATE 로 잡는다(V27 과 같은 이유).
--  - uk_password_reset_token_hash: 해시로 id 만 잠금 없이 읽고 PK 로 FOR UPDATE 한 뒤 상태를 다시 판단한다(보조 유니크 FOR UPDATE 금지).
--  - idx_password_reset_token_expires: 만료 행 배치 삭제(expires_at < ? ORDER BY expires_at LIMIT n). 없으면 DELETE 가 전체를 훑으며
--    살아 있는 행까지 잠근다. 새 행의 expires_at 은 항상 미래라 정리의 범위 잠금과 겹치지 않는다.
--
-- 재실행 가능: INFORMATION_SCHEMA 가드 프로시저(테이블이 없을 때만 생성, CHECK 에 PASSWORD_RESET 이 없을 때만 교체).
-- 기존 데이터 변경 없음, 파괴적 단계 없음(CHECK 교체만).
-- 되돌리기(롤포워드): 새 V 파일에서 password_reset_token 삭제, PASSWORD_RESET 행이 없을 때 refresh_token CHECK 를 5종으로 되돌림.
--
--  - users.credentials_changed_at DATETIME(6) NULL(2026-11-22 추가, V28 은 배포 전): 비밀번호를 재설정·변경한 시각. 인증 필터와 STOMP CONNECT 가
--    토큰 iat(초)가 이 값(초로 내림)보다 작으면 거부해 옛 액세스 토큰을 무효로 만든다. 기존 행은 NULL(= 제한 없음). 인덱스·FK 없음.
--
-- 규칙: V1~V27 은 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상. 다음 마이그레이션은 V29.
-- =====================================================================

DROP PROCEDURE IF EXISTS v28_password_reset_token;

DELIMITER //
CREATE PROCEDURE v28_password_reset_token()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'password_reset_token') THEN
        CREATE TABLE password_reset_token (
            id             BIGINT      NOT NULL AUTO_INCREMENT,
            user_id        BIGINT      NOT NULL,                                          -- 재설정 대상 -> users
            token_hash     CHAR(64)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,    -- SHA-256(토큰) 소문자 hex
            created_at     DATETIME(6) NOT NULL,
            expires_at     DATETIME(6) NOT NULL,
            used_at        DATETIME(6) NULL,                                              -- 이 링크로 비밀번호를 바꾼 시각
            invalidated_at DATETIME(6) NULL,                                              -- 쓰이지 않고 무효가 된 시각
            request_ip     VARCHAR(45) CHARACTER SET ascii COLLATE ascii_bin NULL,         -- 요청 IP(IPv4·IPv6 문자열)
            live_user_id   BIGINT GENERATED ALWAYS AS (
                               IF(used_at IS NULL AND invalidated_at IS NULL, user_id, NULL)) STORED,  -- FK 아님
            PRIMARY KEY (id),
            CONSTRAINT uk_password_reset_token_hash UNIQUE (token_hash),
            CONSTRAINT uk_password_reset_token_live UNIQUE (live_user_id),
            KEY idx_password_reset_token_user (user_id),
            KEY idx_password_reset_token_expires (expires_at),
            CONSTRAINT fk_password_reset_token_user FOREIGN KEY (user_id) REFERENCES users (id),
            CONSTRAINT ck_password_reset_token_hash CHECK (REGEXP_LIKE(token_hash, '^[0-9a-f]{64}$', 'c')),
            CONSTRAINT ck_password_reset_token_expires CHECK (expires_at > created_at),
            CONSTRAINT ck_password_reset_token_state CHECK (
                (used_at IS NULL OR invalidated_at IS NULL)
                AND (used_at IS NULL OR used_at >= created_at)
                AND (invalidated_at IS NULL OR invalidated_at >= created_at)),
            CONSTRAINT ck_password_reset_token_ip CHECK (
                request_ip IS NULL OR (CHAR_LENGTH(request_ip) >= 2 AND REGEXP_LIKE(request_ip, '^[0-9A-Fa-f:.]+$', 'c')))
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_refresh_token_reason'
                     AND CHECK_CLAUSE LIKE '%PASSWORD_RESET%') THEN
        IF EXISTS (SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_refresh_token_reason') THEN
            ALTER TABLE refresh_token DROP CHECK ck_refresh_token_reason;
        END IF;
        ALTER TABLE refresh_token ADD CONSTRAINT ck_refresh_token_reason CHECK (
            revoke_reason IS NULL OR revoke_reason IN ('ROTATED', 'LOGOUT', 'REUSE', 'WITHDRAW', 'ADMIN', 'PASSWORD_RESET'));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'credentials_changed_at') THEN
        ALTER TABLE users ADD COLUMN credentials_changed_at DATETIME(6) NULL;
    END IF;
END//
DELIMITER ;

CALL v28_password_reset_token();
DROP PROCEDURE v28_password_reset_token;
