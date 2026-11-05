package com.m3man.perf;

import android.os.StrictMode;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * T00 进程级性能记录门面：阶段计时聚合 + 计数器 + 最慢区段留存。
 *
 * <p>线程安全：所有写操作为常量时间；采样写入环形数组（每阶段容量上限），
 * p50/p95 仅在 {@link #snapshot()} / {@link #dump(String)} 时按需计算。
 * 聚合与取时逻辑为纯 JDK 实现，可在 JVM 单测中直接验证。
 *
 * @author 3mman
 */
public final class Perf {

    // ---- 埋点阶段常量（方案 §4，禁止自造字符串） ----
    public static final String ST_APP_INJECT = "app.inject";
    public static final String ST_DB_OPEN = "db.open";
    public static final String ST_RECO_ENGINE_INIT = "reco.engine.init";
    public static final String ST_RECO_CACHE_READ = "reco.cache.read";
    public static final String ST_RECO_CACHE_RENDER = "reco.cache.render";
    public static final String ST_RECO_BATCH = "reco.batch";
    public static final String ST_PARSE_QUEUE = "parse.queue";
    public static final String ST_PARSE_DB = "parse.db";
    public static final String ST_PARSE_NETWORK = "parse.network";
    public static final String ST_PARSE_HTML = "parse.html";
    public static final String ST_PLAYER_PREPARE = "player.prepare";
    public static final String ST_PLAYER_FIRST_FRAME = "player.first_frame";
    public static final String ST_COVER_LOAD = "cover.load";
    public static final String ST_COVER_TRANSFORM = "cover.transform";
    public static final String ST_DIFF_COMPUTE = "diff.compute";
    public static final String ST_DIFF_APPLY = "diff.apply";
    public static final String ST_HLS_SEGMENT = "hls.segment";
    public static final String ST_HLS_MERGE = "hls.merge";
    public static final String ST_HLS_REMUX = "hls.remux";
    public static final String ST_HLS_ARCHIVE = "hls.archive";
    public static final String ST_RECO_FLUSH = "reco.flush";

    // ---- outcome 约定 ----
    public static final String OUTCOME_OK = "ok";
    public static final String OUTCOME_FAIL = "fail";
    public static final String OUTCOME_CANCEL = "cancel";
    public static final String OUTCOME_SKIP = "skip";

    /** 每阶段保留的耗时采样上限（环形覆盖，够 50 样本 p50/p95 与长跑聚合用） */
    private static final int SAMPLES_PER_STAGE = 2048;
    /** 全进程最慢区段留存上限（供基线报告取“最慢10段”） */
    private static final int SLOWEST_KEEP = 32;

    private static final AggregatingRecorder RECORDER = new AggregatingRecorder();

    private static final AtomicLong REQUEST_ID = new AtomicLong(1);

    private Perf() {
    }

    /** 进程级记录器单例。 */
    public static PerfRecorder recorder() {
        return RECORDER;
    }

    /** 生成跨线程关联 id。 */
    public static long nextRequestId() {
        return REQUEST_ID.getAndIncrement();
    }

    /** 便捷区段句柄（单线程内 begin/end，不跨线程配对）。 */
    public static Span span(String stage) {
        return new Span(stage);
    }

    /**
     * 测试/调试构建启用 StrictMode 主线程磁盘/网络检测（方案 §5 T00-3）。
     * 只记日志不崩溃；StrictMode 是辅助手段，不能替代调用链检查。
     */
    public static void enableStrictMode() {
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build());
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build());
    }

    /** 按阶段聚合快照（p50/p95/max 单位毫秒；供脚本/报告读取）。 */
    public static Map<String, StageSummary> snapshot() {
        return RECORDER.snapshotAll();
    }

    /** 计数器快照：name → (last, total)。last 为最近一次写入（队列深度等仪表量），total 为累计。 */
    public static Map<String, CounterSummary> countersSnapshot() {
        return RECORDER.countersSnapshot();
    }

    /** 全进程最慢区段（降序），供基线报告取最慢 10 段定位 trace。 */
    public static List<SlowRecord> slowestRecords() {
        return RECORDER.slowestSnapshot();
    }

    /** 输出汇总到 logcat（采样聚合，不逐条打日志）。 */
    public static void dump(String tag) {
        for (StageSummary s : sortedSummaries()) {
            Log.i(tag, "[PERF] " + s.stage
                    + " n=" + s.sampleCount + " fail=" + s.failureCount
                    + " p50=" + String.format(java.util.Locale.US, "%.2f", s.p50Ms) + "ms"
                    + " p95=" + String.format(java.util.Locale.US, "%.2f", s.p95Ms) + "ms"
                    + " max=" + String.format(java.util.Locale.US, "%.2f", s.maxMs) + "ms");
        }
        for (Map.Entry<String, CounterSummary> e : countersSnapshot().entrySet()) {
            Log.i(tag, "[PERF-C] " + e.getKey()
                    + " last=" + e.getValue().last + " total=" + e.getValue().total);
        }
    }

    /** 按阶段名称排序的摘要列表。 */
    public static List<StageSummary> sortedSummaries() {
        Map<String, StageSummary> all = snapshot();
        List<StageSummary> list = new ArrayList<>(all.values());
        list.sort(Comparator.comparing(s -> s.stage));
        return list;
    }

    /** 阶段聚合摘要。 */
    public static final class StageSummary {
        public final String stage;
        public final long sampleCount;
        public final long failureCount;
        public final double p50Ms;
        public final double p95Ms;
        public final double maxMs;

        StageSummary(String stage, long sampleCount, long failureCount,
                     double p50Ms, double p95Ms, double maxMs) {
            this.stage = stage;
            this.sampleCount = sampleCount;
            this.failureCount = failureCount;
            this.p50Ms = p50Ms;
            this.p95Ms = p95Ms;
            this.maxMs = maxMs;
        }
    }

    /** 计数器摘要：last=最近写入值（仪表量），total=累计值。 */
    public static final class CounterSummary {
        public final String name;
        public final long last;
        public final long total;

        CounterSummary(String name, long last, long total) {
            this.name = name;
            this.last = last;
            this.total = total;
        }
    }

    /** 单线程内使用的便捷区段句柄。 */
    public static final class Span implements AutoCloseable {
        public final String stage;
        public final long requestId;
        public final long beginNs;

        private Span(String stage) {
            this.stage = stage;
            this.requestId = nextRequestId();
            this.beginNs = RECORDER.begin(stage, requestId);
        }

        public void end(String outcome) {
            RECORDER.end(stage, requestId, beginNs, outcome);
        }

        @Override
        public void close() {
            end(OUTCOME_OK);
        }
    }

    // ================= 实现 =================

    private static final class AggregatingRecorder implements PerfRecorder {

        private final ConcurrentHashMap<String, StageStats> stages = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, CounterStat> counters = new ConcurrentHashMap<>();
        private final Object slowLock = new Object();
        private SlowRecord[] slowest = new SlowRecord[0];

        @Override
        public long begin(String stage, long requestId) {
            return System.nanoTime();
        }

        @Override
        public void end(String stage, long requestId, long beginNs, String outcome) {
            long durNs = System.nanoTime() - beginNs;
            if (durNs < 0) {
                durNs = 0; // 时钟异常兜底，不记录负时长
            }
            statsFor(stage).record(durNs, !Perf.OUTCOME_OK.equals(outcome));
            noteSlow(stage, requestId, durNs, outcome);
        }

        @Override
        public void counter(String name, long value) {
            statsForCounter(name).record(value);
        }

        private StageStats statsFor(String stage) {
            StageStats s = stages.get(stage);
            if (s == null) {
                StageStats created = new StageStats(stage, SAMPLES_PER_STAGE);
                StageStats prev = stages.putIfAbsent(stage, created);
                return prev != null ? prev : created;
            }
            return s;
        }

        private CounterStat statsForCounter(String name) {
            CounterStat c = counters.get(name);
            if (c == null) {
                CounterStat created = new CounterStat();
                CounterStat prev = counters.putIfAbsent(name, created);
                return prev != null ? prev : created;
            }
            return c;
        }

        private void noteSlow(String stage, long requestId, long durNs, String outcome) {
            synchronized (slowLock) {
                int n = slowest.length;
                if (n < SLOWEST_KEEP) {
                    SlowRecord[] next = new SlowRecord[n + 1];
                    System.arraycopy(slowest, 0, next, 0, n);
                    next[n] = new SlowRecord(stage, requestId, durNs, outcome);
                    slowest = next;
                    return;
                }
                int minIdx = 0;
                for (int i = 1; i < n; i++) {
                    if (slowest[i].durNs < slowest[minIdx].durNs) {
                        minIdx = i;
                    }
                }
                if (slowest[minIdx].durNs < durNs) {
                    slowest[minIdx] = new SlowRecord(stage, requestId, durNs, outcome);
                }
            }
        }

        Map<String, StageSummary> snapshotAll() {
            Map<String, StageSummary> out = new LinkedHashMap<>();
            List<String> names = new ArrayList<>(stages.keySet());
            java.util.Collections.sort(names);
            for (String name : names) {
                out.put(name, stages.get(name).summary());
            }
            return out;
        }

        Map<String, CounterSummary> countersSnapshot() {
            Map<String, CounterSummary> out = new LinkedHashMap<>();
            List<String> names = new ArrayList<>(counters.keySet());
            java.util.Collections.sort(names);
            for (String name : names) {
                CounterStat c = counters.get(name);
                out.put(name, new CounterSummary(name, c.lastValue(), c.totalValue()));
            }
            return out;
        }

        List<SlowRecord> slowestSnapshot() {
            synchronized (slowLock) {
                SlowRecord[] copy = new SlowRecord[slowest.length];
                System.arraycopy(slowest, 0, copy, 0, slowest.length);
                List<SlowRecord> list = new ArrayList<>(Arrays.asList(copy));
                list.sort((a, b) -> Long.compare(b.durNs, a.durNs));
                return list;
            }
        }
    }

    private static final class StageStats {
        private final String stage;
        private final long[] samples;
        private int ringIdx;
        private int filled;
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong failCount = new AtomicLong();

        StageStats(String stage, int capacity) {
            this.stage = stage;
            this.samples = new long[capacity];
        }

        void record(long durNs, boolean failure) {
            count.incrementAndGet();
            if (failure) {
                failCount.incrementAndGet();
            }
            synchronized (this) {
                samples[ringIdx] = durNs;
                ringIdx = (ringIdx + 1) % samples.length;
                if (filled < samples.length) {
                    filled++;
                }
            }
        }

        StageSummary summary() {
            long n = count.get();
            long f = failCount.get();
            double p50;
            double p95;
            double max;
            synchronized (this) {
                int k = filled;
                long[] copy = new long[k];
                System.arraycopy(samples, 0, copy, 0, k);
                Arrays.sort(copy);
                p50 = percentileMs(copy, 0.50);
                p95 = percentileMs(copy, 0.95);
                max = k == 0 ? 0d : copy[k - 1] / 1_000_000d;
            }
            return new StageSummary(stage, n, f, p50, p95, max);
        }

        private static double percentileMs(long[] sorted, double p) {
            int k = sorted.length;
            if (k == 0) {
                return 0d;
            }
            int idx = (int) Math.ceil(p * k) - 1;
            if (idx < 0) {
                idx = 0;
            }
            if (idx >= k) {
                idx = k - 1;
            }
            return sorted[idx] / 1_000_000d;
        }
    }

    private static final class CounterStat {
        private volatile long last;
        private final AtomicLong total = new AtomicLong();

        void record(long v) {
            this.last = v;
            this.total.addAndGet(Math.max(0L, v));
        }

        long lastValue() {
            return last;
        }

        long totalValue() {
            return total.get();
        }
    }

    /** 最慢区段记录（不含 URL/标题等敏感内容）。 */
    public static final class SlowRecord {
        public final String stage;
        public final long requestId;
        public final long durNs;
        public final String outcome;

        SlowRecord(String stage, long requestId, long durNs, String outcome) {
            this.stage = stage;
            this.requestId = requestId;
            this.durNs = durNs;
            this.outcome = outcome;
        }
    }
}
