# -*- coding: utf-8 -*-
"""
06_unit_test.py - 运行 JVM 单元测试（:app:testReleaseUnitTest）
--------------------------------------------------------------
环境与 02_build.py 完全一致（隔离 GRADLE_USER_HOME / 只读依赖缓存）。
用法：python 06_unit_test.py [测试类过滤，默认 com.m3man.*]
成功退出码 0；报告位于 config.WORK/app/build/reports/tests/
"""
import os
import sys
import subprocess
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

filt = sys.argv[1] if len(sys.argv) > 1 else "com.m3man.*"

log_path = os.path.join("C:/faban", "unittest_" + config.STAMP + ".log")
args = [JAVA,
        "-Xmx2048m",
        "-Dorg.gradle.java.home=" + config.JAVA_HOME,
        "-cp", cp,
        "org.gradle.launcher.GradleMain",
        "-p", config.WORK,
        "--project-cache-dir", config.PROJECT_CACHE,
        "--no-daemon",
        ":app:testReleaseUnitTest",
        "--tests", filt]

print("filter =", filt)
with open(log_path, "w", encoding="utf-8", errors="replace") as log:
    p = subprocess.run(args, env=env, stdout=log, stderr=subprocess.STDOUT, cwd=config.WORK)

print("TEST_RC=", p.returncode, "log:", log_path)
sys.exit(p.returncode)
