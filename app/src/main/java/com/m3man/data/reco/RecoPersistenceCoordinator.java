package com.m3man.data.reco;

import android.content.Context;

import com.m3man.perf.ExecutorRegistry;
import com.m3man.perf.Perf;
import com.m3man.utils.AppLog;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.Completable;

/**
 * T06：推荐状态持久化单写者协调器（进程级，持 appContext，不持 View）。
 *
 * <p>契约（方案 §5-T06）：
 * <ul>
 *   <li>{@code requestSave(reason, urgent)}：普通请求 2 秒合并、urgent 最多 500ms 后执行；
 *       已有更早调度且能覆盖本请求时直接合并——执行时才取最新快照，
 *       窗口内请求的状态一定随该次执行落盘（不存在「直接 return 后永远没有下一次」）。</li>
 *   <li>单线程 writer 在实际执行时获取最新快照并写盘，避免「先快照后排队」旧版本后写。</li>
 *   <li>所有写入口（含 reset）都应经此 writer；业务不再自行 subscribe(save) 挂进 View 的
 *       CompositeDisposable（销毁 clear 不再取消保存）。</li>
 *   <li>写失败保留 dirty 并有限重试（1/2/4 秒最多 3 次，之后等下次触发并记录错误），不忙循环。</li>
 *   <li>进程强杀仍可能丢失未提交窗口；本类不承诺绝对持久化。</li>
 * </ul>
 *
 * <p>线程模型：调度决策在锁内（毫秒级）；实际 saveNow（快照+JSON+磁盘）在
 * {@link ExecutorRegistry.Pool#STATE_WRITER} 单线程执行。测试可注入假时钟与调度器。
 *
 * @author 3mman
 */
public final class RecoPersistenceCoordinator {

    private static final String TAG = "RecoPersist";
    /** 普通请求合并窗口 */
    private static final long NORMAL_DELAY_MS = 2000L;
    /** urgent 请求最长延迟 */
    private static final long URGENT_DELAY_MS = 500L;
    /** 写失败重试退避序列（毫秒） */
    private static final long[] RETRY_BACKOFF_MS = {1000L, 2000L, 4000L};

    private static volatile RecoPersistenceCoordinator sInstance;

    /** 进程级单例（appContext 仅用于确定进程存在性，不持 View/Activity） */
    public static RecoPersistenceCoordinator get(Context appContext) {
        if (sInstance == null) {
            synchronized (RecoPersistenceCoordinator.class) {
                if (sInstance == null) {
                    sInstance = new RecoPersistenceCoordinator(appContext.getApplicationContext(),
                            ExecutorRegistry.stateWriterScheduler(), null);
                }
            }
        }
        return sInstance;
    }

    /** 假时钟接口（测试注入；elapsedRealtime 语义） */
    interface Clock {
        long now();
    }

    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final Object lock = new Object();
    /** 当前已调度的执行时刻；0=无调度 */
    private long scheduledAt;
    /** 调度令牌：作废旧回调 */
    private long version;
    /** 连续失败计数（成功清零） */
    private int failCount;
    /** 串行执行栅栏：保证同屏页多次 requestSave 不并发 saveNow */
    private final AtomicBoolean saving = new AtomicBoolean(false);

    RecoPersistenceCoordinator(Context appContext, ScheduledExecutorService scheduler, Clock clock) {
        this.scheduler = scheduler;
        this.clock = clock != null ? clock : new Clock() {
            @Override
            public long now() {
                return android.os.SystemClock.elapsedRealtime();
            }
        };
    }

    /**
     * 请求一次保存。urgent=true（如退出页面/用户反馈）最多 500ms 后执行；
     * 普通（如曝光）2 秒合并。窗口内重复请求会被已调度执行覆盖（执行时取最新快照）；
     * 若新请求更紧急则把调度提前（尾沿语义：永不丢请求，只提前）。
     */
    public void requestSave(final RecoStore store, final String reason, boolean urgent) {
        if (store == null) {
            return;
        }
        final long now = clock.now();
        final long due = now + (urgent ? URGENT_DELAY_MS : NORMAL_DELAY_MS);
        final long v;
        synchronized (lock) {
            if (scheduledAt != 0L && due >= scheduledAt) {
                return; // 已有更早（或相同）的调度会覆盖本请求
            }
            scheduledAt = due;
            v = ++version;
        }
        try {
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    runScheduledSave(store, reason, v);
                }
            }, Math.max(0L, due - now), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // 调度器已关闭（进程退出中）：尽力同步保存一次，失败则等下次触发
            synchronized (lock) {
                scheduledAt = 0L;
            }
            AppLog.e(TAG, "schedule save failed: " + reason);
        }
    }

    private void runScheduledSave(RecoStore store, String reason, long v) {
        synchronized (lock) {
            if (v != version) {
                return; // 已被更紧急的调度取代
            }
            scheduledAt = 0L;
        }
        doSave(store, reason);
    }

    private void doSave(final RecoStore store, final String reason) {
        if (!saving.compareAndSet(false, true)) {
            return; // 已有保存在执行（其执行时快照即最新状态）
        }
        try {
            long req = Perf.nextRequestId();
            long begin = Perf.recorder().begin(Perf.ST_RECO_FLUSH, req);
            boolean ok = false;
            try {
                ok = store.saveNow(reason);
            } finally {
                Perf.recorder().end(Perf.ST_RECO_FLUSH, req, begin,
                        ok ? Perf.OUTCOME_OK : Perf.OUTCOME_FAIL);
            }
            if (ok) {
                synchronized (lock) {
                    failCount = 0;
                }
            } else {
                scheduleRetry(store, reason);
            }
        } finally {
            saving.set(false);
        }
    }

    private void scheduleRetry(final RecoStore store, final String reason) {
        final int attempt;
        synchronized (lock) {
            failCount++;
            attempt = failCount;
        }
        if (attempt > RETRY_BACKOFF_MS.length) {
            // 有限重试耗尽：保留 dirty，等下次 requestSave 触发；不忙循环
            AppLog.e(TAG, "save retry exhausted (" + reason + ")，等待下次触发");
            return;
        }
        long backoff = RETRY_BACKOFF_MS[attempt - 1];
        final long v;
        synchronized (lock) {
            v = ++version;
            scheduledAt = clock.now() + backoff;
        }
        try {
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    runScheduledSave(store, reason, v);
                }
            }, backoff, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            AppLog.e(TAG, "schedule retry failed: " + reason);
        }
    }

    /**
     * 测试用：同步执行一次保存（在 writer 线程），完成或失败后完成流。
     * 业务代码不得使用。
     */
    public Completable flushForTest(final RecoStore store) {
        return Completable.fromRunnable(() -> doSave(store, "flushForTest"))
                .subscribeOn(io.reactivex.schedulers.Schedulers.from(scheduler));
    }
}
