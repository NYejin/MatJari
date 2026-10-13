-- =====================================================================
-- V5: 교환 매칭 (후보를 골라 채팅을 시작한 매칭) + 예약 잠금 — 테이블 2개
--
-- 설계: 산출물/08_ERD/exchange-schema-design.md 1.7, 1.8, 3.2 (2026-10-08 확정)
--  - exchange_match       : 후보 선택으로 열린 매칭(채팅방). 상태 CHATTING -> RESERVED -> COMPLETED, 또는 CANCELED.
--                           a = 후보 목록에서 상대를 고른 쪽(제안자), b = 고른 상대. 같은 요청 쌍의 '열린' 매칭은 1개
--                           (방향 무관, 생성 컬럼 + UNIQUE). 한 요청에 열린 매칭은 여러 개 가능.
--  - exchange_ticket_lock : 예약 잠금. 양쪽이 '이 사람과 교환할게요'를 눌러 RESERVED 가 될 때 두 티켓에 한 트랜잭션으로
--                           INSERT 한다. PK(ticket_id) 때문에 한 티켓은 동시에 한 매칭에서만 예약된다.
--
-- 이번 파일에 없는 것: user_block(차단), chat_message(채팅), exchange_history(교환 이력) — 후속 V 파일.
-- 설계 초안과 다른 점: ticket_a_id / ticket_b_id 컬럼 추가(락 INSERT·티켓 내리기 시 매칭 시스템 취소 조회용),
--   a/b 티켓 서로 다름 CHECK, CANCELED 일관성 CHECK, 요청 a/b 각각의 FK 인덱스, 티켓 인덱스 2개.
-- 규칙: V1~V4 는 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상 (CHECK 강제).
-- =====================================================================

CREATE TABLE exchange_match (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    request_a_id     BIGINT      NOT NULL,
    request_b_id     BIGINT      NOT NULL,
    ticket_a_id      BIGINT      NOT NULL,
    ticket_b_id      BIGINT      NOT NULL,
    user_a_id        BIGINT      NOT NULL,
    user_b_id        BIGINT      NOT NULL,
    status           VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'CHATTING',
    a_reserved_at    DATETIME(6) DEFAULT NULL,
    b_reserved_at    DATETIME(6) DEFAULT NULL,
    a_completed_at   DATETIME(6) DEFAULT NULL,
    b_completed_at   DATETIME(6) DEFAULT NULL,
    canceled_by_id   BIGINT      DEFAULT NULL,
    canceled_at      DATETIME(6) DEFAULT NULL,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    request_low_id   BIGINT GENERATED ALWAYS AS (LEAST(request_a_id, request_b_id)) STORED,
    request_high_id  BIGINT GENERATED ALWAYS AS (GREATEST(request_a_id, request_b_id)) STORED,
    open_flag        TINYINT GENERATED ALWAYS AS (IF(status IN ('CHATTING', 'RESERVED'), 1, NULL)) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uk_exchange_match_open_pair UNIQUE (request_low_id, request_high_id, open_flag),
    KEY idx_exchange_match_request_a (request_a_id),
    KEY idx_exchange_match_request_b (request_b_id),
    -- FK 가 쓰는 인덱스는 반드시 단일 컬럼으로 둔다. (ticket_a_id, status) 같은 복합 인덱스를 FK 인덱스로 쓰면 status 를 바꾸는
    -- UPDATE(취소·거절·티켓 내림에 의한 시스템 취소)가 FK 인덱스 변경으로 취급되어 부모 행(ticket·users)에 S 잠금을 건다.
    -- 그러면 잠금 순서(티켓 id 오름차순) 밖에서 다른 티켓 행을 잡게 되어 교착이 난다(실제 MySQL 경쟁 테스트에서 재현·확인).
    KEY idx_exchange_match_ticket_a (ticket_a_id),
    KEY idx_exchange_match_ticket_b (ticket_b_id),
    KEY idx_exchange_match_user_a (user_a_id),
    KEY idx_exchange_match_user_b (user_b_id),
    CONSTRAINT fk_exchange_match_request_a FOREIGN KEY (request_a_id) REFERENCES exchange_request (id),
    CONSTRAINT fk_exchange_match_request_b FOREIGN KEY (request_b_id) REFERENCES exchange_request (id),
    CONSTRAINT fk_exchange_match_ticket_a  FOREIGN KEY (ticket_a_id)  REFERENCES ticket (id),
    CONSTRAINT fk_exchange_match_ticket_b  FOREIGN KEY (ticket_b_id)  REFERENCES ticket (id),
    CONSTRAINT fk_exchange_match_user_a    FOREIGN KEY (user_a_id)    REFERENCES users (id),
    CONSTRAINT fk_exchange_match_user_b    FOREIGN KEY (user_b_id)    REFERENCES users (id),
    CONSTRAINT fk_exchange_match_canceled_by FOREIGN KEY (canceled_by_id) REFERENCES users (id),
    CONSTRAINT ck_exchange_match_status CHECK (status IN ('CHATTING', 'RESERVED', 'COMPLETED', 'CANCELED')),
    CONSTRAINT ck_exchange_match_distinct_requests CHECK (request_a_id <> request_b_id),
    CONSTRAINT ck_exchange_match_distinct_tickets CHECK (ticket_a_id <> ticket_b_id),
    CONSTRAINT ck_exchange_match_canceled CHECK (
        (status = 'CANCELED' AND canceled_at IS NOT NULL)
        OR (status <> 'CANCELED' AND canceled_at IS NULL AND canceled_by_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE exchange_ticket_lock (
    ticket_id  BIGINT      NOT NULL,
    match_id   BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (ticket_id),
    KEY idx_exchange_ticket_lock_match (match_id),
    CONSTRAINT fk_exchange_ticket_lock_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id),
    CONSTRAINT fk_exchange_ticket_lock_match  FOREIGN KEY (match_id)  REFERENCES exchange_match (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
