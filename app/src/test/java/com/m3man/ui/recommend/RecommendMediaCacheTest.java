package com.m3man.ui.recommend;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecommendMediaCacheTest {
    @Test
    public void placeholderOnlyInvalidatesMainSiteUrls() {
        String url = "https://cdn.example/123.mp4?t=12345";
        assertFalse(RecommendPrefetcher.isReusableMediaUrl(url, false));
        assertTrue(RecommendPrefetcher.isReusableMediaUrl(url, true));
    }

    @Test
    public void expiredOrEmptyUrlsAreNotReusable() {
        assertFalse(RecommendPrefetcher.isReusableMediaUrl(null, false));
        assertFalse(RecommendPrefetcher.isReusableMediaUrl("https://cdn.example/123.mp4?e=1", false));
        long future = System.currentTimeMillis() / 1000L + 3600;
        assertTrue(RecommendPrefetcher.isReusableMediaUrl("https://cdn.example/123.mp4?e=" + future, false));
    }
}
