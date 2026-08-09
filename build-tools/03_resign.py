# -*- coding: utf-8 -*-
"""
03_resign.py - 用原机器 debug.keystore 重签 APK
------------------------------------------------
- 剥离旧签名 META-INF，apksigner v1+v2+v3 重签
- 保证与已安装 App 同签名（SHA-1 e72843...），覆盖升级不报 -7
用法：python 03_resign.py
产物：config.RESIGN_DIR/3mman_v<versionName>.apk（重签后成品）
"""
import os
import zipfile
import subprocess
import sys
import config


def main():
    vc, vn = config.read_version()
    src = config.build_apk_path(vn)
    assert os.path.exists(src), "找不到构建产物: %s" % src
    os.makedirs(config.RESIGN_DIR, exist_ok=True)
    unsigned = os.path.join(config.RESIGN_DIR, "unsigned.apk")
    out = config.resign_apk_path(vn)

    # 1) 剥离 META-INF
    with zipfile.ZipFile(src, 'r') as zin:
        names = [n for n in zin.namelist() if not n.startswith('META-INF/')]
        with zipfile.ZipFile(unsigned, 'w', zipfile.ZIP_DEFLATED) as zout:
            for n in names:
                zout.writestr(n, zin.read(n))
    print("stripped ->", unsigned, os.path.getsize(unsigned))

    # 2) 重签
    env = os.environ.copy()
    env['JAVA_HOME'] = config.JAVA_HOME
    env['PATH'] = os.path.join(config.JAVA_HOME, 'bin') + ";" + env.get('PATH', '')
    apksigner = os.path.join(config.BUILD_TOOLS, "apksigner.bat")
    r = subprocess.run([apksigner, "sign", "--ks", config.ORIG_KS,
                        "--ks-key-alias", "androiddebugkey",
                        "--ks-pass", "pass:android", "--key-pass", "pass:android",
                        "--out", out, unsigned],
                       env=env, capture_output=True, text=True)
    print("sign rc", r.returncode, r.stdout, r.stderr)
    assert r.returncode == 0, "重签失败"

    # 3) 校验
    v = subprocess.run([apksigner, "verify", "-v", "--print-certs", out],
                       env=env, capture_output=True, text=True)
    print("verify rc", v.returncode)
    print(v.stdout)
    assert v.returncode == 0, "apksigner 校验失败"
    assert "e72843708a92958b7ae82c230ddef2c3bf53aa7b" in v.stdout.replace(":", "").lower() or \
           "e72843708a92958b7ae82c230ddef2c3bf53aa7b" in v.stdout.lower(), \
        "证书不是原机器 keystore，请检查 config.ORIG_KS"
    print("OK ->", out)


if __name__ == '__main__':
    sys.exit(main())
