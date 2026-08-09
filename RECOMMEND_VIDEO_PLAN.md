# 推荐视频功能方案（抖音式竖屏滑动）v2

> 状态：方案设计（未实现）。本文档给出交互、视频源选型、点赞/收藏/不喜欢、本地可调推荐算法，以及**基于全量历史语料的标签抽取**与**防卡顿预加载**的完整设计，供评审后进入实现。
>
> v2 更新：① 标签抽取改为基于「全量历史视频语料」构建统一词表（5.8 节）；② 新增预加载流水线，滚动零卡顿（6.2 节）。

---

## 一、功能概述

首页底部导航**新增「推荐」Tab**（第 4 个标签，位于现有「视频 / 分分钟 / 我的」之后），点击进入 `RecommendFeedActivity`，以**全屏竖屏、上下滑动切换**连续播放，类似抖音：

- 上滑 = 下一个，下滑 = 上一个（ViewPager2 垂直方向）；
- 单击暂停/播放，双击点赞；
- 每条视频右侧有 **点赞 / 收藏 / 不喜欢** 三个操作；
- **点赞 + 不喜欢** 实时影响后续推荐（本地算法，不上传服务器）；
- 推荐效果可在「设置 → 推荐偏好」人工调整（探索率、惩罚力度、是否屏蔽等）；
- **标签基于全量历史语料抽取**，保证跨视频可比；
- **多层预加载**，滑动切换无卡顿。

---

## 二、视频源选型评估

现有两个成人视频源：

| 维度 | 视频分类（91mman） | 分分钟（91porny / porny） |
|---|---|---|
| 列表入口 | `loadMman9VideoByCategory(category, viewType, page, m, …)` **分类浏览+分页** | `searchPornyVideos(keywords, page, …)` **只能关键词搜索** |
| 可连续拉取 | ✅ 12 个分类可轮询，天然无限流 | ❌ 需关键词种子，无法开环拉全量 |
| 封面/标题/时长 | `imgUrl`/`title`/`duration`/`info` 齐全 | 同结构（V9MmanItem） |
| 作者维度 | `loadMman9authorVideos(uid, type, page)` | `loadPornyAuthorVideos(authorId, page)` |
| 播放解析 | `loadMman9VideoUrl(viewKey)` → `VideoResult.videoUrl` | `loadPornyVideoUrl(viewKey)` → 同上 |

**结论：主源 = 视频分类（91mman）。** 分类浏览+分页是唯一能支撑开环无限流的方式；12 个分类即极佳候选池种子：`Category.CATEGORY_DEFAULT_91PORN_VALUE = {"index","watch","hot","rp","long","md","tf","mf","rf","top","top1","hd"}`（主页/最近更新/当前最热/最近得分/10分钟以上/本月讨论/本月收藏/收藏最多/最近加精/本月最热/上月最热/高清）。分分钟作为后续可选混合源。

**风险**：需用户在设置配置站点地址（同 `haveNotSetV9pronAddress()`）；feed 首屏未配置显示引导，不崩。

---

## 三、交互与 UI 设计

**容器**：`RecommendFeedActivity` + `ViewPager2`（`ORIENTATION_VERTICAL`）。每页 `RecommendPageFragment`，生命周期管理参考现有横向 play ViewPager。

每页布局：
```
┌─────────────────────────┐
│      [视频画面]           │  ← JZVideoPlayerStandard（停稳即播）
│                         │
│              ❤ 点赞     │  ← 右侧竖排操作栏
│              ★ 收藏     │
│              🚫 不喜欢   │
│                         │
│ ─────────────────────── │
│ @作者名                  │  ← 底部信息（半透明渐变）
│ 标题 / 时长 / 标签        │
└─────────────────────────┘
```
**手势**：滑动切条（原生）；单击暂停/继续；双击点赞（爱心动画）；右侧按钮点赞/收藏/不喜欢。

**播放器生命周期**：`onPageSelected` 时 `releaseAllVideos()` 旧页、`setUp`+播新页；仅预加载下 1 条；离开 `JZVideoPlayer.releaseAllVideos()`。

---

## 四、数据与存储

**复用**：`V9MmanItem`（viewKey/title/imgUrl/duration/info/source/isLocalFavorite）、`VideoResult`（videoUrl/authorId/ownerId/ownerName/videoName/thumbImgUrl/addDate）、GreenDAO（`V9MmanItemDao`/`CategoryDao`）、`AppPreferencesHelper`（SharedPreferences）、本地收藏 `loadLocalFavoriteItems()`。

**新增 1：交互记录 `VideoInteraction`**（GreenDAO 实体）
| 字段 | 说明 |
|---|---|
| viewKey (PK) | 视频唯一 id |
| source / authorId / category | 特征 |
| tags | 关键词（来自统一词表，逗号分隔） |
| liked / disliked / favorited | 反馈 |
| watched / watchMs / completed | 观看行为 |
| ts | 时间戳（衰减用） |

**新增 2：标签词表 `TagVocabulary`**（运行时加载，**主词典随 App 打包**，见 5.8）
| 字段 | 说明 |
|---|---|
| tag | 关键词 |
| df | 出现在多少条语料视频中（文档频率） |
| idf | `log(N/df)` 逆文档频率（越稀有越有区分度） |
| totalDocs | 语料视频总数 N |

**新增 3：推荐画像 + 可调参数**（SharedPreferences JSON）
- `reco_profile`：特征→权重 map；
- `reco_params`：可调参数（见 5.6）。

---

## 五、本地推荐算法（核心）

纯本地、可解释、可人工调参的内容推荐（类 LightFM / 内容加权）。

### 5.1 特征抽取（基于全量历史语料词表）
每条候选视频的特征 = **authorId + category + tags（来自统一词表）+ source**。

- **authorId**：`VideoResult.authorId`，精确匹配（最硬信号）；
- **category**：所属 12 分类之一（结构化，最硬）；
- **tags**：对 `title`+`info` 做轻量分词（正则/词典切词），**只保留命中 `TagVocabulary` 的词**（保证跨视频同一含义、可比较）；
- **source**：视频分类/分分钟。

### 5.2 用户画像（加权向量）
`profile[feature] = weight`。反馈时：
- 点赞：`profile[f] += likeBoost`（该视频全部特征 f）；
- 不喜欢：`profile[f] -= dislikePenalty`；
- 收藏：`profile[f] += favoriteBoost`（>点赞）。
权重带时间衰减（半衰期 `decayDays`，默认 30 天）。

### 5.3 计分函数
```
score(v) = baseScore(v)                              # 来源热度/新鲜度
         + Σ_f  profile[f] × presence(v, f) × idf(f)  # 个性化（idf 加权稀有标签）
         + recencyBias × freshness(v)
         + exploreNoise
```
`presence(v,f)`：authorId 精确相等；category/tag 包含即算；`idf(f)` 来自词表，越稀有区分度越高、加权越大。

### 5.4 探索 / 利用（ε-greedy）
概率 `explorationRate`（默认 0.15）从**未交互过的候选**随机取一条（探索，防茧房）；否则取分数最高（利用）。**非随机**：候选池来自分类种子，85% 走分数排序。

### 5.5 冷启动
无交互时按多分类轮询拉取（index/hot/recent 混排 + 轻量洗牌），保证开屏多样。

### 5.6 可调参数表
| 参数 | 默认 | 说明 |
|---|---|---|
| `likeBoost` | +3 | 点赞特征加权 |
| `dislikePenalty` | -5 | 不喜欢特征加权 |
| `favoriteBoost` | +4 | 收藏加权（>点赞） |
| `explorationRate` | 0.15 | 探索比例（0=纯利用，1=纯随机） |
| `recencyBias` | 1.0 | 新鲜度偏置 |
| `decayDays` | 30 | 权重半衰期 |
| `enableDislikeFilter` | **false（已确认）** | 不喜欢**仅降低权重**，不屏蔽作者/视频（默认持续曝光，靠降权沉底） |
| `useCategoryFeature` / `useTagFeature` | true / true | 是否启用分类/标签特征 |
| `prefetchAhead` | 3 | 预加载条数 |
| `prefetchWifiOnly` | **false（已确认）** | 预加载**不限网络**（Wi-Fi/流量都预热，不区分） |

「设置 → 推荐偏好」提供：探索率滑块、不喜欢屏蔽开关、重置推荐（清空 profile + interaction + 重建词表）。

### 5.7 反馈闭环
点赞/不喜欢 → 即时更新 `profile` → 重排候选队列（不喜欢项下沉/剔除，相似项提前）→ 下一步滑动即感受变化。

### 5.8 标签词表：开发期全量语料抽取 + 打包进本地规则（本次重点①）

> 问题：纯靠用户**本地历史视频太少**，撑不起丰富词表 → 标签信号稀疏、不可比。
> 方案：词表在**开发期**就从源站**全量语料**构建，作为**本地规则文件随 App 打包**；运行时直接加载，用户本地历史只做**增量微调**。

**开发期流水线（`build-tools/gen_reco_tags.py`，在可访问源站的开发机运行）**：
1. **拉取全量语料**：遍历 12 个分类（多页深翻）+ 热门作者页，用现有解析 `ParseV9MmanVideo` 取每条 `title`+`info`（语料规模 = 源站全量，与用户本地历史无关）；
2. **中文分词**：用 jieba 对全量标题分词，过滤停用词/单字；
3. **统计 df / idf**：`df`=词出现文档数，`idf = log(N/df)`；过滤保留 `2 ≤ df ≤ N×0.3` 的稳定词 → 得到 `tag→idf` 词典；
4. **序列化打包**：写出 `assets/reco/tag_dictionary.json`（含 tag、idf、df、N），随 App 打包进安装包。

**运行时**：
- 首次启动读取**打包词典**载入 `TagVocabulary` —— **开箱即有丰富、稳定的标签空间**（规模由源站全量决定，数千视频级）；
- 给视频打标签 = 分词后**只保留命中打包词表的词**（统一口径，避免同词多写法）；
- 计分用 `idf(f)` 加权：越稀有标签区分度越高（「高清」太常见→低权；具体类型词→高权）；
- **词典不依赖本地历史语料**（用户本地无历史数据，故词典完全来自开发期打包，运行时不再扫描本地视频构建词表）；
- 用户产生的**点赞/不喜欢/收藏**仍会更新 `profile` 权重向量（这是推荐信号本身，与词典无关）；
- 「重置推荐」：清空 `profile` + `VideoInteraction`，但**保留打包基础词典**（不会退回稀疏状态）。

**优点**：词表质量由源站全量语料保证，与用户本地历史多少无关；idf 区分度准确；冷启动即高质。

> 注：语料拉取需源站可达（开发机已配置站点地址/代理）。脚本随 `build-tools/` 提供，运行一次产出 JSON 即可；后续源站词频变化可重跑更新。

---

## 六、推荐流加载与预加载（本次重点②：防卡顿）

### 6.1 候选队列与拉取
`RecommendRepository`（单例）：维护按 `score` 排好的候选队列；剩余 ≤ `prefetchAhead` 时，从主源拉下一页（多分类轮询/探索随机分类）→ 解析 `V9MmanItem` → 打标签（5.8）→ 计分 → 合并去重（按 viewKey）→ 重排入队。已不喜欢视频不入队。

### 6.2 预加载流水线（避免滚动卡顿）
为队列中**接下来 `prefetchAhead`（默认 3）条**逐级预热，确保上滑瞬间可播：

| 阶段 | 动作 | 目的 |
|---|---|---|
| 1. 元数据 | 已在队列（V9MmanItem 就绪） | 立即可渲染卡片 |
| 2. 封面 | `Glide.preload(cover)` 预加载下 1–2 条封面 | 滑到即显图，无白屏 |
| 3. 解析 URL | 后台调 `loadMman9VideoUrl(viewKey)` → 缓存 `VideoResult` | 省去滑到才解析的延迟 |
| 4. 视频预热 | 经 videocache 代理 `HttpProxyCacheServer.prepare(videoUrl)` 预缓存若干 MB | 滑到即播，零缓冲 |

- **生命周期**：仅保留「当前 + 下 1 条」完整 prepared；其余 `releaseAllVideos()` 释放，控内存；
- **省流量**：`prefetchWifiOnly=true` 时，阶段 3/4 仅 Wi-Fi 下执行（阶段 1/2 封面始终预载）；
- **封面占位**：视频未起播前先显示封面图，停稳即 `setUp` 播放，观感无缝；
- **失败兜底**：预热失败不阻塞，滑到时按正常流程解析播放（沿用 `BasePlayVideo` 重试）。

### 6.3 去重
维护已展示 viewKey 集合，跨页不重复。

---

## 七、实现要点与风险

- **播放器生命周期**：参考现有横向 play ViewPager 的 `onPageSelected` 管理；只保留当前+下 1 条。
- **预加载带宽/流量**：阶段 3/4 默认仅 Wi-Fi；可在设置关闭。
- **词表质量**：初始词表来自本地历史，若历史少则词表稀疏→标签信号弱，靠 authorId+category 两个硬信号 + 探索兜底；随使用变准。
- **隐私**：全部本地，无上传。
- **源地址未配置**：feed 首屏检测 `haveNotSetV9pronAddress()`，未配置显示引导。
- **HLS/缓存复用**：继续复用 `AppCacheUtils` + videocache 代理。

---

## 八、实施步骤

0. **开发期语料抽取（前置）**：`build-tools/gen_reco_tags.py` 在开发机拉全量分类/作者页 → jieba 分词 → 算 df/idf → 产出 `assets/reco/tag_dictionary.json` 打包进 App（5.8）。
1. **数据层**：`VideoInteraction` 实体+DAO；`TagVocabulary`（首次从打包 JSON 载入，支持本地增量）；`AppPreferencesHelper` 增 reco 参数。
2. **算法层**：`RecommendProfile`（画像）、`RecommendScorer`（计分+idf+ε-greedy）、`RecommendRepository`（队列+拉取+去重）。
3. **预加载层**：`RecommendPrefetcher`（封面 Glide 预载 / URL 预解析 / videocache.prepare 预热，6.2）。
4. **UI 层**：`RecommendFeedActivity` + `RecommendPageFragment`（ViewPager2 垂直）+ 操作栏 + 手势 + 预加载联动；在 `MainActivity` 底部导航**新增第 4 个 Tab「推荐」**，于 `doOnTabSelected` 增加 `case 3` 启动 `RecommendFeedActivity`（参照现有 0/1/2 三个 Tab 的接线方式）。
5. **调参 UI**：「设置 → 推荐偏好」（滑块/开关/重置）。
6. **联调**：接入视频分类源，验证滑动流畅、预加载生效无卡顿、点赞/不喜欢即时影响、参数调节生效。

---

## 九、已确认决策（用户拍板）

| # | 问题 | 决策 |
|---|---|---|
| 1 | 主源 | **视频分类（91mman）**（12 分类开环无限流） |
| 2 | 不喜欢处理 | **仅降低权重，不屏蔽作者/视频**（持续曝光靠降权沉底） |
| 3 | 默认参数 | 采用方案默认值（探索率 0.15 / 点赞 +3 / 不喜欢 -5 / 收藏 +4 / 新鲜度 1.0 / 衰减 30 天 / prefetchAhead 3） |
| 4 | 入口 | **首页底部导航新增第 4 个 Tab「推荐」** |
| 5 | 预加载网络 | **不限网络**（Wi-Fi/流量都预热，不区分） |
| 6 | 历史语料 | **不读本地历史**；词表完全来自开发期全量语料抽取并打包，运行时本地无数据也不影响 |

→ 决策已全部明确，**方案定稿，可进入实现**（第八节 6 步）。
