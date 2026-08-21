# -*- coding: utf-8 -*-
"""
01_copy_src.py - 复制源码到全新构建目录
---------------------------------------
- 跳过 .git/build/.gradle/gradle-home*/缓存等过程目录与 .apk/.tmp 文件
- 在 ANDROID_USER_HOME 下放入原机器 debug.keystore（保证 debug 构建用原密钥签名）
用法：python 01_copy_src.py
"""
import os
import config

SRC = config.SRC
DST = config.WORK
SKIP_DIRS = {'.git', 'build', '.gradle', 'gradle-home', 'gradle-home-build',
             'build-fresh', '.gradle-build', 'emulator_verify', '__pycache__',
             'captures', '.workbuddy'}
SKIP_DIR_PREFIX = ('gradle-home',)
SKIP_FILE_SUFFIX = ('.tmp', '.apk', '.apk_', '.log')


def ok_dir(name):
    if name in SKIP_DIRS:
        return False
    for p in SKIP_DIR_PREFIX:
        if name.startswith(p):
            return False
    return True


def ok_file(name):
    low = name.lower()
    return not low.endswith(SKIP_FILE_SUFFIX)


def main():
    os.makedirs(DST, exist_ok=True)
    count = 0
    for root, dirs, files in os.walk(SRC):
        dirs[:] = [d for d in dirs if ok_dir(d)]
        rel = os.path.relpath(root, SRC)
        dst_root = DST if rel == '.' else os.path.join(DST, rel)
        os.makedirs(dst_root, exist_ok=True)
        for f in files:
            if not ok_file(f):
                continue
            srcf = os.path.join(root, f)
            dstf = os.path.join(dst_root, f)
            try:
                with open(srcf, 'rb') as fh:
                    data = fh.read()
                with open(dstf, 'wb') as fh:
                    fh.write(data)
                # Windows 沙箱可能让新副本继承只读属性；R8 会回写 seeds/usage 等文件，
                # 因此显式清除目标文件的只读位。
                try:
                    os.chmod(dstf, 0o666)
                except OSError:
                    pass
                count += 1
            except Exception as e:
                print("SKIP", srcf, type(e).__name__, str(e)[:60])
    print("COPIED", count, "files ->", DST)

    android_dir = os.path.join(config.AHOME, '.android')
    os.makedirs(android_dir, exist_ok=True)
    with open(config.ORIG_KS, 'rb') as fh:
        ks = fh.read()
    with open(os.path.join(android_dir, 'debug.keystore'), 'wb') as fh:
        fh.write(ks)
    print("KEYSTORE placed:", os.path.join(android_dir, 'debug.keystore'), len(ks), "bytes")


if __name__ == '__main__':
    main()
