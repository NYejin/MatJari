-- =====================================================================
-- V1: SeatSwap 새 기준선 (5개 테이블: users, venue, performance, performance_session, ticket)
--
-- 2026-10-07 방향 전환으로 좌석표 트랙(seat_map_*·seat_correction 등)과 아직 구현하지 않은
-- 교환·채팅·후기 테이블을 걷어내고 처음부터 다시 쌓는다. 이전 V1~V3(12개+좌석표 테이블)는 삭제했고,
-- 좌석표 코드와 마이그레이션은 git 태그 archive/seatmap-track-20261007 에 보관되어 있다.
--
-- 기준: 직전 운영 스키마의 users/venue/performance/performance_session/ticket (SHOW CREATE TABLE)와
--       JPA 엔티티. ticket 에서는 seatmap_id(좌석표 FK)만 뺐다. 컬럼·제약·인덱스·collation은 그대로다.
--
-- 규칙
--  - 이 파일은 적용된 뒤에는 절대 수정하지 않는다. 변경은 새 V{n}__*.sql 로 추가한다.
--  - 이 새 기준선은 빈 DB에서만 실행된다. 이전 스키마가 남은 로컬 DB는 docker compose down -v 로 비운 뒤 적용한다.
--    (flyway_schema_history 에 옛 V1~V3 이력이 남아 있으면 체크섬 불일치로 기동하지 않는다.)
--  - FK/UNIQUE 제약 이름 중 FK... 로 시작하는 것은 Hibernate가 생성하던 이름을 그대로 쓴 것이다.
--  - 시각 컬럼(datetime)은 모두 KST(Asia/Seoul) 벽시계 시각으로 저장한다.
--  - 상태·enum 컬럼은 utf8mb4_bin + CHECK (기본 collation ai_ci 면 소문자 값이 CHECK 를 통과해 enum 매핑이 500 이 된다).
--  - 테이블 기본 collation: utf8mb4_0900_ai_ci (명시해서 서버 기본값에 의존하지 않는다)
--  - 요구 버전: MySQL 8.0.16 이상 (CHECK 강제)
--  - 교환 범위는 같은 공연이면 다른 회차끼리도 가능하다. ticket 은 performance_session 을 참조하고
--    공연은 performance_session.performance 로 얻는다 (performance_id 를 중복으로 두지 않는다).
--  - 관리자는 DB에서 직접 부여한다: UPDATE users SET role = 'ADMIN' WHERE email = '...';  (대문자만)
-- =====================================================================

-- ---------------------------------------------------------------------
-- 회원
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    created_at  DATETIME(6)  DEFAULT NULL,                -- 가입 시각 (KST)
    email       VARCHAR(255) NOT NULL,                    -- 로그인 ID
    nickname    VARCHAR(255) NOT NULL,
    password    VARCHAR(255) NOT NULL,                    -- BCrypt 해시
    trust_score DOUBLE       DEFAULT NULL,                -- 신뢰도 점수
    role        VARCHAR(20)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'USER',   -- USER/ADMIN. 가입은 항상 USER
    PRIMARY KEY (id),
    CONSTRAINT UK6dotkott2kjsp8vw4d0m25fb7 UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 공연장 (공유 기준 데이터)
-- ---------------------------------------------------------------------
CREATE TABLE venue (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    address         VARCHAR(255) DEFAULT NULL,
    created_at      DATETIME(6)  NOT NULL,
    name            VARCHAR(100) NOT NULL,
    normalized_name VARCHAR(100) NOT NULL,                -- 중복 판정용 정규화 이름 (기본 collation ai_ci 유지)
    status          VARCHAR(20)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'UNVERIFIED',  -- UNVERIFIED/VERIFIED (정식 등록)
    verified_by     BIGINT       DEFAULT NULL,            -- 정식 등록한 관리자 -> users
    verified_at     DATETIME(6)  DEFAULT NULL,            -- 정식 등록 시각 (KST)
    PRIMARY KEY (id),
    CONSTRAINT uk_venue_normalized_name UNIQUE (normalized_name),
    KEY fk_venue_verified_by (verified_by),
    CONSTRAINT fk_venue_verified_by FOREIGN KEY (verified_by) REFERENCES users (id),
    CONSTRAINT ck_venue_status CHECK (status IN ('UNVERIFIED', 'VERIFIED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 공연 (티켓팅 링크 기준 등록, 날짜는 performance_session 으로 분리)
-- ---------------------------------------------------------------------
CREATE TABLE performance (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    created_at    DATETIME(6)   NOT NULL,
    -- 링크 정규화 키. URL은 대소문자를 구분하므로 utf8mb4_bin (500자*4byte=2000byte, 인덱스 한도 3072 이내)
    source_key    VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_url    VARCHAR(2048) NOT NULL,
    title         VARCHAR(200)  NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,
    registrant_id BIGINT        NOT NULL,                 -- 등록자 -> users
    venue_id      BIGINT        NOT NULL,                 -- 공연장 -> venue
    PRIMARY KEY (id),
    CONSTRAINT uk_performance_source_key UNIQUE (source_key),
    KEY FK6p310v5n1wwgdqry9ksyx39nf (registrant_id),
    KEY FKlx8rv5t5hrt28t6v29osmacai (venue_id),
    CONSTRAINT FK6p310v5n1wwgdqry9ksyx39nf FOREIGN KEY (registrant_id) REFERENCES users (id),
    CONSTRAINT FKlx8rv5t5hrt28t6v29osmacai FOREIGN KEY (venue_id) REFERENCES venue (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 공연 회차 (날짜·시간). 같은 공연이면 다른 회차끼리도 교환할 수 있다.
-- ---------------------------------------------------------------------
CREATE TABLE performance_session (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    created_at     DATETIME(6) NOT NULL,
    starts_at      DATETIME(6) NOT NULL,                  -- 분 단위로 잘라 저장 (KST 벽시계 시각)
    performance_id BIGINT      NOT NULL,                  -- -> performance
    PRIMARY KEY (id),
    CONSTRAINT uk_performance_session_performance_starts_at UNIQUE (performance_id, starts_at),
    KEY idx_performance_session_starts_at (starts_at),
    CONSTRAINT FKmkyy6hirggrmacpvn0jr70xdk FOREIGN KEY (performance_id) REFERENCES performance (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 티켓 (사용자가 보유한 좌석). 좌석은 텍스트로 입력한다.
-- 구역·희망 범위 등 교환 매칭용 컬럼은 교환 도메인 설계 후 새 V 파일로 추가한다.
-- ---------------------------------------------------------------------
CREATE TABLE ticket (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    col_label              VARCHAR(255) DEFAULT NULL,     -- 번(좌석 번호)
    row_label              VARCHAR(255) DEFAULT NULL,     -- 열
    performance_session_id BIGINT       NOT NULL,         -- -> performance_session
    user_id                BIGINT       NOT NULL,         -- 보유자 -> users
    PRIMARY KEY (id),
    KEY FKr9sms242y9nbmnf5yjlqsb5xe (performance_session_id),
    KEY FKmvugyjf7b45u0juyue7k3pct0 (user_id),
    CONSTRAINT FKmvugyjf7b45u0juyue7k3pct0 FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT FKr9sms242y9nbmnf5yjlqsb5xe FOREIGN KEY (performance_session_id) REFERENCES performance_session (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
