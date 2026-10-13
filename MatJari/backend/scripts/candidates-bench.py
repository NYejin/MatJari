"""후보 조회 응답 시간 측정 (임시 MySQL + java -jar 로 띄운 앱 전용).
사용: python candidates-bench.py <토큰파일> <요청id> <size> [page] [포트=18081]
첫 5회(워밍업)를 뺀 중앙값/p95/최대를 ms로 출력한다. 비교용으로 가벼운 /api/users/me 도 같이 잰다.
"""
import json
import statistics
import subprocess
import sys

token = open(sys.argv[1]).read().strip()
rid, size = sys.argv[2], sys.argv[3]
page = sys.argv[4] if len(sys.argv) > 4 else "0"
port = sys.argv[5] if len(sys.argv) > 5 else "18081"


def timed(url, out):
    r = subprocess.run(["curl", "-s", "-o", out, "-w", "%{time_total}", "-H", "Authorization: Bearer " + token, url],
                       capture_output=True, text=True)
    return float(r.stdout) * 1000


def summarize(ts):
    w = sorted(ts[5:])
    return statistics.median(w), w[int(len(w) * 0.95)], w[-1]


base = f"http://localhost:{port}"
url = f"{base}/api/exchange/requests/{rid}/candidates?size={size}&page={page}"
ts = [timed(url, "bench-body.json") for _ in range(45)]
d = json.load(open("bench-body.json"))
med, p95, mx = summarize(ts)
ref = summarize([timed(f"{base}/api/users/me", "bench-ref.json") for _ in range(45)])
print(f"req {rid} size {size} page {page}: total={d['totalElements']} returned={len(d['content'])} "
      f"median={med:.0f}ms p95={p95:.0f}ms max={mx:.0f}ms (cold first={ts[0]:.0f}ms) | /api/users/me median={ref[0]:.0f}ms")
