package com.m3man.perf;

/**
 * T00 性能埋点契约（design/性能优化技术方案_2026-11-05.md §4）。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code begin/end} 一次配对一个区段；跨线程用 requestId 关联单独区段，
 *       不跨线程配对（与 Trace.begin/end 的线程绑定语义一致）。</li>
 *   <li>采样聚合后本地记录，不每帧写日志、不自动上传；
 *       不记录完整签名 URL、Cookie 或用户标题。</li>
 *   <li>生产包仅保留低开销计数与阶段计时（纳秒级取时 + 环形数组写）。</li>
 * </ul>
 *
 * @author 3mman
 */
public interface PerfRecorder {

    /**
     * 开始一个阶段区段。
     *
     * @param stage     阶段名，使用 {@link Perf} 中的 ST_* 常量，不要自造字符串
     * @param requestId 关联 id（跨线程拆段时使用同一 id）
     * @return 传给 {@link #end} 的起始时间戳（{@link System#nanoTime()} 基）
     */
    long begin(String stage, long requestId);

    /**
     * 结束一个阶段区段并记录耗时。
     *
     * @param stage   与 begin 相同的阶段名
     * @param requestId 与 begin 相同的关联 id
     * @param beginNs begin 返回的时间戳
     * @param outcome 结果：{@link Perf#OUTCOME_OK} / {@link Perf#OUTCOME_FAIL} / {@link Perf#OUTCOME_CANCEL} 等
     */
    void end(String stage, long requestId, long beginNs, String outcome);

    /** 记录一个计数（次数/字节数/队列深度等，语义由调用方命名）。 */
    void counter(String name, long value);
}
