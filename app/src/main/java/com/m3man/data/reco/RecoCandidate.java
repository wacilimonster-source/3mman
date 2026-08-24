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

    /** 方向：未知（封面尺寸尚未探测到） */
    public static final int ORIENT_UNKNOWN = 0;
    /** 方向：竖屏（宽高比 < 0.9） */
    public static final int ORIENT_PORTRAIT = 1;
    /** 方向：横屏（宽高比 > 1.1，方形封面归入横屏） */
    public static final int ORIENT_LANDSCAPE = 2;
    /** M78：封面方向，默认未知，由 RecoRepository 探测封面尺寸后填充 */
    public int orientation = ORIENT_UNKNOWN;

    /** 根据封面宽高比判断方向：<0.9 竖 / >1.1 横 / 其余（含方形）归横屏 */
    public static int classifyOrientation(int w, int h) {
        if (w <= 0 || h <= 0) {
            return ORIENT_UNKNOWN;
        }
        float ratio = (float) w / (float) h;
        if (ratio < 0.9f) {
            return ORIENT_PORTRAIT;
        }
        return ORIENT_LANDSCAPE;
    }

    public List<String> tags;
    /** 已知作者（仅当本地库里已解析过该视频时才有） */
    public String authorKey;
    public String authorName;
    /**
     * M77：从 info 里解析出的真实发布年份（解析不到为 -1）。
     * 新鲜度改用它做「越老扣越多」的软衰减，不再用列表位置近似。
     */
    public int publishYear = -1;

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
