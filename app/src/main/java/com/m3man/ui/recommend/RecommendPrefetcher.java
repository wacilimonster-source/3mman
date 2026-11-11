package com.m3man.ui.recommend;

import android.content.Context;
import android.text.TextUtils;

import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.VideoResult;
import com.m3man.data.reco.RecoCandidate;
import com.m3man.utils.AppLog;
import com.m3man.utils.PlayUiPrefs;
import com.m3man.utils.GlideApp;
import com.orhanobut.logger.Logger;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;

/**
 * 推荐流预加载流水线。
 * <p>
 * 一条视频从「列表项」到「能秒开」要经过三步，全部提前做掉：
 * <ol>
 *   <li><b>元数据</b>：请求播放页 HTML 解析出真实播放地址（最耗时的一步，通常 300~1500ms）。</li>
 *   <li><b>封面</b>：Glide 预热磁盘/内存缓存，滑到时封面不闪白。</li>
 *   <li><b>视频首包</b>：通过 videocache 代理拉一小段字节，让本地缓存文件先建立，
 *       播放器 prepare 时直接命中本地。</li>
 * </ol>
 * 按用户决策 #5，不区分 WiFi / 移动网络，始终预加载。
 *
 * @author 3mman
 */
public class RecommendPrefetcher {

    private static final String TAG = "RecoPrefetch";

    /** 首包预热字节数，够播放器起播即可，避免浪费流量 */
    private static final int WARM_BYTES = 512 * 1024;
    /** 解析结果缓存上限 */
    private static final int MAX_CACHE = 60;
    /** 只对紧邻的 N 条做字节级预热（解析本身按 prefetchAhead 做） */
    private static final int WARM_AHEAD = 1;

    /** 解析回调 */
    public interface ResolveCallback {
        /**
         * @param viewKey  视频 key
         * @param playUrl  可直接交给播放器的地址（已过 videocache 代理）
         * @param item     已写回 VideoResult 的实体
         */
        void onResolved(String viewKey, String playUrl, V9MmanItem item);

        void onFailed(String viewKey, String message);
    }

    private final Context appContext;
    private final DataManager dataManager;

    private final CompositeDisposable disposables = new CompositeDisposable();
    /** viewKey -> 播放地址（原始地址，播放时再包代理） */
    private final LinkedHashMap<String, String> urlCache = new LinkedHashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    private final Set<String> warmed = new HashSet<>();
    /**
     * viewKey -> 等待该 key 解析结果的回调列表。
     * 同一条视频可能被「起播」和「下载」同时等待，所以是一对多，
     * 早期用单值 Map 会导致后来者把前面的回调顶掉（表现为点了下载后视频卡住不播）。
     */
    private final Map<String, List<ResolveCallback>> waiting = new LinkedHashMap<>();

    private final ExecutorService warmExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean released = false;

    // ==================== T04：预取调度 ====================

    /**
     * T04：负缓存——viewKey → 失败封禁截止时间（elapsedRealtime）。
     * 分级 TTL（方案 §5-T04）：断网/超时/5xx 短 TTL（5s，站点恢复即可重试）；
     * 解析无结果（确定不可用）保留长 TTL（10min）；403 失效只允许一次快速重解析。
     * LRU ≤120，满时淘汰最旧，并顺带清除过期项（不再整体清空）。
     */
    private static final long NEG_TTL_NETWORK_MS = 5_000L;
    private static final long NEG_TTL_NO_RESULT_MS = 10L * 60L * 1000L;
    private static final long NEG_TTL_FORBIDDEN_MS = 1_000L;
    private static final int MAX_NEG_CACHE = 120;
    private final LinkedHashMap<String, Long> failedUntil = new LinkedHashMap<>();
    /** 403 失效计数：每个 key 只允许一次快速重解析，第二次按解析失败长封禁 */
    private final Set<String> forbiddenReparseUsed = new HashSet<>();
    /** 后台解析任务表：viewKey → Disposable（翻页出窗可取消；前台/下载解析不进这张表） */
    private final Map<String, io.reactivex.disposables.Disposable> backgroundParses =
            new ConcurrentHashMap<>();
    /** 当前窗口（current + 后续 ahead 条的 viewKey） */
    private final Set<String> windowKeys = new HashSet<>();
    /** warm 等待队列计数：同时 1 个执行 + 最多 1 条待队列 */
    private final java.util.concurrent.atomic.AtomicInteger warmPending =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 活动预热连接句柄：release/翻页立即断开 */
    private volatile HttpURLConnection warmConnection;

    public RecommendPrefetcher(Context context, DataManager dataManager) {
        this.appContext = context.getApplicationContext();
        this.dataManager = dataManager;
    }

    /** 已解析出的原始播放地址，没有返回 null */
    public String peekRawUrl(String viewKey) {
        if (TextUtils.isEmpty(viewKey)) {
            return null;
        }
        synchronized (urlCache) {
            return urlCache.get(viewKey);
        }
    }

    /**
     * M72：判断 9mman 直链的时效签名是否已过期。
     * M152：站点已把签名格式由旧 secure=&lt;base64&gt;,&lt;unix秒&gt; 切换为新 st=&lt;token&gt;&amp;e=&lt;unix秒&gt;&amp;f=...，
     * 旧检查只认 secure=，对新格式一律放行，导致 DB 缓存直链过期 403（2026-09-03 用户日志实证：
     * 缓存命中条目 e= 过期 6 天仍被下发）。此处在兼容旧格式的同时识别新格式的 e= 参数。
     * 过期的直链交给 videocache 本地代理时，代理建流失败会导致播放器连不上
     * 127.0.0.1 端口而无限转圈（实测日志：Unable to connect to http://127.0.0.1:44183）。
     *
     * @return true=确定过期；false=无签名(其它源)或仍在有效期内
     */
    public static boolean isSecureUrlExpired(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            boolean pornyCdn = host != null
                    && (host.contains("jiuse") || host.contains("cdnkdjs"));
            if (pornyCdn) {
                // 91porny 主源 HLS：/s/<过期时间戳>/<签名>/hls/<id>/index.m3u8。
                // 该时间戳是 CDN 的过期桶（实测下一整点），过期后 403；
                // 提前 5 分钟判过期，避免起播途中失效。
                String path = uri.getPath();
                if (path != null) {
                    int s = path.indexOf("/s/");
                    if (s >= 0) {
                        int start = s + 3;
                        int end = start;
                        while (end < path.length() && Character.isDigit(path.charAt(end))) {
                            end++;
                        }
                        if (end > start) {
                            long expireSec = Long.parseLong(path.substring(start, end));
                            if (expireSec >= 1_000_000_000L) {
                                return nowSec >= expireSec - 300;
                            }
                        }
                    }
                }
                // 91porny 精品/蝌蚪：?t=<签发秒>&m=<签名>，实测约 3 分钟窗口。
                // t 是签发时间不是过期时间，提前按 150s 判过期，宁可多解析一次也不播错误内容。
                String pornyQuery = uri.getRawQuery();
                if (pornyQuery != null) {
                    for (String p : pornyQuery.split("&")) {
                        if (p.startsWith("t=")) {
                            long issuedSec = Long.parseLong(p.substring(2));
                            if (issuedSec >= 1_000_000_000L) {
                                return nowSec >= issuedSec + 150;
                            }
                        }
                    }
                }
            }
            String query = uri.getRawQuery();
            if (query == null) {
                return false;
            }
            for (String p : query.split("&")) {
                if (p.startsWith("secure=")) {
                    // 旧格式：secure=<base64>%3D%3D,1787513682 或 <base64>==,1787513682（可能已 URL 编码）
                    String val = p.substring("secure=".length());
                    int comma = val.lastIndexOf(',');
                    if (comma < 0 || comma + 1 >= val.length()) {
                        return false;
                    }
                    long expireSec = Long.parseLong(val.substring(comma + 1));
                    // 提前 5 分钟视为过期，避免起播途中跨过有效期
                    return nowSec >= expireSec - 300;
                }
                if (p.startsWith("e=")) {
                    // M152 新格式：st=<token>&e=<unix秒>&f=...；e= 为整型 unix 秒，
                    // 同样提前 5 分钟视为过期；非法值（非数字）继续 fail-open 走原逻辑
                    long expireSec = Long.parseLong(p.substring(2));
                    return nowSec >= expireSec - 300;
                }
            }
        } catch (NumberFormatException ignored) {
            // 签名参数不是数字，判不了时效，当未过期走原逻辑
        } catch (Throwable ignored) {
            // 解析不了就当没过期，走原有逻辑
        }
        return false;
    }

    /**
     * 把普通 MP4 包成本地代理地址；HLS m3u8 必须保留原始地址。
     * videocache 2.7.1 不能为 m3u8 内的相对分片补全基地址，会把 index0.ts
     * 直接交给播放器，最终触发 "no protocol"。
     */
    public String toPlayUrl(String rawUrl) {
        if (TextUtils.isEmpty(rawUrl) || isHlsUrl(rawUrl)) {
            return rawUrl;
        }
        try {
            String proxy = dataManager.getVideoCacheProxyUrl(rawUrl);
            return TextUtils.isEmpty(proxy) ? rawUrl : proxy;
        } catch (Exception e) {
            return rawUrl;
        }
    }

    private static boolean isHlsUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase(java.util.Locale.US);
        int query = lower.indexOf('?');
        if (query >= 0) {
            lower = lower.substring(0, query);
        }
        return lower.endsWith(".m3u8") || lower.contains(".m3u8/");
    }

    /**
     * 立刻要播：命中内存缓存就同步回调，否则发起解析并在完成后回调。
     * <p>
     * M98：本方法可能在主线程被调用——已移除原先的同步 DB 查库
     * （findV9MmanItemByViewKey 是 greenDAO 磁盘读，会卡主线程）。
     * resolveNow 只做「内存缓存命中判断 + 负缓存 fail-fast」，
     * 未命中直接挂回调走 {@link #parse} 异步链；DB 缓存直链的检查
     * 移到 parse 链起始的 IO 线程执行。调用方（起播 / 下载）本就按
     * 异步回调模式编写（onResolved/onFailed），无需额外适配。
     */
    public void resolveNow(final RecoCandidate candidate, final ResolveCallback callback) {
        if (candidate == null || candidate.item == null || TextUtils.isEmpty(candidate.viewKey())) {
            if (callback != null) {
                callback.onFailed(null, "视频信息不完整");
            }
            return;
        }
        String viewKey = candidate.viewKey();
        String cached = peekRawUrl(viewKey);
        if (!TextUtils.isEmpty(cached)) {
            // M72：内存缓存命中但签名已过期 → 丢弃走重新解析
            if (isSecureUrlExpired(cached)) {
                AppLog.i(TAG, "缓存直链已过期，重新解析 viewKey=" + viewKey);
                synchronized (urlCache) {
                    urlCache.remove(viewKey);
                }
            } else {
                if (callback != null) {
                    callback.onResolved(viewKey, toPlayUrl(cached), candidate.item);
                }
                warm(cached);
                return;
            }
        }
        // M98/T04：负缓存未到期直接快速失败，不打网络请求
        if (isNegativeCached(viewKey)) {
            AppLog.i(TAG, "负缓存命中，跳过解析 viewKey=" + viewKey);
            if (callback != null) {
                callback.onFailed(viewKey, "解析视频链接失败了");
            }
            return;
        }
        if (callback != null) {
            synchronized (waiting) {
                List<ResolveCallback> list = waiting.get(viewKey);
                if (list == null) {
                    list = new ArrayList<>(2);
                    waiting.put(viewKey, list);
                }
                list.add(callback);
            }
        }
        parse(candidate, true);
    }

    /** 取出并清空某个 key 上挂着的全部等待回调 */
    private List<ResolveCallback> drainWaiting(String viewKey) {
        synchronized (waiting) {
            List<ResolveCallback> list = waiting.remove(viewKey);
            return list == null ? Collections.<ResolveCallback>emptyList() : list;
        }
    }

    /**
     * 预加载：从 startIndex 开始往后 ahead 条。
     */
    public void prefetch(List<RecoCandidate> list, int startIndex, int ahead) {
        // L-fix：设置「推荐页预加载」关闭时直接短路，不解析、不预热、不耗流量
        if (appContext != null && !PlayUiPrefs.isRecoPrefetchEnabled(appContext)) {
            return;
        }
        if (released || list == null || ahead <= 0) {
            return;
        }
        int warmCount = 0;
        for (int i = startIndex; i < list.size() && i < startIndex + ahead; i++) {
            RecoCandidate c = list.get(i);
            if (c == null || c.item == null || TextUtils.isEmpty(c.viewKey())) {
                continue;
            }
            // 1. 封面
            preloadCover(c.item.getImgUrl());
            // 2. 播放地址
            String cached = peekRawUrl(c.viewKey());
            if (TextUtils.isEmpty(cached)) {
                parse(c, false);
            } else if (warmCount < WARM_AHEAD) {
                // 3. 首包
                warm(cached);
                warmCount++;
            }
        }
    }

    private void preloadCover(String imgUrl) {
        if (TextUtils.isEmpty(imgUrl) || released) {
            return;
        }
        try {
            if (com.m3man.perf.PerfFlags.coverRequestV2) {
                // T03：与展示侧共享同一 spec（String model + override + SmartCoverTransformation），
                // 预取的解码结果能命中展示请求的 resource cache——旧 load(String).preload()
                // 与展示 load(Uri) 缓存键不一致，预取白做。
                com.m3man.utils.CoverRequests.Spec spec =
                        com.m3man.utils.CoverRequests.Spec.card(imgUrl);
                com.m3man.utils.GlideApp.with(appContext)
                        .load(imgUrl)
                        .apply(new com.bumptech.glide.request.RequestOptions()
                                .override(spec.widthPx, spec.heightPx)
                                .transform(new com.m3man.utils.SmartCoverTransformation(imgUrl)))
                        .preload();
            } else {
                GlideApp.with(appContext).load(imgUrl).preload();
            }
        } catch (Exception e) {
            Logger.t(TAG).d("preload cover failed: " + e.getMessage());
        }
    }

    private void parse(final RecoCandidate candidate, final boolean urgent) {
        final String viewKey = candidate.viewKey();
        // M98/T04：负缓存 fail-fast——分级 TTL 内失败过的 key 不再发起网络解析
        if (isNegativeCached(viewKey)) {
            AppLog.i(TAG, "负缓存命中(parse)，跳过 viewKey=" + viewKey);
            for (ResolveCallback cb : drainWaiting(viewKey)) {
                try {
                    cb.onFailed(viewKey, "解析视频链接失败了");
                } catch (Exception e) {
                    Logger.t(TAG).d("failed callback error: " + e.getMessage());
                }
            }
            return;
        }
        synchronized (inFlight) {
            if (released || inFlight.contains(viewKey)) {
                return;
            }
            inFlight.add(viewKey);
        }
        // M60：hex viewkey（20位十六进制）= 91porny 视频，走 91porny 解析器；其余走 9mman。
        // M62：9mman 候选的 viewKey 恒带 "viewkey=" 前缀（extractViewKey 契约），必须完整传给
        // loadMman9VideoUrl（其入口已做归一化兜底）；v1.0.60 在此剥前缀导致推荐流 9mman 解析必败。
        // 只有裸 key 且恰为纯 hex 才判定为 91porny 源。
        final boolean isMmanPrefixed = viewKey != null && viewKey.startsWith("viewkey=");
        final boolean isHexViewKey = !isMmanPrefixed && viewKey != null && viewKey.matches("[0-9a-fA-F]{16,32}");
        final Observable<VideoResult> parseObs = isHexViewKey
                ? dataManager.loadPornyVideoUrl(viewKey)
                : dataManager.loadMman9VideoUrl(viewKey);
        disposables.add(Observable
                // M98：链路起始（IO 线程）先查 DB 缓存的 raw url，命中且未过期直接回调，
                // 不再发起详情页网络请求（resolveNow 已不再主线程查库，DB 检查移到这里）
                .defer(new java.util.concurrent.Callable<Observable<VideoResult>>() {
                    @Override
                    public Observable<VideoResult> call() {
                        VideoResult dbCached = loadDbCachedResult(viewKey);
                        if (dbCached != null) {
                            AppLog.i(TAG, "DB缓存直链命中，跳过网络解析 viewKey=" + viewKey);
                            put(viewKey, dbCached.getVideoUrl());
                            return Observable.just(dbCached);
                        }
                        return parseObs;
                    }
                })
                .subscribeOn(Schedulers.io())
                .map(videoResult -> {
                    if (videoResult == null || TextUtils.isEmpty(videoResult.getVideoUrl())) {
                        if (!isHexViewKey && videoResult != null
                                && VideoResult.OUT_OF_WATCH_TIMES.equals(videoResult.getId())) {
                            dataManager.resetMman91VideoWatchTime(true);
                        }
                        AppLog.e("RecoPrefetcher", "推荐解析失败(空结果) viewKey=" + viewKey
                                + " 源=" + (isHexViewKey ? "91porny" : "9mman")
                                + " 代理=" + (dataManager.isOpenHttpProxy()
                                        ? dataManager.getProxyIpAddress() + ":" + dataManager.getProxyPort() : "关"));
                        throw new IllegalStateException("解析视频链接失败了");
                    }
                    AppLog.i("RecoPrefetcher", "推荐解析成功 viewKey=" + viewKey
                            + " 源=" + (isHexViewKey ? "91porny" : "9mman")
                            + " url=" + shortUrl(videoResult.getVideoUrl()));
                    persist(videoResult, candidate.item);
                    return videoResult;
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        videoResult -> onParseSuccess(viewKey, candidate, videoResult, urgent),
                        throwable -> onParseFailed(viewKey, throwable)));
    }

    /**
     * M98：IO 线程查 DB 里缓存的直链（原 resolveNow 主线程查库逻辑的迁移）。
     * 直链为空或 secure 签名已过期时返回 null → 走网络重新解析。
     */
    private VideoResult loadDbCachedResult(String viewKey) {
        try {
            V9MmanItem dbItem = dataManager.findV9MmanItemByViewKey(viewKey);
            VideoResult dbVr = (dbItem == null || dbItem.getVideoResultId() == 0)
                    ? null : dbItem.getVideoResult();
            if (dbVr == null || TextUtils.isEmpty(dbVr.getVideoUrl())) {
                return null;
            }
            if (isSecureUrlExpired(dbVr.getVideoUrl())) {
                AppLog.i(TAG, "DB直链已过期，强制重新解析 viewKey=" + viewKey);
                return null;
            }
            return dbVr;
        } catch (Throwable ignored) {
            // DB 查询失败不影响后续网络解析流程
            return null;
        }
    }

    private static String shortUrl(String url) {
        if (url == null) {
            return "null";
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            String tail = url.length() > 120 ? url.substring(0, 120) + "..." : url;
            return (host == null ? url : host) + " | " + tail;
        } catch (Exception e) {
            return url.length() > 120 ? url.substring(0, 120) + "..." : url;
        }
    }

    private void onParseSuccess(String viewKey, RecoCandidate candidate,
                                VideoResult videoResult, boolean urgent) {
        synchronized (inFlight) {
            inFlight.remove(viewKey);
        }
        // M98：解析成功，解除负缓存封禁
        failedUntil.remove(viewKey);
        put(viewKey, videoResult.getVideoUrl());
        // 回填作者，供作者召回与作者收藏使用
        if (!TextUtils.isEmpty(videoResult.getOwnerId())) {
            candidate.authorKey = videoResult.getOwnerId();
        }
        if (!TextUtils.isEmpty(videoResult.getOwnerName())) {
            candidate.authorName = videoResult.getOwnerName();
        }
        String playUrl = toPlayUrl(videoResult.getVideoUrl());
        for (ResolveCallback cb : drainWaiting(viewKey)) {
            try {
                cb.onResolved(viewKey, playUrl, candidate.item);
            } catch (Exception e) {
                Logger.t(TAG).d("resolve callback failed: " + e.getMessage());
            }
        }
        if (urgent) {
            warm(videoResult.getVideoUrl());
        }
    }

    private void onParseFailed(String viewKey, Throwable throwable) {
        synchronized (inFlight) {
            inFlight.remove(viewKey);
        }
        // M98/T04：记入分级负缓存（断网/超时/5xx 短 TTL；解析无结果长 TTL；
        // 403 只允许一次快速重解析），LRU ≤120
        if (!TextUtils.isEmpty(viewKey)) {
            recordFailure(viewKey, throwable);
        }
        backgroundParses.remove(viewKey);
        String msg = throwable == null || TextUtils.isEmpty(throwable.getMessage())
                ? "解析视频链接失败了" : throwable.getMessage();
        // M75：升为 AppLog.e（diag 可见），便于定位某条视频详情页 HTTP 200 后静默解析失败的根因
        // （如 c48a0932f2e2d58d7fa4 反复重打详情页却无成功日志）。
        AppLog.e("RecoPrefetcher", "推荐解析异常 viewKey=" + viewKey + " : "
                + (throwable == null ? "null" : throwable.getClass().getSimpleName() + ": " + msg));
        for (ResolveCallback cb : drainWaiting(viewKey)) {
            try {
                cb.onFailed(viewKey, msg);
            } catch (Exception e) {
                Logger.t(TAG).d("failed callback error: " + e.getMessage());
            }
        }
    }

    // ==================== T04：负缓存（分级 TTL + LRU） ====================

    /** 负缓存是否命中；顺带清除已过期项 */
    private boolean isNegativeCached(String viewKey) {
        if (TextUtils.isEmpty(viewKey)) {
            return false;
        }
        Long until;
        synchronized (failedUntil) {
            until = failedUntil.get(viewKey);
        }
        return until != null && android.os.SystemClock.elapsedRealtime() < until;
    }

    /** 按异常分类记录负缓存（方案 §5-T04） */
    private void recordFailure(String viewKey, Throwable t) {
        String msg = t == null ? "" : String.valueOf(t.getMessage());
        long ttl;
        boolean networkish = t != null && (t instanceof java.net.SocketTimeoutException
                || t instanceof java.net.UnknownHostException
                || t instanceof java.net.ConnectException
                || t instanceof java.io.IOException);
        if (msg != null && msg.contains("403")) {
            // 403 失效：只允许一次快速重解析（签名刷新场景），第二次按解析失败长封禁
            synchronized (forbiddenReparseUsed) {
                if (forbiddenReparseUsed.add(viewKey)) {
                    ttl = NEG_TTL_FORBIDDEN_MS;
                } else {
                    ttl = NEG_TTL_NO_RESULT_MS;
                }
            }
        } else if (networkish) {
            ttl = NEG_TTL_NETWORK_MS; // 断网/超时/5xx：短 TTL，网络恢复即可重试
        } else {
            ttl = NEG_TTL_NO_RESULT_MS; // 解析无结果：确定不可用，长 TTL
        }
        synchronized (failedUntil) {
            failedUntil.remove(viewKey);
            failedUntil.put(viewKey, android.os.SystemClock.elapsedRealtime() + ttl);
            // LRU 淘汰最旧 + 清除过期项（不整体清空）
            Iterator<String> it = failedUntil.keySet().iterator();
            while (failedUntil.size() > MAX_NEG_CACHE && it.hasNext()) {
                it.next();
                it.remove();
            }
            long now = android.os.SystemClock.elapsedRealtime();
            it = failedUntil.keySet().iterator();
            while (it.hasNext()) {
                if (failedUntil.get(it.next()) <= now) {
                    it.remove();
                }
            }
        }
    }

    /** 用户显式重试：绕过负缓存一次（清掉该 key 的封禁） */
    public void bypassNegativeCacheOnce(String viewKey) {
        if (TextUtils.isEmpty(viewKey)) {
            return;
        }
        synchronized (failedUntil) {
            failedUntil.remove(viewKey);
        }
        synchronized (forbiddenReparseUsed) {
            forbiddenReparseUsed.remove(viewKey);
        }
    }

    // ==================== T04：窗口调度 ====================

    /**
     * 更新预取窗口：当前页 + 后续 ahead 条（方案 §5-T04）。
     * 窗口外已发起的后台解析取消（仍有前台/下载等待者或已缓存的除外）；
     * 窗口内未解析的条目发起后台解析（在途后台解析 ≤8，只保留最新窗口）。
     */
    public void updateWindow(List<RecoCandidate> list, int currentPosition, int ahead) {
        // L-fix：预取开关关闭时不做任何后台预取（解析/封面/字节预热）；
        // 用户明确请求的播放与下载解析走 resolveNow，不受此影响
        if (appContext != null && !PlayUiPrefs.isRecoPrefetchEnabled(appContext)) {
            return;
        }
        if (released || list == null || currentPosition < 0 || currentPosition >= list.size()) {
            return;
        }
        // 1. 计算新窗口
        Set<String> newWindow = new HashSet<>();
        for (int i = currentPosition; i < list.size() && i <= currentPosition + ahead; i++) {
            RecoCandidate c = list.get(i);
            if (c != null && c.item != null && !TextUtils.isEmpty(c.viewKey())) {
                newWindow.add(c.viewKey());
            }
        }
        windowKeys.clear();
        windowKeys.addAll(newWindow);

        // 2. 取消窗口外的后台解析（有等待者/已完成的除外——等待者由 waiting 表判定）
        for (Map.Entry<String, io.reactivex.disposables.Disposable> e
                : backgroundParses.entrySet()) {
            if (!newWindow.contains(e.getKey())) {
                boolean hasWaiter;
                synchronized (waiting) {
                    hasWaiter = waiting.containsKey(e.getKey());
                }
                if (!hasWaiter) {
                    io.reactivex.disposables.Disposable d = backgroundParses.remove(e.getKey());
                    if (d != null && !d.isDisposed()) {
                        d.dispose(); // 真取消网络（不只是停回调）
                        AppLog.i(TAG, "后台解析出窗取消 viewKey=" + e.getKey());
                    }
                    synchronized (inFlight) {
                        inFlight.remove(e.getKey());
                    }
                }
            }
        }

        // 3. 窗口内未解析条目发起后台解析（前台当前页由 resolveNow 单独走，不在此列）
        int launched = 0;
        for (int i = currentPosition + 1; i < list.size() && launched < ahead; i++) {
            RecoCandidate c = list.get(i);
            if (c == null || c.item == null || TextUtils.isEmpty(c.viewKey())) {
                continue;
            }
            String key = c.viewKey();
            if (!TextUtils.isEmpty(peekRawUrl(key)) || isNegativeCached(key)) {
                continue;
            }
            synchronized (inFlight) {
                if (inFlight.contains(key)) {
                    continue;
                }
            }
            boolean hasWaiter;
            synchronized (waiting) {
                hasWaiter = waiting.containsKey(key);
            }
            if (hasWaiter) {
                continue; // 已有前台/下载在等，parse 会由其触发
            }
            synchronized (backgroundParses) {
                if (backgroundParses.size() >= 8) {
                    break; // 后台待队列 ≤8：只保留最新窗口
                }
            }
            final RecoCandidate bc = c;
            disposables.add(parseTracked(bc));
            launched++;
        }
    }

    /** 发起后台解析并登记到可取消表（不在 CompositeDisposable 里与前台混放） */
    private io.reactivex.disposables.Disposable parseTracked(final RecoCandidate candidate) {
        final String viewKey = candidate.viewKey();
        final boolean isMmanPrefixed = viewKey != null && viewKey.startsWith("viewkey=");
        final boolean isHexViewKey = !isMmanPrefixed && viewKey != null
                && viewKey.matches("[0-9a-fA-F]{16,32}");
        final Observable<VideoResult> parseObs = isHexViewKey
                ? dataManager.loadPornyVideoUrl(viewKey)
                : dataManager.loadMman9VideoUrl(viewKey);
        synchronized (inFlight) {
            inFlight.add(viewKey);
        }
        io.reactivex.disposables.Disposable d = Observable
                .defer(() -> {
                    VideoResult dbCached = loadDbCachedResult(viewKey);
                    if (dbCached != null) {
                        put(viewKey, dbCached.getVideoUrl());
                        return Observable.just(dbCached);
                    }
                    return parseObs;
                })
                .subscribeOn(Schedulers.io())
                .map(videoResult -> {
                    if (videoResult == null || TextUtils.isEmpty(videoResult.getVideoUrl())) {
                        throw new IllegalStateException("解析视频链接失败了");
                    }
                    persist(videoResult, candidate.item);
                    return videoResult;
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        videoResult -> {
                            backgroundParses.remove(viewKey);
                            onParseSuccess(viewKey, candidate, videoResult, false);
                        },
                        throwable -> onParseFailed(viewKey, throwable));
        backgroundParses.put(viewKey, d);
        return d;
    }

    /** 退出页面/隐藏时取消全部后台预取（前台与下载等待不受影响） */
    public void cancelBackground() {
        windowKeys.clear();
        // 快照遍历：取消时必须同步清 inFlight，否则后续 parse()/resolveNow 会因
        // “已在解析中”直接返回，回调永远不触发（表现为永久转圈，重试也无效）。
        for (String key : new ArrayList<>(backgroundParses.keySet())) {
            boolean hasWaiter;
            synchronized (waiting) {
                hasWaiter = waiting.containsKey(key);
            }
            if (hasWaiter) {
                continue; // 前台起播/下载仍在等结果，不能取消
            }
            io.reactivex.disposables.Disposable d = backgroundParses.remove(key);
            if (d != null && !d.isDisposed()) {
                d.dispose();
            }
            synchronized (inFlight) {
                inFlight.remove(key);
            }
        }
        backgroundParses.clear();
        // 取消未开始的 warm 待队列（在途的由连接断开与循环检查收尾）
        warmPending.set(0);
        disconnectWarm();
    }

    /** 立即断开活动预热连接（阻塞 read 1 秒内失败退出） */
    private void disconnectWarm() {
        HttpURLConnection conn = warmConnection;
        if (conn != null) {
            try {
                conn.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 解析结果落库：与播放页保持一致，这样从推荐流点进详情页时可以直接复用，
     * 同时补上浏览历史。
     * <p>
     * M71：91porn 列表页服务端渲染存在系统性「标题/封面串位」（实测同一页面 45 个条目
     * 里约一半的预览视频ID与封面图ID对不上），列表解析出的 title 不可信。
     * 播放页 h4 标题是权威数据，解析成功后用它覆盖条目标题并回写 DB，
     * 详情页 / 推荐流 / 历史记录从此都显示权威标题。
     */
    private void persist(VideoResult videoResult, V9MmanItem item) {
        try {
            // M71：用播放页权威标题修正列表页串位标题
            if (item != null && videoResult != null
                    && !TextUtils.isEmpty(videoResult.getVideoName())
                    && !TextUtils.equals(item.getTitle(), videoResult.getVideoName())) {
                AppLog.i(TAG, "标题修正(站点列表页串位) viewKey=" + item.getViewKey()
                        + " 列表页=\"" + item.getTitle() + "\" -> 播放页=\"" + videoResult.getVideoName() + '"');
                item.setTitle(videoResult.getVideoName());
            }
            dataManager.saveVideoResult(videoResult);
            if (item != null) {
                item.setVideoResult(videoResult);
                if (item.getSourceName() == null) {
                    item.setSourceName(item.getSource());
                }
                dataManager.saveV9MmanItem(item);
            }
        } catch (Exception e) {
            Logger.t(TAG).d("persist video result failed: " + e.getMessage());
        }
    }

    /** 标记为已观看（写浏览历史），在真正开播时调用 */
    public void markWatched(final V9MmanItem item) {
        if (item == null || released) {
            return;
        }
        disposables.add(io.reactivex.Observable.just(item)
                .subscribeOn(Schedulers.io())
                .subscribe(it -> {
                    try {
                        it.setViewHistoryDate(new Date());
                        dataManager.saveV9MmanItem(it);
                    } catch (Exception ignored) {
                    }
                }, throwable -> Logger.t(TAG).d("markWatched failed: " + throwable.getMessage())));
    }

    private void put(String viewKey, String rawUrl) {
        if (TextUtils.isEmpty(viewKey) || TextUtils.isEmpty(rawUrl)) {
            return;
        }
        synchronized (urlCache) {
            urlCache.remove(viewKey);
            urlCache.put(viewKey, rawUrl);
            while (urlCache.size() > MAX_CACHE) {
                Iterator<String> it = urlCache.keySet().iterator();
                if (!it.hasNext()) {
                    break;
                }
                it.next();
                it.remove();
            }
        }
    }

    /**
     * 通过 videocache 代理拉首包，把本地缓存文件建立起来。
     * 失败不影响播放（播放器自己会拉流），所以全程吞异常。
     * T04：warm 同时 1 个、待队列最多紧邻 1 条（warmPending ≥2 时跳过新请求），
     * 队列不累计；release/翻页断开活动连接。
     */
    private void warm(final String rawUrl) {
        if (released || TextUtils.isEmpty(rawUrl)) {
            return;
        }
        // T04（第一期）：HLS 直接读 manifest 未形成媒体缓存，无收益——禁止此 warm，
        // 保留必要的详情解析；分片预热留给未来播放器统一缓存任务
        if (isHlsUrl(rawUrl)) {
            return;
        }
        synchronized (warmed) {
            if (warmed.contains(rawUrl)) {
                return;
            }
            warmed.add(rawUrl);
            if (warmed.size() > MAX_CACHE) {
                warmed.clear();
                warmed.add(rawUrl);
            }
        }
        if (warmPending.get() >= 2) {
            return; // 1 执行 + 1 待队列，不再累积
        }
        warmPending.incrementAndGet();
        try {
            warmExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        // HLS 不经过 videocache（上面已跳过）；MP4 包代理拉首包
                        doWarm(toPlayUrl(rawUrl));
                    } finally {
                        warmPending.decrementAndGet();
                    }
                }
            });
        } catch (Exception ignored) {
            warmPending.decrementAndGet();
            // 线程池已关闭
        }
    }

    private void doWarm(String playUrl) {
        if (released || TextUtils.isEmpty(playUrl)) {
            return;
        }
        HttpURLConnection conn = null;
        InputStream is = null;
        long readBytes = 0L;
        try {
            conn = (HttpURLConnection) new URL(playUrl).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Range", "bytes=0-" + (WARM_BYTES - 1));
            warmConnection = conn;
            conn.connect();
            is = conn.getInputStream();
            byte[] buf = new byte[16 * 1024];
            int total = 0;
            int len;
            // T04：应用读取上限 512KiB；每次 read 最多 min(buf, remaining)；
            // Range 被忽略时读满上限立即关流（底层代理仍可能自主取数，以网络统计区分）
            while (!released && total < WARM_BYTES) {
                int want = (int) Math.min(buf.length, WARM_BYTES - total);
                len = is.read(buf, 0, want);
                if (len <= 0) {
                    break;
                }
                total += len;
            }
            readBytes = total;
        } catch (Throwable t) {
            Logger.t(TAG).d("warm failed: " + t.getMessage());
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
            if (warmConnection == conn) {
                warmConnection = null;
            }
            // Range 被忽略时实际读取量可能超过 512KiB 计划值——如实记录供网络统计核对
            if (readBytes > WARM_BYTES) {
                AppLog.i(TAG, "warm 实际读取超过上限: " + readBytes + "B（服务端忽略 Range）");
            }
        }
    }

    public void release() {
        released = true;
        disposables.clear();
        // T04：翻页/隐藏/release 立即取消后台解析并断开活动预热连接
        cancelBackground();
        synchronized (waiting) {
            waiting.clear();
        }
        synchronized (inFlight) {
            inFlight.clear();
        }
        // M98：一并清空负缓存，避免复用实例时旧失败记录误伤新会话
        synchronized (failedUntil) {
            failedUntil.clear();
        }
        synchronized (forbiddenReparseUsed) {
            forbiddenReparseUsed.clear();
        }
        try {
            warmExecutor.shutdownNow();
        } catch (Exception ignored) {
        }
        disconnectWarm();
    }
}
