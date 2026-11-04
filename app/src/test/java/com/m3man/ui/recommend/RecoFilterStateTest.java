package com.m3man.ui.recommend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.m3man.utils.PlayUiPrefs;

import org.junit.Test;

/**
 * EX-03 草稿状态单测：取消/重置只影响草稿；只有结构或连播真的变化才应提交。
 */
public class RecoFilterStateTest {

    @Test
    public void resetDraft_doesNotTouchAppliedState() {
        RecoFilterState applied = new RecoFilterState(
                PlayUiPrefs.FILTER_PORTRAIT, 5, true);
        RecoFilterState draft = applied.withMaxDurationMinutes(10).withAutoplay(false);

        RecoFilterState reset = draft.resetDraft();

        assertTrue(reset.orientation == PlayUiPrefs.FILTER_ALL);
        assertTrue(reset.maxDurationMinutes == 0);
        assertFalse(reset.autoplay);
        // 已应用值保持原样
        assertTrue(applied.orientation == PlayUiPrefs.FILTER_PORTRAIT);
        assertTrue(applied.maxDurationMinutes == 5);
        assertTrue(applied.autoplay);
    }

    @Test
    public void sameAs_detectsNoChangeAndAutoplayOnly() {
        RecoFilterState applied = new RecoFilterState(
                PlayUiPrefs.FILTER_LANDSCAPE, 3, true);

        assertTrue(applied.sameAs(new RecoFilterState(
                PlayUiPrefs.FILTER_LANDSCAPE, 3, true)));
        assertFalse(applied.sameAs(new RecoFilterState(
                PlayUiPrefs.FILTER_LANDSCAPE, 3, false)));
        assertFalse(applied.sameAs(new RecoFilterState(
                PlayUiPrefs.FILTER_ALL, 3, true)));
        assertFalse(applied.sameAs(new RecoFilterState(
                PlayUiPrefs.FILTER_LANDSCAPE, 0, true)));
    }

    @Test
    public void sameStructure_ignoresAutoplay() {
        RecoFilterState applied = new RecoFilterState(
                PlayUiPrefs.FILTER_ALL, 1, false);
        assertTrue(applied.sameStructure(new RecoFilterState(
                PlayUiPrefs.FILTER_ALL, 1, true)));
    }
}
