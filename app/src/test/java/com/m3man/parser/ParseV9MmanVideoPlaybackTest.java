package com.m3man.parser;

import com.m3man.data.db.entity.VideoResult;
import com.m3man.data.model.User;

import org.junit.Test;

import okhttp3.HttpUrl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ParseV9MmanVideoPlaybackTest {
    private static final String SOURCE = "https://cdn.example/123.mp4";
    private static final String VIDEO = "<video id='player_one'><source src='" + SOURCE + "'></video>";
    private static final HttpUrl PAGE_URL = HttpUrl.parse("https://mirror.example/watch/456.html");

    @Test
    public void interactivePageKeepsOwnerMetadataAndStillReturnsMedia() {
        String html = VIDEO + "<script>request.open('POST','/vlk.php',false);</script>"
                + "<a href='uvideos.php?UID=author-token'>Author</a>";

        VideoResult result = ParseV9MmanVideo.parseVideoPlayUrl(html, new User(), PAGE_URL);

        // 页面引用 /vlk.php 不代表没有直链：有 <source> 就必须交给播放器
        assertEquals(SOURCE, result.getVideoUrl());
        assertEquals("author-token", result.getOwnerId());
        assertFalse(result.requiresSitePlayback());
    }

    @Test
    public void legacyPageKeepsNativeMediaUrl() {
        VideoResult result = ParseV9MmanVideo.parseVideoPlayUrl(VIDEO, new User(), PAGE_URL);

        assertEquals(SOURCE, result.getVideoUrl());
        assertEquals("123", result.getVideoId());
    }

    @Test
    public void directVideoElementSrcStartsNativePlayback() {
        VideoResult result = ParseV9MmanVideo.parseVideoPlayUrl(
                "<video src='" + SOURCE + "'></video>", new User(), PAGE_URL);

        assertEquals(SOURCE, result.getVideoUrl());
        assertFalse(result.requiresSitePlayback());
    }

    @Test
    public void realSignedMediaUrlKeepsTokenParamAndReachesPlayer() {
        // 2026-11 线上实测：主站直链形如 ?st=&e=&t=&k=&f=，其中 t= 是签名的一部分，
        // 线路/CDN 对该地址返回 206 video/mp4。旧实现把「含 t 参数」当成占位地址丢空，
        // 导致首页每条视频都弹「需要源站播放器验证」——这条测试锁死该回归。
        String signed = "https://cdn.example//mp43/1259929.mp4"
                + "?st=xJ6oaFRs4Et5tVVoAxSYnQ&e=1795777832&t=ce8e&k=e046c2&f=00bde4EjNsocOHWT";
        VideoResult result = ParseV9MmanVideo.parseVideoPlayUrl(
                "<video><source src='" + signed + "'></video>", new User(), PAGE_URL);

        assertEquals(signed, result.getVideoUrl());
        assertFalse(result.requiresSitePlayback());
        assertFalse(result.requiresLogin());
    }

    @Test
    public void playerOneWinsOverEarlierAdVideo() {
        // 真实页面广告位同样会出现 <video>，正文播放器固定是 #player_one
        String html = "<video src='https://ads.example/ad.mp4'></video>"
                + "<video id='player_one'><source src='" + SOURCE + "'></video>";

        VideoResult result = ParseV9MmanVideo.parseVideoPlayUrl(html, new User(), PAGE_URL);

        assertEquals(SOURCE, result.getVideoUrl());
    }

    @Test
    public void interactiveTemplateWithStaticSourceStillPlays() {
        // 登录态下的新视频页：既引用 /vlk.php，又在 vbF0=strencode2(...) / <source> 里带着可播直链。
        // 旧实现看到 /vlk.php 就把地址丢空 → 用户侧表现为「该视频需要源站播放器验证」。实测该直链 206。
        String html = "<video id='player_one'><source src='" + SOURCE + "'></video>"
                + "<script>x.open('POST','/vlk.php',false);</script>";

        VideoResult result = ParseV9MmanVideo.parseVideoPlayUrl(html, new User(), PAGE_URL);

        assertEquals(SOURCE, result.getVideoUrl());
        assertFalse(result.requiresSitePlayback());
    }

    @Test
    public void interactiveTemplateWithoutAnySourceStillAsksForSitePlayer() {
        // 只有交互脚本、页面确实没有静态地址时，才保留「需要站点播放器」的判定
        String html = "<video id='player_one'></video>"
                + "<script>x.open('POST','/vlk.php',false);</script>";

        VideoResult result = ParseV9MmanVideo.parseVideoPlayUrl(html, new User(), PAGE_URL);

        assertEquals("", result.getVideoUrl());
        assertTrue(result.requiresSitePlayback());
        assertFalse(result.requiresLogin());
    }

    @Test
    public void guestBlockedPageIsReportedAsLoginRequired() {
        // 真实页面（游客打开 3 天内新视频）：没有 <video>/<source>，只有这句登录提示。
        String html = "<div id='videodetails-content'><b>New videos are for logged-in members only. "
                + "Please <a href=login.php>log in</a> or <a href=signup.php>register for free</a> to watch. "
                + "Guests can watch videos older than 3 days.</b></div>"
                + "<a href='uvideos.php?UID=author-token'>Author</a>";

        VideoResult result = ParseV9MmanVideo.parseVideoPlayUrl(html, new User(), PAGE_URL);

        assertEquals("", result.getVideoUrl());
        assertTrue(result.requiresLogin());
        assertFalse(result.requiresSitePlayback());
        assertEquals("author-token", result.getOwnerId());
    }

    @Test
    public void relativeSourceIsResolvedAgainstFinalPageUrl() {
        VideoResult result = ParseV9MmanVideo.parseVideoPlayUrl(
                "<video><source src='../media/456.mp4?token=abc&amp;quality=hd'></video>", new User(), PAGE_URL);

        assertEquals("https://mirror.example/media/456.mp4?token=abc&quality=hd", result.getVideoUrl());
        assertFalse(result.requiresSitePlayback());
    }

    @Test
    public void sourceWithNoSchemeInheritsPageScheme() {
        VideoResult result = ParseV9MmanVideo.parseVideoPlayUrl(
                "<video src='//cdn.example/456.mp4'></video>", new User(), PAGE_URL);

        assertEquals("https://cdn.example/456.mp4", result.getVideoUrl());
    }

    @Test
    public void metadataOnlyPageKeepsOwnerWithoutInventingMediaUrl() {
        String html = "<div class='videodetails-yakov'><a href='uvideos.php?UID=author-token'>Author</a></div>";

        VideoResult result = ParseV9MmanVideo.parseVideoPlayUrl(html, new User(), PAGE_URL);

        assertEquals("author-token", result.getOwnerId());
        assertEquals("", result.getVideoUrl());
        assertFalse(result.requiresSitePlayback());
        assertFalse(result.requiresLogin());
    }
}
