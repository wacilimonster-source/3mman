package com.m3man.utils;

import static org.junit.Assert.assertEquals;

import com.m3man.data.db.entity.V9MmanItem;

import org.junit.Test;

/**
 * EX-02 卡片元信息规则的单测：
 * 时长只输出 mm:ss / h:mm:ss，解析失败省略而不是 00:00；来源名固定映射。
 */
public class VideoCardMetaTest {

    @Test
    public void formatDuration_keepsValidValues() {
        assertEquals("04:53", VideoCardMeta.formatDuration("04:53"));
        assertEquals("04:53", VideoCardMeta.formatDuration("4:53"));
        assertEquals("1:58:04", VideoCardMeta.formatDuration("1:58:04"));
        assertEquals("05:14", VideoCardMeta.formatDuration("00:05:14"));
    }

    @Test
    public void formatDuration_dropsInvalidOrZero() {
        assertEquals("", VideoCardMeta.formatDuration(null));
        assertEquals("", VideoCardMeta.formatDuration(""));
        assertEquals("", VideoCardMeta.formatDuration("00:00"));
        assertEquals("", VideoCardMeta.formatDuration("abc"));
        assertEquals("", VideoCardMeta.formatDuration("1:2:3:4"));
        assertEquals("", VideoCardMeta.formatDuration("12:aa"));
    }

    @Test
    public void sourceLabel_mapsPornyAndMman9() {
        V9MmanItem porny = new V9MmanItem();
        porny.setSourceName("91porny");
        assertEquals("分分钟", VideoCardMeta.sourceLabel(porny));

        V9MmanItem mman9 = new V9MmanItem();
        mman9.setSourceName("mman9");
        mman9.setViewKey("viewkey=abc123");
        assertEquals("视频源", VideoCardMeta.sourceLabel(mman9));

        V9MmanItem bareHex = new V9MmanItem();
        bareHex.setViewKey("0123456789abcdef");
        assertEquals("分分钟", VideoCardMeta.sourceLabel(bareHex));
    }

    @Test
    public void metaLine_omitsMissingAuthorWithoutReplacingSource() {
        V9MmanItem item = new V9MmanItem();
        item.setSourceName("mman9");
        assertEquals("视频源", VideoCardMeta.metaLine(item));

        item.setAuthorText("作者A");
        assertEquals("视频源 · 作者A", VideoCardMeta.metaLine(item));
    }
}
