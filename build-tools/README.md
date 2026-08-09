# build-tools — 3mman 打包/发版脚本

把"改版本 → 构建 → 重签 → 推 GitHub → 校验"的全流程脚本化，按编号顺序执行。
**请在用户本机（非沙箱）运行**——沙箱的删除保护与文件锁定会干扰构建。

## 一键发版（推荐）

```bat
cd /d G:\game\新建文件夹\3mman\build-tools
python 01_copy_src.py          :: 复制源码到全新构建目录（时间戳命名）
python 02_build.py             :: Gradle assembleDebug（全新 GRADLE_USER_HOME / ANDROID_USER_HOME）
python 03_resign.py            :: 剥 META-INF + 原机器 keystore 重签（避免覆盖安装 -7）
python 04_push.py              :: read-tree 法推送 GitHub（含 version.txt / build.gradle / 新 APK）
python 05_verify.py            :: 校验远程 master / version.txt / APK 版本
```

- 每次构建使用全新目录（`C:\p3mman_<时间戳>` 等），避免环境"写后文件锁定"污染。
- `04_push.py` 可追加源码文件：`python 04_push.py --files app/src/main/java/com/m3man/utils/SDCardUtils.java`。
- 推送后 raw.githubusercontent CDN 约 5 分钟缓存，App 内"检查更新"稍后触发。

## 脚本一览

| 脚本 | 作用 | 说明 |
|---|---|---|
| `config.py` | 统一配置 | 所有机器路径/版本读取集中于此，换机器只改这里 |
| `01_copy_src.py` | 复制源码 | 跳过 .git/build/.gradle/旧 APK/日志等过程文件；放置原机器 debug.keystore |
| `02_build.py` | 构建 APK | 直接 `java -cp gradle-libs GradleMain assembleDebug`，绕开 wrapper |
| `03_resign.py` | 重签 | apksigner v1+v2+v3 + 原机器密钥；校验证书 SHA-1=e72843... |
| `04_push.py` | 推送 | git plumbing（read-tree→cacheinfo→write-tree→commit-tree→push），带空树/条目数断言 |
| `05_verify.py` | 校验 | 远程 master、树内 APK、version.txt、aapt badging 一致性 |
| `99_clean_workspace.py` | 清理 | 一键清理工作区过程文件（默认 dry-run，`--delete` 真正删除） |

## 关键事实（务必阅读 BUILD_PROCESS.md）

- 版本号在 `app/build.gradle`（versionCode/versionName）与 `version.txt` 两处，**必须同步改**；
  `version.txt` 的 `apkDownloadUrl` 必须指向 `3mman_v<versionName>.apk`（与构建产物同名）。
- 签名必须用**原机器 debug.keystore**（`config.ORIG_KS`，SHA-1 `e72843708a92958b7ae82c230ddef2c3bf53aa7b`），
  否则覆盖安装报"安装失败 -7 签名不同"。
- 推送父提交实时取自远程 master，**绝不触碰本地被污染的 index/ref**；
  每次推送前断言 `tree != 空树(4b825dc...)`，防止空树顶掉 master。
- 构建/重签/推送脚本在沙箱内已验证可运行（BUILD_RC=0、apksigner verify=0、push rc=0），
  本机运行同样适用。
