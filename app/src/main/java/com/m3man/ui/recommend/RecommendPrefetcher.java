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
    /** M98：负缓存 TTL——解析失败后 10 分钟内直接快速失败，不再反复打详情页 */
    private static final long NEGATIVE_CACHE_TTL_MS = 10L * 60L * 1000L;

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
     * M98：负缓存——viewKey -> 失败封禁截止时间（elapsedRealtime）。
     * 解析失败的 key 在 TTL 内直接 fail-fast，避免反复对同一条死链打详情页网络请求。
     */
    private final Map<String, Long> failedUntil = new ConcurrentHashMap<>();
    /**
     * viewKey -> 等待该 key 解析结果的回调列表。
     * 同一条视频可能被「起播」和「下载」同时等待，所以是一对多，
     * 早期用单值 Map 会导致后来者把前面的回调顶掉（表现为点了下载后视频卡住不播）。
     */
    private final Map<String, List<ResolveCallback>> waiting = new LinkedHashMap<>();

    private final ExecutorService warmExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean released = false;

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
     * M72：判断 9mman 直链的时效签名（secure=&lt;base64&gt;,&lt;unix秒&gt;）是否已过期。
     * 过期的直链交给 videocache 本地代理时，代理建流失败会导致播放器连不上
     * 127.0.0.1 端口而无限转圈（实测日志：Unable to connect to http://127.0.0.1:44183）。
     *
     * @return true=确定过期；false=无签名(其它源)或仍在有效期内
     */
    public static boolean isSecureUrlExpired(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String query = uri.getRawQuery();
            if (query == null || !query.contains("secure=")) {
                return false;
            }
            for (String p : query.split("&")) {
                if (p.startsWith("secure=")) {
                    String val = p.substring("secure=".length());
                    // 形如 <base64>%3D%3D,1787513682 或 <base64>==,1787513682（可能已 URL 编码）
                    int comma = val.lastIndexOf(',');
                    if (comma < 0 || comma + 1 >= val.length()) {
                        return false;
                    }
                    long expireSec = Long.parseLong(val.substring(comma + 1));
                    // 提前 5 分钟视为过期，避免起播途中跨过有效期
                    return System.currentTimeMillis() / 1000L >= expireSec - 300;
                }
            }
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
        // M98：负缓存未到期直接快速失败，不打网络请求
        Long until = failedUntil.get(viewKey);
        if (until != null && android.os.SystemClock.elapsedRealtime() < until) {
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
            GlideApp.with(appContext).load(imgUrl).preload();
        } catch (Exception e) {
            Logger.t(TAG).d("preload cover failed: " + e.getMessage());
        }
    }

    private void parse(final RecoCandidate candidate, final boolean urgent) {
        final String viewKey = candidate.viewKey();
        // M98：负缓存 fail-fast——TTL 内失败过的 key 不再发起网络解析，直接回调失败
        Long until = failedUntil.get(viewKey);
        if (until != null && android.os.SystemClock.elapsedRealtime() < until) {
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
        // M98：记入负缓存，10 分钟内对该 key 的解析请求直接 fail-fast
        if (!TextUtils.isEmpty(viewKey)) {
            failedUntil.put(viewKey,
                    android.os.SystemClock.elapsedRealtime() + NEGATIVE_CACHE_TTL_MS);
        }
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
     */
    private void warm(final String rawUrl) {
        if (released || TextUtils.isEmpty(rawUrl)) {
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
        try {
            warmExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    // HLS 不经过 videocache，避免缓存服务错误解析相对 ts 分片。
                    doWarm(toPlayUrl(rawUrl));
                }
            });
        } catch (Exception ignored) {
            // 线程池已关闭
        }
    }

    private void doWarm(String playUrl) {
        if (released || TextUtils.isEmpty(playUrl)) {
            return;
        }
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            conn = (HttpURLConnection) new URL(playUrl).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Range", "bytes=0-" + (WARM_BYTES - 1));
            conn.connect();
            is = conn.getInputStream();
            byte[] buf = new byte[16 * 1024];
            int total = 0;
            int len;
            while (!released && total < WARM_BYTES && (len = is.read(buf)) > 0) {
                total += len;
            }
            Logger.t(TAG).d("warm " + total + " bytes");
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
        }
    }

    public void release() {
        released = true;
        disposables.clear();
        synchronized (waiting) {
            waiting.clear();
        }
        synchronized (inFlight) {
            inFlight.clear();
        }
        // M98：一并清空负缓存，避免复用实例时旧失败记录误伤新会话
        failedUntil.clear();
        try {
            warmExecutor.shutdownNow();
        } catch (Exception ignored) {
        }
    }
}
