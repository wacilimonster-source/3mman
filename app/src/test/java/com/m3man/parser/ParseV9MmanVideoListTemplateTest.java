package com.m3man.parser;

import com.m3man.data.db.entity.V9MmanItem;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 2026-11 线上模板实测回归：分类页改成 article.vitem + 随机标题类名（&lt;bdi&gt;），
 * 首页是 div.videos-text-align；老解析器在分类页恒 0 条、首页只出 2 条。
 */
public class ParseV9MmanVideoListTemplateTest {

    /** 首页条目结构（摘自 https://www.91porn.com/index.php 真实 HTML，去掉无关脚本）。 */
    private static String indexHtml() {
        return "<html><body><div id=\"wrapper\">"
                + "<div class=\"container\"><div class=\"row\"><div class=\"col-sm-12\">导航</div></div></div>"
                + "<div class=\"container container-minheight\"><div class=\"row\">"
                + "<div class=\"col-xs-12 col-sm-4 col-md-3 col-lg-3\">"
                + "<div class=\"well well-sm videos-text-align\">"
                + "<a href=\"https://www.91porn.com/view_video.php?viewkey=aaaaaaaaaaaaaaaaaaaa&page=1\">"
                + "<div class=\"thumb-overlay\" id=\"playvthumb_1\">"
                + "<img class=\"img-responsive\" src=\"https://cdn.example/thumb/1.jpg\"/>"
                + "<span class=\"duration\">00:02:24</span></div>"
                + "<span class=\"video-title title-truncate m-t-5\">第一条真实标题</span></a>"
                + "</div></div>"
                + "<div class=\"col-xs-12 col-sm-4 col-md-3 col-lg-3\">"
                + "<div class=\"well well-sm videos-text-align\">"
                + "<a href=\"https://www.91porn.com/view_video.php?viewkey=bbbbbbbbbbbbbbbbbbbb&page=1\">"
                + "<div class=\"thumb-overlay\" id=\"playvthumb_2\">"
                + "<img class=\"img-responsive\" src=\"https://cdn.example/thumb/2.jpg\"/>"
                + "<span class=\"duration\">00:05:01</span></div>"
                + "<span class=\"video-title title-truncate m-t-5\">第二条真实标题</span></a>"
                + "</div></div>"
                + "</div></div></div></body></html>";
    }

    /** 分类页条目结构：article.vitem + 随机 class 里的 bdi 标题 + col-lg-8 隐藏重复块。 */
    private static String categoryHtml() {
        return "<html><body><div id=\"wrapper\">"
                + "<div class=\"container container-minheight\"><div class=\"row\">"
                + "<div class=\"col-xs-12 col-sm-4 col-md-3 col-lg-3\">"
                + "<article class=\"vitem well-sm well\">"
                + "<a href=\"https://www.91porn.com/view_video.php?viewkey=cccccccccccccccccccc&page=1&category=mr\">"
                + "<div class=\"thumb-overlay\" id=\"playvthumb_3\">"
                + "<img class=\"img-responsive\" src=\"https://cdn.example/thumb/3.jpg\" />"
                + "<span class=\"duration\">00:00:21</span></div>"
                + "<div class=\"tc60871\"><bdi>分类页真实标题</bdi></div></a>"
                + "<span class=\"info\">Views:</span> 1286"
                + "</article></div>"
                + "<div class=\"col-xs-12 col-sm-4 col-md-3 col-lg-8\">"
                + "<article class=\"vitem well-sm well\">"
                + "<a href=\"https://www.91porn.com/view_video.php?viewkey=dddddddddddddddddddd&page=1\">"
                + "<div class=\"thumb-overlay\" id=\"playvthumb_4\">"
                + "<img class=\"img-responsive\" src=\"https://cdn.example/thumb/4.jpg\" />"
                + "<span class=\"duration\">00:00:33</span></div>"
                + "<div class=\"tc60871\"><bdi>隐藏重复条目</bdi></div></a>"
                + "</article></div>"
                + "</div></div></div></body></html>";
    }

    @Test
    public void indexTemplateParsesEveryVisibleItem() {
        List<V9MmanItem> items = ParseV9MmanVideo.parseIndex(indexHtml());

        assertEquals(2, items.size());
        assertEquals("第一条真实标题", items.get(0).getTitle());
        assertEquals("viewkey=aaaaaaaaaaaaaaaaaaaa", items.get(0).getViewKey());
        assertEquals("00:02:24", items.get(0).getDuration());
        assertEquals("https://cdn.example/thumb/1.jpg", items.get(0).getImgUrl());
        assertEquals("mman9", items.get(0).getSourceName());
    }

    @Test
    public void categoryTemplateParsesArticleVitemAndSkipsHiddenColumn() {
        List<V9MmanItem> items = ParseV9MmanVideo.parseByCategory(categoryHtml()).getData();

        assertEquals(1, items.size());
        V9MmanItem item = items.get(0);
        assertEquals("分类页真实标题", item.getTitle());
        assertEquals("viewkey=cccccccccccccccccccc", item.getViewKey());
        assertEquals("00:00:21", item.getDuration());
        assertEquals("https://cdn.example/thumb/3.jpg", item.getImgUrl());
        assertFalse("隐藏的 col-lg-8 区块不应进入列表", item.getTitle().contains("隐藏"));
    }

    @Test
    public void oldTemplateStillWinsWhenPresent() {
        String html = "<html><body><div id=\"wrapper\"><div class=\"container\">"
                + "<div class=\"row\"><div class=\"col-sm-12\"><div class=\"row\">"
                + "<div class=\"col-lg-3\"><a href=\"view_video.php?viewkey=old1\">"
                + "<img class=\"img-responsive\" src=\"https://cdn.example/old.jpg\"/>"
                + "<span class=\"duration\">00:01:00</span>"
                + "<span class=\"video-title\">老模板标题</span></a></div>"
                + "</div></div></div>"
                + "</div></div></body></html>";

        List<V9MmanItem> items = ParseV9MmanVideo.parseByCategory(html).getData();
        assertEquals(1, items.size());
        assertEquals("老模板标题", items.get(0).getTitle());
        assertTrue(items.get(0).getViewKey().contains("old1"));
    }
}
