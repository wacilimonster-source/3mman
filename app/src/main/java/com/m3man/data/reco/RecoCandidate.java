package com.m3man.data.reco;

import com.m3man.data.db.entity.V9MmanItem;

import java.util.List;

/**
 * 推荐候选：视频 + 它的召回来源 + 抽取出的标签 + 打分明细。
 *
 * @author 3mman
 */
public class RecoCandidate {

    /** 召回来源：分类页 */
    public static final int FROM_CATEGORY = 0;
    /** 召回来源：作者页（画像中高权重作者） */
    public static final int FROM_AUTHOR = 1;
    /** 召回来源：探索（随机分类） */
    public static final int FROM_EXPLORE = 2;

    public final V9MmanItem item;
    public final String categoryValue;
    public final int from;

    public List<String> tags;
    /** 已知作者（仅当本地库里已解析过该视频时才有） */
    public String authorKey;
    public String authorName;

    public double score;
    public double tagScore;
    public double categoryScore;
    public double authorScore;
    public double recencyScore;
    public double noise;

    public RecoCandidate(V9MmanItem item, String categoryValue, int from) {
        this.item = item;
        this.categoryValue = categoryValue;
        this.from = from;
    }

    public String viewKey() {
        return item == null ? null : item.getViewKey();
    }

    public String title() {
        return item == null ? null : item.getTitle();
    }

    @Override
    public String toString() {
        return "RecoCandidate{" + title() + ", score=" + String.format(java.util.Locale.US, "%.3f", score)
                + ", tag=" + String.format(java.util.Locale.US, "%.2f", tagScore)
                + ", cat=" + String.format(java.util.Locale.US, "%.2f", categoryScore)
                + ", author=" + String.format(java.util.Locale.US, "%.2f", authorScore)
                + ", from=" + from + '}';
    }
}
