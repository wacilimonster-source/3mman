# 3mman 发版工作流程（SOP）

> 适用版本：v1.0.1 ~ v1.0.4（2026-08，沙箱内完整验证可行）。
> 一键脚本见 `build-tools/`（README 有速查）；本文档讲清每一步的原理与注意事项。

## 0. 前置环境（一次性）

| 项 | 位置/值 | 说明 |
|---|---|---|
| JDK 17 | `C:\jdk17\jdk-17.0.14+7` | Temurin；Gradle 7.6.4 需要 17 |
| Android SDK | `C:\Android\Sdk`（build-tools 34.0.0） | aapt / apksigner |
| Gradle 发行 | `C:\gwork\gradle-home\wrapper\dists\gradle-7.6.4-bin\...\gradle-7.6.4\lib` | 直接 `java -cp` 启动，绕开 wrapper |
| 依赖只读缓存 | `C:\gh_r6\caches\modules-2` | `GRADLE_RO_DEP_CACHE`，首次全量下载后复用 |
| **原机器 debug.keystore** | `C:\gwork\origks\debug.keystore`（SHA-1 `e72843708a92958b7ae82c230ddef2c3bf53aa7b`） | ⚠️ 与已安装 App 同签名，**缺它覆盖安装报 -7** |
| git 工作副本 | `C:\repo3mman`（clone 3mman） | 仅用于 plumbing 推送，不碰本地 ref |
| 远程仓库 | `github.com:wacilimonster-source/3mman`，分支 `master` | raw 直链供 App 更新 |

> ⚠️ **沙箱"写后文件锁定"**：凡被 Java/Gradle 进程打开过的文件，后续写会被拒（err=5），
> 且会传染给副本。对策：**每次构建全部使用全新目录**（脚本已用时间戳命名），绝不复用旧构建产物/缓存。

## 1. 更新版本号（两处必须同步）

`app/build.gradle`：
```gradle
defaultConfig {
    versionCode 38          // 严格递增（App 内 versionCode > 本机才提示更新）
    versionName "1.0.4"
}
```
`version.txt`（GitHub 检查更新接口读取它，位于仓库根）：
```json
{
  "versionCode": 38,
  "versionName": "1.0.4",
  "updateMessage": "…本次更新说明…",
  "apkDownloadUrl": "https://raw.githubusercontent.com/wacilimonster-source/3mman/master/3mman_v1.0.4.apk"
}
```
- 产物命名规则：`outputFileName = "3mman_v${versionName}.apk"`（app/build.gradle 已配置），
  所以 **versionName 变了文件名就变，apkDownloadUrl 必须同步改**。
- `versionCode` 是 App 判更新唯一依据（`UpdatePresenter`：远程 versionCode > 本机才提示）。

## 2. 构建 APK

```bat
cd build-tools
python 01_copy_src.py   :: 复制源码到 C:\p3mman_<ts>（跳过 .git/build/旧apk/日志；放 keystore）
python 02_build.py      :: 全新 GRADLE_USER_HOME/ANDROID_USER_HOME 下 assembleDebug
```
- 产物：`C:\p3mman_<ts>\app\build\outputs\apk\debug\3mman_v<versionName>.apk`
- 退出码 0 = 成功；日志 `C:\build_<ts>.log`。
- 构建用 debug 配置（release 的 jks 只配了 storeFile，缺密码，无法出 release 包）。

## 3. 重签（原机器密钥）

```bat
python 03_resign.py
```
- 剥 META-INF → `apksigner sign --ks 原机器debug.keystore`（v1+v2+v3）→ verify 通过。
- 脚本会断言证书 SHA-1 = `e72843708a92958b7ae82c230ddef2c3bf53aa7b`，不符即失败。
- 产物：`C:\resign_<ts>\3mman_v<versionName>.apk`（这就是最终交付包）。

## 4. 推送 GitHub（git plumbing，read-tree 法）

```bat
python 04_push.py                        :: 默认：version.txt + build.gradle + 新APK（自动移除旧APK）
python 04_push.py --files 路径1 路径2    :: 追加源码文件一起提交
```
原理（经过 v1.0.2 空树事故后定型的**安全方法**）：
1. `git ls-remote origin master` 实时取父提交（绝不信任本地 ref）
2. `GIT_INDEX_FILE=<新文件> git read-tree <parent>` —— 让 git 自己解析父树填充 index（**不手拼条目**）
3. `git update-index --add --cacheinfo 100644,<sha>,<path>` 覆盖/新增；`--force-remove` 删旧 APK
4. `git write-tree` → **断言 tree ≠ 空树 `4b825dc642cb6eb9a060e54bf8d69288fbee4904`**、条目数按增删净变化
5. `git commit-tree -p <parent>`（设 `GIT_AUTHOR_*`/`GIT_COMMITTER_*`）→ `git push origin <sha>:refs/heads/master`

> ⚠️ 铁律（血泪教训）：
> - 不要用 `ls-tree -z` + 手拼 `update-index --index-info` —— -z 模式 sha 与 path 之间是 NUL 不是 TAB，
>   解析错会截断 sha → write-tree 回退**空树** → push 把 master 顶掉（曾两次发生，靠 `push --force` 救回）。
> - 推送前 `ls-remote` 必须等于预期父提交；树条目数断言按"新增/删除"净变化算（如 +1 图标文件 -1 旧apk +1 新apk = +1）。

## 5. 验证

```bat
python 05_verify.py
```
- 输出：远程 master commit、树条目数、树内 APK 名、version.txt 的 versionCode/apkDownloadUrl、APK badging 版本。
- 手动复核：
  - `git ls-remote origin master` 指向新 commit；
  - `https://raw.githubusercontent.com/wacilimonster-source/3mman/master/version.txt` 内容正确；
  - 下载 APK 后 `aapt dump badging` 确认 versionCode/versionName；
  - 与原已装 App 同签名（`apksigner verify --print-certs` SHA-1 = e72843...）。

## 6. 发布后

- **CDN 缓存**：raw.githubusercontent 约 5 分钟，App 内"检查更新"稍后触发。
- 把最终 APK 复制一份到项目根（如 `G:\game\新建文件夹\3mman\3mman_v1.0.4.apk`）便于侧载。

## 7. 工作区维护

- 清理过程文件（构建目录/缓存/旧 APK/日志）：`python build-tools/99_clean_workspace.py --delete`
  （沙箱内删除被保护钩子拦截，需在用户本机执行）。
- 保留：`app/src`、`app/build.gradle`、`build-tools/`、`3mman_v1.0.4.apk`（最新）、
  `3mman.jks`、`version.txt`、`.git/`、`.workbuddy/`、检测报告 md。

## 8. 常见问题

| 现象 | 原因/处理 |
|---|---|
| 安装报 -7 签名不同 | 用了别的 keystore；必须用原机器 debug.keystore 重签 |
| 提示已是最新版本 | versionCode 没递增，或 CDN 未刷新（等 5 分钟） |
| 点了更新下载到旧包 | 仓库里 APK 没更新（漏了 04_push），或 apkDownloadUrl 与文件名不一致 |
| 构建失败 err=5 写文件被拒 | 复用了被 Java 锁定的旧目录；用全新目录（脚本已处理） |
| 推送后 master 空了 | 空树事故；`git push --force origin <好commit>:refs/heads/master` 救回，并改用 read-tree 法 |
