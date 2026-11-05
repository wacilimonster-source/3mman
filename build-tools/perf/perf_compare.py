#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""T00 基线比较器：baseline vs candidate summary.json → 每指标 PASS/FAIL/NOT_RUN。

用法：
  python perf_compare.py --baseline reports/performance/run_a/summary.json \
                         --candidate reports/performance/run_b/summary.json \
                         [--metric ttid_ms --goal-pct -15]

规则（方案 §3.2）：
  * 未执行的指标输出 NOT_RUN，不得 PASS；
  * p95 相对变化超过阈值输出 FAIL；改善为 PASS；
  * 失败样本占比 >5% 输出 FAIL。
"""

import argparse
import json
import sys

# 初始验收门槛（方案 §3.2 表格；T00 低端机结果后允许修订一次）
DEFAULT_GOALS = {
    "cold_start": {"metric": "ttid_ms", "goalPct": -15.0, "fallbackDegradationLimitPct": 5.0},
    "reco_mem": {"metric": "java_heap_kb", "goalPct": 0.0},
}


def load(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def verdict(base, cand, goal):
    metric = goal["metric"]
    b = (base.get("raw") or {}).get(metric) or []
    c = (cand.get("raw") or {}).get(metric) or []
    b_valid = [x for x in b if x is not None]
    c_valid = [x for x in c if x is not None]
    if len(b_valid) < 2 or len(c_valid) < 2:
        return {"metric": metric, "verdict": "NOT_RUN",
                "reason": "baseline n=%d candidate n=%d（<2 视为未执行）" % (len(b_valid), len(c_valid))}
    bp95 = sorted(b_valid)[max(0, int(round(0.95 * len(b_valid))) - 1)]
    cp95 = sorted(c_valid)[max(0, int(round(0.95 * len(c_valid))) - 1)]
    pct = (cp95 - bp95) * 100.0 / bp95 if bp95 else 0.0
    fail_rate_c = cand.get("failureCount", 0) / max(1, cand.get("sampleCount", 1) + cand.get("failureCount", 0))
    if fail_rate_c > 0.05:
        return {"metric": metric, "verdict": "FAIL",
                "reason": "candidate 失败率 %.1f%% > 5%%" % (fail_rate_c * 100),
                "baselineP95": bp95, "candidateP95": cp95, "deltaPct": round(pct, 2)}
    goal_pct = goal["goalPct"]
    # goalPct<0 表示要求降低；>0 表示要求增长（内存目标是“不增长”，用 0）
    improved = (pct <= goal_pct) if goal_pct < 0 else (pct <= goal_pct)
    if improved:
        return {"metric": metric, "verdict": "PASS",
                "baselineP95": bp95, "candidateP95": cp95, "deltaPct": round(pct, 2)}
    return {"metric": metric, "verdict": "FAIL",
            "reason": "p95 变化 %+.2f%% 未达目标 %.2f%%" % (pct, goal_pct),
            "baselineP95": bp95, "candidateP95": cp95, "deltaPct": round(pct, 2)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline", required=True)
    ap.add_argument("--candidate", required=True)
    ap.add_argument("--scenario", default=None, help="默认取 candidate 的 scenario")
    args = ap.parse_args()

    base = load(args.baseline)
    cand = load(args.candidate)
    scenario = args.scenario or cand.get("scenario")
    goal = DEFAULT_GOALS.get(scenario)

    results = {
        "schemaVersion": 1,
        "scenario": scenario,
        "baselineApk": base.get("environment", {}).get("apkSha256"),
        "candidateApk": cand.get("environment", {}).get("apkSha256"),
        "checks": [],
    }
    if base.get("environment", {}).get("apkSha256") == cand.get("environment", {}).get("apkSha256"):
        results["checks"].append({"metric": "apk-identity", "verdict": "FAIL",
                                  "reason": "两个 summary 来自同一 APK，比较无效"})
    elif goal is None:
        results["checks"].append({"metric": scenario, "verdict": "NOT_RUN",
                                  "reason": "该场景无登记门槛"})
    else:
        results["checks"].append(verdict(base, cand, goal))

    ok = all(c["verdict"] in ("PASS", "NOT_RUN") for c in results["checks"])
    print(json.dumps(results, ensure_ascii=False, indent=2))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
