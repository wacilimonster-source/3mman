# 3mman 打包发版流程（供其他 Agent 直接执行）

> 本文档记录 2026-08-22 v1.0.61(94) 实际验证通过的完整打包流程。所有命令均在 Git Bash 中执行，
> 工作区为 `G:/game/nw/3mman`（旧式 Android 项目：AGP + Gradle 7.6.4 + GreenDAO + support lib）。
> 按顺序执行九个阶段即可复现发版。

---

## 阶段一：环境与工具链（全部使用绝对路径）

| 组件 | 绝对路径 | 说明 |
|---|---|---|
| Gradle | `C:/Users/wacil/.workbuddy/binaries/gradle/gradle-7.6.4/bin/gradle.bat` | **必须 7.6.4**。Gradle 8.x 与本项目 GreenDAO 插件的 `IncrementalTaskInputs` API 不兼容，直接报错 |
| JDK | `C:/Users/wacil/.workbuddy/binaries/jdk17/jdk-17.0.2` | 通过环境变量 `JAVA_HOME` 注入 |
| Android SDK | `C:/Android/sdk` | `ANDROID_HOME` / `ANDROID_SDK_ROOT` 都要设 |
| adb | `C:/Android/sdk/platform-tools/adb.exe` | 安装验证用 |
| apksigner | `C:/Android/sdk/build-tools/34.0.0/apksigner.bat` | 验签用 |
| 签名库 | `C:/gwork/origks/debug.keystore` | E7 密钥，配置在 app/build.gradle 内 |

**签名要求（硬约束）**：产物必须以 E7 密钥签名，SHA-1 必须等于
`e72843708a92958b7ae82c230ddef2c3bf53aa7b`。
签名配置已写在 `app/build.gradle` 的 `signingConfigs.release`：

```gradle
signingConfigs {
    release {
        storeFile file('C:/gwork/origks/debug.keystore')
        storePassword 'android'
        keyAlias 'androiddebugkey'
        keyPassword 'android'
    }
}
```

release 构建块同时启用：`minifyEnabled true`、`shrinkResources true`、
`proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro', 'proguard-fresco.pro'`、
`lintOptions.checkReleaseBuilds false`（跳过 lint 崩溃）。
**不要改动这些配置**——尤其 proguard-rules.pro 缺失会导致启动即崩（见阶段五坑点）。

---

## 阶段二：修改版本号

编辑 `app/build.gradle` 第 11–12 行附近：

```gradle
versionCode 94          // 每次 +1
versionName "1.0.61"    // 按需递增
```

产物文件名由构建脚本自动生成为 `3mman_v${versionName}.apk`。

---

## 阶段三：准备隔离构建目录

**⚠️ 目录约定（必须遵守）**：所有构建临时目录统一放在 **`C:/faban/`** 下，
**禁止直接建在 C 盘根目录**。这是用户在 2026-08-10 明确确立的约定，
1.0.22～1.0.59 历次发版均遵守。命名沿用既有惯例：

| 用途 | 路径模式 | 示例 |
|---|---|---|
| 源码副本 | `C:/faban/p3mman_<tag>` | `p3mman_tr_20260822_m60fix2` |
| Gradle 用户目录 | `C:/faban/gh_<tag>` | `gh_tr_20260822_applog` |
| Android 用户目录 | `C:/faban/ah_<tag>` | |
| 项目缓存 | `C:/faban/project-cache-<tag>` | |
| 构建日志 | `C:/faban/build_<tag>.log` | |

清理时可用项目自带的 `build-tools` 清理脚本（`99_clean_workspace.py`，需本机跑），
或手动删 `C:/faban/` 下对应 tag 的目录。

**为什么隔离**：工作区里可能有 gradle daemon 锁、`.gradle` 缓存锁，与正在运行的模拟器/IDE 冲突；
且工作区可能存在未提交的实验性修改，需要精确控制进入构建的代码。

**关键坑**：目录必须用 **Windows 绝对路径**（如 `C:/faban/p3mman_<tag>`）。
不要用 `/tmp` 或 Git Bash 的 `/tmp` 映射 —— `gradle.bat` 是 Windows 进程，
无法解析 Unix 风格路径，会报路径不存在或行为异常。

```bash
TAG="YYYYMMDD"                          # 每次换 tag 避免残留冲突
FABAN='C:/faban'
BLD="$FABAN/p3mman_$TAG"

rm -rf "$BLD" "$FABAN/project-cache-$TAG"
mkdir -p "$BLD/app/src" "$BLD/exolibrary"
```

---

## 阶段四：同步源码到副本

两层来源，缺一不可：

1. **git archive HEAD** —— 已提交的基线（干净、不含 .git 和 build 产物）；
2. **cp 覆盖工作区未提交修改** —— 保证本次修复真正进入构建。

```bash
cd 'G:/game/nw/3mman'

# 1) 基线
git archive HEAD | tar -x -C "$BLD"

# 2) 工作区覆盖（注意 cp -a app/src/. 复制"内容"而不是 src 目录本身）
cp -a app/src/. "$BLD/app/src/"
cp app/build.gradle app/proguard-rules.pro "$BLD/app/"
cp app/proguard-fresco.pro "$BLD/app/" 2>/dev/null || true
cp build.gradle settings.gradle gradle.properties "$BLD/"

# 3) exolibrary 子模块（链式模块，缺失会导致 Could not resolve project :exolibrary）
cp exolibrary/build.gradle "$BLD/exolibrary/"
cp -a exolibrary/src "$BLD/exolibrary/"

# 4) 清掉 git archive 可能带出的陈旧 build 产物（保险）
rm -rf "$BLD/app/build" "$BLD/exolibrary/build" 2>/dev/null
```

> 若 `git archive` 后发现 exolibrary 下只有零星文件（如仅 build/proguard-rules.pro），
> 说明仓库中该模块曾误提交不完整内容，必须按上面第 3 步从工作区显式补齐
> `exolibrary/build.gradle` 和整个 `exolibrary/src`。

---

## 阶段五：⚠️ 副本完整性强制校验（跳过必翻车）

这是本流程最重要的防线。历史上发生过：git archive 提取不完整导致
`app/proguard-rules.pro` 缺失 → R8 无 keep 规则 → GreenDAO 生成的 DAO 类被混淆剥掉反射字段
→ 安装后启动即崩：

```
Unable to create application com.m3man.MyApplication: Could not init DAOConfig
Caused by: java.lang.NoSuchFieldException: TABLENAME
```

校验清单：

```bash
# 1) 文件数对比：两边应相等（当前应为 471 = 471）
cd 'G:/game/nw/3mman' && find app/src exolibrary/src -type f | wc -l
cd "$BLD"             && find app/src exolibrary/src -type f | wc -l

# 2) 关键文件存在性（任一缺失立即中止并重新同步）
for f in \
  app/proguard-rules.pro \
  app/build.gradle \
  app/src/main/AndroidManifest.xml \
  exolibrary/build.gradle ; do
  [ -f "$BLD/$f" ] && echo "OK $f" || { echo "MISSING $f"; exit 1; }
done

# 3) 抽查本次修复的代码确实在副本里（示例）
grep -c "resolveExistingDownloadFile" "$BLD/app/src/main/java/com/m3man/utils/SDCardUtils.java"
```

---

## 阶段六：执行构建

完整命令（一次性复制可用）。要点：
- 独立 `GRADLE_USER_HOME` / `ANDROID_USER_HOME` / `--project-cache-dir`，彻底避开文件锁；
- `--no-daemon` 防止 daemon 残留占锁；
- 用 `gradle.bat`（Windows 批处理），不是 `gradle`。

```bash
cd "$BLD" && \
JAVA_HOME='C:/Users/wacil/.workbuddy/binaries/jdk17/jdk-17.0.2' \
ANDROID_HOME='C:/Android/sdk' ANDROID_SDK_ROOT='C:/Android/sdk' \
ANDROID_USER_HOME="$FABAN/ah_$TAG" \
GRADLE_USER_HOME="$FABAN/gh_$TAG" \
'C:/Users/wacil/.workbuddy/binaries/gradle/gradle-7.6.4/bin/gradle.bat' \
  -p "$BLD" \
  --project-cache-dir "$FABAN/project-cache-$TAG" \
  :app:assembleRelease --no-daemon
```

成功标志：`BUILD SUCCESSFUL`，产物位于
`$BLD/app/build/outputs/apk/release/3mman_v<版本>.apk`。
首次构建无缓存时约需数分钟；后续同 GRADLE_USER_HOME 会快很多。

---

## 阶段七：验签与校验和

```bash
APK="$BLD/app/build/outputs/apk/release/3mman_v<版本>.apk"

# 1) 签名者 SHA-1 必须是 e72843708a92958b7ae82c230ddef2c3bf53aa7b（E7），否则覆盖安装失败
'C:/Android/sdk/build-tools/34.0.0/apksigner.bat' verify --print-certs "$APK"

# 2) 记录 sha256（写进 version.txt 用）
sha256sum "$APK"
```

---

## 阶段八：安装实测与 version.txt

```bash
ADB='C:/Android/sdk/platform-tools/adb.exe'

"$ADB" install -r "$APK"
"$ADB" shell am start -n com.m3man/.ui.main.MainActivity   # 注意组件名中间是 / 不是空格

# 观察 logcat 是否有崩溃（另开终端或后台跑几秒后抓取）
"$ADB" logcat -d | grep -E "FATAL EXCEPTION|NoSuchFieldException|Could not init DAOConfig"
```

测试通过后更新根目录 `version.txt`：写入最新版本号（如 `1.0.61 (94)`）、
发布日期、变更摘要、apk 文件名与 sha256。

---

## 阶段九：收尾

1. 把 `app/build.gradle`（版本号）、本次源码修改、`version.txt` 提交并推送：

   ```bash
   cd 'G:/game/nw/3mman'
   git add <修改的源文件> app/build.gradle version.txt
   git commit -m "release: vX.Y.Z (code)"
   git push
   ```

2. 把 apk 复制到工作区根目录归档（命名 `3mman_vX.Y.Z.apk`）。
3. **关闭模拟器上的测试应用**（用户惯例）：

   ```bash
   "$ADB" shell am force-stop com.m3man
   ```

4. 清理临时构建目录（可选）：删 `C:/faban/` 下本次 tag 对应的 `p3mman_*`、`gh_*`、`ah_*`、`project-cache-*` 目录。

---

## 踩坑速查表

| # | 现象 | 根因 | 对策 |
|---|---|---|---|
| 1 | 启动崩 `NoSuchFieldException: TABLENAME` / `Could not init DAOConfig` | 副本缺 proguard-rules.pro，R8 剥掉 GreenDAO 反射字段 | 阶段五强制校验，缺失即中止重建 |
| 2 | `Could not resolve project :exolibrary` | 副本 exolibrary 只有 build/proguard，缺 build.gradle 和 src | 从工作区补拷两样 |
| 3 | `Manifest file does not exist ... AndroidManifest.xml` | `cp app/src/main` 合并不完整 | 用 `cp -a app/src/.` 全量同步 |
| 4 | gradle 报路径不存在/行为诡异 | 使用了 `/tmp` 等 Unix 路径 | 一律 Windows 绝对路径 |
| 5 | Gradle 任务 API 报错（IncrementalTaskInputs） | 用了 Gradle 8.x | 锁定 7.6.4 |
| 6 | lint 阶段崩 `For input string: 37.0` | 老 AGP lint bug | 已由 `lintOptions.checkReleaseBuilds false` 规避，勿开 |
| 7 | 覆盖安装失败（签名不一致） | 签名非 E7 | 阶段七验签，SHA-1 必须 e72843...aa7b |
| 8 | `am start` 后退到桌面 | 组件名写成 `com.m3man/.ui.main MainActivity`（多了空格） | 用 `com.m3man/.ui.main.MainActivity` |
| 9 | 文件锁冲突 / daemon 残留 | 与工作区共享缓存 | 独立 GRADLE_USER_HOME、project-cache-dir、--no-daemon |
