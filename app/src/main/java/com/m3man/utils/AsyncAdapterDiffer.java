package com.m3man.utils;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.m3man.perf.ExecutorRegistry;
import com.m3man.perf.Perf;
import com.m3man.perf.PerfRecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * T02：异步 Diff 计算器（方案 §5-T02）。
 *
 * <p>契约：
 * <ul>
 *   <li>主线程捕获旧列表快照与新目标列表（不可变快照：快照后调用方再改原集合不影响计算）；</li>
 *   <li>后台 {@link ExecutorRegistry.Pool#DIFF}（≤2 线程）calculateDiff；
 *       计算线程最多保留正在计算的任务与一个最新目标——新 submit 只更新 pendingTarget，
 *       不为每次刷新无限排队；</li>
 *   <li>回主线程校验 submitGeneration 与 adapter 生命周期，过期结果丢弃；
 *       原子切换列表后 dispatch；空 ↔ 非空仍走 setNewData（保留 BRVAH 空视图逻辑）；</li>
 *   <li>计算期间列表被外部改动（追加/清空）时重新捕获一轮 diff，超过 3 轮退回 setNewData 兜底；</li>
 *   <li>派发语义与 M115 同步版完全一致（dispatchUpdatesTo(adapter)，header 偏移由
 *       BRVAH/RecyclerView 既有处理承担），行为不变、位置不抖。</li>
 * </ul>
 *
 * <p>线程模型：submit/pump/onComputed 全部只在主线程执行（字段无并发访问），
 * 仅 calculateDiff 在 DIFF 池；disposed 为 volatile，允许任意线程触发销毁。
 *
 * @author 3mman
 */
public final class AsyncAdapterDiffer<T> {

    private static final PerfRecorder PERF = Perf.recorder();
    /** 追加期间刷新保护的最大重捕获轮数，超过退回 setNewData */
    private static final int MAX_REDIFF_ROUNDS = 3;

    /** 弱引用 adapter：AdapterDiffUtil 的 WeakHashMap 若被 value 强引用 key 会永久泄漏。 */
    private final java.lang.ref.WeakReference<BaseQuickAdapter<T, ?>> adapterRef;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService diffPool;

    // ---- 主线程独占字段 ----
    private long submitGeneration;
    private boolean computing;
    private List<T> pendingTarget;
    private DiffUtil.ItemCallback<T> pendingCallback;
    private int rediffRounds;

    private volatile boolean disposed;

    public AsyncAdapterDiffer(BaseQuickAdapter<T, ?> adapter) {
        this(adapter, ExecutorRegistry.get(ExecutorRegistry.Pool.DIFF));
    }

    /** 测试注入用。 */
    AsyncAdapterDiffer(BaseQuickAdapter<T, ?> adapter, ExecutorService diffPool) {
        this.adapterRef = new java.lang.ref.WeakReference<>(adapter);
        this.diffPool = diffPool;
    }

    /**
     * 提交一次刷新目标（替代同步 AdapterDiffUtil.apply）。必须在主线程调用。
     * A慢/B快两次提交：A 的计算结果按代际丢弃，最终只显示 B。
     */
    public void submit(@Nullable List<T> newData, DiffUtil.ItemCallback<T> itemCallback) {
        checkMain();
        List<T> target = newData == null ? new ArrayList<T>() : new ArrayList<T>(newData);
        pendingTarget = target;
        pendingCallback = itemCallback;
        rediffRounds = 0;
        submitGeneration++;
        if (!computing) {
            pump();
        }
    }

    /**
     * 追加统一入口：取消未开始的 diff 目标后直接在主线程追加，
     * 避免「一边 diff 一边直接改 adapter.getData()」造成快照失真。
     */
    public void append(List<T> items) {
        checkMain();
        if (items == null || items.isEmpty()) {
            return;
        }
        pendingTarget = null;
        pendingCallback = null;
        submitGeneration++; // 使在途计算作废（其结果基于旧快照，派发会撞上追加后的列表）
        BaseQuickAdapter<T, ?> adapter = adapter();
        if (adapter == null) {
            return;
        }
        adapter.getData().addAll(items);
        adapter.notifyDataSetChanged();
    }

    private BaseQuickAdapter<T, ?> adapter() {
        return adapterRef.get();
    }

    /** 销毁时调用；在途结果丢弃，不再触碰 adapter。 */
    public void dispose() {
        disposed = true;
        mainHandler.removeCallbacksAndMessages(null);
    }

    // ==================== 内部：主线程调度 ====================

    private void pump() {
        if (disposed || pendingTarget == null) {
            return;
        }
        BaseQuickAdapter<T, ?> adapter = adapter();
        if (adapter == null) {
            disposed = true;
            return;
        }
        computing = true;
        // 主线程捕获不可变快照（浅拷贝隔离集合结构；实体的字段并发变化由代际校验兜底）
        final List<T> oldSnap = new ArrayList<T>(adapter.getData());
        final int oldSize = oldSnap.size();
        final List<T> newSnap = pendingTarget;
        final DiffUtil.ItemCallback<T> cb = pendingCallback;
        pendingTarget = null;
        pendingCallback = null;
        final long gen = submitGeneration;
        final long rounds = rediffRounds;

        final long req = Perf.nextRequestId();
        final long begin = PERF.begin(Perf.ST_DIFF_COMPUTE, req);
        diffPool.execute(new Runnable() {
            @Override
            public void run() {
                DiffUtil.DiffResult result = null;
                String outcome = Perf.OUTCOME_OK;
                try {
                    if (!oldSnap.isEmpty() && !newSnap.isEmpty()) {
                        result = DiffUtil.calculateDiff(
                                new AdapterDiffUtil.WrapperCallback<>(oldSnap, newSnap, cb), false);
                    }
                } catch (Exception e) {
                    outcome = Perf.OUTCOME_FAIL;
                }
                PERF.end(Perf.ST_DIFF_COMPUTE, req, begin, outcome);
                final DiffUtil.DiffResult fr = result;
                final String fo = outcome;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onComputed(gen, rounds, oldSnap, oldSize, newSnap, cb, fr, fo);
                    }
                });
            }
        });
    }

    private void onComputed(long gen, long rounds, List<T> oldSnap, int oldSize,
                            List<T> newSnap, DiffUtil.ItemCallback<T> cb,
                            DiffUtil.DiffResult result, String outcome) {
        computing = false;
        BaseQuickAdapter<T, ?> adapter = adapter();
        if (adapter == null) {
            disposed = true;
            return;
        }
        if (disposed || gen != submitGeneration) {
            pump(); // 过期结果丢弃；若有新目标继续
            return;
        }
        if (outcome.equals(Perf.OUTCOME_FAIL) || result == null) {
            // 计算异常：退回全量刷新（与旧版空↔非空路径一致），不吞列表
            applySetNewData(newSnap);
        } else if (adapter.getData().size() != oldSize) {
            // 计算期间列表被外部改动（追加/清空）：重新捕获一轮，超限退回全量
            rediffRounds = (int) rounds + 1;
            if (rediffRounds <= MAX_REDIFF_ROUNDS) {
                pendingTarget = newSnap;
                pendingCallback = cb;
                pump();
                return;
            }
            applySetNewData(newSnap);
        } else {
            long applyReq = Perf.nextRequestId();
            long applyBegin = PERF.begin(Perf.ST_DIFF_APPLY, applyReq);
            try {
                // 与 M115 同步版顺序一致：先原子切换列表内容，再派发细粒度更新
                List<T> current = adapter.getData();
                current.clear();
                current.addAll(newSnap);
                result.dispatchUpdatesTo(adapter);
            } finally {
                PERF.end(Perf.ST_DIFF_APPLY, applyReq, applyBegin, Perf.OUTCOME_OK);
            }
        }
        rediffRounds = 0;
        pump();
    }

    private void applySetNewData(List<T> target) {
        BaseQuickAdapter<T, ?> adapter = adapter();
        if (adapter != null) {
            adapter.setNewData(target);
        }
    }

    private static void checkMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("AsyncAdapterDiffer must be used on the main thread");
        }
    }
}
