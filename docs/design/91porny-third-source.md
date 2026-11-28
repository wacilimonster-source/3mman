# 91porny 作为第三可选来源：产品与技术设计（v1）

> 状态：已评审待实现　　作者：lead　　日期：2026-11-28
> 背景：主站（www.91porn.com）对未通过邮箱验证的账号一律 302 到 `confirm_email.php`，
> 播放无法直连；91porny 无需账号即可拿到 mp4/m3u8。列表侧需给用户一个"能直接播"的来源。

## 1. 站点能力盘点（2026-11-28 实测）

| 页面 | 条目/页 | 地址 | 结构要点 |
|---|---|---|---|
| 首页 | **48** | `/`、`/?page=N` | `div.colVideoList > div.video-elem`；链接 `/video/view/<24hex>`；封面 `div.img[style=background-image]`；时长 `small.layer`；标题 `a.title`；作者 `div.text-muted a`；「18小时前 \| 18.8万次播放」 |
| 高清原创 | **24** | `/video`、`/video/category/hd/N`（实测可翻到 17226 页） | 同上，另有 `small.vip-layer`「高清」角标；链接 `/video/viewhd/<24hex>`；封面 `<img class="img" src="//int.ucloud165.xyz/thumb/….jpg">` |
| 蝌蚪（长视频） | **24** | `/videos`、`/videos?page=N` | 链接 `/videos/view/<id>/<slug>/`；封面色 `int.qiniuyun*.xyz`；时长可达 56:37；只有「3年前 \| 5.7万次播放」，无作者 |
| 搜索 | **24** | `/search?keywords=<kw>&page=N`（空关键词返回 404 页） | 与高清同构 |
| 标签云 | — | `/tags` | 143 KB 标签页，本轮不做 |
| 作者 | 24 | `/author/<name>?page=N`、`/dashen/<name>?page=N` | 已有实现（`loadPornyAuthorVideos`） |
| 播放 | — | `/video/view/<hex>`、`/video/viewhd/<hex>`、`/videos/view/<id>/<slug>/`、`/vod/view/<key>` | 已有实现（`loadPornyVideoUrl` 按前缀分发） |

排序：`/video?sort=time` 与 `/video` 内容完全一致（无排序参数），故**不提供排序**，只提供分页。

## 2. 产品设计

### 2.1 来源选择器（首页顶部胶囊）

现有两项：`主站`、`4源`。新增第三项：

```
┌ 选择视频来源 ─────────────────┐
│ ✓ 主站      91porn.com       │
│   4源       ——               │
│   91porny   免账号直连播放     │  ← 新增
└──────────────────────────────┘
```

- 选中后胶囊文案 `91porny`，记忆到独立 SharedPreferences（现有 `SOURCE_PREFS`）。
- 切换来源 → 列表回到第 1 页、保留滚动位置到顶部、Toast 提示来源名。

### 2.2 91porny 来源的首页结构

选中 91porny 后，原来的 5 个分类 Tab **替换**为该来源的 3 个 Tab（其余来源保持原样）：

| Tab | 含义 | 接口 | 每页 |
|---|---|---|---|
| 最新 | 混合最新 | `/?page=N` | 48 |
| 高清 | 高清原创 | `/video/category/hd/N` | 24 |
| 蝌蚪 | 长视频 | `/videos?page=N` | 24 |

- 分页：上拉加载下一页，`page=1` 起步，失败给「加载失败 · 点击重试」。
- 下拉刷新：重置到第 1 页并清缓存。
- 卡片与主站一致（封面/时长/标题/作者/来源角标），角标文案 `91porny`；高清 Tab 的条目额外显示 `高清` 小标。
- 播放：直接走 `loadPornyVideoUrl`（已有），无账号依赖。
- 收藏/历史/下载/作者收藏：沿用现有 porny 逻辑（`IS_LOCAL_FAVORITE`、`VideoResult`、作者键前缀）。

### 2.3 搜索 Tab

不变（已是 91porny 搜索）。若来源为 91porny，搜索页顶部提示「当前来源 91porny」。

### 2.4 空态 / 错误态

| 场景 | 表现 |
|---|---|
| 网络失败 | 列表区显示「加载失败 · 点击重试」，重试只重请求当前页 |
| 返回 0 条 | 「这一页没有内容了」+「回到顶部」按钮 |
| 来源不可达（域名被墙） | 首次失败即提示「91porny 当前不可达，可在 设置→91porny 地址 更换域名」，并给「切回主站」按钮 |

### 2.5 不做的部分（明确范围）

- `/tags` 标签浏览、`/article/*` 文章页
- 排序参数（站点不支持）
- 主页 48 条与高清 24 条的"合并去重"（两个 Tab 各自独立）

## 3. 技术设计

### 3.1 接口层（`PornyServiceApi`）

```java
@Headers({"Domain-Name: " + Api.PORNY_DOMAIN_NAME})
@GET("/")                        Observable<String> listHome(@Query("page") int page, @Header("User-Agent") String ua);
@GET("/video/category/hd/{page}") Observable<String> listHd(@Path("page") int page, @Header("User-Agent") String ua);
@GET("/videos")                  Observable<String> listTadpole(@Query("page") int page, @Header("User-Agent") String ua);
```

> 复用现有 `HeaderUtils.getPornyHeader(addressHelper)`，与搜索/作者页一致。

### 3.2 解析层（`Parse91PornyVideo`）

新增 `parseList(String html, PornyListKind kind)`：
- 容器 `div.colVideoList`，逐块取
- 链接：`a.display[href]` → `/video/viewhd/<hex>` → viewKey `hd/<hex>`；`/videos/view/<id>/<slug>` → `videos/<id>/<slug>`；`/video/view/<hex>` → 裸 `<hex>`
- 标题：`a.title` 文本（HTML 实体反转义）
- 封面：`img.img[src]` 或 `div.img[style=background-image:url(...)]`，`//` 开头补 `https:`
- 时长：`small.layer`（`HH:MM:SS`/`MM:SS`）
- 作者：`div.text-muted a[href^=/author/]` → 作者键（复用 `normalizePathKey`）
- 高清角标：`small.vip-layer` 存在即 `HD`
- 时间/播放量：`div.text-muted` 第二段（只做展示字段，不入库）

`viewKey` 与播放分发（`loadPornyVideoUrl`）：新增 `isHdViewKey("hd/")` → `getHdVideoPlayPage(hex)`（`/video/viewhd/<hex>`）。

### 3.3 数据层

- `ApiHelper` / `AppApiHelper` / `DataManager`：新增
  `Observable<BaseResult<List<V9MmanItem>>> loadPornyList(PornyListKind kind, int page, boolean cleanCache)`
- RxCache key：`"porny-" + kind.name() + "-" + page`，`EvictProvider(cleanCache)`；与主站缓存互不影响。
- 首次加载失败不写缓存。

### 3.4 UI 层

- `BaseMainFragment`：
  - `SOURCE_PORNY = 2` 写入 `SOURCE_PREFS`（`PREF_SOURCE`）
  - `applySourceVisibility()` 增加 porny 分支：隐藏主站 5 个分类 Tab，显示 porny 3 个 Tab
  - 空/错误态复用现有 `LoadViewHelper`
- `V91MmanAdapter`：角标逻辑加 porny 分支（`Parse91PornyVideo.SOURCE`），高清 Tab 条目加 `高清` 小标
- 意图：切 Tab 只做一次请求（`page=1`），切回来若已有数据不重复请求

## 4. 验收标准（真机可测）

1. 来源面板出现第三项「91porny」，选中后胶囊文案变为 `91porny`，杀进程重启后仍保持
2. 91porny 来源下 Tab 变为「最新 / 高清 / 蝌蚪」，三个 Tab 都能拉到真实数据（条数 48 / 24 / 24）
3. 上拉加载第 2 页成功（最新 48→96 条，高清 24→48 条），第 2 页内容与第 1 页不重复
4. 任意一个 91porny 条目点击 → 原生播放器起播（无需账号、无邮箱验证提示）
5. 高清 Tab 的条目卡片显示 `高清` 小标与 `91porny` 来源角标
6. 收藏 / 历史 / 下载在该来源下均可用（收藏后 DB `IS_LOCAL_FAVORITE=1`）
7. 断网时显示「加载失败 · 点击重试」，恢复后重试成功
8. 切回「主站」来源：Tab 恢复 5 个分类，列表回到主站内容（缓存不串）
9. 单测：解析器新增用例（3 种结构 + 空页 + 畸形 HTML）全绿；lint 0 error
