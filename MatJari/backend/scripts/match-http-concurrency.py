"""매칭 생성·예약(reserve/unreserve) 동시성 HTTP 검증 (임시 MySQL + java -jar 로 띄운 앱 전용, 개발 DB·backend/.env 사용 금지).
사용: python match-http-concurrency.py [포트=18082] [라운드=30] [mysql컨테이너=matjari-tmp-match] [DB=matjari_http]
앱은 TICKET_MAX_ACTIVE_PER_USER=1000 으로 띄운다(라운드마다 티켓을 새로 만들기 때문).
검증: 같은 쌍 동시 제안 -> 201 하나 + 409 나머지 / 같은 티켓을 건 두 매칭의 동시 예약 -> RESERVED 하나 /
예약·예약 취소·취소·티켓 내리기 경쟁 -> 5xx(교착·락 대기 초과) 0건, 잠금 행과 상태 불일치 0건.
"""
import json
import random
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta

PORT = sys.argv[1] if len(sys.argv) > 1 else "18082"
ROUNDS = int(sys.argv[2]) if len(sys.argv) > 2 else 30
CONTAINER = sys.argv[3] if len(sys.argv) > 3 else "matjari-tmp-match"
DB = sys.argv[4] if len(sys.argv) > 4 else "matjari_http"
BASE = f"http://localhost:{PORT}"
RUN = str(int(time.time()))


def api(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw) if raw else None
        except Exception:
            return e.code, raw.decode(errors="replace")


def sql(q):
    out = subprocess.run(["docker", "exec", CONTAINER, "mysql", "-uroot", "-ptmp", "-N", "-B", DB, "-e", q],
                         capture_output=True, text=True)
    return [line.split("\t") for line in out.stdout.strip().splitlines() if line.strip()]


def signup_login(n):
    email = f"m{RUN}_{n}@t.com"
    api("POST", "/api/auth/signup", body={"email": email, "password": "password123", "nickname": f"u{n}{RUN[-4:]}"})
    s, b = api("POST", "/api/auth/login", body={"email": email, "password": "password123"})
    assert s == 200, (s, b)
    return b["accessToken"]


users = [signup_login(i) for i in range(1, 4)]
starts = (datetime.now() + timedelta(days=30)).replace(minute=0, second=0, microsecond=0).isoformat(timespec="minutes")
s, perf = api("POST", "/api/performances", users[0], {"sourceUrl": f"https://tickets.interpark.com/goods/{RUN}",
                                                     "title": "동시성", "venueName": "홀", "sessions": [starts]})
assert s == 201, (s, perf)
sid = perf["sessions"][0]["id"]


def ticket_and_request(token, zone, col, want_cols):
    s1, t = api("POST", "/api/tickets", token, {"sessionId": sid, "zone": zone, "row": "1", "col": str(col)})
    assert s1 == 201, (s1, t)
    ranges = [{"zone": zone, "rowFrom": "1", "rowTo": "1", "colFrom": str(c), "colTo": str(c), "extraType": "ANY"} for c in want_cols]
    s2, r = api("POST", "/api/exchange/requests", token,
                {"ticketId": t["id"], "wantSessions": [{"sessionId": sid, "priority": 1}], "ranges": ranges})
    assert s2 == 201, (s2, r)
    return t["id"], r["id"]


def burst(calls):
    """calls: [(label, fn)] 를 같은 순간에 출발시켜 [(label, status, body)] 로 돌려준다."""
    barrier = threading.Barrier(len(calls))

    def run(item):
        label, fn = item
        barrier.wait()
        st, body = fn()
        return label, st, body

    with ThreadPoolExecutor(max_workers=len(calls)) as pool:
        return list(pool.map(run, calls))


hist = Counter()
violations = []


def tally(scenario, results):
    for label, st, body in results:
        hist[(scenario, st)] += 1
        if st >= 500:
            violations.append((scenario, label, st, body))


# --- A: 같은 쌍 동시 제안 (양방향 4+4) -------------------------------------------------
for rnd in range(ROUNDS):
    zone = f"A{rnd}"
    (t1, r1), (t2, r2) = ticket_and_request(users[0], zone, 1, [2]), ticket_and_request(users[1], zone, 2, [1])
    calls = []
    for i in range(4):
        calls.append((f"1to2-{i}", lambda r1=r1, r2=r2: api("POST", f"/api/exchange/requests/{r1}/proposals", users[0], {"targetRequestId": r2})))
        calls.append((f"2to1-{i}", lambda r1=r1, r2=r2: api("POST", f"/api/exchange/requests/{r2}/proposals", users[1], {"targetRequestId": r1})))
    res = burst(calls)
    tally("A.propose", res)
    created = sum(1 for _, st, _ in res if st == 201)
    conflicts = sum(1 for _, st, b in res if st == 409 and isinstance(b, dict) and b.get("code") == "MATCH_ALREADY_OPEN")
    if created != 1 or conflicts != 7:
        violations.append(("A", rnd, created, conflicts, [st for _, st, _ in res]))

# --- B: 같은 티켓(p)을 건 두 매칭의 동시 예약 (한 명이 눌러도 RESERVED) ---------------------------------------
for rnd in range(ROUNDS):
    zone = f"B{rnd}"
    (tp, rp) = ticket_and_request(users[0], zone, 1, [2, 3])
    (tq, rq) = ticket_and_request(users[1], zone, 2, [1])
    (tr, rr) = ticket_and_request(users[2], zone, 3, [1])
    s, m1 = api("POST", f"/api/exchange/requests/{rp}/proposals", users[0], {"targetRequestId": rq})
    assert s == 201, (s, m1)
    s, m2 = api("POST", f"/api/exchange/requests/{rp}/proposals", users[0], {"targetRequestId": rr})
    assert s == 201, (s, m2)
    calls = [("p-m1", lambda: api("POST", f"/api/exchange/matches/{m1['id']}/reserve", users[0])),
             ("q-m1", lambda: api("POST", f"/api/exchange/matches/{m1['id']}/reserve", users[1])),
             ("p-m2", lambda: api("POST", f"/api/exchange/matches/{m2['id']}/reserve", users[0])),
             ("r-m2", lambda: api("POST", f"/api/exchange/matches/{m2['id']}/reserve", users[2]))]
    random.shuffle(calls)
    res = burst(calls)
    tally("B.reserve", res)
    reserved_responses = len({b["id"] for _, st, b in res if st == 200 and b["status"] == "RESERVED"})   # 같은 매칭을 두 사람이 눌러도 한 매칭
    others_ok = all(st in (200, 409) for _, st, _ in res)
    reserved_rows = sql(f"SELECT COUNT(*) FROM exchange_match WHERE id IN ({m1['id']},{m2['id']}) AND status='RESERVED'")[0][0]
    lock_rows = sql(f"SELECT COUNT(*) FROM exchange_ticket_lock WHERE match_id IN ({m1['id']},{m2['id']})")[0][0]
    if reserved_responses != 1 or not others_ok or reserved_rows != "1" or lock_rows != "2":
        violations.append(("B", rnd, reserved_responses, reserved_rows, lock_rows, [(l, st) for l, st, _ in res]))

# --- C: 예약/예약 취소/취소/거절 경쟁 ---------------------------------------------------------------
for rnd in range(ROUNDS):
    zone = f"C{rnd}"
    (tp, rp) = ticket_and_request(users[0], zone, 1, [2])
    (tq, rq) = ticket_and_request(users[1], zone, 2, [1])
    s, m = api("POST", f"/api/exchange/requests/{rp}/proposals", users[0], {"targetRequestId": rq})
    assert s == 201, (s, m)
    mid = m["id"]
    calls = [("p-reserve", lambda: api("POST", f"/api/exchange/matches/{mid}/reserve", users[0])),
             ("q-reserve", lambda: api("POST", f"/api/exchange/matches/{mid}/reserve", users[1])),
             ("p-cancel", lambda: api("POST", f"/api/exchange/matches/{mid}/cancel", users[0])),
             ("q-reject", lambda: api("POST", f"/api/exchange/matches/{mid}/reject", users[1]))]
    random.shuffle(calls)
    res = burst(calls)
    tally("C.reserve-cancel", res)

# --- E: RESERVED 매칭에서 예약 취소/재예약/채팅 종료 경쟁 ---------------------------------
for rnd in range(ROUNDS):
    zone = f"E{rnd}"
    (tp, rp) = ticket_and_request(users[0], zone, 1, [2])
    (tq, rq) = ticket_and_request(users[1], zone, 2, [1])
    s, m = api("POST", f"/api/exchange/requests/{rp}/proposals", users[0], {"targetRequestId": rq})
    assert s == 201, (s, m)
    mid = m["id"]
    s, rsv = api("POST", f"/api/exchange/matches/{mid}/reserve", users[0])
    assert s == 200 and rsv["status"] == "RESERVED", (s, rsv)
    calls = [("q-unreserve", lambda: api("POST", f"/api/exchange/matches/{mid}/unreserve", users[1])),
             ("q-reserve", lambda: api("POST", f"/api/exchange/matches/{mid}/reserve", users[1])),
             ("p-cancel", lambda: api("POST", f"/api/exchange/matches/{mid}/cancel", users[0])),
             ("p-unreserve", lambda: api("POST", f"/api/exchange/matches/{mid}/unreserve", users[0]))]
    random.shuffle(calls)
    res = burst(calls)
    tally("E.unreserve-race", res)

# --- D: 티켓 내리기 vs 예약 -----------------------------------------------------------
deact_ok = deact_409 = 0
for rnd in range(ROUNDS):
    zone = f"D{rnd}"
    (tp, rp) = ticket_and_request(users[0], zone, 1, [2])
    (tq, rq) = ticket_and_request(users[1], zone, 2, [1])
    s, m = api("POST", f"/api/exchange/requests/{rp}/proposals", users[0], {"targetRequestId": rq})
    assert s == 201, (s, m)
    mid = m["id"]

    def delayed_delete():
        time.sleep(random.uniform(0, 0.04))
        return api("DELETE", f"/api/tickets/{tq}", users[1])

    calls = [("p-reserve", lambda: api("POST", f"/api/exchange/matches/{mid}/reserve", users[0])),
             ("q-reserve", lambda: api("POST", f"/api/exchange/matches/{mid}/reserve", users[1])),
             ("q-deactivate", delayed_delete)]
    random.shuffle(calls)
    res = burst(calls)
    tally("D.deactivate-reserve", res)
    d = [st for l, st, _ in res if l == "q-deactivate"][0]
    deact_ok += d == 204
    deact_409 += d == 409

# --- 전체 불변식 -------------------------------------------------------------------------
inv = {
    "RESERVED 아닌 매칭에 남은 잠금": sql("SELECT COUNT(*) FROM exchange_ticket_lock l JOIN exchange_match m ON m.id=l.match_id WHERE m.status<>'RESERVED'")[0][0],
    "잠금이 2행 아닌 RESERVED 매칭": sql("SELECT COUNT(*) FROM exchange_match m WHERE m.status='RESERVED' AND (SELECT COUNT(*) FROM exchange_ticket_lock l WHERE l.match_id=m.id)<>2")[0][0],
    "INACTIVE 티켓에 남은 잠금": sql("SELECT COUNT(*) FROM exchange_ticket_lock l JOIN ticket t ON t.id=l.ticket_id WHERE t.status<>'ACTIVE'")[0][0],
    "같은 쌍 열린 매칭 중복": sql("SELECT COUNT(*) FROM (SELECT 1 FROM exchange_match WHERE open_flag=1 GROUP BY request_low_id, request_high_id HAVING COUNT(*)>1) d")[0][0],
    "RESERVED 인데 예약자·시각 없음 또는 비RESERVED 인데 예약자 있음": sql("SELECT COUNT(*) FROM exchange_match WHERE (status='RESERVED' AND (reserved_by_id IS NULL OR reserved_at IS NULL)) OR (status<>'RESERVED' AND (reserved_by_id IS NOT NULL OR reserved_at IS NOT NULL))")[0][0],
    "두 RESERVED 매칭이 공유하는 티켓": sql("SELECT COUNT(*) FROM (SELECT t FROM (SELECT ticket_a_id t FROM exchange_match WHERE status='RESERVED' UNION ALL SELECT ticket_b_id FROM exchange_match WHERE status='RESERVED') x GROUP BY t HAVING COUNT(*)>1) y")[0][0],
}
print(f"rounds per scenario = {ROUNDS}")
for k in sorted(hist):
    print(f"  {k[0]:22s} HTTP {k[1]}: {hist[k]}")
print(f"  D 티켓 내리기: 204={deact_ok}, 409={deact_409}")
print("불변식 위반 (0이어야 함):")
for k, v in inv.items():
    print(f"  {k}: {v}")
print("5xx/시나리오 위반:", len(violations))
for v in violations[:10]:
    print("   ", v)
sys.exit(1 if violations or any(v != "0" for v in inv.values()) else 0)
