-- 후보 조회 대량 수동 검증용 시드 (임시 MySQL 전용. 개발 DB에 실행하지 말 것).
-- 전제: 임시 DB에 앱(jar)을 한 번 띄워 V1~V7이 적용돼 있고, 사용자 1명(id=1)이 회원가입돼 있다(이 사용자가 "나").
-- 규모: 나의 희망 좌석 5,000석(1F 100열 x 50번 전부, 상한 값), 희망 회차 3개, 상대 4,000명(전원 후보가 되도록 구성).
--   - 내 티켓: 회차 1, 2F 50열 25번. 상대 티켓: 1F 구역 안(회차 1~3에 고르게 분산). 상대는 모두 2F 46~55열 x 1~50번(500석)과 회차 1~3을 희망.
--   - 내 추가금 ANY라 모든 추가금 유형이 호환. 상대 등록 시각은 분 단위로 분산(일부 동률 없음).
-- 실행: docker exec -i <컨테이너> mysql -uroot -ptmp matjari < candidates-bulk-seed.sql
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE exchange_want_seat;
TRUNCATE TABLE exchange_want_session;
TRUNCATE TABLE exchange_want_range;
TRUNCATE TABLE exchange_request;
TRUNCATE TABLE ticket;
TRUNCATE TABLE performance_session;
TRUNCATE TABLE performance;
DELETE FROM users WHERE id > 1;
SET FOREIGN_KEY_CHECKS = 1;

DROP TABLE IF EXISTS nums;
CREATE TABLE nums (n INT PRIMARY KEY);
INSERT INTO nums
SELECT a.d + b.d * 10 + c.d * 100 + e.d * 1000 + 1
FROM (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
     (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
     (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
     (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e;

INSERT INTO performance (id, created_at, updated_at, source_key, source_url, title, venue_name, registrant_id)
VALUES (1, NOW(6), NOW(6), 'bulk', 'http://x/bulk', 'bulk', 'venue', 1);
INSERT INTO performance_session (id, created_at, starts_at, performance_id)
SELECT n, NOW(6), DATE_ADD(DATE(NOW()), INTERVAL 30 + n DAY) + INTERVAL 19 HOUR, 1 FROM nums WHERE n <= 3;

INSERT INTO users (id, created_at, email, nickname, password, role)
SELECT n + 1, NOW(6), CONCAT('o', n, '@t.com'), CONCAT('nick', n), 'x', 'USER' FROM nums WHERE n <= 4000;

-- 내 티켓(id=1)과 상대 티켓(id=n+1)
INSERT INTO ticket (id, performance_session_id, user_id, zone_label, zone_key, row_label, row_key, col_label, col_key, status, created_at, updated_at)
VALUES (1, 1, 1, '2F', '2F', '50', '50', '25', '25', 'ACTIVE', NOW(6), NOW(6));
INSERT INTO ticket (id, performance_session_id, user_id, zone_label, zone_key, row_label, row_key, col_label, col_key, status, created_at, updated_at)
SELECT n + 1, (n - 1) % 3 + 1, n + 1, '1F', '1F',
       CAST(((n - 1) DIV 3) DIV 50 + 1 AS CHAR), CAST(((n - 1) DIV 3) DIV 50 + 1 AS CHAR),
       CAST(((n - 1) DIV 3) % 50 + 1 AS CHAR), CAST(((n - 1) DIV 3) % 50 + 1 AS CHAR),
       'ACTIVE', NOW(6), NOW(6)
FROM nums WHERE n <= 4000;

-- 요청(V6 이후 추가금은 요청이 아니라 희망 좌석/범위가 가진다): 나(id=1), 상대(id=n+1, 등록 시각은 n분 전)
INSERT INTO exchange_request (id, ticket_id, status, created_at, updated_at)
VALUES (1, 1, 'OPEN', NOW(6), NOW(6));
INSERT INTO exchange_request (id, ticket_id, status, created_at, updated_at)
SELECT n + 1, n + 1, 'OPEN', DATE_SUB(NOW(6), INTERVAL n MINUTE), NOW(6)
FROM nums WHERE n <= 4000;

-- 희망 회차: 모두 1,2,3 (우선순위 1,2,3)
INSERT INTO exchange_want_session (request_id, performance_session_id, priority)
SELECT r.id, s.n, s.n FROM exchange_request r JOIN nums s ON s.n <= 3;

-- 희망 좌석: 나는 1F 전체 5,000석(추가금 ANY), 상대는 2F 46~55열 x 1~50번 500석(추가금 유형은 (id-1)%4 순환: X/ANY/POS/NEG, 한 요청 안은 모두 같은 유형)
INSERT INTO exchange_want_seat (request_id, zone_key, row_key, col_key, extra_type, extra_amount)
SELECT 1, '1F', CAST(r.n AS CHAR), CAST(c.n AS CHAR), 'ANY', NULL FROM nums r JOIN nums c ON r.n <= 100 AND c.n <= 50;
INSERT INTO exchange_want_seat (request_id, zone_key, row_key, col_key, extra_type, extra_amount)
SELECT q.id, '2F', CAST(r.n AS CHAR), CAST(c.n AS CHAR),
       ELT((q.id - 1) % 4 + 1, 'X', 'ANY', 'POS', 'NEG'), ELT((q.id - 1) % 4 + 1, NULL, NULL, 10000, -10000)
FROM exchange_request q JOIN nums r ON r.n BETWEEN 46 AND 55 JOIN nums c ON c.n <= 50
WHERE q.id > 1;

ANALYZE TABLE users, ticket, exchange_request, exchange_want_seat, exchange_want_session, performance_session;
SELECT (SELECT COUNT(*) FROM exchange_request) AS requests,
       (SELECT COUNT(*) FROM exchange_want_seat) AS want_seat_rows,
       (SELECT COUNT(*) FROM exchange_want_seat WHERE request_id = 1) AS my_want_seats;
