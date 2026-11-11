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

    // ==================== 91porny 短时效直链 ====================

    @Test
    public void pornyMainHls_expiryBucketPast_isExpired() {
        assertTrue(RecommendPrefetcher.isSecureUrlExpired(
                "https://cdn2.jiuse3.cloud/s/" + PAST + "/sig/hls/1018639/index.m3u8"));
    }

    @Test
    public void pornyMainHls_expiryBucketFuture_notExpired() {
        assertFalse(RecommendPrefetcher.isSecureUrlExpired(
                "https://cdn2.jiuse3.cloud/s/" + FUTURE + "/sig/hls/1018639/index.m3u8"));
    }

    @Test
    public void pornyVodHls_issueTimeOld_isExpired() {
        assertTrue(RecommendPrefetcher.isSecureUrlExpired(
                "https://cdn22.jiuse3.cloud/2026/11/10/hash/index.m3u8?t=" + PAST + "&m=sig"));
    }

    @Test
    public void pornyVodHls_issueTimeFresh_notExpired() {
        long now = System.currentTimeMillis() / 1000L;
        assertFalse(RecommendPrefetcher.isSecureUrlExpired(
                "https://cdn22.jiuse3.cloud/2026/11/10/hash/index.m3u8?t=" + now + "&m=sig"));
    }

    @Test
    public void pornyTadpoleMp4_issueTimeOld_isExpired() {
        assertTrue(RecommendPrefetcher.isSecureUrlExpired(
                "https://cdn.cdnkdjs.com/get_file/3/hash/127000/127581/127581.mp4?t=" + PAST + "&m=sig"));
    }

    @Test
    public void nonPornyHost_sPathAndTParam_notAffected() {
        // 非 91porny CDN 的同形 URL 不参与判定，避免误伤其它站点
        assertFalse(RecommendPrefetcher.isSecureUrlExpired(
                "https://example.com/s/" + PAST + "/hls/1/index.m3u8"));
        assertFalse(RecommendPrefetcher.isSecureUrlExpired(
                "https://example.com/video.mp4?t=" + PAST + "&m=sig"));
    }
}
