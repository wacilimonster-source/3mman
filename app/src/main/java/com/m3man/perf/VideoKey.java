package com.m3man.perf;

/**
 * T00 不可变视频身份键 {@code VideoKey(source, canonicalViewKey)}（方案 §4）。
 *
 * <p>集中封装现有 viewKey 归一化规则（与解析器输出契约一致，纯只读收敛，不回写数据库）：
 * <ul>
 *   <li>mman9 源：{@code viewkey=<hex>}（ParseV9MmanVideo.extractViewKey 输出形态）</li>
 *   <li>91porny 源：裸 hex（/video/view/）、{@code videos/<id>/<slug>}、{@code vod/<key>}</li>
 *   <li>路径形态键：去查询参数、去尾斜杠、去尾部 "/." 残留（镜像 Parse91PornyVideo.normalizePathKey）</li>
 * </ul>
 *
 * <p>规则必须幂等：对解析器已产出的规范化键再次 canonicalize 结果不变。
 * 来源常量与 {@code AuthorFavorite.SOURCE_MMAN9/SOURCE_PORNY} 一致，避免重复定义漂移。
 *
 * @author 3mman
 */
public final class VideoKey {

    public static final String SOURCE_MMAN9 = "mman9";
    public static final String SOURCE_PORNY = "91porny";

    public final String source;
    public final String canonicalViewKey;

    private VideoKey(String source, String canonicalViewKey) {
        this.source = source;
        this.canonicalViewKey = canonicalViewKey;
    }

    /**
     * 构造身份键；rawKey 为空或 source 为空返回 null（调用方按无稳定身份处理，
     * 不得把所有无身份对象映射到同一个 NO_ID）。
     */
    public static VideoKey of(String source, String rawViewKey) {
        String canonical = canonicalize(rawViewKey);
        if (source == null || source.length() == 0 || canonical == null) {
            return null;
        }
        return new VideoKey(source, canonical);
    }

    /**
     * 归一化 viewKey（幂等，纯内存字符串操作）。
     * 兼容两种形态：viewkey= 前缀型与路径型；解析器已产出的键原样通过。
     */
    public static String canonicalize(String rawViewKey) {
        if (rawViewKey == null) {
            return null;
        }
        String key = rawViewKey.trim();
        if (key.length() == 0 || key.startsWith("?")) {
            // 空串或纯查询串残留（解析异常产物）都算无稳定身份
            return null;
        }
        if (key.startsWith("viewkey=")) {
            return key;
        }
        if (key.indexOf('/') < 0) {
            return key;
        }
        // 路径形态：与 Parse91PornyVideo.normalizePathKey 同语义
        int q = key.indexOf('?');
        if (q >= 0) {
            key = key.substring(0, q);
        }
        while (true) {
            if (key.endsWith("/")) {
                key = key.substring(0, key.length() - 1);
            } else if (key.endsWith("/.")) {
                key = key.substring(0, key.length() - 2);
            } else {
                break;
            }
            if (key.length() == 0) {
                return null;
            }
        }
        return key;
    }

    /** 与另一个键是否同源同键。 */
    public boolean sameAs(VideoKey other) {
        return this == other || (other != null
                && source.equals(other.source)
                && canonicalViewKey.equals(other.canonicalViewKey));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VideoKey)) {
            return false;
        }
        VideoKey that = (VideoKey) o;
        return source.equals(that.source) && canonicalViewKey.equals(that.canonicalViewKey);
    }

    @Override
    public int hashCode() {
        return source.hashCode() * 31 + canonicalViewKey.hashCode();
    }

    @Override
    public String toString() {
        // 不输出签名 URL/标题；键形如 mman9|viewkey=xxx（保留原大小写，hex 可能大小写敏感）
        return source + "|" + canonicalViewKey;
    }
}
