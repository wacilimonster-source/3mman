# 3mman 代码质量经验教训（2026-11-12）

这份文档记录本轮"屎山清理"发现的**真实问题、形成原因、以及以后 agent 必须遵守的硬规则**。
目的不是追责，而是让下一次自动化改代码时不再重复同样的错误。

---

## 0. 一句话结论

这个项目不是"某一次写崩了"，而是**长期只加不删、每个修复都留一条旧路径、同一件事写四份实现、验证只覆盖改动路径**，
一层层叠加出来的。代码量本身不大（约 5.3 万行 Java / 288 个文件），但**认知负担**已经很高：
一个"来源判定"有 4 套规则，一个"URL 脱敏"有 2 份拷贝，一个 HLS 判定有 3 份，一个下载状态回写有 4 个入口。

本轮清理后：
- 历史 APK 从 git 索引移除 26 个（443 MB），仓库只保留当前发布包；
- 删除 5 个死布局、31 个死 drawable 文件、10 个不可达 mipmap PNG、66 条死字符串、13 个死颜色、2 个死数组、2 个死 menu；
- 删除/收敛死亡代码与重复实现：净减少约 1,355 行（412 行新增 / 1,767 行删除）；
- 修复 4 个 P1（ANR/崩溃/数据覆盖/进度卡 0%）和 6 个 P2；
- lint 从"全关"恢复为发布门禁，debug/release 均 0 error；release APK 体积减少约 340 KB。

---

## 1. 屎山是怎么形成的（根因）

### 1.1 只加不删：每次修复都留一条旧路径

典型证据：
- 推荐缓存有 v1（`PlayUiPrefs.reco_cache_batch`，V9MmanItem 裸数组）和 v2（`RecoBatchCache` + `reco_cache_batch_v2`）两套。
  `PerfFlags.cacheFirst` 恒为 `true`，v1 的读取/写入/展示整条链路永远不会执行，但代码、SP key、注释全部保留。
- 夜间模式同时存在布尔键 `key_sp_open_night_mode` 和整型键 `key_sp_night_mode`，还有一套只做迁移的兼容读写。
- 布局 `activity_recommend_feed.xml`、`item_99_mm.xml`、`item_picture_adapter.xml` 对应的 Activity/来源早已删除，布局仍然健在。
- 项目删除了"蝌蚪/精品"频道，但频道相关字符串、drawable、viewKey 判定仍散落多处。

**教训**：删除功能时必须做一次"反向引用扫描"（代码 + 布局 + 字符串 + drawable + 测试 + 文档），
并在同一个提交里删干净。遗留的兼容层必须有**明确过期条件**（例如"v1.0.140 后删除"），否则永远没人删。

### 1.2 同一职责多份实现，口径必然漂移

本轮实测：
- **来源判定 4 套**：`PlayVideoPresenter.isPornySource`、`DownloadPresenter.isPornyVideo`、
  `Parse91PornyVideo.isTadpoleViewKey/isPremiumViewKey`、`VideoCardMeta.sourceLabel`，
  再加上 `RecommendPrefetcher` 里"看 viewKey 形状猜来源"。裸 hex 的搜索/作者视频曾被误路由到 91porny。
- **URL 日志脱敏 2 份**：`BasePlayVideo.maskUrl` 与 `ExoMediaPlayerActivity.maskUrl`，逐行复制，只差判空写法。
- **HLS 判定 3 份**：`DownloadManager.isHlsUrl`、`RecommendPrefetcher.isHlsUrl`、`BasePlayVideo.isHlsUrl`。
- **viewKey 归一化 5 处**：`AppApiHelper`、`AppDbHelper`、`DownloadPresenter`、`VideoCardActions`、`DownloadEnqueuer`
  各写一遍"裸 key / 带 viewkey= 前缀"。历史上已经因为口径不一致出过 bug。
- **下载状态回写 4 个入口**：`AppDbHelper.updateV9MmanItem`（整行）、`DownloadManager.saveDownloadInfo`、
  `HlsDownloadService.updateHlsProgressColumns`（定向列）、`DownloadingFragment` 本地合并，
  整行读改写的路径会互相覆盖 `localFilePath`/完成时间。

**教训**：同一语义第二次出现时，必须**当场抽公共方法**，而不是"先复制一份、以后再说"。
以后再说 = 永远不会说。判断标准很简单：同一个判断/格式化/归一化在仓库里出现第二次，就该收敛。

### 1.3 状态有多份真值

- 下载角标：`downloadLabelStates` 里存中文文案 `"已完成"`，同时 DB 里还有 `status`，业务判断用中文文案做哨兵。
- 收藏：推荐引擎的 `RecoStore.action` 单槽位（LIKE/DISLIKE/FAVORITE 互斥）与 DB 的 `isLocalFavorite` 两套真相；
  先点赞再收藏时 action 仍是 LIKE，收藏记录查不到。
- 推荐缓存：v1/v2 两套 key；夜间模式：布尔/整型两套 key。
- 搜索渠道选择：裸 `SharedPreferences("search_channel")` 与项目 `PreferencesHelper` 体系并存。

**教训**：一个业务状态只能有一个权威来源（single source of truth）。
其他层只能是缓存/派生，并且必须能明确说明"以谁为准"。禁止用 UI 文案（"已完成"）做业务判断。

### 1.4 "开关"没有测试，也没有回收

`PerfFlags` 里 8 个 `public static volatile boolean`：
- `cacheFirst` 全仓库无赋值点，恒 `true`；
- `prefetchScheduler` 也无赋值点；
- 其余开关也没有任何测试覆盖"关掉时"的行为。

结果：关闭分支要么是死代码，要么根本没人验证过，出事时也不敢关。

**教训**：特性开关必须有：
1. 至少一个测试或调试入口能真正切换它；
2. 明确的默认值、生效范围和删除时间；
3. 默认值确定后，**删掉关闭分支**，不要长期双维护。

### 1.5 验证只覆盖"我改的那条路径"

这是自动化改代码最危险的地方。真实例子：
- **HLS 进度节流跨任务残留**：`lastSeenBytes` 等字段在新任务 `startDownload` 时没有重置。
  单任务下载测试完全正常；只有"下载中再起一个新任务/续传较小任务"才会出现进度一直 0%。
  之前的验收只测了"点一个下载 → 进度正常 → 完成"。
- **RecoRepository 锁内网络探测**：方向探测在 `synchronized(pool)` 里做最多 3×1.5s 的 HTTP。
  `resetSession()` 在主线程调用，会等这把锁 → 极端情况下 ANR。单测只测了"探测能返回方向"。
- **Parse91 videoId 用作者名兜底**：同一作者的不同视频共用 `porny_<author>`，
  `saveVideoResult` 去重时互相覆盖。测试只验证了"能解析出 URL"，没有验证"两条同作者视频都保存后各自还能播"。
- **MediaStoreArchiver 直接把 viewKey 拼进文件名**：`videos/<id>/<slug>`、`vod/<key>` 含 `/`，
  MediaStore insert 失败被 `catch` 吞掉，表现为这些来源"归档永远退回私有副本"，但功能看起来"没崩"。
- **`Handler.hasCallbacks`**：API 29 才有的方法，minSdk 28。lint 关着，没人发现；Android 9 真机会 `NoSuchMethodError`。

**教训**：验证必须覆盖"状态组合"，不是只覆盖"主路径"：
- 连续操作（任务 A 未完成时启动任务 B）
- 并发/竞态（刷新 + loadMore、reset + 在途请求）
- 生命周期（Fragment view 重建、页面销毁后回调到达）
- 边界（空列表、null 字段、超长/含 `/` 的 key、API 28）
- 失败路径（网络失败、权限拒绝、DB 异常）

### 1.6 异常被吞，问题从"崩溃"变成"静默错误"

全仓 255 个 `catch`，其中约 149 个是空 catch 或只 `printStackTrace()`。
典型：
- MediaStore 归档失败 → 静默返回 null；
- HLS 分片失败 → 只汇总一句"N 个分片失败"，没有 host/分片号；
- Parse91 整文件 0 条日志，解析退化只能靠猜；
- 下载状态回写失败 → 静默，用户看到"文件丢失"。

**教训**：`catch` 只有三种合法写法：
1. 记录日志（`AppLog.w/e`，release 也可见）并继续；
2. 转换成用户可理解的错误并向上抛；
3. 明确注释"为什么可以忽略"（例如"探测性请求，失败按未知处理"），且注释要写清影响范围。

禁止：空 catch、只 `printStackTrace()`、`if (BuildConfig.DEBUG) log` 把 release 诊断也关掉。

### 1.7 上帝类与"顺手加一点"

`RecommendFeedFragment` 2,349 行，`SearchPornyFragment` 1,360 行，`DownloadPresenter` 1,190 行，
`ParseV9MmanVideo` 1,170 行。它们不是一次写成的，而是每次需求"顺手在 Fragment 里加 30 行"：
播放、下载、收藏、筛选、缓存、埋点、权限、弹窗全在一个类里。

**教训**：新职责进入大文件前先问三个问题：
1. 它需要 Fragment 的生命周期吗？
2. 它需要 View 引用吗？
3. 它能被独立测试吗？

如果答案是否，抽成 Helper/Repository/Manager（项目里已有 `LandscapeOrientationHelper`、
`DownloadEnqueuer`、`RecoPersistenceCoordinator` 这些好例子）。

### 1.8 仓库卫生与构建配置长期无人管

- 27 个历史 APK 被 `git add -f` 进仓库，443 MB；`.git` 717 MB。
- `3mman.jks` 被跟踪，但构建根本不用它；签名配置指向项目外 `C:/gwork/...`，密码明文。
- 根目录 57 个日志/脚本/报告/临时文件；`build_apk.bat` 里的路径还是 `G:\game\新建文件夹\3mman`。
- `BUILD_PROCESS.md` 写的是 v1.0.61、文件数 471，实际 528。
- `lintOptions` 全关，`abortOnError=false`，任何 lint 问题都无法阻断发布。

**教训**：仓库卫生是代码质量的一部分。发布流程必须可复现、路径可移植、密钥不进库。
文档只写"当前事实"，过期内容移到 `docs/archive/` 或删除。

---

## 2. 以后 agent 必须遵守的硬规则

> 这些规则按"最容易犯"排序，每条都能在 code review 时直接检查。

### R1. 改动前先做引用扫描
- 删除/修改 public/private 方法、常量、资源前，全仓 `grep`（含 `app/src/test`、`exolibrary`、布局、strings）。
- 删除资源前，检查 `R.<type>.name`、`@<type>/name`、动态 `getIdentifier`、库按名覆盖（QMUI/night）。
- 删除接口方法前，检查所有实现类、Presenter、View 接口、匿名类。

### R2. 不允许"恒真/恒假"的开关
- 新增 flag 必须同时提供切换入口和测试；默认值确定后删除关闭分支。
- 禁止 `if (FLAG)` 里包着永远不会执行的旧实现。

### R3. 同一职责不允许第二份实现
- 出现第二次时，先找现有工具：`VideoKey`、`UrlUtils`、`SDCardUtils`、`AppLog`、`RxSchedulersHelper`。
- 本轮已收敛：URL 脱敏 → `UrlUtils.maskForLog`；HLS 判定 → `UrlUtils.isHlsUrl`；文件名清洗 → `SDCardUtils.sanitizeFileName`。
- 尚未收敛（下一轮优先）：来源判定、viewKey 归一化、下载状态回写、收藏写库。

### R4. 主线程禁止 DB/文件/网络；锁内禁止 IO
- 播放页 `initData` 里的 greenDAO 查询、下载入口的 `File.exists()`、`MediaStore` 归档都必须异步。
- `synchronized` 块内只做内存操作；网络/文件操作先取快照，出锁执行，再回锁合并。
- 新异步回调必须带 **generation/代际校验**（`viewGeneration`、`loadGeneration`、`sessionGeneration`），
  否则旧结果会覆盖新状态。

### R5. catch 必须可诊断
- 使用 `AppLog.w/e` + `AppLog.cause(t)`；不要在 `BuildConfig.DEBUG` 条件下才记录。
- 用户可感知的失败（下载、归档、解析）必须有明确提示或错误状态，不能静默。

### R6. API 级别纪律
- minSdk 28：API 29+ 方法必须 `Build.VERSION.SDK_INT >=` 判断。
- 不知道 API 级别时，先查 `android.os.Build.VERSION_CODES`，不要凭印象。
- lint 的 `NewApi` 必须 0 error；发布前跑 `lintRelease`。

### R7. 生命周期与视图重建
- Fragment 的 `onViewCreated` 必须复位所有"view 已销毁"标记（如 `viewDestroyed`）。
- `onDestroyView` 必须解绑监听、移除 Handler 回调、dismiss 弹窗、置空 View 引用。
- `postDelayed` 的 Runnable 必须在 `onDestroyView` 移除；`Handler` 用主线程 Looper 时注意 Activity 泄漏。

### R8. 每轮改动的最低验证门禁
```bash
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
./gradlew :app:compileDebugJavaWithJavac
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug          # 必须 0 error
```
- 改了 UI/生命周期/并发：还要在模拟器上验证对应页面 + 至少一次"连续操作/页面往返/杀进程重启"。
- 改了下载/HLS：必须验证"任务 A 未完成时启动任务 B"和"暂停后重试"。
- 改了资源：`assembleDebug` 必须通过（资源链接会暴露缺失引用）。

### R9. 提交前自检
- `git status` 确认没有把 APK、日志、`local.properties`、`keystore.properties`、个人 memory 加进去。
- `version.txt` 的 `versionCode`/`versionName`/`sha256` 与实际包一致。
- 新增/修改的用户可见文案进 `strings.xml`，不要硬编码中文。
- 新增依赖先查是否已有同类库；不要为了一个小功能引入新框架。

### R10. 文档与注释写"当前事实"
- 注释里的 `Mxx`/`Txx` 编号可以保留（历史锚点），但描述必须与代码一致。
- 过期文档不要留在根目录冒充当前流程，移到 `docs/archive/` 或删除。
- 每次删除兼容层/开关时，同步删除对应注释和文档。

---

## 3. 审计遗留项（2026-11-14 已修复 7 项，剩 3 项未做）

2026-11-14 轮已修复（细节见 `检测报告_遗留项修复代码测试_2026-11-14.md`）：

1. ~~**下载状态整行回写竞态**（P1/P2）~~ ✅ 已修复：`DbHelper` 新增定向列写方法组
   （`updateDownloadStateColumns` / `updateLocalFilePathColumn` / `updateDownloadBindingColumns` /
   `updateVideoResultIdColumn` / `updateVideoSourceColumn` / `updateLocalFavoriteColumn` / `updateViewHistoryColumn`），
   `DownloadManager`、`HlsDownloadService`、`DownloadPresenter`、`DownloadingFragment`、`FinishedFragment`、
   `DownloadEnqueuer`、`BasePlayVideo` 的进度/状态/绑定/成品路径写全部改定向列；
   终态（completed）只由各自下载通道提交一次；`PornyFallbackResolver.applyPornyResult` 改为重读 DB 行后落库。
2. ~~**RecoProfile 非原子计数**（P2）~~ ✅ 已修复：四计数改 `AtomicInteger`（Gson 不参与画像序列化，org.json 手写读写在 RecoStore）。
3. ~~**来源判定 4 套未完全统一**（P2）~~ ✅ 已修复：唯一权威实现收敛到 `Parse91PornyVideo.isPornySource(V9MmanItem)`，
   `PlayVideoPresenter.isPornySource` / `DownloadPresenter.isPornyVideo` / `VideoCardMeta.sourceLabel` 全部委托；
   `Parse91PornyVideoSourceTest` 固定状态组合。注意：`RecommendPrefetcher` 的 `isPornyHex`（直链刷新路由）与
   `isTadpoleViewKey/isPremiumViewKey`（解析端点路由）是 URL 形状判定，非持久化来源路由，有意保留。
4. ~~**收藏写库 4 份实现**（P2）~~ ✅ 已修复：唯一写库路径 `FavoritePresenter.setLocalFavorite/toggleLocalFavorite`（静态），
   `VideoCardActions.toggleFavorite` / `PlayVideoPresenter.addLocalFavorite` / `RecommendFeedFragment.saveFavorite` 全部委托；
   DB 已有行只定向写 `IS_LOCAL_FAVORITE`/`SOURCE_NAME` 列。
7. ~~**推荐缓存 v2 存在 SharedPreferences**（P2）~~ ✅ 已修复：迁到 `filesDir/reco/cache_batch_v2.json`（临时文件+rename 原子写），
   首次读取时从旧 SP 键一次性迁移并删除；`PlayUiPrefs` 的 v2 存取方法已删。
8. ~~**`enableDislikeFilter` 设置项无效**（P2）~~ ✅ 已修复（移除）：该开关承诺"不喜欢直接过滤"但 scorer 从不消费，
   且与产品决策 #2（不喜欢只降权不屏蔽）冲突；`RecoParams`/`RecoSettingsDialog`/strings.xml 已同步删除。
10. ~~**441447 旧封面域名**（P3）~~ ✅ 已修复：`AppDbHelper.repairBroken441447Covers()` 启动一次性把历史行
    `IMG_URL` 的 `://cover.317347.xyz/` 改写为 `://p1.338484.xyz/`（与解析入口 normalizeCoverUrl 同口径），SP 标记防重扫。

仍未做（下一轮评估）：

5. **AppDataManager 纯委托膨胀**（P3）：新字段要在接口 + 实现 ×3 处同步；本轮定向列方法同样经此层委托，
   长期应考虑合并 DataManager/DbHelper 层或生成委托。
6. **依赖/构建栈老化**（P3）：AGP 7.4.2 + compileSdk 34（靠 suppress）、jsoup 1.11.3、Retrofit 2.5/OkHttp 3.12、Glide 4.9、
   ButterKnife/GreenDAO 已归档。升级需要单独排期和完整回归，不要顺手做。
9. **91porny 直链 TTL**（P2）：`?t=&m=` 目前按实测 150s 保守处理，需要更多样本收紧（数据采集任务，非代码修复）。

---

## 4. 发布前检查清单（建议贴到 `BUILD_PROCESS.md` 顶部）

- [ ] `versionCode`/`versionName` 递增，`version.txt` 同步
- [ ] `compileDebugJavaWithJavac` 通过
- [ ] `testDebugUnitTest` 全绿
- [ ] `lintDebug` 0 error
- [ ] `assembleRelease` 通过（R8/资源收缩）
- [ ] 签名校验（SHA-1 与发布密钥一致）
- [ ] 模拟器冒烟：冷启动 / 四 Tab / 推荐滑动播放 / 搜索 / 播放页返回 / 下载完成
- [ ] 指定场景回归：本次改动涉及的页面 + 连续操作 + 页面往返 + 杀进程重启
- [ ] logcat 无 FATAL/ANR
- [ ] APK sha256 与 `version.txt` 一致
- [ ] `git status` 无构建产物/密钥/个人文件

---

## 5. 维护节奏建议

- **每次发版前 30 分钟**：跑一次死代码/死资源扫描（`lintDebug` 的 `UnusedResources` 就是现成入口），删掉本轮新增的无引用项。
- **每季度一次**：按本文第 2 节 R1-R10 做一次专项审计，重点看"同一职责多份实现"和"恒真开关"。
- **每次删除功能**：同提交内完成代码、资源、字符串、测试、文档的反向清理。
- **每次新增抽象**：先证明它能被两个以上调用点复用，否则不要抽象；但同一逻辑出现第二次时必须抽象。

---

*本文基于 2026-11-12 的全量只读审计（4 个分片：UI 上帝类 / 数据层并发 / 解析与工具 / 仓库与构建）与本轮实际修复整理。*
