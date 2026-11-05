package com.m3man.perf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * VideoKey 归一化契约：幂等、与解析器输出形态一致、空身份返回 null。
 */
public class VideoKeyTest {

    @Test
    public void mman9ViewkeyForm_keptAsIs() {
        VideoKey k = VideoKey.of("mman9", "viewkey=abc123DEF");
        assertEquals("mman9", k.source);
        assertEquals("viewkey=abc123DEF", k.canonicalViewKey);
    }

    @Test
    public void pornyBareHex_keptAsIs() {
        VideoKey k = VideoKey.of("91porny", "a1b2c3d4");
        assertEquals("a1b2c3d4", k.canonicalViewKey);
    }

    @Test
    public void pornyPathForms_normalized() {
        assertEquals("videos/123/slug-name", VideoKey.canonicalize("videos/123/slug-name/"));
        assertEquals("vod/somekey", VideoKey.canonicalize("vod/somekey/."));
        assertEquals("videos/9/x", VideoKey.canonicalize("videos/9/x/?uid=zzz"));
    }

    @Test
    public void canonicalize_isIdempotent() {
        String[] raws = {"viewkey=abc", "a1b2c3d4", "videos/123/slug-name/",
                "vod/somekey/.", "videos/9/x/?uid=zzz"};
        for (String raw : raws) {
            String once = VideoKey.canonicalize(raw);
            assertEquals(once, VideoKey.canonicalize(once));
        }
    }

    @Test
    public void emptyIdentity_returnsNull() {
        assertNull(VideoKey.of("mman9", null));
        assertNull(VideoKey.of("mman9", "  "));
        assertNull(VideoKey.of(null, "viewkey=x"));
        assertNull(VideoKey.canonicalize("?"));
        assertNull(VideoKey.canonicalize(null));
    }

    @Test
    public void sameSourceSameKey_equal_crossSourceNotEqual() {
        VideoKey a = VideoKey.of("mman9", "viewkey=x1");
        VideoKey b = VideoKey.of("mman9", "viewkey=x1");
        VideoKey c = VideoKey.of("91porny", "viewkey=x1");
        assertEquals(a, b);
        assertTrue(a.sameAs(b));
        assertTrue(!a.equals(c));
    }

    @Test
    public void toString_doesNotLeakUrl() {
        VideoKey k = VideoKey.of("mman9", "viewkey=x1");
        assertEquals("mman9|viewkey=x1", k.toString());
    }
}
