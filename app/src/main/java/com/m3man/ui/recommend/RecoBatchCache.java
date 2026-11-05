package com.m3man.ui.recommend;

import android.content.Context;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.m3man.data.DataManager;
import com.m3man.data.reco.RecoCandidate;
import com.m3man.perf.ExecutorRegistry;
import com.m3man.perf.Perf;
import com.m3man.perf.PerfFlags;
import com.m3man.utils.PlayUiPrefs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;

/**
 * T01：推荐缓存批次读写器（方案 §5-T01）。
 *
 * <p>契约：
 * <ul>
 *   <li>JSON encode/read/write 全部在缓存执行器（PARSE_BACKGROUND 池），主线程零 JSON/IO。</li>
 *   <li>{@link CacheScope} 含格式版本、源配置摘要、方向/时长筛选；scope 不匹配的旧缓存一律视为未命中，
 *       不展示不符合当前筛选的旧内容。</li>
 *   <li>最多 {@link com.m3man.ui.recommend.RecommendFeedFragment} BATCH_SIZE 条，编码后 ≤256KB；</li>
 *   <li>保存时间超过 24h 的旧批不展示、不自动起播（视为未命中）。</li>
 *   <li>writeLatest 合并尚未执行的旧写（只写最新批），并保证尾沿补写：写入期间到来的新批一定落盘。</li>
 *   <li>不写签名直链、DaoSession 或整个 greenDAO 对象图。</li>
 * </ul>
 *
 * @author 3mman
 */
public final class RecoBatchCache {

    /** 缓存格式版本：v1 为旧「裸 V9MmanItem 数组」，本类只读写 v2；v1 键保留不动（兼容可读）。 */
    public static final int FORMAT_VERSION = 2;
    /** 缓存有效期：超过不自动起播旧批 */
    public static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
    /** 编码后大小上限（方案 §5-T01） */
    public static final int MAX_ENCODED_BYTES = 256 * 1024;

    private static final String TAG = "RecoBatchCache";

    /** 缓存身份：源配置摘要 + 筛选 + 格式版本。不可变。 */
    public static final class CacheScope {
        public final int formatVersion;
        /** 源配置摘要（mman9/porny 地址），源切换后旧缓存不命中 */
        public final String sourceDigest;
        public final int orientationFilter;
        public final int maxDurationMinutes;

        CacheScope(int formatVersion, String sourceDigest, int orientationFilter, int maxDurationMinutes) {
            this.formatVersion = formatVersion;
            this.sourceDigest = sourceDigest;
            this.orientationFilter = orientationFilter;
            this.maxDurationMinutes = maxDurationMinutes;
        }

        boolean sameAs(CacheScope other) {
            return other != null
                    && formatVersion == other.formatVersion
                    && orientationFilter == other.orientationFilter
                    && maxDurationMinutes == other.maxDurationMinutes
                    && TextUtils.equals(sourceDigest, other.sourceDigest);
        }
    }

    /** 一次读出的缓存批次（不可变内容；candidates 为全新 detached 对象）。 */
    public static final class CacheBatch {
        public static final CacheBatch EMPTY =
                new CacheBatch(new ArrayList<CachedVideo>(), new ArrayList<RecoCandidate>(), 0L, false);

        public final List<CachedVideo> items;
        public final List<RecoCandidate> candidates;
        public final long savedAt;
        /** true=存在但 scope/期限不满足（视为未命中） */
        public final boolean expired;

        CacheBatch(List<CachedVideo> items, List<RecoCandidate> candidates, long savedAt, boolean expired) {
            this.items = items;
            this.candidates = candidates;
            this.savedAt = savedAt;
            this.expired = expired;
        }
    }

    // ---- v2 JSON 镜像（Gson 字段名即键名，短名控制体积） ----
    private static final class SnapshotV2 {
        int v;
        long savedAt;
        String src;
        int of;
        int dur;
        List<CachedVideo> items;
    }

    private final Context appContext;
    private final DataManager dataManager;
    private final Gson gson = new Gson();
    private final Executor cacheExecutor;
    private final AtomicBoolean drainBusy = new AtomicBoolean(false);
    private final AtomicReference<PendingWrite> pending = new AtomicReference<>();

    private static final class PendingWrite {
        final CacheScope scope;
        final List<CachedVideo> items;

        PendingWrite(CacheScope scope, List<CachedVideo> items) {
            this.scope = scope;
            this.items = items;
        }
    }

    public RecoBatchCache(Context appContext, DataManager dataManager) {
        this.appContext = appContext.getApplicationContext();
        this.dataManager = dataManager;
        this.cacheExecutor = ExecutorRegistry.get(ExecutorRegistry.Pool.PARSE_BACKGROUND);
    }

    // ==================== 读 ====================

    /**
     * 读取与当前 scope 匹配的缓存批次。SP 读、JSON 解析、候选重建都在缓存执行器；
     * 坏 JSON / scope 不匹配 / 超 24h 一律返回 EMPTY（缓存未命中语义，不抛错）。
     */
    public Single<CacheBatch> read() {
        return Single.fromCallable(this::readOnExecutor)
                .subscribeOn(Schedulers.from(cacheExecutor));
    }

    private CacheBatch readOnExecutor() {
        long req = Perf.nextRequestId();
        long begin = Perf.recorder().begin(Perf.ST_RECO_CACHE_READ, req);
        try {
            return doRead();
        } catch (Exception e) {
            // 坏 JSON/未知结构：视为未命中，不阻断首屏
            return CacheBatch.EMPTY;
        } finally {
            Perf.recorder().end(Perf.ST_RECO_CACHE_READ, req, begin, Perf.OUTCOME_OK);
        }
    }

    private CacheBatch doRead() {
        String json = PlayUiPrefs.getRecoCacheBatchV2(appContext);
        if (TextUtils.isEmpty(json)) {
            return CacheBatch.EMPTY;
        }
        SnapshotV2 snap;
        try {
            snap = gson.fromJson(json, SnapshotV2.class);
        } catch (JsonSyntaxException e) {
            return CacheBatch.EMPTY;
        }
        if (snap == null || snap.v != FORMAT_VERSION || snap.items == null
                || snap.savedAt <= 0L
                || System.currentTimeMillis() - snap.savedAt > MAX_AGE_MS) {
            return CacheBatch.EMPTY;
        }
        CacheScope stored = new CacheScope(snap.v, snap.src, snap.of, snap.dur);
        if (!stored.sameAs(currentScope())) {
            return CacheBatch.EMPTY;
        }
        List<CachedVideo> items = new ArrayList<>();
        List<RecoCandidate> candidates = new ArrayList<>();
        for (CachedVideo it : snap.items) {
            if (it == null || TextUtils.isEmpty(it.viewKey)) {
                continue;
            }
            items.add(it);
            RecoCandidate c = it.toCandidate();
            if (c != null) {
                candidates.add(c);
            }
        }
        if (candidates.isEmpty()) {
            return CacheBatch.EMPTY;
        }
        return new CacheBatch(items, candidates, snap.savedAt, false);
    }

    // ==================== 写 ====================

    /**
     * 便捷入口：scope 在缓存执行器内捕获（含 SP 读），编码与落盘同线程。
     * 多次调用只保留最新一批（合并未执行的旧写），并保证尾沿补写。
     */
    public void writeLatest(final List<CachedVideo> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        cacheExecutor.execute(() -> {
            CacheScope scope = currentScope();
            pending.set(new PendingWrite(scope, items));
            drainOnExecutor();
        });
    }

    /** 方案接口形态：调用方已持有 scope 时直接写。 */
    public void writeLatest(CacheScope scope, List<CachedVideo> items) {
        if (scope == null || items == null || items.isEmpty()) {
            return;
        }
        cacheExecutor.execute(() -> {
            pending.set(new PendingWrite(scope, items));
            drainOnExecutor();
        });
    }

    private void drainOnExecutor() {
        if (!drainBusy.compareAndSet(false, true)) {
            return; // 已有写入在执行；其 finally 的尾沿检查会补写本批
        }
        try {
            PendingWrite w;
            while ((w = pending.getAndSet(null)) != null) {
                doWrite(w);
            }
        } finally {
            drainBusy.set(false);
            // 尾沿：drain 执行期间到来的新批必须再写一次，不能永远没有下一次
            if (pending.get() != null) {
                cacheExecutor.execute(this::drainOnExecutor);
            }
        }
    }

    private void doWrite(PendingWrite w) {
        long req = Perf.nextRequestId();
        long begin = Perf.recorder().begin(Perf.ST_RECO_FLUSH, req);
        try {
            SnapshotV2 snap = new SnapshotV2();
            snap.v = FORMAT_VERSION;
            snap.savedAt = System.currentTimeMillis();
            snap.src = w.scope.sourceDigest;
            snap.of = w.scope.orientationFilter;
            snap.dur = w.scope.maxDurationMinutes;
            snap.items = w.items;
            String json = gson.toJson(snap);
            if (json == null
                    || json.getBytes(StandardCharsets.UTF_8).length > MAX_ENCODED_BYTES) {
                return; // 超限不写（6 条规模正常远小于该值，防御性保护）
            }
            PlayUiPrefs.setRecoCacheBatchV2(appContext, json);
        } catch (Exception ignored) {
            // 缓存写失败不影响功能；下次出批会再写
        } finally {
            Perf.recorder().end(Perf.ST_RECO_FLUSH, req, begin, Perf.OUTCOME_OK);
        }
    }

    // ==================== scope ====================

    /**
     * 在缓存执行器内捕获当前 scope：源地址摘要 + 方向/时长筛选（SP 读）。
     * 注意：不要在主线程调用（含 SP 与 DataManager 读）。
     */
    public CacheScope currentScope() {
        String digest;
        try {
            String raw = (dataManager == null ? "" : nz(dataManager.getMman9VideoAddress()))
                    + "|" + (dataManager == null ? "" : nz(dataManager.getPornyAddress()));
            digest = sha16(raw);
        } catch (Exception e) {
            digest = "err";
        }
        int orientation = PlayUiPrefs.getOrientationFilter(appContext);
        int duration;
        try {
            duration = dataManager == null ? 0 : dataManager.getRecoMaxDurationMinutes();
        } catch (Exception e) {
            duration = 0;
        }
        return new CacheScope(FORMAT_VERSION, digest, orientation, duration);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String sha16(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", out[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(raw.hashCode());
        }
    }
}
