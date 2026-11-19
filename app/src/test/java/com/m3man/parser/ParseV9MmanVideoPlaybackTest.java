package com.m3man.parser;

import com.m3man.data.db.entity.VideoResult;
import com.m3man.data.model.User;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ParseV9MmanVideoPlaybackTest {
    private static final String PAGE_URL = "https://main.example/view_video.php?viewkey=sample";
    private static final String SOURCE = "https://cdn.example/123.mp4";
    private static final String VIDEO = "<video id='player_one'><source src='" + SOURCE + "'></video>";

    @Test
    public void interactivePageUsesWebsitePlayerInsteadOfPlaceholder() {
        String html = VIDEO + "<script>request.open('POST','/vlk.php',false);</script>";

        VideoResult result = ParseV9MmanVideo.parseVideoPlayUrl(html, new User(), PAGE_URL);

        assertEquals(PAGE_URL, result.getWebPlaybackUrl());
        assertEquals("", result.getVideoUrl());
    }

    @Test
    public void legacyPageKeepsNativeMediaUrl() {
        VideoResult result = ParseV9MmanVideo.parseVideoPlayUrl(VIDEO, new User(), PAGE_URL);

        assertNull(result.getWebPlaybackUrl());
        assertEquals(SOURCE, result.getVideoUrl());
        assertEquals("123", result.getVideoId());
    }
}
