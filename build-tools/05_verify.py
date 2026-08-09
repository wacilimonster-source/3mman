# -*- coding: utf-8 -*-
"""
05_verify.py - 发版后校验
-------------------------
- 远程 master 指向的 commit / 树条目数
- version.txt 内容（versionCode / apkDownloadUrl）
- 重签 APK 的 versionCode/versionName（aapt）
用法：python 05_verify.py
"""
import os
import subprocess
import config


def git(args):
    return subprocess.run(['git'] + args, cwd=config.GIT_REPO,
                          capture_output=True, text=True)


def main():
    vc, vn = config.read_version()
    # 远程状态
    r = git(['ls-remote', config.GIT_REMOTE, config.GIT_BRANCH])
    head = r.stdout.split()[0] if r.stdout.strip() else "(empty)"
    print("remote master =", head)
    entries = git(['ls-tree', '-r', head]).stdout.count('\n') if head != "(empty)" else -1
    print("tree entries   =", entries)
    # 树内 APK
    ls = git(['ls-tree', '-r', head]).stdout
    for line in ls.splitlines():
        if '3mman_v' in line:
            print("tree apk       =", line.split('\t')[-1])
    # version.txt
    vtxt = git(['show', head + ':version.txt']).stdout
    for line in vtxt.splitlines():
        if 'versionCode' in line or 'apkDownloadUrl' in line:
            print("version.txt    =", line.strip())
    # 重签 APK 版本（aapt）
    apk = config.resign_apk_path(vn)
    aapt = os.path.join(config.BUILD_TOOLS, "aapt")
    b = subprocess.run([aapt, "dump", "badging", apk], capture_output=True, text=True)
    for line in b.stdout.splitlines():
        if line.startswith("package:"):
            print("apk badging    =", line.split("package: ")[1].split(" ")[0],
                  line.split("versionCode='")[1].split("'")[0],
                  "versionName=" + line.split("versionName='")[1].split("'")[0])
    # 一致性
    ok = (head != "(empty)") and ("3mman_v%s.apk" % vn in ls)
    print("RESULT:", "OK" if ok else "CHECK FAILED")


if __name__ == '__main__':
    main()
