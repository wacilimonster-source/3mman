package com.m3man.parser;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.model.BaseResult;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 91porny 列表解析回归（2026-11-28 实测结构）：
 * 首页「最新」48 条/页、高清 /video/category/hd/N、蝌蚪 /videos，三种条目链接形态不同：
 * <ul>
 *   <li>{@code /video/viewhd/<hex>}  → viewKey {@code hd/<hex>}</li>
 *   <li>{@code /videos/view/<id>/<slug>} → viewKey {@code videos/<id>/<slug>}</li>
 *   <li>{@code /video/view/<hex>}    → 裸 hex</li>
 * </ul>
 */
public class Parse91PornyVideoListTest {

    private static String item(String href, String thumb, String duration, String title,
                               String author, String dateLine) {
        return "<div class=\"colVideoList\"><div class=\"video-elem\">"
                + "<a target=\"_self\" class=\"display d-block\" href=\"" + href + "\">"
                + "<div class=\"scale\"></div>"
                + "<div class=\"img\" style=\"background-image: url('" + thumb + "')\"></div>"
                + "<small class=\"layer\">" + duration + "</small>"
                + "<small class=\"vip-layer\">高清</small>"
                + "</a>"
                + "<a target=\"_self\" class=\"title text-sub-title mt-2 mb-1\" href=\"" + href + "\">" + title + "</a>"
                + "<small class=\"d-inline-block text-truncate mt-1 mb-1\" style=\"max-width: 100%;\">"
                + "<div class=\"text-muted\">作者: <a class=\"text-dark\" href=\"/author/" + author + "\">九色国产</a></div>"
                + "<div class=\"text-muted\">" + dateLine + "</div>"
                + "</small></div></div>";
    }

    private static String page(String items) {
        return "<html><body><div class=\"row\">" + items + "</div>"
                + "<div class=\"pagination\"><a class=\"page-link\" href=\"/video/category/hd/3\">3</a></div>"
                + "</body></html>";
    }

    @Test
    public void hdListProducesHdPrefixedViewKey() {
        String html = page(item("/video/viewhd/6b86709822fa2def5f61",
                "//int.ucloud165.xyz/thumb/1263266.jpg", "00:02:16",
                "[原创] 标题A", "%E4%B9%9D%E8%89%B2%E5%9B%BD%E4%BA%A7", "2026-11-27&nbsp;|&nbsp;1.2万次播放"));
        BaseResult<List<V9MmanItem>> result = Parse91PornyVideo.parseSearchVideos(html);
        assertNotNull(result);
        List<V9MmanItem> items = result.getData();
        assertEquals(1, items.size());
        V9MmanItem first = items.get(0);
        assertEquals("hd/6b86709822fa2def5f61", first.getViewKey());
        assertTrue(Parse91PornyVideo.isHdViewKey(first.getViewKey()));
        assertEquals("[原创] 标题A", first.getTitle());
        assertEquals("00:02:16", first.getDuration());
        assertEquals("https://int.ucloud165.xyz/thumb/1263266.jpg", first.getImgUrl());
        assertEquals(Parse91PornyVideo.SOURCE, first.getSourceName());
    }

    @Test
    public void tadpoleListProducesVideosPrefixedViewKey() {
        String html = page(item("/videos/view/115370/av799/",
                "https://int.qiniuyun57.xyz/contents/videos_screenshots/180x135/1.jpg", "56:37",
                "【日本AV】标题B", "", "3年前&nbsp;|&nbsp;5.7万次播放"));
        List<V9MmanItem> items = Parse91PornyVideo.parseSearchVideos(html).getData();
        assertEquals(1, items.size());
        assertEquals("videos/115370/av799", items.get(0).getViewKey());
        assertTrue(Parse91PornyVideo.isTadpoleViewKey(items.get(0).getViewKey()));
    }

    @Test
    public void homeListKeepsBareHexViewKey() {
        String html = page(item("/video/view/7751efe39c225a7622bc",
                "//int.ucloud165.xyz/thumb/1262523.jpg", "00:03:13",
                "标题C", "%E5%8F%91%E5%93%A5", "18小时前&nbsp;|&nbsp;18.8万次播放"));
        List<V9MmanItem> items = Parse91PornyVideo.parseSearchVideos(html).getData();
        assertEquals(1, items.size());
        assertEquals("7751efe39c225a7622bc", items.get(0).getViewKey());
        assertFalse(Parse91PornyVideo.isHdViewKey(items.get(0).getViewKey()));
        assertFalse(Parse91PornyVideo.isTadpoleViewKey(items.get(0).getViewKey()));
    }

    @Test
    public void adItemIsFilteredOut() {
        String ad = "<div class=\"colVideoList\"><div class=\"video-elem ad-banner\">"
                + "<a class=\"display d-block\" href=\"https://ads.example.com/click\">"
                + "<div class=\"img\" style=\"background-image: url('https://ads.example.com/a.jpg')\"></div></a>"
                + "<a class=\"title\" href=\"https://ads.example.com/click\">广告</a></div></div>";
        String normal = item("/video/view/abcdef1234567890abcdef12",
                "//int.ucloud165.xyz/thumb/1.jpg", "00:01:00", "正常条目", "", "1天前");
        List<V9MmanItem> items = Parse91PornyVideo.parseSearchVideos(page(ad + normal)).getData();
        assertEquals(1, items.size());
        assertEquals("正常条目", items.get(0).getTitle());
    }

    @Test
    public void emptyPageReturnsEmptyListNotError() {
        BaseResult<List<V9MmanItem>> result = Parse91PornyVideo.parseSearchVideos("<html><body>没有结果</body></html>");
        assertNotNull(result);
        assertEquals(0, result.getData().size());
        assertEquals(1, (int) result.getTotalPage());
    }

    @Test
    public void malformedHtmlDoesNotThrow() {
        String broken = "<div class=\"colVideoList\"><div class=\"video-elem\"><a class=\"display\" href=\"";
        BaseResult<List<V9MmanItem>> result = Parse91PornyVideo.parseSearchVideos(broken);
        assertNotNull(result);
        assertNotNull(result.getData());
    }
}
