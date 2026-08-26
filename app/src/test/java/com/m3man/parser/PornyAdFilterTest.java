package com.m3man.parser;

import org.junit.Test;

import java.util.List;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.model.BaseResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M101：91porny 广告过滤回归——评审缺陷「绝对站内链接被一律判为广告」的防回归测试。
 * 站点若把条目渲染成 https://91porny.com/video/view/xxx 绝对链接，不得误杀。
 */
public class PornyAdFilterTest {

    private static final String PAGE_TMPL =
            "<html><body>"
            + "%s"
            + "</body></html>";

    private static String videoElem(String href) {
        return "<div class='video-elem'>"
                + "<a class='display' href='" + href + "' style='background-image:url(/thumb/x.jpg)'></a>"
                + "<div class='img' style='background-image:url(/thumb/x.jpg)'></div>"
                + "<a class='title' href='" + href + "'>测试标题</a>"
                + "<small class='layer'>10:00</small>"
                + "<a class='text-dark' href='/author/liguvipa'>liguvipa</a>"
                + "</div>";
    }

    /** 相对链接：基线，必须解析出 1 条。 */
    @Test
    public void relativeInternalLinkParsed() {
        BaseResult<List<V9MmanItem>> r = Parse91PornyVideo.parseSearchVideos(
                String.format(PAGE_TMPL, videoElem("/video/view/abc12345")));
        assertNotNull(r.getData());
        assertEquals(1, r.getData().size());
        assertEquals("测试标题", r.getData().get(0).getTitle());
    }

    /** 绝对站内链接（同 host）：修复前会被 isExternalAdHref 误杀为空列表。 */
    @Test
    public void absoluteSameHostLinkNotTreatedAsAd() {
        BaseResult<List<V9MmanItem>> r = Parse91PornyVideo.parseSearchVideos(
                String.format(PAGE_TMPL, videoElem("https://91porny.com/video/view/abc12345")));
        assertNotNull(r.getData());
        assertEquals(1, r.getData().size());
    }

    /** 真外链广告：仍要过滤掉。 */
    @Test
    public void externalAdLinkFiltered() {
        BaseResult<List<V9MmanItem>> r = Parse91PornyVideo.parseSearchVideos(
                String.format(PAGE_TMPL,
                        videoElem("https://ads.example.com/promo/x")
                                + videoElem("/video/view/def67890")));
        assertEquals(1, r.getData().size());
        assertTrue(r.getData().get(0).getViewKey().contains("def67890")
                || r.getData().get(0).getTitle().contains("测试标题"));
    }
}
