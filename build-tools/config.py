# -*- coding: utf-8 -*-
"""
3mman 打包工具 - 统一配置
=======================
所有路径集中在此，脚本按编号顺序执行：
  01_copy_src.py -> 02_build.py -> 03_resign.py -> 04_push.py -> 05_verify.py
每个版本构建使用全新目录（时间戳命名），避免沙箱“写后文件锁定”污染。

注意：本目录脚本需要在“用户本机 / 非沙箱环境”运行（沙箱的删除保护与
文件锁定会干扰构建）。沙箱内可读可写新建文件，但删除与复用旧文件受限。
"""
import os
import datetime

# ---------- 机器环境（如换机器请修改） ----------
JAVA_HOME = r"C:\jdk17\jdk-17.0.14+7"
ANDROID_SDK = r"C:\Android\Sdk"
BUILD_TOOLS = os.path.join(ANDROID_SDK, "build-tools", "34.0.0")
# Gradle 发行包 lib 目录（wrapper 下载后固定路径）
GRADLE_DIST_LIB = (
    r"C:\gwork\gradle-home\wrapper\dists\gradle-7.6.4-bin"
    r"\ejvplpfkvhpzr5ejlqt4tjey7\gradle-7.6.4\lib"
)
# 只读依赖缓存（首次全量下载后复用，加快解析）
DEP_CACHE = r"C:\gh_r6\caches\modules-2"
# 原机器 debug.keystore 副本（与已安装 App 同签名，避免覆盖安装报 -7）
ORIG_KS = r"C:\gwork\origks\debug.keystore"
# git 工作副本（用于 plumbing 推送，不触碰本地污染 ref）
GIT_REPO = r"C:\repo3mman"

# ---------- 项目 ----------
SRC = r"G:\game\新建文件夹\3mman"
# GitHub 远程（ls-remote 取父提交）
GIT_REMOTE = "origin"
GIT_BRANCH = "refs/heads/master"

# ---------- 每版构建的临时目录（时间戳，全新） ----------
STAMP = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
_DRIVE = "C:/"                                          # Windows 接受正斜杠，避免 r"C:\" 语法坑
WORK = os.path.join(_DRIVE, "p3mman_" + STAMP)         # 源码副本（构建目录）
GHOME = os.path.join(_DRIVE, "gh3mman_" + STAMP)       # GRADLE_USER_HOME
AHOME = os.path.join(_DRIVE, "ah3mman_" + STAMP)       # ANDROID_USER_HOME
OUT = os.path.join(WORK, "app", "build", "outputs", "apk", "debug")
LOG = os.path.join(_DRIVE, "build_" + STAMP + ".log")

# 构建出的 APK 名：3mman_v<versionName>.apk（与 build.gradle 的 outputFileName 一致）
def apk_name(version_name):
    return "3mman_v%s.apk" % version_name

def build_apk_path(version_name):
    return os.path.join(OUT, apk_name(version_name))

# ---------- 重签输出 ----------
RESIGN_DIR = os.path.join(_DRIVE, "resign_" + STAMP)
def resign_apk_path(version_name):
    return os.path.join(RESIGN_DIR, apk_name(version_name))

# ---------- 版本读取 ----------
def read_version():
    """从 app/build.gradle 读取 versionCode / versionName"""
    vc = vn = None
    with open(os.path.join(SRC, "app", "build.gradle"), "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line.startswith("versionCode"):
                vc = int(line.split()[1])
            elif line.startswith("versionName"):
                vn = line.split('"')[1]
    assert vc is not None and vn is not None, "无法从 build.gradle 读取版本号"
    return vc, vn
