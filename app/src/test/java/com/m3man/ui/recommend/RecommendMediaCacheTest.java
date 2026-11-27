package com.m3man.ui.recommend;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecommendMediaCacheTest {
    @Test
    public void signedMainSiteUrlIsReusable() {
        // 2026-11 线上直链带 st=/e=/t=/k=/f= 签名参数，其中 t= 属于签名，
        // 不能再当成“占位地址”丢弃，否则推荐流缓存永远命中不了可播放直链。
        long future = System.currentTimeMillis() / 1000L + 3600;
        String url = "https://cdn.example//mp43/123.mp4?st=abc&e=" + future + "&t=ce8e&k=e046c2&f=00bde4";
        assertTrue(RecommendPrefetcher.isReusableMediaUrl(url));
    }

    @Test
    public void expiredOrEmptyUrlsAreNotReusable() {
        assertFalse(RecommendPrefetcher.isReusableMediaUrl(null));
        assertFalse(RecommendPrefetcher.isReusableMediaUrl(""));
        assertFalse(RecommendPrefetcher.isReusableMediaUrl("https://cdn.example/123.mp4?e=1"));
        long future = System.currentTimeMillis() / 1000L + 3600;
        assertTrue(RecommendPrefetcher.isReusableMediaUrl("https://cdn.example/123.mp4?e=" + future));
    }
}
