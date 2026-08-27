# 3mman 全链路代码评审报告

评审范围：获取列表 → 查看详情 → 播放 → 下载 → 收藏/搜索/评论/历史/升级 等全部业务链路。
评审方式：5 路并行深度审查 + 关键发现人工复核源码。行号基于当前 master（v1.0.70, commit 728154f）。

---

## 一、P0（必现功能失效，建议立即修复）

### P0-1 所有 Activity 页面的 Toast 提示静默失效
`ui/BaseAppCompatActivity.java:203`
```java
TastyToast.makeText(getApplicationContext(), msg, TastyToast.LENGTH_SHORT, type);
```
**漏掉 `.show()`**（对比 BaseFragment.java:195 是有的）。MvpActivity 及所有 Activity 子类
（搜索、收藏、历史、设置、播放页、推荐 Activity 等 20+ 个页面）的"加载失败""设置成功"
等提示全部不显示——用户操作无反馈。已亲自核实属实。
修复：补 `.show()`。

### P0-2 推荐流 Fragment 下载兜底对 porny 条目误判（Activity 已修、Fragment 漏改）
`ui/recommend/RecommendFeedFragment.java:812`
```java
if (!Parse91PornyVideo.SOURCE.equals(target.getSource())) {
```
依赖 transient 字段 `source`：DB 读出的 porny 条目该字段为空 → 误走 `loadMman9VideoUrl`
必然失败。同逻辑 Activity 在 785 行已改用 `PlayVideoPresenter.isPornySource(target)`，
Fragment 是漏改（已核实）。修复：与 Activity 对齐用 isPornySource。

---

## 二、P1（特定场景功能失败 / ANR 风险）

### 播放链路
1. **BasePlayVideo DB 命中分支缺过期直链校验**（`play/BasePlayVideo.java:269-278`，已核实）
   `tmp != null` 时直接用 `videoResult.getVideoUrl()` 播放，不做 `isSecureUrlExpired` 校验。
   M72 只修了推荐流的 RecommendPrefetcher.resolveNow；从收藏/历史/作者页进入详情页仍会拿到
   历史过期直链 → 转圈（正是用户反复遇到的转圈问题的残留入口）。

2. **解析层多处 `.first()` 无判空导致整页失败**
   - `ParseV9MmanVideo.java:177,641`：`getElementsByClass("video-title").first()` 为 null 即 NPE，一条异常条目炸掉整页列表；
   - `:443-447`：播放页直链已提取成功后，UID/VID/VUID 元素缺失即 NPE，"有直链却播放失败"；
   - `:694`：收藏页 `input.first().attr("value")` 无判空，一条坏条目炸掉整个我的收藏。

3. **主线程同步 DB IO（ANR 风险）**
   - `HistoryPresenter.java:37`：onCreate 主线程同步 greenDAO 分页查询；
   - `DownloadingFragment.java:93`：下载回调里同步 loadDownloadingDatas；
   - `SearchHistoryPanel.java:104-120`：搜索历史主线程直写 DB；
   - `DownloadManager.java:194-236`：每个进度回调全量查库+写库（大文件下载数千次同步 IO）。

### 下载链路
4. **HLS 后台下载 Android 8+ startService 静默失败**（`PornyFallbackResolver.java:124-127`）
   Oreo+ 后台启动 Service 限制，应用退后台后 porny HLS 兜底下载不会启动且无任何报错。
   应改 startForegroundService 或 JobIntentService。

5. **升级下载无法续传 + 失败无通知**（`UpdateDownloadService.java:81-96`）
   GO_ON 不复用 downloadId（每次从头下）；error 回调为空 → 用户不知道升级失败。

6. **CommonHeaderInterceptor 重定向污染全局域名映射**（`:105-117` + `AppApiHelper.java:263`)
   CDN 错误页/302 到验证页会 putDomain "毒化"整个会话；testPornyAddress 先写映射再测试、
   失败依赖调用方回滚，其他入口漏滚。

### UI 并发与状态机
7. **列表跳页缺陷**（`videolist/VideoListPresenter.java:55-57,62,78`，已核实）
   skipPage>0 直接置 page，但 totalPage 只在 page==1 时更新：首进即跳第 5 页时
   totalPage=1 → `page>=totalPage` 恒成立误判"没有更多"。刷新/加载更多也无在途守卫，
   响应乱序会错插数据（SearchPresenter 同样问题）。

8. **加载失败后下拉刷新永久禁用**（IndexFragment:162、VideoListFragment:251、FavoriteActivity:203）
   showLoading 里 setEnabled(false)，showError 未恢复 enable，重试再失败后刷新失效只能退出页面。

### 工具层
9. **AppLog SimpleDateFormat 线程不安全**（`utils/AppLog.java:33,57`，已核实）
   TIME_FMT 静态共享且 format 在锁外执行，多线程并发可产生错误时间戳甚至 AIOOBE。
   改 ThreadLocal 或移入锁内。

10. **OkHttpClient 无显式超时/DNS/连接池配置**（`di/module/ApiServiceModule.java:93-108`）
    全默认参数（10s），慢速 CDN 易超时；retryOnConnectionFailure 默认 true，POST 可能被静默重放。

---

## 三、P2（边界 / 性能 / 体验）

| # | 位置 | 问题 |
|---|------|------|
| 1 | ParseV9MmanVideo:633 | col-lg-8 过滤只覆盖 parserByDivContainer，parseMyFavorite 用同选择器却没过滤，收藏页仍有废弃串位块 |
| 2 | AppDbHelper:196 + Parse91PornyVideo:177 | porny 解析恒 setVideoId("") 绕过 videoId 去重 → VIDEO_RESULT 表对 porny 无限膨胀 |
| 3 | DownloadManager:223 | 假完成防护 <100KB 判截断，可能误删合法小视频；SDCardUtils:161 完成判定 len>=totalBytes 与 95% 容差条件冗余 |
| 4 | HlsDownloadService:114 | 伪 downloadId = Math.abs(hashCode) 有碰撞风险，可能误停他人任务 |
| 5 | PornyFallbackResolver:93 | isAlive 探活复用全局 OkHttpClient，被域名映射污染时探活结果不可信 |
| 6 | PlayVideoPresenter:160-166 | diagnoseMsg 把短错误原文替换为通用文案，丢失诊断信息 |
| 7 | CommentPresenter:118 | 评论内容裸拼引号提交未转义，含引号评论提交失败/被截断 |
| 8 | AuthorPresenter:130-141 | pullCount%2 交替切换 public/private 数据集，行为不可预期；cleanCache 置 true 永不复位 |
| 9 | AppDbHelper:50/59 | 构造函数内 initCategory 写库 + repairMisflaggedPornyRows 全表扫描，发生在启动主线程拖慢冷启动 |
| 10 | RxCache 缓存一致性 | 删收藏后 15min 内 getFavorite 磁盘缓存仍返回已删条目（删除无 EvictProvider）；四个 CacheProviders 方法共用 @ProviderKey("cache_v113") 有互相污染风险 |
| 11 | MyApplication:47-72 | 无进程判断：FileDownloader 的 :filedownloader 进程重复执行 Dagger 注入/ProxySelector/Bugly 初始化 |
| 12 | MyHeaderInjector:14-32 | hashMap 非 volatile 且直接暴露，UI 写/代理读有可见性风险 |
| 13 | HlsDownloader:347-355 | 分片兜底排除绝对 URL 行，与 resolveSegmentUrl 支持绝对 URL 自相矛盾，特定 m3u8 解析出 0 个分片 |
| 14 | V9MmanItem int 存字节数 | >2GB 文件溢出 |
| 15 | BasePlayVideo:261/231 等 | 详情页进入路径多处主线程同步磁盘/DB 读 |
| 16 | RecoEngine 首次 get | 触发同步文件读写（主线程）；resetSession 与在途 fetch 有竞态 |
| 17 | JiaoZi 引擎 onPause | 无恢复逻辑，切后台回来可能黑屏 |
| 18 | favoriteDialog | Activity 销毁时未 dismiss → WindowLeaked |

---

## 四、P3（代码质量 / 低危）

- `ParseV9MmanVideo:84,346,706`：getElementById("paging") 未判空直接 .select（authorVideos 已修其余没同步）；
- `Parse91PornyVideo:267,271`：广告过滤 contains("ad-") 子串匹配会误杀 load-more 等正常类名；
- `ParseV9MmanVideo:512`：strencode 解密正则全贪婪，多段加密时组匹配串位；
- `AppPreferencesHelper:115`：密码仅 Base64 存储（可逆）；getter 内含写副作用；
- `DownloadDiag:18`：MAP 无上限仅 reset 不淘汰；
- `FavoriteFragment`(mman9video) 空壳死代码仍在 Dagger 注册；ServiceModule 持 Service 引用无 Provides；
- `AddressHelper:30` nextInt(255) 永不出 255；
- postDelayed 回调里 ButterKnife unbind 不置空字段，判空防御无效。

---

## 五、业务流程完整性评估

**能跑通的主干**：分类浏览→列表分页→详情→播放→评论/收藏→历史→下载 的闭环完整；
双源路由（9mman/porny）、推荐流秒开、断点续传、E7 升级链路均已落地。经过 M44~M72 多轮
修复，高频用户可见问题大多已有针对性防护。

**系统性短板（设计层面）**：
1. **爬虫型架构天然脆弱**：站点任何 DOM 改动都会击穿 Jsoup 解析层。col-lg-8 过滤只打了
   一个入口，parseMyFavorite/parseSearchVideos 同类问题未收敛——缺少统一的"解析容错基类"
   （单条目失败降级跳过而非整页炸掉）。
2. **双源路由靠隐式契约**：viewkey 前缀约定区分来源，已有 repairMisflaggedPornyRows 这类
   数据修复佐证其代价。建议把"源站适配器"接口化：每个源自带列表/详情/播放解析与自检。
3. **Presenter 层普遍缺并发纪律**：无在途请求锁、无响应序校验、错误分支状态恢复不彻底
   （SwipeRefresh 使能、totalPage 语义）。
4. **DB 访问线程纪律不足**：读操作大量散落在主线程，写操作随进度回调高频触发，
   是性能隐患中最值得优先重构的一处。
5. **网络层配置偏薄**：OkHttp 全默认参数、域名映射可被重定向毒化、探活与业务复用同一
   client，故障时会连锁放大。

---

## 六、修复优先级建议

1. **立即修（P0 两项）**：BaseAppCompatActivity 补 .show()；RecommendFeedFragment:812 改
   isPornySource —— 都是几行的改动，收益立竿见影。
2. **下一版修（P1 高价值子集）**：BasePlayVideo DB 直链过期校验（转圈残留入口）、解析层
   三处 first() 判空、AppLog 线程安全、HLS 后台启动改 startForegroundService、
   VideoListPresenter 跳页 totalPage。
3. **择机重构**：DB 读写统一切调度器 + 进度落库节流、解析容错基类、OkHttp 显式超时。
