package com.m3man.parser;

import com.m3man.data.db.entity.VideoResult;
import com.m3man.data.model.User;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ParseV9MmanVideoPlaybackTest {
    private static final String SOURCE = "https://cdn.example/123.mp4";
    private static final String VIDEO = "<video id='player_one'><source src='" + SOURCE + "'></video>";

    @Test
    public void interactivePageKeepsOwnerMetadataWithoutReturningPlaceholder() {
        String html = VIDEO + "<script>request.open('POST','/vlk.php',false);</script>"
                + "<a href='uvideos.php?UID=author-token'>Author</a>";

        VideoResult result = ParseV9MmanVideo.parseVideoPlayUrl(html, new User());

        assertEquals("", result.getVideoUrl());
        assertEquals("author-token", result.getOwnerId());
        assertTrue(result.requiresSitePlayback());
    }

    @Test
    public void legacyPageKeepsNativeMediaUrl() {
        VideoResult result = ParseV9MmanVideo.parseVideoPlayUrl(VIDEO, new User());

        assertEquals(SOURCE, result.getVideoUrl());
        assertEquals("123", result.getVideoId());
    }

    @Test
    public void directVideoElementSrcStartsNativePlayback() {
        VideoResult result = ParseV9MmanVideo.parseVideoPlayUrl(
                "<video src='" + SOURCE + "'></video>", new User());

        assertEquals(SOURCE, result.getVideoUrl());
        assertFalse(result.requiresSitePlayback());
    }

    @Test
    public void signedPlaceholderDoesNotReachPlayer() {
        VideoResult result = ParseV9MmanVideo.parseVideoPlayUrl(
                "<video><source src='" + SOURCE + "?t=placeholder'></video>", new User());

        assertEquals("", result.getVideoUrl());
        assertTrue(result.requiresSitePlayback());
    }

    @Test
    public void hasTQueryParam_detectsStaticFallbackUrl() {
        assertTrue(ParseV9MmanVideo.hasTQueryParam("https://cdn.example/1.mp4?st=a&e=1&t=dcb6s&k=2&f=3"));
        assertTrue(ParseV9MmanVideo.hasTQueryParam("https://cdn.example/1.mp4?t=dcb6s"));
        assertFalse(ParseV9MmanVideo.hasTQueryParam("https://cdn.example/1.mp4?st=a&e=1&k=2&f=3"));
        assertFalse(ParseV9MmanVideo.hasTQueryParam("https://cdn.example/1.mp4?st=a&token=1"));
        assertFalse(ParseV9MmanVideo.hasTQueryParam(""));
    }
}
