package com.m3man.utils;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.network.Source441447Client;
import com.m3man.parser.Parse91PornyVideo;

/**
 * 视频卡片元信息工具（spec EX-02）。
 * <p>
 * 统一处理时长规范化与来源显示名，避免各适配器各写一套：
 * <ul>
 * <li>时长支持 mm:ss / h:mm:ss；解析失败返回空串，绝不输出 00:00；</li>
 * <li>来源为多源列表必显字段，91porny 显示名统一为「分分钟」；</li>
 * <li>作者只能来自 item.authorText（transient），缺失时省略，不用来源名顶替。</li>
 * </ul>
 * 本类刻意不依赖 android.text.TextUtils，保证纯 JVM 单测覆盖真实逻辑。
 */
public final class VideoCardMeta {

    private VideoCardMeta() {
    }

    /** 时长规范化为 mm:ss / h:mm:ss；无效输入返回空串。 */
    public static String formatDuration(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return "";
        }
        String[] parts = text.split(":");
        if (parts.length == 0 || parts.length > 3) {
            return "";
        }
        long seconds = 0L;
        try {
            for (String part : parts) {
                String value = part.trim();
                if (value.isEmpty() || value.length() > 3) {
                    return "";
                }
                seconds = seconds * 60L + Long.parseLong(value);
            }
        } catch (NumberFormatException e) {
            return "";
        }
        if (seconds <= 0L) {
            return "";
        }
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        if (hours > 0L) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, secs);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, secs);
    }

    /** 来源显示名：分分钟（91porny）/ 视频源（9mman）。 */
    public static String sourceLabel(V9MmanItem item) {
        if (item == null) {
            return "";
        }
        if (Source441447Client.isSource(item)) {
            return "441447";
        }
        String sourceName = item.getSourceName();
        String source = item.getSource();
        if (Parse91PornyVideo.SOURCE.equals(sourceName)
                || Parse91PornyVideo.SOURCE.equals(source)) {
            return "分分钟";
        }
        String key = item.getViewKey();
        if (key != null && !key.startsWith("viewkey=")
                && key.matches("[0-9a-fA-F]{16,32}")) {
            return "分分钟";
        }
        return "视频源";
    }

    /**
     * 卡片元信息行：来源 [· 作者]，字段缺失时省略对应片段。
     * 时长不放这里（封面右下角有独立角标），避免同一信息重复占两行。
     */
    public static String metaLine(V9MmanItem item) {
        if (item == null) {
            return "";
        }
        String source = sourceLabel(item);
        String author = item.getAuthorText();
        if (author != null && !author.trim().isEmpty()) {
            return source + " · " + author.trim();
        }
        return source;
    }
}
