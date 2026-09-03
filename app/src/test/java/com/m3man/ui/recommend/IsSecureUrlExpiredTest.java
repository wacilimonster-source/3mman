package com.m3man.ui.recommend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * M152：isSecureUrlExpired 对站点新旧两种签名格式的过期判定。
 * 背景：站点由 secure=<base64>,<unix秒> 切换为 st=<token>&e=<unix秒>&f=...，
 * 旧检查只认 secure= 导致 DB 缓存直链过期 403。
 */
public class IsSecureUrlExpiredTest {

    // 固定"当前时间"参照：远期时间戳，保证与真实 now 的相对关系稳定
    private static final long PAST = 1_000_000_000L;      // 2001 年，必过期
    private static final long FUTURE = 4_000_000_000L;    // 2096 年，必未过期

    @Test
    public void newFormat_eParam_past_isExpired() {
        assertTrue(RecommendPrefetcher.isSecureUrlExpired(
                "https://la.btc620.com//mp43/1236940.mp4?st=abc&e=" + PAST + "&f=012dl"));
    }

    @Test
    public void newFormat_eParam_future_notExpired() {
        assertFalse(RecommendPrefetcher.isSecureUrlExpired(
                "https://la.btc620.com//mp43/1236940.mp4?st=abc&e=" + FUTURE + "&f=012dl"));
    }

    @Test
    public void oldFormat_secure_past_isExpired() {
        assertTrue(RecommendPrefetcher.isSecureUrlExpired(
                "https://old.example.com/v.mp4?secure=abc%3D%3D," + PAST));
    }

    @Test
    public void oldFormat_secure_future_notExpired() {
        assertFalse(RecommendPrefetcher.isSecureUrlExpired(
                "https://old.example.com/v.mp4?secure=abc%3D%3D," + FUTURE));
    }

    @Test
    public void unsignedUrl_notExpired() {
        assertFalse(RecommendPrefetcher.isSecureUrlExpired(
                "https://example.com/video.mp4"));
        assertFalse(RecommendPrefetcher.isSecureUrlExpired(
                "https://example.com/video.mp4?token=abc"));
    }

    @Test
    public void malformedSignature_failsOpen() {
        // e= 非数字 → fail-open，不抛异常、不误判过期
        assertFalse(RecommendPrefetcher.isSecureUrlExpired(
                "https://la.btc620.com//mp43/1.mp4?st=abc&e=notanumber&f=x"));
        // secure= 缺逗号 → fail-open
        assertFalse(RecommendPrefetcher.isSecureUrlExpired(
                "https://old.example.com/v.mp4?secure=abcdef"));
    }

    @Test
    public void nullOrEmpty_notExpired() {
        assertFalse(RecommendPrefetcher.isSecureUrlExpired(null));
        assertFalse(RecommendPrefetcher.isSecureUrlExpired(""));
    }
}
