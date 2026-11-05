package com.m3man.data.db;

import android.os.Looper;

import com.m3man.BuildConfig;
import com.m3man.data.db.entity.DaoMaster;
import com.m3man.data.db.entity.DaoSession;
import com.m3man.perf.Perf;
import com.m3man.utils.AppLog;

import org.greenrobot.greendao.database.Database;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.Completable;
import io.reactivex.schedulers.Schedulers;

/**
 * T07：DB 惰性就绪链（方案 §5-T07）。
 *
 * <p>把「同步开库」从 DI 注入关键路径移出：构造只保存 helper，不调用 getWritableDb。
 * <ul>
 *   <li>{@link #ensureReady()}：首次开库/迁移在 db-init 单线程执行器上只执行一次，
 *       可作为 Completable 观察或 {@link #awaitSession()} 阻塞等待。</li>
 *   <li>{@link #requireReadySession()}：仅供 worker 使用；未 ready 或主线程误用时
 *       调试构建立刻报错并指出调用者（fail-fast，不静默搬成本）。</li>
 *   <li>{@link #awaitSession()}：既有同步 DbHelper 接口的兼容路径——IO 线程等待 ready；
 *       主线程调用在 PERF_STRICT 构建记错误日志（StrictMode 之外的第二道检测），
 *       release 下仍兜底放行（保持既有可用性，等待成本即旧路径成本）。</li>
 * </ul>
 * 默认分类种子与误标修复仍由 AppDbHelper 在后台线程于 ready 后串行执行（顺序与旧版一致）。
 *
 * @author 3mman
 */
public final class DbRuntime {

    private static final String TAG = "DbRuntime";
    /** 开库等待上限（迁移大库时放宽；超时按失败抛出，不无限等） */
    private static final long READY_TIMEOUT_SEC = 60L;

    private final MySQLiteOpenHelper helper;
    private final ExecutorService initExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "db-init");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private final Object lock = new Object();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private volatile DaoSession session;
    private volatile String initError;

    public DbRuntime(MySQLiteOpenHelper helper) {
        this.helper = helper;
    }

    /** 触发一次开库（幂等）；不阻塞调用线程，完成可用 {@link #awaitSession()} 等待。 */
    public void startOpen() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        initExecutor.execute(new Runnable() {
            @Override
            public void run() {
                long req = Perf.nextRequestId();
                long begin = Perf.recorder().begin(Perf.ST_DB_OPEN, req);
                try {
                    Database db = helper.getWritableDb();
                    session = new DaoMaster(db).newSession();
                    // 全工程单 DaoSession 约束（见 AppDbHelper 构造注释）；此处仅清空一次
                    session.clear();
                    Perf.recorder().end(Perf.ST_DB_OPEN, req, begin, Perf.OUTCOME_OK);
                } catch (Throwable t) {
                    initError = t == null ? "null" : t.getClass().getSimpleName()
                            + ": " + t.getMessage();
                    Perf.recorder().end(Perf.ST_DB_OPEN, req, begin, Perf.OUTCOME_FAIL);
                    AppLog.e(TAG, "DB 初始化失败: " + initError);
                } finally {
                    readyLatch.countDown();
                    initExecutor.shutdown();
                }
            }
        });
    }

    /** 就绪链（幂等）；订阅方在 db-init 线程等待完成。失败以 onError 传递。 */
    public Completable ensureReady() {
        startOpen();
        return Completable.fromAction(() -> {
            if (!readyLatch.await(READY_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                throw new IllegalStateException("DB ready 超时(" + READY_TIMEOUT_SEC + "s)");
            }
            if (session == null) {
                throw new IllegalStateException("DB 初始化失败: " + initError);
            }
        }).subscribeOn(Schedulers.from(startOpenExecutor()));
    }

    /** ensureReady 的等待放在 db-init 执行器上（未启动则先启动），保证开库只一次 */
    private ExecutorService startOpenExecutor() {
        startOpen();
        return initExecutor;
    }

    /**
     * 供 worker 使用：未 ready 或主线程误用时调试构建立刻报错。
     * 返回 null 表示尚未 ready（调用方应改走异步 ensureReady 路径）。
     */
    public DaoSession requireReadySession() {
        DaoSession s = session;
        if (s != null) {
            return s;
        }
        boolean onMain = Looper.myLooper() == Looper.getMainLooper();
        if (BuildConfig.PERF_STRICT) {
            // 打印调用栈，直接指出误用者
            IllegalStateException probe = new IllegalStateException(
                    "DB not ready or main-thread misuse onMain=" + onMain);
            AppLog.e(TAG, "requireReadySession 未 ready 或主线程误用 caller=" + caller()
                    + " cause=" + AppLog.cause(probe));
        }
        return null;
    }

    /**
     * 同步兼容路径：阻塞等待就绪并返回 session（IO 线程用）。
     * 主线程误用在 PERF_STRICT 构建记错误（不崩溃，兜底保持旧可用性）。
     */
    public DaoSession awaitSession() {
        boolean onMain = Looper.myLooper() == Looper.getMainLooper();
        if (onMain && BuildConfig.PERF_STRICT && session == null) {
            IllegalStateException probe = new IllegalStateException("awaitSession on main thread");
            AppLog.e(TAG, "主线程同步等待开库（应移至 IO 线程）caller=" + caller()
                    + " cause=" + AppLog.cause(probe));
        }
        startOpen();
        try {
            if (!readyLatch.await(READY_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                AppLog.e(TAG, "awaitSession 超时（" + READY_TIMEOUT_SEC + "s）caller=" + caller());
                return session; // 可能为 null：调用方按既有空处理路径走
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return session;
        }
        return session;
    }

    private static String caller() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        for (int i = 2; i < st.length && i < 6; i++) {
            if (!st[i].getClassName().equals(DbRuntime.class.getName())) {
                return st[i].getClassName() + "." + st[i].getMethodName() + ":" + st[i].getLineNumber();
            }
        }
        return "unknown";
    }
}
