package com.m3man.utils;

import android.os.Environment;
import android.text.TextUtils;

/**
 * @author flymegoc
 * @date 2018/1/13
 */

public class SDCardUtils {
    private static final String ROOT_FOLDER = Environment.getExternalStorageDirectory() + "/3mman/";
    public static final String DOWNLOAD_VIDEO_PATH = ROOT_FOLDER + "video/";
    public static final String DOWNLOAD_IMAGE_PATH = ROOT_FOLDER + "image/";
    public static final String DATE_FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND = "yyyy-MM-dd HH:mm:ss";
    public static final String EXPORT_FILE = ROOT_FOLDER + "export.txt";

    /**
     * 存储卡是否挂载
     *
     * @return b
     */
    public static boolean isSDCardMounted() {
        return Environment.getExternalStorageState().equals(
                Environment.MEDIA_MOUNTED);
    }

    /**
     * 清洗文件名，防止目录穿越 / 写入异常位置 / 非法字符导致创建失败。
     * 去除路径分隔符与非法字符，限制长度。
     */
    public static String sanitizeFileName(String title) {
        if (TextUtils.isEmpty(title)) {
            return "video_" + System.currentTimeMillis();
        }
        // 去除 \ / : * ? " < > | 以及回车/换行/制表等控制字符
        String sanitized = title.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_")
                .replaceAll("\\.{2,}", ".").trim();
        if (sanitized.length() > 120) {
            sanitized = sanitized.substring(0, 120).trim();
        }
        return sanitized;
    }
}
