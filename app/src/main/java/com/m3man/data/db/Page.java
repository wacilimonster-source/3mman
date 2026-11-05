package com.m3man.data.db;

import java.util.Collections;
import java.util.List;

/**
 * T08：游标分页结果（方案 §5-T08）。limit+1 探测 hasMore；
 * cursor 语义由各查询定义（历史/完成：(date,id)；收藏：id）。
 *
 * @author 3mman
 */
public final class Page<T> {

    public static final Page<?> EMPTY = new Page<>(Collections.emptyList(), false);

    public final List<T> items;
    public final boolean hasMore;

    public Page(List<T> items, boolean hasMore) {
        this.items = items == null ? Collections.<T>emptyList() : items;
        this.hasMore = hasMore;
    }

    @SuppressWarnings("unchecked")
    public static <T> Page<T> empty() {
        return (Page<T>) EMPTY;
    }

    /**
     * limit+1 探测：查询取 limit+1 行，多出的一行只用于 hasMore 判定后丢弃。
     */
    public static <T> Page<T> fromProbe(List<T> probe, int limit) {
        if (probe == null || probe.isEmpty()) {
            return Page.empty();
        }
        if (probe.size() > limit) {
            return new Page<>(probe.subList(0, limit), true);
        }
        return new Page<>(probe, false);
    }
}
