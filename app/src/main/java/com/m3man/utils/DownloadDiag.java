package com.m3man.utils;

import android.text.TextUtils;

import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 下载诊断日志收集器（进程内单例）。
 * <p>
 * 下载「没速度 / 0%」往往依赖设备侧网络与 CDN 行为，纯看代码难以定性。
 * 这里在下载决策点（re-parse 结果、isAlive 探活、91porny 兜底、实际起下）按 viewKey
 * 累加诊断文本，「正在下载」列表的「复制日志」按钮据此拼装后复制到剪贴板，便于排查。
 */
public final class DownloadDiag {

    /**
     * M97：MAP 换为同步 LinkedHashMap 并加容量上限（>50 移除最早插入的 key），
     * 防止 viewKey 无限累积导致内存缓慢增长；比 Hashtable 语义更贴合“按插入序淘汰”。
     */
    private static final int MAX_KEYS = 50;

    private static final Map<String, StringBuilder> MAP =
            Collections.synchronizedMap(new LinkedHashMap<String, StringBuilder>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Entry<String, StringBuilder> eldest) {
                    return size() > MAX_KEYS;
                }
            });

    private DownloadDiag() {
    }

    /** 开始一次新下载流程时重置该 viewKey 的历史记录 */
    public static void reset(String key) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        MAP.put(key, new StringBuilder());
    }

    /** 追加一行诊断 */
    public static void append(String key, String line) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        StringBuilder sb;
        synchronized (MAP) {
            sb = MAP.get(key);
            if (sb == null) {
                sb = new StringBuilder();
                MAP.put(key, sb);
            }
        }
        // M97：同一 key 可能被多线程并发追加，StringBuilder 非线程安全，须按实例互斥
        synchronized (sb) {
            sb.append(line).append("\n");
        }
    }

    /** 读取该 viewKey 的完整诊断文本（无则空串） */
    public static String get(String key) {
        if (TextUtils.isEmpty(key)) {
            return "";
        }
        StringBuilder sb = MAP.get(key);
        if (sb == null) {
            return "";
        }
        synchronized (sb) {
            return sb.toString();
        }
    }

    /** 安全提取 URL host（用于日志脱敏，只记域名不记完整直链） */
    public static String hostOf(String url) {
        if (TextUtils.isEmpty(url)) {
            return "空";
        }
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return "host解析失败";
        }
    }
}
