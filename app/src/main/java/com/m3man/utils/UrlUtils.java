package com.m3man.utils;

import android.text.TextUtils;

import java.net.URI;
import java.util.Locale;

/**
 * URL 相关的小工具：日志脱敏与 HLS 判定。
 * 这些规则原本在播放页/预取器/下载器各写一份，口径不一致导致过线上误判，统一收敛到这里。
 */
public final class UrlUtils {

    private UrlUtils() {
    }

    /**
     * 日志脱敏：URL 只保留 scheme://host/path（丢弃 query 中的签名/token），
     * 追加原始长度便于排查；解析失败时兜底去掉 query 再截断，绝不打印完整直链。
     */
    public static String maskForLog(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        int len = url.length();
        String head = url;
        try {
            URI uri = URI.create(url);
            StringBuilder sb = new StringBuilder();
            if (uri.getScheme() != null) {
                sb.append(uri.getScheme()).append("://");
            }
            if (uri.getHost() != null) {
                sb.append(uri.getHost());
            }
            if (uri.getPath() != null) {
                sb.append(uri.getPath());
            }
            if (sb.length() > 0) {
                head = sb.toString();
            }
        } catch (Exception e) {
            int query = url.indexOf('?');
            if (query >= 0) {
                head = url.substring(0, query);
            }
        }
        if (head.length() > 96) {
            head = head.substring(0, 96) + "...";
        }
        return head + "|len=" + len;
    }

    /** 判断是否为 HLS 地址（忽略 query；兼容 .m3u8/ 目录形态） */
    public static boolean isHlsUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.US);
        int query = lower.indexOf('?');
        if (query >= 0) {
            lower = lower.substring(0, query);
        }
        return lower.endsWith(".m3u8") || lower.contains(".m3u8/");
    }
}
