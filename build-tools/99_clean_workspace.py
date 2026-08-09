# -*- coding: utf-8 -*-
"""
99_clean_workspace.py - 清理工作区过程文件（请在“用户本机”运行）
-----------------------------------------------------------------
沙箱内删除被保护钩子拦截（回收站不可用），本脚本供本机一键清理。
- 默认 dry-run 只列出；加 --delete 才真正删除
- 删除项均为可再生成的过程产物（构建目录/缓存/旧 APK/探测文件），源码与配置不受影响
用法：
  python 99_clean_workspace.py            # 只列出将删除项
  python 99_clean_workspace.py --delete   # 真正删除
"""
import os
import shutil
import sys

ROOT = r"G:\game\新建文件夹\3mman"

# 构建产物/缓存目录（可再生成）
DIRS = [
    "gradle-home", "gradle-home-build", "app/build", "exolibrary/build",
    ".gradle", ".gradle-build", "build-fresh", "emulator_verify", "test_dir",
]
# 旧 APK / debug 残留（保留最新 3mman_v1.0.4.apk）
FILES = [
    "3mman_v1.0.1.apk", "3mman_v1.0.apk", "3mman_v1.0_debug.apk",
    "3mman_v1.3.4_debug.apk", "3mman_v1.0.3.apk",
    "build_v101.log", "gradle_args.txt", "gradle_args_gbk.txt", "gradle_direct_args.txt",
    "env_done.txt", "env_m.txt", "env_p.txt", "env_u.txt",
    "lock_scan.txt", "proc_scan.txt", "__rw_probe.tmp", "_p_new.tmp", "probe_new.tmp",
    "TestFile.java", "init_builddir.gradle", "gradle.properties.bak",
]


def main():
    do_delete = "--delete" in sys.argv
    print("模式:", "DELETE" if do_delete else "DRY-RUN（加 --delete 真正删除）")
    print("=" * 60)
    for d in DIRS:
        p = os.path.join(ROOT, d)
        if os.path.exists(p):
            print("目录:", d)
            if do_delete:
                shutil.rmtree(p, ignore_errors=True)
    for f in FILES:
        p = os.path.join(ROOT, f)
        if os.path.exists(p):
            print("文件:", f)
            if do_delete:
                os.remove(p)
    print("=" * 60)
    print("完成。保留：源码(app/src 等)、3mman_v1.0.4.apk、3mman.jks、version.txt、.git、.workbuddy、文档")
    print("提示：删除后若需重新构建，先运行 build-tools/01_copy_src.py 重新生成构建目录。")


if __name__ == '__main__':
    main()
