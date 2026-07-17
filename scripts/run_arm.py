#!/usr/bin/env python3
"""arm 하나를 한 지점에서 돌리고 결과를 JSON 으로 뱉는다.

이 스크립트가 **총 자원 고정 불변식의 단일 출처**다.
총량(CACHE_TOTAL / CPU_TOTAL / THREADS_TOTAL)에서 노드당 값을 계산한다:
    arm A → 1 노드가 총량 전부
    arm B → 2 노드가 절반씩
    arm C → 2 노드가 절반씩
compose 나 arm.env 에 노드당 값을 직접 적으면 언젠가 한쪽만 고쳐져
불변식이 조용히 깨진다. 계산은 여기서만 한다.

실행 위치: 개발 노트북(192.168.55.114). BPC/APC 를 ssh 로 조종한다.
"""
import argparse, json, math, subprocess, sys, time, urllib.request
from datetime import datetime, timezone

BPC = "jun@192.168.55.164"
APC = "jun@192.168.55.9"
BPC_IP = "192.168.55.164"
REMOTE = "cache-split-lab"

# ── 총량 — arm 무관 상수. 이 셋이 "같은 자원을 어떻게 배치하느냐"의 '같은 자원'이다.
CACHE_TOTAL   = 10_000    # 엔트리 (바이트 아님 — DECISIONS.md §10)
CPU_TOTAL     = 4.0       # BPC 16코어 중 앱 몫
THREADS_TOTAL = 200       # tomcat 워커 (DECISIONS.md §17)
ORIGIN_CPUS   = 4.0       # origin 은 측정 대상이 아니다 — 병목이 되면 안 된다
ORIGIN_MEM    = "3g"
APP_MEM       = "2g"

ARMS = {
    "a-single":     {"nodes": 1, "profile": ""},
    "b-roundrobin": {"nodes": 2, "profile": "two-node"},
    "c-keyhash":    {"nodes": 2, "profile": "two-node"},
}


def sh(cmd, check=True, capture=True):
    r = subprocess.run(cmd, shell=True, text=True,
                       stdout=subprocess.PIPE if capture else None,
                       stderr=subprocess.STDOUT if capture else None)
    if check and r.returncode != 0:
        raise RuntimeError(f"명령 실패({r.returncode}): {cmd}\n{r.stdout}")
    return (r.stdout or "").strip()


def get_json(url, timeout=5):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.loads(r.read())


def post(url, timeout=5):
    req = urllib.request.Request(url, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def per_node(arm):
    """총량 → 노드당. **여기가 불변식의 단일 출처.**"""
    n = ARMS[arm]["nodes"]
    if CACHE_TOTAL % n != 0:
        raise SystemExit(f"CACHE_TOTAL({CACHE_TOTAL})이 노드 수({n})로 나누어떨어지지 않는다 — "
                         f"A 와 B/C 의 총량이 어긋나 최상위 불변식이 깨진다")
    if THREADS_TOTAL % n != 0:
        raise SystemExit(f"THREADS_TOTAL({THREADS_TOTAL})이 노드 수({n})로 나누어떨어지지 않는다")
    return {
        "APP_CACHE_CAPACITY": CACHE_TOTAL // n,
        "APP_CPUS":           f"{CPU_TOTAL / n:g}",
        "APP_MEM":            APP_MEM,
        "THREADS_MAX":        THREADS_TOTAL // n,
        "ORIGIN_CPUS":        f"{ORIGIN_CPUS:g}",
        "ORIGIN_MEM":         ORIGIN_MEM,
    }


def compose(arm, keyspace, delay_ms, action):
    env = per_node(arm)
    env["ORIGIN_DELAY_MS"] = str(delay_ms)
    env["CSV_PATH_FILE"] = f"dataset-{keyspace}.csv"
    envs = " ".join(f"{k}={v}" for k, v in env.items())
    prof = f"COMPOSE_PROFILES={ARMS[arm]['profile']}"
    return sh(f"ssh {BPC} 'cd {REMOTE} && {envs} {prof} DATASET={keyspace} "
              f"docker compose --env-file arms/{arm}/arm.env -f arms/compose.yml {action}'",
              check=(action != "down"))


def wait_healthy(nodes, timeout=120):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            for p in nodes:
                get_json(f"http://{BPC_IP}:{p}/admin/stats")
            get_json(f"http://{BPC_IP}:9090/admin/stats")
            return time.time() - t0
        except Exception:
            time.sleep(2)
    raise RuntimeError("헬스체크 타임아웃")


def k6(keyspace, skew, rps, duration, seed=42, background=False):
    cmd = (f"ssh {APC} 'docker run --rm -v ~/{REMOTE}-k6:/s "
           f"-e TARGET=http://{BPC_IP} -e KEYSPACE={keyspace} -e SKEW={skew} "
           f"-e RPS={rps} -e DURATION={duration} -e SEED={seed} "
           f"grafana/k6:latest run --summary-export=/dev/stdout --quiet /s/load.js'")
    if background:
        return subprocess.Popen(cmd, shell=True, text=True,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return sh(cmd, check=False)


def prewarm(keyspace):
    """모든 키를 정확히 한 번씩 훑는다 — 쿠폰 수집가 구간을 건너뛴다.

    비율 ≤ 1 에서 arm A 는 축출을 안 하므로 히트율이 100% 에 **점근**하는데,
    Zipf 꼬리 때문에 수렴이 극도로 느리다(실측: 40,000 요청에 10,000 키 중 6,638 개만).
    수렴 전에 재면 A 가 과소평가되고 손실(A−B)도 과소평가된다 —
    **우리 가설에 불리한 방향의 편향**이다.

    정상상태를 바꾸는 게 아니라 거기 빨리 도달할 뿐이고, 세 arm 에 똑같이 적용된다.
    """
    t0 = time.time()
    sh(f"ssh {APC} 'docker run --rm -v ~/{REMOTE}-k6:/s "
       f"-e TARGET=http://{BPC_IP} -e KEYSPACE={keyspace} "
       f"grafana/k6:latest run --quiet /s/prewarm.js'", check=False)
    return round(time.time() - t0, 1)


def totals(nodes):
    """전 노드의 히트/미스 합."""
    h = m = 0
    for p in nodes:
        s = get_json(f"http://{BPC_IP}:{p}/admin/stats")
        h += s["hits"]; m += s["misses"]
    return h, m


def warmup_until_plateau(nodes, keyspace, skew, rps, max_s=420, window=10, tol=0.003, need=3):
    """★ 고정 횟수로 못 박지 않고 **순간 히트율이 평평해지는 것을 탐지**한다.

    N 마다 필요한 워밍업이 다른데 한 숫자로 정하면 한쪽은 낭비고 한쪽은 오염이다.
    누적 히트율은 천천히 움직여서 plateau 판정이 둔하다 → **구간 델타**로 본다.
    """
    proc = k6(keyspace, skew, rps, f"{max_s}s", seed=1, background=True)
    curve, stable, prev = [], 0, None
    ph, pm = totals(nodes)
    t0 = time.time()
    try:
        while time.time() - t0 < max_s - window:
            time.sleep(window)
            h, m = totals(nodes)
            dh, dm = h - ph, m - pm
            ph, pm = h, m
            if dh + dm == 0:
                continue
            hr = dh / (dh + dm)                      # 이 구간의 순간 히트율
            curve.append({"t": round(time.time() - t0, 1), "interval_hit_rate": round(hr, 5)})
            if prev is not None and abs(hr - prev) < tol:
                stable += 1
                if stable >= need:
                    return {"plateaued": True, "seconds": round(time.time() - t0, 1),
                            "final_interval_hit_rate": round(hr, 5), "curve": curve}
            else:
                stable = 0
            prev = hr
        return {"plateaued": False, "seconds": round(time.time() - t0, 1),
                "final_interval_hit_rate": round(prev or 0, 5), "curve": curve,
                "warning": "plateau 미도달 — 이 런의 히트율은 정상상태가 아닐 수 있다"}
    finally:
        proc.terminate()
        try: proc.wait(10)
        except Exception: proc.kill()
        time.sleep(2)


def collect(arm, nodes):
    per = []
    for p in nodes:
        s = get_json(f"http://{BPC_IP}:{p}/admin/stats")
        k = get_json(f"http://{BPC_IP}:{p}/admin/cache/keys")
        per.append({"port": p, "node_id": s["nodeId"], "hits": s["hits"], "misses": s["misses"],
                    "hit_rate": s["hitRate"], "cache_size": s["cacheSize"],
                    "capacity": s["capacity"], "threads_active": s["threadsActive"],
                    "in_flight_max": s["inFlightMax"], "threads_max": s["threadsMax"],
                    "keys": set(k["keys"])})
    o = get_json(f"http://{BPC_IP}:9090/admin/stats")

    h = sum(x["hits"] for x in per); m = sum(x["misses"] for x in per)
    out = {
        "arm": arm,
        "total_hits": h, "total_misses": m,
        "hit_rate": h / (h + m) if h + m else 0.0,
        "origin_requests": o["requests"], "dataset_size": o["datasetSize"],
        "per_node": [{k: v for k, v in x.items() if k != "keys"} for x in per],
    }
    # 노드별 요청 분포 — arm C 의 핫키 쏠림. 없으면 C 의 대가를 못 본다.
    reqs = [x["hits"] + x["misses"] for x in per]
    tot = sum(reqs) or 1
    out["node_load_share"] = [round(r / tot, 4) for r in reqs]

    # ★ 스모킹 건 — 두 노드 캐시의 교집합.
    #   히트율 차이는 "결과"고 이 교집합이 "원인"이다.
    if len(per) == 2:
        a, b = per[0]["keys"], per[1]["keys"]
        inter = len(a & b)
        out["cache_overlap"] = {
            "node1_size": len(a), "node2_size": len(b), "intersection": inter,
            "overlap_ratio": round(inter / min(len(a), len(b)), 4) if min(len(a), len(b)) else 0.0,
            "union": len(a | b),
        }
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("arm", choices=list(ARMS))
    ap.add_argument("--keyspace", type=int, required=True)
    ap.add_argument("--skew", type=float, required=True)
    ap.add_argument("--rps", type=int, required=True)
    ap.add_argument("--duration", default="60s")
    ap.add_argument("--delay-ms", type=int, default=5)
    ap.add_argument("--warmup-max", type=int, default=300)
    # 히트율은 RPS 에 무관하므로(DECISIONS.md §15) 워밍업은 빠르게 돌려 시간을 아낀다.
    ap.add_argument("--warmup-rps", type=int, default=3000)
    ap.add_argument("--out")
    a = ap.parse_args()

    nodes = [8081, 8082][: ARMS[a.arm]["nodes"]]
    pn = per_node(a.arm)
    rec = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "arm": a.arm, "keyspace": a.keyspace, "skew": a.skew, "rps": a.rps,
        "duration": a.duration, "delay_ms": a.delay_ms,
        "ratio": round(a.keyspace / CACHE_TOTAL, 4),
        "totals": {"cache": CACHE_TOTAL, "cpu": CPU_TOTAL, "threads": THREADS_TOTAL},
        "per_node_config": pn,
    }
    print(f"── {a.arm} | N={a.keyspace} (비율 {rec['ratio']}) | s={a.skew} | {a.rps} RPS", flush=True)
    print(f"   노드당: 캐시 {pn['APP_CACHE_CAPACITY']} × {ARMS[a.arm]['nodes']}노드 "
          f"= {CACHE_TOTAL} | CPU {pn['APP_CPUS']} | 스레드 {pn['THREADS_MAX']}", flush=True)

    try:
        compose(a.arm, a.keyspace, a.delay_ms, "up -d --build")
        rec["startup_seconds"] = round(wait_healthy(nodes), 1)

        print("   프리웜 (전 키 1회 훑기 — 쿠폰수집가 구간 제거)…", flush=True)
        rec["prewarm_seconds"] = prewarm(a.keyspace)
        pre = [get_json(f"http://{BPC_IP}:{p}/admin/stats") for p in nodes]
        rec["prewarm_cache_sizes"] = [s["cacheSize"] for s in pre]
        print(f"   프리웜 {rec['prewarm_seconds']}s → 캐시 상주 {rec['prewarm_cache_sizes']} "
              f"(용량 {pre[0]['capacity']})", flush=True)

        print("   Zipf 워밍업 (plateau 탐지 — LRU 가 핫셋 순서를 정리)…", flush=True)
        rec["warmup"] = warmup_until_plateau(nodes, a.keyspace, a.skew, a.warmup_rps,
                                             max_s=a.warmup_max)
        print(f"   워밍업 {rec['warmup']['seconds']}s, plateau={rec['warmup']['plateaued']}, "
              f"구간 히트율 {rec['warmup']['final_interval_hit_rate']}", flush=True)

        for p in nodes:
            post(f"http://{BPC_IP}:{p}/admin/reset-counters")
        origin_before = get_json(f"http://{BPC_IP}:9090/admin/stats")["requests"]

        print("   측정…", flush=True)
        rec["k6_raw"] = k6(a.keyspace, a.skew, a.rps, a.duration, seed=42)[-4000:]
        rec.update(collect(a.arm, nodes))
        rec["origin_requests_delta"] = rec["origin_requests"] - origin_before

        # 교차 검증: 앱들의 miss 합 ≡ origin 요청 증가분
        rec["sanity"] = {
            "miss_equals_origin": rec["total_misses"] == rec["origin_requests_delta"],
            "miss_vs_origin": [rec["total_misses"], rec["origin_requests_delta"]],
            "cache_exact": all(n["cache_size"] <= n["capacity"] for n in rec["per_node"]),
        }
        print(f"   → 히트율 {rec['hit_rate']:.4f} | 노드분포 {rec['node_load_share']} "
              f"| 교집합 {rec.get('cache_overlap', {}).get('overlap_ratio', '-')}", flush=True)
    finally:
        compose(a.arm, a.keyspace, a.delay_ms, "down -v")

    if a.out:
        import pathlib
        p = pathlib.Path(a.out); p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(rec, indent=2, ensure_ascii=False))
        print(f"   저장: {a.out}", flush=True)
    else:
        print(json.dumps(rec, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
