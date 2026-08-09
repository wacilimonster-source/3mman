# -*- coding: utf-8 -*-
"""
04b_fullsync.py - 全量源码同步推送（推荐功能发版用）
---------------------------------------------------
与 04_push.py 的区别：除 version.txt / build.gradle / 新 APK 外，
把 SRC 工作区**全部源码文件**一并加入树，避免仓库源码与构建产物漂移
（推荐功能源码此前完全不在仓库中）。

安全机制（沿用 04_push 的成熟做法）：
- 父提交 = 当前远程 master（ls-remote 实时获取）
- read-tree 填充全新 index（不手拼条目，杜绝空树事故）
- 空树守卫：tree == 4b825dc... 直接拒绝
- 条目数按 增删净变化 断言
- 关键文件存在性校验（新 APK / version.txt / build.gradle / 新增源码）

优化：所有源码文件用单次 `git hash-object -w --stdin-paths` 批量计算，
避免逐个 spawn 子进程被沙箱限制。

用法：
  python 04b_fullsync.py            # 正式推送
  python 04b_fullsync.py --dry-run  # 只构建树并打印校验，不 commit/push
"""
import os
import sys
import subprocess
import datetime
import config

HEX = set('0123456789abcdef')
EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"

SKIP_DIRS = {'.git', 'build', '.gradle', 'gradle-home', 'gradle-home-build',
             'build-fresh', '.gradle-build', 'emulator_verify', '__pycache__',
             'captures', '.workbuddy'}
SKIP_DIR_PREFIX = ('gradle-home',)
SKIP_SUFFIX = ('.tmp', '.apk', '.apk_', '.log')


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
GIT_REPO = config.GIT_REPO


def git(args, use_index=None, text=True):
    e = dict(ENV)
    if use_index:
        e['GIT_INDEX_FILE'] = use_index
    return subprocess.run(['git'] + args, cwd=GIT_REPO, env=e,
                          capture_output=True, text=text)


def hash_objects_batch(paths):
    """paths: 绝对路径列表，返回等长 sha 列表。单次 git 调用。"""
    inp = "\n".join(p.replace('\\', '/') for p in paths)
    r = subprocess.run(['git', 'hash-object', '-w', '--stdin-paths'],
                       cwd=GIT_REPO, env=ENV, input=inp,
                       capture_output=True, text=True)
    assert r.returncode == 0, "hash-object batch failed: %s" % r.stderr
    shas = r.stdout.splitlines()
    assert len(shas) == len(paths), "hash 数量不匹配 %d vs %d" % (len(shas), len(paths))
    return shas


def collect_src_files():
    """返回 [(rel仓库路径, abs本地路径), ...] 的 eligible 文件有序列表。"""
    out = []
    for root, dirs, files in os.walk(config.SRC):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS
                   and not any(d.startswith(p) for p in SKIP_DIR_PREFIX)]
        for f in files:
            low = f.lower()
            if any(low.endswith(s) for s in SKIP_SUFFIX):
                continue
            full = os.path.join(root, f)
            rel = os.path.relpath(full, config.SRC).replace('\\', '/')
            out.append((rel, full))
    return out


def main():
    dry = '--dry-run' in sys.argv
    vc, vn = config.read_version()
    apk = config.resign_apk_path(vn)
    assert os.path.exists(apk), "重签产物不存在: %s（请先运行 03_resign.py）" % apk

    # 0) 父提交
    parent = git(['ls-remote', config.GIT_REMOTE, config.GIT_BRANCH]).stdout.split()[0]
    print("parent =", parent)
    assert is_sha(parent), "ls-remote 结果异常"
    if git(['cat-file', '-t', parent]).returncode != 0:
        git(['fetch', config.GIT_REMOTE, parent])
    assert git(['cat-file', '-t', parent]).stdout.strip() == 'commit'

    parent_paths = set(git(['ls-tree', '-r', '--name-only', parent]).stdout.splitlines())
    before = len(parent_paths)
    print("parent tree entries =", before)

    # 1) 收集 SRC 全部源码文件
    src_files = collect_src_files()
    print("eligible SRC files =", len(src_files))

    # 2) 批量计算 hash（单次 git 调用）
    rels = [r for r, _ in src_files]
    fulls = [f for _, f in src_files]
    shas = hash_objects_batch(fulls)
    staged_map = dict(zip(rels, shas))
    new_src = sum(1 for r in rels if r not in parent_paths)
    print("new SRC files (not in parent) =", new_src)

    # 3) read-tree 填充全新 index（临时 index 放在 WORK 构建目录内，沙箱允许写锁）
    idx = os.path.join(config.WORK, "idx_sync_%d.bin" % int(datetime.datetime.now().timestamp() * 1000))
    os.makedirs(os.path.dirname(idx), exist_ok=True)
    rt = git(['read-tree', parent], use_index=idx)
    assert rt.returncode == 0, "read-tree failed: %s" % rt.stderr.decode('utf-8', 'replace')

    # 4) 覆盖/新增全部 SRC 文件（批量 update-index，避免逐个 spawn 被沙箱限制）
    items = list(staged_map.items())
    chunk = 100
    for i in range(0, len(items), chunk):
        batch = items[i:i + chunk]
        args = ['update-index', '--add']
        for p, s in batch:
            args.append('--cacheinfo')
            args.append('100644,%s,%s' % (s, p))
        up = git(args, use_index=idx)
        assert up.returncode == 0, "cacheinfo batch failed: %s" % up.stderr.decode('utf-8', 'replace')

    # 5) 移除树中所有旧 3mman_v*.apk
    old_apks = [p for p in parent_paths if p.startswith('3mman_v') and p.endswith('.apk')]
    for p in old_apks:
        rm = git(['update-index', '--force-remove', p], use_index=idx)
        assert rm.returncode == 0, "force-remove failed %s" % p
        print("removed", p)

    # 6) 加入新 APK
    apk_sha = hash_objects_batch([apk])[0]
    new_apk_path = "3mman_v%s.apk" % vn
    up = git(['update-index', '--add', '--cacheinfo', '100644,%s,%s' % (apk_sha, new_apk_path)], use_index=idx)
    assert up.returncode == 0, "cacheinfo apk failed: %s" % up.stderr.decode('utf-8', 'replace')
    print("added", new_apk_path, apk_sha)

    # 7) 条目数断言
    after = git(['ls-files', '--stage'], use_index=idx).stdout.count('\n')
    expected = before - len(old_apks) + new_src + 1  # +1 = 新 APK
    print("index entries: before=%d after=%d expected=%d (old_apks=%d new_src=%d)" %
          (before, after, expected, len(old_apks), new_src))
    assert after == expected, "条目数变化异常: after=%d expected=%d" % (after, expected)

    # 8) 关键文件校验
    staged2 = git(['ls-files', '--stage'], use_index=idx).stdout
    for must in [new_apk_path, 'version.txt', 'app/build.gradle',
                 'app/src/main/java/com/m3man/ui/recommend/RecoRecordsDialog.java',
                 'app/src/main/java/com/m3man/ui/recommend/RecommendFeedActivity.java',
                 'app/src/main/java/com/m3man/ui/recommend/RecoSettingsDialog.java']:
        assert must in staged2, "关键文件缺失于树: %s" % must
    print("key files present: OK")

    # 9) write-tree（空树守卫）
    wt = git(['write-tree'], use_index=idx)
    tree = wt.stdout.strip()
    print("tree =", tree, "rc", wt.returncode)
    assert is_sha(tree) and tree != EMPTY_TREE, "REFUSING empty tree!"
    assert git(['ls-tree', '-r', tree]).stdout.count('\n') == after

    if dry:
        print("\n[DRY-RUN] 树已构建并通过校验，未执行 commit/push。")
        print("tree entries:", after, "| new APK:", new_apk_path, "| version:", vn, "vc", vc)
        return

    # 10) commit-tree
    msg = ("release: v%s (versionCode %d) — 全量同步源码\n\n" % (vn, vc) +
           "- APK: %s\n- 推荐视频功能：进度条拖动/下载/调参步进器+高级设置折叠/学习记录查看\n"
           "- 覆盖最近10年视频；修复作者视频解析失败与标题错位\n"
           "- 全量同步 app/src、res、build-tools 等到仓库" % new_apk_path)
    ct = git(['commit-tree', tree, '-p', parent, '-m', msg])
    commit = ct.stdout.strip()
    print("commit =", commit, "rc", ct.returncode)
    assert is_sha(commit)

    # 11) push
    rp = git(['push', config.GIT_REMOTE, commit + ':' + config.GIT_BRANCH])
    print("push rc", rp.returncode)
    print(rp.stdout, rp.stderr.decode('utf-8', 'replace') if isinstance(rp.stderr, bytes) else rp.stderr)
    assert rp.returncode == 0, "push failed"
    print("COMMIT=" + commit)
    print("完成！等待 raw.githubusercontent CDN 缓存刷新（约 5 分钟）后 App 内可检测到更新。")


if __name__ == '__main__':
    main()
