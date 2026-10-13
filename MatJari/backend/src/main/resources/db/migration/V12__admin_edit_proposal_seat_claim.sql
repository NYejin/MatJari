-- =====================================================================
-- V12: 관리자 처리 대상 두 가지 (공연 수정 제안, 좌석 소유 인증 요청)
--
-- 설계: feature/admin 확정 설계 (2026-10-12)
--  - performance_edit_proposal: 사용자가 공연 제목·공연장 이름 수정을 제안하고 관리자가 승인/거절한다.
--    회차·링크는 수정 대상이 아니다. 새 값은 new_title / new_venue_name 중 하나 이상(공백만은 불가, ck_..._change).
--    승인 시점의 이전 값은 old_title / old_venue_name 에 둘 다 스냅샷한다(APPROVED 일 때만 값이 있다, ck_..._snapshot).
--  - seat_claim: '내 티켓 인증'. 티켓 id 가 아니라 좌석 좌표(회차·구역·열·번)를 담는다(409 응답이 상대 티켓 id 를
--    노출하지 않으므로). 좌석 키 컬럼은 ticket 과 같은 길이(구역 50, 열·번 20)·collation(*_key 는 utf8mb4_bin)이며
--    정규화는 서비스(SeatKeyNormalizer)가 한다. 예매내역 사진은 서버 디스크에 UUID 이름(image_key)으로 저장하고
--    처리 후 파일을 지운 뒤 image_deleted_at 을 기록한다(행은 남긴다). resolved_ticket_id 는 승인 때 내려진 티켓 id 기록이며
--    FK 가 아니다(감사용 값, 티켓 잠금 경로와 엮지 않는다).
--  - 상태: PENDING / APPROVED / REJECTED (utf8mb4_bin + CHECK). 처리됐으면 reviewed_by_id·reviewed_at 이 반드시 있고,
--    PENDING 이면 reviewed_*·review_note 가 비어 있다(ck_..._review).
--  - 'PENDING 은 1건' 유니크: 생성 컬럼(STORED) pending_*_id = IF(status = 'PENDING', 사용자 id, NULL) 를 맨 앞에 둔 UNIQUE.
--    처리되면 NULL 이 되어 유니크에서 빠지므로 같은 사용자가 다시 제안/요청할 수 있다.
--    맨 앞을 FK 컬럼이 아닌 생성 컬럼으로 둔 이유(V7 과 같음): FK 컬럼이 선두인 복합 인덱스는 FK 인덱스로 쓰일 수 있고,
--    상태 전이 때 갱신되는 컬럼이 섞이면 부모 행 공유 잠금으로 교착이 날 수 있다. 이 유니크는 '사용자당 PENDING 개수' 조회
--    (pending_*_id = ?) 인덱스도 겸한다.
--  - FK 인덱스는 단일 컬럼이며 갱신 컬럼을 붙이지 않는다. FK 에는 ON DELETE/UPDATE 동작을 붙이지 않는다
--    (CHECK·생성 컬럼에 쓰이는 컬럼의 FK 는 동작을 붙일 수 없다).
--  - 관리자 목록(status = ? ORDER BY id DESC)용 idx_*_status(status) 는 FK 인덱스가 아니다(InnoDB 보조 인덱스가 PK 를 포함해 정렬 불필요).
--  - 잠금 순서(서비스): 제안 -> 공연 / 인증 요청 -> 티켓 id↑ -> 요청 id↑ -> 매칭. 다른 경로에서 이 두 테이블을 나중에 잠그지 않는다.
--
-- 재실행 가능: V2·V6~V11 과 같은 INFORMATION_SCHEMA 가드 프로시저. 파괴적 단계 없음.
-- 되돌리기(롤포워드): 새 V 파일에서 두 테이블 삭제.
--
-- 규칙: V1~V11 은 수정하지 않는다. 요구 버전 MySQL 8.0.16 이상. 다음 마이그레이션은 V13.
-- =====================================================================

DROP PROCEDURE IF EXISTS v12_admin_edit_proposal_seat_claim;

DELIMITER //
CREATE PROCEDURE v12_admin_edit_proposal_seat_claim()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance_edit_proposal') THEN
        CREATE TABLE performance_edit_proposal (
            id                  BIGINT       NOT NULL AUTO_INCREMENT,
            performance_id      BIGINT       NOT NULL,                 -- -> performance
            proposer_id         BIGINT       NOT NULL,                 -- 제안자 -> users
            new_title           VARCHAR(200) NULL,                     -- 제안 값(바꾸지 않으면 NULL)
            new_venue_name      VARCHAR(100) NULL,
            reason              VARCHAR(500) NULL,
            status              VARCHAR(20)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'PENDING',
            old_title           VARCHAR(200) NULL,                     -- 승인 시점의 이전 값 스냅샷(APPROVED 일 때만)
            old_venue_name      VARCHAR(100) NULL,
            reviewed_by_id      BIGINT       NULL,                     -- 처리한 관리자 -> users
            reviewed_at         DATETIME(6)  NULL,
            review_note         VARCHAR(300) NULL,
            created_at          DATETIME(6)  NOT NULL,
            updated_at          DATETIME(6)  NOT NULL,
            pending_proposer_id BIGINT GENERATED ALWAYS AS (IF(status = 'PENDING', proposer_id, NULL)) STORED,  -- FK 아님
            PRIMARY KEY (id),
            CONSTRAINT uk_performance_edit_proposal_pending UNIQUE (pending_proposer_id, performance_id),
            KEY idx_performance_edit_proposal_performance (performance_id),
            KEY idx_performance_edit_proposal_proposer (proposer_id),
            KEY idx_performance_edit_proposal_reviewed_by (reviewed_by_id),
            KEY idx_performance_edit_proposal_status (status),
            CONSTRAINT fk_performance_edit_proposal_performance FOREIGN KEY (performance_id) REFERENCES performance (id),
            CONSTRAINT fk_performance_edit_proposal_proposer    FOREIGN KEY (proposer_id)    REFERENCES users (id),
            CONSTRAINT fk_performance_edit_proposal_reviewed_by FOREIGN KEY (reviewed_by_id) REFERENCES users (id),
            CONSTRAINT ck_performance_edit_proposal_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
            CONSTRAINT ck_performance_edit_proposal_change CHECK (
                (new_title IS NOT NULL OR new_venue_name IS NOT NULL)
                AND (new_title IS NULL OR CHAR_LENGTH(TRIM(new_title)) > 0)
                AND (new_venue_name IS NULL OR CHAR_LENGTH(TRIM(new_venue_name)) > 0)),
            CONSTRAINT ck_performance_edit_proposal_text CHECK (
                (reason IS NULL OR CHAR_LENGTH(TRIM(reason)) > 0)
                AND (review_note IS NULL OR CHAR_LENGTH(TRIM(review_note)) > 0)),
            CONSTRAINT ck_performance_edit_proposal_review CHECK (
                (status = 'PENDING' AND reviewed_by_id IS NULL AND reviewed_at IS NULL AND review_note IS NULL)
                OR (status IN ('APPROVED', 'REJECTED') AND reviewed_by_id IS NOT NULL AND reviewed_at IS NOT NULL)),
            CONSTRAINT ck_performance_edit_proposal_snapshot CHECK (
                (status = 'APPROVED' AND old_title IS NOT NULL AND old_venue_name IS NOT NULL)
                OR (status <> 'APPROVED' AND old_title IS NULL AND old_venue_name IS NULL))
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'seat_claim') THEN
        CREATE TABLE seat_claim (
            id                     BIGINT       NOT NULL AUTO_INCREMENT,
            claimant_id            BIGINT       NOT NULL,              -- 요청자 -> users
            performance_session_id BIGINT       NOT NULL,              -- -> performance_session
            zone_label             VARCHAR(50)  NOT NULL,              -- ticket 과 같은 길이·collation
            zone_key               VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
            row_label              VARCHAR(20)  NOT NULL,
            row_key                VARCHAR(20)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
            col_label              VARCHAR(20)  NOT NULL,
            col_key                VARCHAR(20)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
            memo                   VARCHAR(300) NULL,
            image_key              VARCHAR(36)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,     -- 서버 저장 이름(UUID 소문자)
            image_content_type     VARCHAR(20)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,     -- 매직 넘버로 판정한 형식
            image_size             INT          NOT NULL,              -- 바이트, 1 ~ 5 MiB
            image_deleted_at       DATETIME(6)  NULL,                  -- 처리 후 파일 삭제 시각
            status                 VARCHAR(20)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'PENDING',
            reviewed_by_id         BIGINT       NULL,                  -- 처리한 관리자 -> users
            reviewed_at            DATETIME(6)  NULL,
            review_note            VARCHAR(300) NULL,
            resolved_ticket_id     BIGINT       NULL,                  -- 승인 때 내려진 티켓 id (FK 아님, 이미 비어 있었으면 NULL)
            created_at             DATETIME(6)  NOT NULL,
            updated_at             DATETIME(6)  NOT NULL,
            pending_claimant_id    BIGINT GENERATED ALWAYS AS (IF(status = 'PENDING', claimant_id, NULL)) STORED,  -- FK 아님
            PRIMARY KEY (id),
            CONSTRAINT uk_seat_claim_pending_seat UNIQUE (pending_claimant_id, performance_session_id, zone_key, row_key, col_key),
            CONSTRAINT uk_seat_claim_image_key UNIQUE (image_key),
            KEY idx_seat_claim_claimant (claimant_id),
            KEY idx_seat_claim_session (performance_session_id),
            KEY idx_seat_claim_reviewed_by (reviewed_by_id),
            KEY idx_seat_claim_status (status),
            CONSTRAINT fk_seat_claim_claimant    FOREIGN KEY (claimant_id)            REFERENCES users (id),
            CONSTRAINT fk_seat_claim_session     FOREIGN KEY (performance_session_id) REFERENCES performance_session (id),
            CONSTRAINT fk_seat_claim_reviewed_by FOREIGN KEY (reviewed_by_id)         REFERENCES users (id),
            CONSTRAINT ck_seat_claim_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
            CONSTRAINT ck_seat_claim_seat CHECK (
                CHAR_LENGTH(zone_key) > 0 AND CHAR_LENGTH(row_key) > 0 AND CHAR_LENGTH(col_key) > 0),
            CONSTRAINT ck_seat_claim_text CHECK (
                (memo IS NULL OR CHAR_LENGTH(TRIM(memo)) > 0)
                AND (review_note IS NULL OR CHAR_LENGTH(TRIM(review_note)) > 0)),
            CONSTRAINT ck_seat_claim_image_key CHECK (
                REGEXP_LIKE(image_key, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
            CONSTRAINT ck_seat_claim_image_type CHECK (image_content_type IN ('image/jpeg', 'image/png', 'image/webp')),
            CONSTRAINT ck_seat_claim_image_size CHECK (image_size BETWEEN 1 AND 5242880),
            CONSTRAINT ck_seat_claim_image_deleted CHECK (status <> 'PENDING' OR image_deleted_at IS NULL),
            CONSTRAINT ck_seat_claim_review CHECK (
                (status = 'PENDING' AND reviewed_by_id IS NULL AND reviewed_at IS NULL AND review_note IS NULL)
                OR (status IN ('APPROVED', 'REJECTED') AND reviewed_by_id IS NOT NULL AND reviewed_at IS NOT NULL)),
            CONSTRAINT ck_seat_claim_resolved CHECK (resolved_ticket_id IS NULL OR status = 'APPROVED')
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
    END IF;
END//
DELIMITER ;

CALL v12_admin_edit_proposal_seat_claim();
DROP PROCEDURE v12_admin_edit_proposal_seat_claim;
