-- =====================================================================
-- V27: 리프레시 토큰 저장(refresh_token) — 회전·재사용 탐지·로그아웃/탈퇴 폐기
--
-- 설계: feature/refresh-token-rotation (2026-11-22)
--  - 지금 리프레시 토큰은 무상태 JWT(14일)라 도난되면 로그아웃해도 막을 수 없다. 서버에 토큰 해시를 저장하고
--    재발급할 때마다 회전(옛 행 revoked_at + ROTATED + replaced_by_id, 새 행 INSERT)한다.
--  - 평문 토큰은 저장하지 않는다. token_hash = SHA-256(토큰) 소문자 16진 64자(ck_refresh_token_hash).
--  - family_id: 로그인 한 번에서 이어진 회전 계열(UUID 소문자). 이미 회전된(폐기된) 토큰이 다시 오면 재사용으로 보고
--    그 계열의 살아 있는 행을 모두 REUSE 로 폐기한다.
--  - revoke_reason: ROTATED / LOGOUT / REUSE / WITHDRAW / ADMIN(이용 정지·강제 탈퇴 등 관리자 조치).
--    revoked_at 과 revoke_reason 은 함께 NULL 이거나 함께 값(ck_refresh_token_revoked).
--    replaced_by_id 는 ROTATED 일 때만, ROTATED 면 반드시 값(ck_refresh_token_replaced). 자기 자신 참조 금지는
--    AUTO_INCREMENT 컬럼을 CHECK 에 쓸 수 없어(MySQL 제약) 서비스가 지킨다. replaced_by_id 는 FK 가 아니다
--    (만료 행 정리가 계열 순서와 무관하게 지울 수 있도록).
--  - family_started_at: 계열의 첫 로그인 시각. 회전할 때 새 행이 그대로 물려받고, 서비스가 새 토큰 expires_at 을 min(now + 수명, family_started_at + 세션 최대 수명)으로 정해 회전으로 세션이 무한 연장되지 않게 한다(ck_refresh_token_family_started: family_started_at <= created_at).
--  - 이용자 정보는 user_id 하나뿐이다(기기·IP·User-Agent 는 저장하지 않는다, 최소 수집).
--
--  인덱스·잠금 검토
--  - fk_refresh_token_user 는 단일 컬럼 인덱스(idx_refresh_token_user)를 쓴다. 폐기 UPDATE 는 revoked_at 등만 바꾸고
--    FK 인덱스 값은 바꾸지 않으므로 부모 users 행에 공유 잠금을 걸지 않는다. ON DELETE/UPDATE 동작 없음(탈퇴는 익명화).
--    INSERT 는 FK 검사로 users 행에 공유 잠금을 건다 → 토큰을 INSERT 하는 경로(로그인·재발급)는 users 행을 먼저
--    FOR UPDATE 로 잡는다(탈퇴·정지와 잠금 순서를 users → refresh_token 으로 맞춘다, RefreshToken Javadoc).
--  - uk_refresh_token_hash: 해시로 id 만 잠금 없이 읽고 PK 로 FOR UPDATE 한 뒤 상태를 다시 판단한다
--    (보조 유니크 인덱스에 FOR UPDATE 를 걸지 않는다 — 좌석 인증 승인과 같은 규칙).
--  - idx_refresh_token_family: 재사용 탐지 시 계열 폐기 UPDATE(family_id = ? AND revoked_at IS NULL).
--  - idx_refresh_token_expires: 만료 행 배치 삭제(expires_at < ? ORDER BY expires_at LIMIT n). 인덱스가 없으면
--    DELETE 가 전체를 훑으며 살아 있는 행까지 잠가 재발급을 막는다. 새 행의 expires_at 은 항상 미래라
--    정리의 범위 잠금과 겹치지 않는다. 폐기됐지만 만료 전인 행은 재사용 탐지용으로 남긴다.
--
-- 재실행 가능: INFORMATION_SCHEMA 가드 프로시저. 기존 데이터 변경 없음, 파괴적 단계 없음.
-- 되돌리기(롤포워드): 새 V 파일에서 refresh_token 삭제(무상태 토큰 검증으로 되돌리는 코드와 함께).
--
-- 규칙: V1~V26 은 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상. 다음 마이그레이션은 V28.
-- =====================================================================

DROP PROCEDURE IF EXISTS v27_refresh_token;

DELIMITER //
CREATE PROCEDURE v27_refresh_token()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refresh_token') THEN
        CREATE TABLE refresh_token (
            id             BIGINT      NOT NULL AUTO_INCREMENT,
            user_id        BIGINT      NOT NULL,                                          -- 토큰 주인 -> users
            token_hash     CHAR(64)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,    -- SHA-256(토큰) 소문자 hex
            family_id      CHAR(36)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,    -- 회전 계열(UUID 소문자)
            family_started_at DATETIME(6) NOT NULL,                                       -- 계열 시작 시각(첫 로그인, 회전 때 그대로 물려줌 — 세션 최대 수명 계산)
            created_at     DATETIME(6) NOT NULL,
            expires_at     DATETIME(6) NOT NULL,
            revoked_at     DATETIME(6) NULL,
            revoke_reason  VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
            replaced_by_id BIGINT      NULL,                                              -- 회전으로 만든 새 행 id(FK 아님)
            PRIMARY KEY (id),
            CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
            KEY idx_refresh_token_user (user_id),
            KEY idx_refresh_token_family (family_id),
            KEY idx_refresh_token_expires (expires_at),
            CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES users (id),
            CONSTRAINT ck_refresh_token_hash CHECK (REGEXP_LIKE(token_hash, '^[0-9a-f]{64}$', 'c')),
            CONSTRAINT ck_refresh_token_family CHECK (
                REGEXP_LIKE(family_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
            CONSTRAINT ck_refresh_token_expires CHECK (expires_at > created_at),
            CONSTRAINT ck_refresh_token_family_started CHECK (family_started_at <= created_at),
            CONSTRAINT ck_refresh_token_revoked CHECK (
                (revoked_at IS NULL AND revoke_reason IS NULL)
                OR (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL AND revoked_at >= created_at)),
            CONSTRAINT ck_refresh_token_reason CHECK (
                revoke_reason IS NULL OR revoke_reason IN ('ROTATED', 'LOGOUT', 'REUSE', 'WITHDRAW', 'ADMIN')),
            CONSTRAINT ck_refresh_token_replaced CHECK (
                (replaced_by_id IS NULL AND (revoke_reason IS NULL OR revoke_reason <> 'ROTATED'))
                OR (replaced_by_id IS NOT NULL AND revoke_reason IS NOT NULL AND revoke_reason = 'ROTATED'))
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;
END//
DELIMITER ;

CALL v27_refresh_token();
DROP PROCEDURE v27_refresh_token;
