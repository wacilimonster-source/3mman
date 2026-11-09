package com.m3man.ui.recommend;

import com.m3man.utils.PlayUiPrefs;

/**
 * 推荐筛选的草稿状态（EX-03）。
 * <p>
 * 面板打开时从已应用值复制一份 draft；面板内的选择只改 draft；
 * Back/遮罩/关闭丢弃 draft；只有「应用」才把它与 applied 比较并提交。
 * 纯 Java 对象，方便单测覆盖键位边界与「无变化不提交」。
 */
final class RecoFilterState {

    final int orientation;
    final int maxDurationMinutes;
    final boolean autoplay;

    RecoFilterState(int orientation, int maxDurationMinutes, boolean autoplay) {
        this.orientation = orientation;
        this.maxDurationMinutes = maxDurationMinutes;
        this.autoplay = autoplay;
    }

    static RecoFilterState defaults() {
        return new RecoFilterState(PlayUiPrefs.FILTER_ALL, 0, false);
    }

    RecoFilterState withOrientation(int value) {
        return new RecoFilterState(value, maxDurationMinutes, autoplay);
    }

    RecoFilterState withMaxDurationMinutes(int value) {
        return new RecoFilterState(orientation, value, autoplay);
    }

    RecoFilterState withAutoplay(boolean value) {
        return new RecoFilterState(orientation, maxDurationMinutes, value);
    }

    /** 「重置草稿」：全部方向 / 时长不限 / 保留当前循环开关，但不提交。 */
    RecoFilterState resetDraft() {
        // 循环开关是独立播放偏好，不属于“方向/时长”筛选；重置不应把默认开启的连播关掉。
        return new RecoFilterState(PlayUiPrefs.FILTER_ALL, 0, autoplay);
    }

    boolean sameStructure(RecoFilterState other) {
        return other != null && orientation == other.orientation
                && maxDurationMinutes == other.maxDurationMinutes;
    }

    boolean sameAs(RecoFilterState other) {
        return sameStructure(other) && other.autoplay == autoplay;
    }
}
