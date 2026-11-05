#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""T00 性能基线测量驱动（design/性能优化技术方案_2026-11-05.md §3/§5-T00）。

用法：
  python perf_run.py --serial <device> --apk <apk> --scenario cold_start \
      --iterations 50 --output reports/performance/<run-id>

行为约定：
  * 缺设备/缺 APK 明确失败退出（exit 2），不生成成功报告；
  * 冷启动用 force-stop 保留用户数据，不用 pm clear；
  * 输出 environment.json + summary.json（schemaVersion=1，含原始样本）；
  * 未执行的指标记 NOT_RUN，不得标 PASS。
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time

SCHEMA_VERSION = 1

SCENARIOS = ("cold_start", "reco_scroll", "reco_mem", "reco_cache_first", "hls_segments")


def fail(msg, code=2):
    print("[PERF-RUN][FAIL] " + msg, file=sys.stderr)
    sys.exit(code)


def run_adb(adb, serial, *args, timeout=60):
    cmd = [adb, "-s", serial] + list(args)
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                          errors="replace")
    return proc.returncode, proc.stdout, proc.stderr


def require_device(adb, serial):
    code, out, err = run_adb(adb, serial, "get-state")
    if code != 0 or out.strip() != "device":
        fail("设备 %s 不可用（state=%s err=%s）——缺设备必须明确失败，不生成成功报告"
             % (serial, out.strip(), err.strip()))


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def git_info(repo):
    def git(*args):
        proc = subprocess.run(["git", "-C", repo] + list(args), capture_output=True,
                              text=True, errors="replace")
        return proc.stdout.strip() if proc.returncode == 0 else ""
    commit = git("rev-parse", "HEAD")
    porcelain = git("status", "--porcelain")
    changed = len([l for l in porcelain.splitlines() if l.strip()])
    return {"commit": commit, "workspaceChangedFiles": changed}


def device_info(adb, serial):
    def prop(name):
        _, out, _ = run_adb(adb, serial, "shell", "getprop", name)
        return out.strip()
    _, refresh, _ = run_adb(adb, serial, "shell", "dumpsys", "display")
    m = re.search(r"mRefreshRate=([\d.]+)", refresh)
    return {
        "model": prop("ro.product.model"),
        "brand": prop("ro.product.brand"),
        "apiLevel": prop("ro.build.version.sdk"),
        "fingerprint": prop("ro.build.fingerprint"),
        "ramTotalKb": int(prop("ro.sys.fw.memsize") or 0) or None,
        "refreshRate": float(m.group(1)) if m else None,
        "abi": prop("ro.product.cpu.abi"),
    }


def apk_version(adb, serial, package):
    _, out, _ = run_adb(adb, serial, "shell", "dumpsys", "package", package)
    m = re.search(r"versionName=([\w.\-]+)", out)
    v = re.search(r"versionCode=(\d+)", out)
    return {"versionName": m.group(1) if m else None,
            "versionCode": int(v.group(1)) if v else None}


def install_apk(adb, serial, apk):
    print("[PERF-RUN] 安装 APK（显式步骤）...")
    code, out, err = run_adb(adb, serial, "install", "-r", "-g", apk, timeout=600)
    if code != 0 or "Success" not in (out + err):
        fail("APK 安装失败: %s%s" % (out.strip(), err.strip()))


def percentile(sorted_samples, p):
    if not sorted_samples:
        return None
    idx = max(0, min(len(sorted_samples) - 1, int(round(p * len(sorted_samples))) - 1))
    return sorted_samples[idx]


def scenario_cold_start(adb, serial, package, activity, iterations, raw):
    """冷启动：force-stop → am start -W，采集 TotalTime/WaitTime（ms）。"""
    component = "%s/%s" % (package, activity)
    for i in range(iterations):
        run_adb(adb, serial, "shell", "am", "force-stop", package)
        time.sleep(1.0)
        _, out, _ = run_adb(adb, serial, "shell", "am", "start", "-W", "-n", component)
        total = re.search(r"TotalTime: (\d+)", out)
        wait = re.search(r"WaitTime: (\d+)", out)
        if total:
            raw["ttid_ms"].append(int(total.group(1)))
            raw["wait_ms"].append(int(wait.group(1)) if wait else None)
        else:
            raw["failures"].append({"iter": i, "reason": "no TotalTime in am start -W"})
        time.sleep(2.0)


def scenario_reco_scroll(adb, serial, package, activity, iterations, raw):
    """滑动流畅度：固定脚本滑动 + gfxinfo 帧统计（帧数/janky/超16.67ms比例）。"""
    component = "%s/%s" % (package, activity)
    run_adb(adb, serial, "shell", "am", "start", "-n", component)
    time.sleep(4.0)  # 等推荐页就绪
    _, _, _ = run_adb(adb, serial, "shell", "dumpsys", "gfxinfo", package, "reset")
    for i in range(iterations):
        run_adb(adb, serial, "shell", "input", "swipe", "540", "1700", "540", "500", "300")
        time.sleep(0.8)
    _, out, _ = run_adb(adb, serial, "shell", "dumpsys", "gfxinfo", package)
    m_total = re.search(r"Total frames rendered: (\d+)", out)
    m_janky = re.search(r"Janky frames: (\d+) \(([\d.]+)%\)", out)
    m_p95 = re.search(r"95th percentile: (\d+)ms", out)
    if m_total:
        total_frames = int(m_total.group(1))
        janky = int(m_janky.group(1)) if m_janky else 0
        raw["frames_total"] = total_frames
        raw["frames_janky"] = janky
        raw["janky_pct"] = janky * 100.0 / total_frames if total_frames else None
        raw["frame_p95_ms"] = int(m_p95.group(1)) if m_p95 else None
    else:
        raw["failures"].append({"reason": "gfxinfo output missing"})


def scenario_reco_mem(adb, serial, package, activity, iterations, raw):
    """内存长跑采样：翻页 + meminfo Java heap / PSS。iterations 此处为采样次数。"""
    component = "%s/%s" % (package, activity)
    run_adb(adb, serial, "shell", "am", "start", "-n", component)
    time.sleep(4.0)
    for i in range(iterations):
        for _ in range(10):
            run_adb(adb, serial, "shell", "input", "swipe", "540", "1700", "540", "500", "250")
            time.sleep(0.5)
        _, out, _ = run_adb(adb, serial, "shell", "dumpsys", "meminfo", package)
        heap = re.search(r"Java Heap:\s+(\d+)", out)
        pss = re.search(r"TOTAL PSS:\s+(\d+)", out)
        raw["java_heap_kb"].append(int(heap.group(1)) if heap else None)
        raw["pss_kb"].append(int(pss.group(1)) if pss else None)


def scenario_reco_cache_first(adb, serial, package, activity, iterations, raw):
    """推荐缓存首屏：依赖 T01 的 PERF_MARK 日志；未接线时输出 NOT_RUN。"""
    raw["failures"].append({"reason": "T01 PERF_MARK 未接线，指标 NOT_RUN"})


def scenario_hls_segments(adb, serial, package, activity, iterations, raw):
    """HLS 分片资源约束：依赖 T05 的 PERF_MARK 日志；未接线时输出 NOT_RUN。"""
    raw["failures"].append({"reason": "T05 PERF_MARK 未接线，指标 NOT_RUN"})


SCENARIO_FUNCS = {
    "cold_start": scenario_cold_start,
    "reco_scroll": scenario_reco_scroll,
    "reco_mem": scenario_reco_mem,
    "reco_cache_first": scenario_reco_cache_first,
    "hls_segments": scenario_hls_segments,
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--scenario", required=True, choices=SCENARIOS)
    ap.add_argument("--iterations", type=int, default=50)
    ap.add_argument("--output", required=True)
    ap.add_argument("--package", default="com.m3man.perf",
                    help="默认 benchmark 构建测试包 com.m3man.perf")
    ap.add_argument("--activity", default="com.m3man.ui.main.MainActivity")
    ap.add_argument("--adb", default=os.environ.get("ANDROID_ADB", r"G:\Android\platform-tools\adb.exe"))
    args = ap.parse_args()

    if not os.path.isfile(args.adb):
        fail("adb 不存在: %s（用 --adb 或 ANDROID_ADB 指定本机实际路径）" % args.adb)
    if not os.path.isfile(args.apk):
        fail("APK 不存在: %s" % args.apk)
    require_device(args.adb, args.serial)

    os.makedirs(args.output, exist_ok=True)
    repo = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

    install_apk(args.adb, args.serial, args.apk)

    env = {
        "schemaVersion": SCHEMA_VERSION,
        "runId": os.path.basename(os.path.normpath(args.output)),
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "scenario": args.scenario,
        "iterations": args.iterations,
        "package": args.package,
        "apk": {"path": os.path.abspath(args.apk), "sha256": sha256_file(args.apk)},
        "source": git_info(repo),
        "device": device_info(args.adb, args.serial),
        "version": apk_version(args.adb, args.serial, args.package),
        "buildMode": "benchmark(release-derived,R8)",
        "networkModel": "as-is(记录用，未注入限速)",
    }
    with open(os.path.join(args.output, "environment.json"), "w", encoding="utf-8") as f:
        json.dump(env, f, ensure_ascii=False, indent=2)

    raw = {
        "ttid_ms": [], "wait_ms": [], "java_heap_kb": [], "pss_kb": [],
        "failures": [],
    }
    SCENARIO_FUNCS[args.scenario](args.adb, args.serial, args.package, args.activity,
                                  args.iterations, raw)

    samples_key = {"cold_start": "ttid_ms", "reco_mem": "java_heap_kb"}.get(args.scenario)
    valid = [s for s in (raw.get(samples_key) or []) if s is not None]
    ordered = sorted(valid)
    summary = {
        "schemaVersion": SCHEMA_VERSION,
        "scenario": args.scenario,
        "status": "OK" if valid else "NOT_RUN",
        "sampleCount": len(valid),
        "failureCount": len(raw["failures"]),
        "p50": percentile(ordered, 0.50),
        "p95": percentile(ordered, 0.95),
        "raw": raw,
        "environment": {
            "apkSha256": env["apk"]["sha256"],
            "commit": env["source"]["commit"],
            "device": env["device"]["model"] + " api" + str(env["device"]["apiLevel"]),
        },
    }
    out_path = os.path.join(args.output, "summary.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    print("[PERF-RUN] 完成: %s (n=%d fail=%d p50=%s p95=%s)"
          % (out_path, summary["sampleCount"], summary["failureCount"],
             summary["p50"], summary["p95"]))


if __name__ == "__main__":
    main()
