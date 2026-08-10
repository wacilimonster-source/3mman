# -*- coding: utf-8 -*-
"""
04_push.py - 用 git plumbing（read-tree 法）推送发版到 GitHub
-------------------------------------------------------------
- 父提交 = 当前远程 master（ls-remote 实时获取，绝不触碰本地污染的 ref/index）
- 默认更新：version.txt、app/build.gradle；移除树中所有旧 3mman_v*.apk；新增当前版本 APK
- 可选：--files 空格分隔的相对路径，追加为覆盖（如源码修复文件）
- 安全断言：父提交一致、条目数按增删净变化、tree 非空树（4b825dc...）
用法：
  python 04_push.py
  python 04_push.py --files app/src/main/java/com/m3man/utils/SDCardUtils.java
"""
import os
import sys
import subprocess
import datetime
import config

HEX = set('0123456789abcdef')
EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"


def is_sha(s):
    return isinstance(s, str) and len(s) == 40 and all(c in HEX for c in s)


def make_env():
    env = os.environ.copy()
    env['GIT_AUTHOR_NAME'] = 'wacil'
    env['GIT_AUTHOR_EMAIL'] = 'wacilimonster-source@users.noreply.github.com'
    env['GIT_COMMITTER_NAME'] = 'wacil'
    env['GIT_COMMITTER_EMAIL'] = 'wacilimonster-source@users.noreply.github.com'
    return env


ENV = make_env()


def git(args, use_index=None, text=True):
    e = dict(ENV)
    if use_index:
        e['GIT_INDEX_FILE'] = use_index
    return subprocess.run(['git'] + args, cwd=config.GIT_REPO, env=e,
                          capture_output=True, text=text)


def hash_object(path):
    r = subprocess.run(['git', 'hash-object', '-w', path], cwd=config.GIT_REPO,
                       env=ENV, capture_output=True, text=True)
    assert r.returncode == 0, "hash-object failed %s: %s" % (path, r.stderr)
    return r.stdout.strip()


def main():
    extra_files = []
    if '--files' in sys.argv:
        i = sys.argv.index('--files')
        extra_files = [p for p in sys.argv[i + 1:] if not p.startswith('--')]
    vc, vn = config.read_version()
    apk = config.resign_apk_path(vn)
    assert os.path.exists(apk), "重签产物不存在: %s（请先运行 03_resign.py）" % apk

    # 0) 父提交 = 当前远程 master
    parent = git(['ls-remote', config.GIT_REMOTE, config.GIT_BRANCH]).stdout.split()[0]
    print("parent =", parent)
    assert is_sha(parent), "ls-remote 结果异常"
    if git(['cat-file', '-t', parent]).returncode != 0:
        git(['fetch', config.GIT_REMOTE, parent])
    assert git(['cat-file', '-t', parent]).stdout.strip() == 'commit'

    # 1) blob
    apk_sha = hash_object(apk)
    vtxt_sha = hash_object(os.path.join(config.SRC, "version.txt"))
    bgradle_sha = hash_object(os.path.join(config.SRC, "app", "build.gradle"))
    shas = {'version.txt': vtxt_sha, 'app/build.gradle': bgradle_sha}
    for p in extra_files:
        local = os.path.join(config.SRC, p)
        assert os.path.exists(local), "额外文件不存在: %s" % local
        shas[p] = hash_object(local)
        print("hashed(extra)", p, shas[p])
    print("apk", apk_sha, "vtxt", vtxt_sha, "bgradle", bgradle_sha)
    assert is_sha(apk_sha) and is_sha(vtxt_sha) and is_sha(bgradle_sha)

    # 2) read-tree 填充全新 index
    idx = os.path.join(r"C:\gwork", "idx_push_%d.bin" % int(datetime.datetime.now().timestamp() * 1000))
    os.makedirs(os.path.dirname(idx), exist_ok=True)
    rt = git(['read-tree', parent], use_index=idx)
    assert rt.returncode == 0, "read-tree failed: %s" % rt.stderr.decode('utf-8', 'replace')
    before = git(['ls-files', '--stage'], use_index=idx).stdout.count('\n')
    print("index entries before:", before)

    staged = git(['ls-files', '--stage'], use_index=idx).stdout

    # 3.5) 旧版内置 OCR 训练数据不再打包，从树中移除（避免被重新打包进 APK）
    OLD_ASSET = "app/src/main/assets/tessdata/eng.traineddata"
    removed_old = 0
    if any(OLD_ASSET == l.split('\t')[-1].strip() for l in staged.splitlines() if '\t' in l):
        rm = git(['update-index', '--force-remove', OLD_ASSET], use_index=idx)
        assert rm.returncode == 0, "force-remove old asset failed: %s" % rm.stderr.decode('utf-8', 'replace')
        removed_old += 1
        print("removed old asset", OLD_ASSET)

    # 3) 覆盖 version.txt / build.gradle / extra files
    for path, sha in shas.items():
        assert path in staged, "树中不存在该路径: %s" % path
        up = git(['update-index', '--add', '--cacheinfo', '100644,%s,%s' % (sha, path)], use_index=idx)
        assert up.returncode == 0, "cacheinfo failed %s: %s" % (path, up.stderr.decode('utf-8', 'replace'))

    # 4) 移除树中所有旧 3mman_v*.apk，加入当前版本
    old_apks = [l.split('\t')[-1].strip() for l in staged.splitlines() if '\t' in l and '3mman_v' in l.split('\t')[-1]]
    for p in old_apks:
        rm = git(['update-index', '--force-remove', p], use_index=idx)
        assert rm.returncode == 0, "force-remove failed %s" % p
        print("removed", p)
    new_apk_path = "3mman_v%s.apk" % vn
    up = git(['update-index', '--add', '--cacheinfo', '100644,%s,%s' % (apk_sha, new_apk_path)], use_index=idx)
    assert up.returncode == 0, "cacheinfo apk failed: %s" % up.stderr.decode('utf-8', 'replace')

    after = git(['ls-files', '--stage'], use_index=idx).stdout.count('\n')
    print("index entries after:", after)
    # 净变化 = -旧APK -旧资源 +新APK +树中原本不存在的额外文件(--files 覆盖已存在文件不算新增)
    new_extra = len([p for p in extra_files if p not in staged])
    assert after == before - len(old_apks) - removed_old + 1 + new_extra, "条目数变化异常"
    staged2 = git(['ls-files', '--stage'], use_index=idx).stdout
    for sha in shas.values():
        assert sha in staged2, "missing staged blob"
    assert apk_sha in staged2, "missing staged apk"
    assert new_apk_path in staged2

    # 5) write-tree（空树守卫）
    wt = git(['write-tree'], use_index=idx)
    tree = wt.stdout.strip()
    print("tree =", tree, "rc", wt.returncode)
    assert is_sha(tree) and tree != EMPTY_TREE, "REFUSING empty tree!"
    assert git(['ls-tree', '-r', tree]).stdout.count('\n') == after

    # 6) commit-tree
    msg = ("release: v%s (versionCode %d)\n\n" % (vn, vc) +
           "- APK: %s\n- version.txt / app/build.gradle 已更新" % new_apk_path +
           ("\n- 源码: " + ", ".join(extra_files) if extra_files else ""))
    ct = git(['commit-tree', tree, '-p', parent, '-m', msg])
    commit = ct.stdout.strip()
    print("commit =", commit, "rc", ct.returncode)
    assert is_sha(commit)

    # 7) push
    rp = git(['push', config.GIT_REMOTE, commit + ':' + config.GIT_BRANCH])
    print("push rc", rp.returncode)
    print(rp.stdout, rp.stderr.decode('utf-8', 'replace') if isinstance(rp.stderr, bytes) else rp.stderr)
    assert rp.returncode == 0, "push failed"
    print("COMMIT=" + commit)
    print("完成！等待 raw.githubusercontent CDN 缓存刷新（约 5 分钟）后 App 内可检测到更新。")


if __name__ == '__main__':
    main()
