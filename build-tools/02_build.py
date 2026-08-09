# -*- coding: utf-8 -*-
"""
02_build.py - 用 Gradle 直接构建 assembleDebug（绕过 gradlew wrapper）
-----------------------------------------------------------------------
- 全新 GRADLE_USER_HOME / ANDROID_USER_HOME（config 中时间戳命名）
- GRADLE_RO_DEP_CACHE 指向已下载的只读依赖缓存加速
用法：python 02_build.py
成功退出码 0，产物位于 config.WORK/app/build/outputs/apk/debug/
"""
import os
import subprocess
import sys
import config

GRADLE_LIB = config.GRADLE_DIST_LIB
cp = GRADLE_LIB + "\\*;" + GRADLE_LIB + "\\plugins\\*"

env = os.environ.copy()
env['GRADLE_USER_HOME'] = config.GHOME
env['GRADLE_RO_DEP_CACHE'] = config.DEP_CACHE
env['ANDROID_USER_HOME'] = config.AHOME
env['ANDROID_HOME'] = config.ANDROID_SDK
env['ANDROID_SDK_ROOT'] = config.ANDROID_SDK
env['JAVA_HOME'] = config.JAVA_HOME
env['PATH'] = os.path.join(config.JAVA_HOME, 'bin') + ";" + env.get('PATH', '')

JAVA = os.path.join(config.JAVA_HOME, "bin", "java.exe")
args = [JAVA,
        "-Xmx2048m",
        "-Dorg.gradle.java.home=" + config.JAVA_HOME,
        "-cp", cp,
        "org.gradle.launcher.GradleMain",
        "-p", config.WORK,
        "--no-daemon",
        "assembleDebug",
        "--stacktrace"]

log = open(config.LOG, "w", encoding="utf-8", errors="replace")
p = subprocess.run(args, env=env, stdout=log, stderr=subprocess.STDOUT, cwd=config.WORK)
log.close()
print("BUILD_RC=", p.returncode, "log:", config.LOG)
sys.exit(p.returncode)
