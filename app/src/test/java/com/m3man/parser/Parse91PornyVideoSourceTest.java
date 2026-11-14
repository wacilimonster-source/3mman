package com.m3man.parser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.perf.VideoKey;

import org.junit.Test;

/**
 * R-fix（LESSONS §3-3）：isPornySource 收敛为全仓库唯一权威实现后的状态组合回归。
 * 覆盖历史事故形态：裸 hex 的 9mman 视频曾被误判成 porny（播放走错源、作者页 404）。
 */
public class Parse91PornyVideoSourceTest {

    @Test
    public void markedPorny_viaSourceNameOrSource() {
        V9MmanItem byName = new V9MmanItem();
        byName.setSourceName(Parse91PornyVideo.SOURCE);
        assertTrue(Parse91PornyVideo.isPornySource(byName));

        V9MmanItem bySource = new V9MmanItem();
        bySource.setSource(Parse91PornyVideo.SOURCE);
        assertTrue(Parse91PornyVideo.isPornySource(bySource));
    }

    @Test
    public void mman9Mark_shortCircuitsBeforeHexGuess() {
        // M66b 历史漏洞回归：裸 hex + mman9 标记绝不能再走格式兜底误判成 porny
        V9MmanItem bareHexWithMark = new V9MmanItem();
        bareHexWithMark.setViewKey("c9236ce81db652e4b5d9");
        bareHexWithMark.setSourceName(VideoKey.SOURCE_MMAN9);
        assertFalse(Parse91PornyVideo.isPornySource(bareHexWithMark));

        V9MmanItem prefixedWithMark = new V9MmanItem();
        prefixedWithMark.setViewKey("viewkey=c9236ce81db652e4b5d9");
        prefixedWithMark.setSourceName(VideoKey.SOURCE_MMAN9);
        assertFalse(Parse91PornyVideo.isPornySource(prefixedWithMark));
    }

    @Test
    public void unmarked_bareHexFallsBackToPorny() {
        V9MmanItem item = new V9MmanItem();
        item.setViewKey("abc123def456abc123def456");
        assertTrue(Parse91PornyVideo.isPornySource(item));
    }

    @Test
    public void unmarked_prefixedHexIsAlwaysMman9() {
        V9MmanItem item = new V9MmanItem();
        item.setViewKey("viewkey=c9236ce81db652e4b5d9");
        assertFalse(Parse91PornyVideo.isPornySource(item));
    }

    @Test
    public void unmarked_pathShapesAreNotPornyByGuess() {
        // 蝌蚪/精品形态键无标记时不猜 porny（两类条目解析时恒写入标记，见解析器契约）
        V9MmanItem tadpole = new V9MmanItem();
        tadpole.setViewKey("videos/123/some-slug");
        assertFalse(Parse91PornyVideo.isPornySource(tadpole));

        V9MmanItem premium = new V9MmanItem();
        premium.setViewKey("vod/abc");
        assertFalse(Parse91PornyVideo.isPornySource(premium));
    }

    @Test
    public void nullOrEmptyInputsAreNotPorny() {
        assertFalse(Parse91PornyVideo.isPornySource(null));

        V9MmanItem noKey = new V9MmanItem();
        assertFalse(Parse91PornyVideo.isPornySource(noKey));

        V9MmanItem emptyKey = new V9MmanItem();
        emptyKey.setViewKey("");
        assertFalse(Parse91PornyVideo.isPornySource(emptyKey));
    }
}
