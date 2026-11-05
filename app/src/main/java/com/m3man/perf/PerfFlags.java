package com.m3man.perf;

/**
 * T00 性能任务特性开关（方案 §4）。
 *
 * <p>规则：
 * <ul>
 *   <li>默认开发分支逐项打开；正式发布配置在此处决定默认值，不新增产品设置项。</li>
 *   <li>开关关闭时必须保留可读的数据兼容性（旧缓存/旧文件能读）。</li>
 *   <li>正确性修复类路径（下载终态保护、状态原子写盘）不允许退回不安全实现——
 *       对应 flag 关闭只改变调度/时机，不改变数据安全语义。</li>
 * </ul>
 *
 * @author 3mman
 */
public final class PerfFlags {

    /** T01：推荐缓存首屏脱离引擎关键路径 */
    public static volatile boolean cacheFirst = true;
    /** T02：DiffUtil 后台计算 + 不可变列表快照 */
    public static volatile boolean asyncDiff = true;
    /** T03：封面展示/预取共享请求规格 */
    public static volatile boolean coverRequestV2 = true;
    /** T04：推荐预取窗口调度 */
    public static volatile boolean prefetchScheduler = true;
    /** T05：HLS 有界下载与串行状态 */
    public static volatile boolean hlsScheduler = true;
    /** T06：推荐状态单写者（关闭时仍走安全写盘路径，仅回退合并窗口） */
    public static volatile boolean stateWriter = true;
    /** T07：懒开库 ready 链 */
    public static volatile boolean lazyDb = true;
    /** T08：本地列表分页查询 */
    public static volatile boolean pagedLocalList = true;
    /** T09：推荐长会话窗口存储 */
    public static volatile boolean feedWindow = true;

    private PerfFlags() {
    }
}
