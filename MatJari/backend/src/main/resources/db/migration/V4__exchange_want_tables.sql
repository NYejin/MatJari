-- =====================================================================
-- V4: 교환 희망 조건 (요청 단위) — 희망 쪽 테이블 4개
--
-- 설계: 산출물/08_ERD/exchange-schema-design.md 1.2~1.5, 4.1, 6절 (2026-10-08 확정)
--  - exchange_request     : 티켓의 교환 희망 1건 (티켓당 1개). 추가금은 요청 단위 값(유형만 매칭에 쓰고 금액은 표시용).
--  - exchange_want_range  : 사용자가 입력한 희망 범위 (구역, 열 from~to, 번 from~to). 수정 화면 복원용.
--  - exchange_want_seat   : 범위를 펼친 개별 희망 좌석 (파생 데이터, 수정 시 전부 삭제 후 재생성). PK가 곧 후보 조회 인덱스.
--  - exchange_want_session: 희망 회차 + 사용자 우선순위 (1이 가장 높음). 같은 공연의 회차만(서비스 검사).
--
-- 이번 파일에 없는 것: 차단·매칭·예약 잠금·채팅·이력 테이블 (V5 이후).
-- row_from/row_to/col_from/col_to 에는 정규화 키(SeatKeyNormalizer)를 저장한다 (숫자는 앞 0 제거, 영문 대문자).
-- 규칙: V1~V3 는 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상 (CHECK 강제).
-- =====================================================================

CREATE TABLE exchange_request (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    ticket_id    BIGINT      NOT NULL,
    extra_type   VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    extra_amount INT         DEFAULT NULL,
    status       VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'OPEN',
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_exchange_request_ticket UNIQUE (ticket_id),
    CONSTRAINT fk_exchange_request_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id),
    CONSTRAINT ck_exchange_request_extra_type CHECK (extra_type IN ('X', 'ANY', 'POS', 'NEG')),
    CONSTRAINT ck_exchange_request_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_exchange_request_amount CHECK (
        (extra_type IN ('X', 'ANY') AND extra_amount IS NULL)
        OR (extra_type = 'POS' AND extra_amount > 0)
        OR (extra_type = 'NEG' AND extra_amount < 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE exchange_want_range (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    request_id BIGINT      NOT NULL,
    zone_label VARCHAR(50) NOT NULL,
    zone_key   VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    row_from   VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    row_to     VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    col_from   VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    col_to     VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    sort_order SMALLINT    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_exchange_want_range_request (request_id, sort_order),
    CONSTRAINT fk_exchange_want_range_request FOREIGN KEY (request_id)
        REFERENCES exchange_request (id) ON DELETE CASCADE,
    CONSTRAINT ck_exchange_want_range_sort CHECK (sort_order >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE exchange_want_seat (
    request_id BIGINT      NOT NULL,
    zone_key   VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    row_key    VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    col_key    VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (request_id, zone_key, row_key, col_key),
    CONSTRAINT fk_exchange_want_seat_request FOREIGN KEY (request_id)
        REFERENCES exchange_request (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE exchange_want_session (
    request_id             BIGINT   NOT NULL,
    performance_session_id BIGINT   NOT NULL,
    priority               SMALLINT NOT NULL,
    PRIMARY KEY (request_id, performance_session_id),
    KEY idx_exchange_want_session_session (performance_session_id),
    CONSTRAINT fk_exchange_want_session_request FOREIGN KEY (request_id)
        REFERENCES exchange_request (id) ON DELETE CASCADE,
    CONSTRAINT fk_exchange_want_session_session FOREIGN KEY (performance_session_id)
        REFERENCES performance_session (id),
    CONSTRAINT ck_exchange_want_session_priority CHECK (priority >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
