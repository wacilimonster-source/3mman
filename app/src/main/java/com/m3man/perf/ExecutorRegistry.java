package com.m3man.perf;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * T00 进程级执行器注册表（方案 §4）。
 *
 * <p>契约：
 * <ul>
 *   <li>进程级单例；不持有 Activity/View/Context（纯线程池，无 Android 依赖）。</li>
 *   <li>池规格（方案 §4 固定值）：
 *       dbWrite=单线程；stateWriter=单线程调度；diff=最多2线程；
 *       parseForeground=1并发；parseBackground=2并发；warm=1并发。</li>
 *   <li>不强行把现有 Rx 链迁入统一线程池；各业务任务只接管其声明范围。</li>
 *   <li>线程空闲 30s 回收，避免常驻；线程名可读，便于 Perfetto/systrace 定位。</li>
 * </ul>
 *
 * @author 3mman
 */
public final class ExecutorRegistry {

    /** 池类型；规格见类注释，新增池必须先在本方案中登记用途。 */
    public enum Pool {
        /** 单线程：DB 进度写、终态提交等必须串行的落盘操作 */
        DB_WRITE,
        /** 单线程：状态合并写盘（T06 RecoStore 单写者） */
        STATE_WRITER,
        /** 最多 2 线程：DiffUtil 后台计算（T02） */
        DIFF,
        /** 1 并发：当前页解析（T04，优先级最高） */
        PARSE_FOREGROUND,
        /** 2 并发：后台解析（T04） */
        PARSE_BACKGROUND,
        /** 1 并发：字节预热等后台 IO（T04） */
        WARM,
    }

    private static final long KEEP_ALIVE_SECONDS = 30L;

    private static volatile ScheduledExecutorService stateWriter;
    private static volatile ScheduledExecutorService stateWriterForTest;

    private ExecutorRegistry() {
    }

    /** 取指定池。池按需惰性创建，进程内复用。 */
    public static ExecutorService get(Pool pool) {
        switch (pool) {
            case DB_WRITE:
                return Holder.DB_WRITE;
            case STATE_WRITER:
                return stateWriterScheduler();
            case DIFF:
                return Holder.DIFF;
            case PARSE_FOREGROUND:
                return Holder.PARSE_FOREGROUND;
            case PARSE_BACKGROUND:
                return Holder.PARSE_BACKGROUND;
            case WARM:
                return Holder.WARM;
            default:
                throw new IllegalArgumentException("unknown pool " + pool);
        }
    }

    /** 单线程调度器（stateWriter 合并窗口/尾沿保存用）。 */
    public static ScheduledExecutorService stateWriterScheduler() {
        ScheduledExecutorService s = stateWriter;
        if (s == null) {
            synchronized (ExecutorRegistry.class) {
                s = stateWriter;
                if (s == null) {
                    s = stateWriterForTest != null
                            ? stateWriterForTest
                            : Executors.newSingleThreadScheduledExecutor(namedFactory("perf-state-writer"));
                    stateWriter = s;
                }
            }
        }
        return s;
    }

    /** 仅测试注入；生产代码不得调用。传 null 恢复真实调度器。 */
    static void setStateWriterForTest(ScheduledExecutorService scheduler) {
        synchronized (ExecutorRegistry.class) {
            stateWriterForTest = scheduler;
            stateWriter = null; // 下次 get 重建时采用测试实例
        }
    }

    private static ExecutorService newBounded(Pool pool, int threads) {
        ThreadPoolExecutor ex = new ThreadPoolExecutor(threads, threads,
                KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(),
                namedFactory(pool.name().toLowerCase().replace('_', '-') + "-"));
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    private static ThreadFactory namedFactory(final String prefix) {
        final AtomicInteger seq = new AtomicInteger(1);
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + seq.getAndIncrement());
                t.setPriority(Thread.NORM_PRIORITY - 1); // 后台偏移，不与主线程争核
                return t;
            }
        };
    }

    private static final class Holder {
        static final ExecutorService DB_WRITE = newBounded(Pool.DB_WRITE, 1);
        static final ExecutorService DIFF = newBounded(Pool.DIFF, 2);
        static final ExecutorService PARSE_FOREGROUND = newBounded(Pool.PARSE_FOREGROUND, 1);
        static final ExecutorService PARSE_BACKGROUND = newBounded(Pool.PARSE_BACKGROUND, 2);
        static final ExecutorService WARM = newBounded(Pool.WARM, 1);
    }
}
