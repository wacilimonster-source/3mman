#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""T00 数据库种子注入驱动：通过 instrumentation 触发 DbSeedTest（测试代码注入数据）。

用法：
  python seed_db.py --serial <device> --rows 50000 [--package com.m3man.perf]

种子只写入测试包专属数据库（applicationId 后缀 .perf），不触碰正式包用户数据。
"""

import argparse
import os
import subprocess
import sys

TEST_CLASS = "com.m3man.perf.DbSeedTest"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--rows", type=int, required=True)
    ap.add_argument("--package", default="com.m3man.perf")
    ap.add_argument("--adb", default=os.environ.get("ANDROID_ADB", r"G:\Android\platform-tools\adb.exe"))
    args = ap.parse_args()

    runner = "%s.test/androidx.test.runner.AndroidJUnitRunner" % args.package
    cmd = [args.adb, "-s", args.serial, "shell", "am", "instrument", "-w",
           "-e", "class", TEST_CLASS,
           "-e", "seed_rows", str(args.rows),
           runner]
    print("[SEED] " + " ".join(cmd))
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=1800, errors="replace")
    out = proc.stdout + proc.stderr
    print(out)
    if proc.returncode != 0 or "FAILURES" in out:
        print("[SEED][FAIL] 注入失败", file=sys.stderr)
        sys.exit(1)
    print("[SEED] 完成 rows=%d" % args.rows)


if __name__ == "__main__":
    main()
