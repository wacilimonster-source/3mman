package com.m3man.utils;

import android.content.Context;
import android.os.Environment;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

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

    /**
     * M40：真实写入能力探测。尝试在下载目录建目录并写入/删除一个临时文件，
     * 用于判断存储是否真的可写。比运行时权限检测更可靠，可跨 Android 版本/厂商一致，
     * 避免旧版 AndPermission 在 Android 11+ 上对 READ/WRITE_EXTERNAL_STORAGE 的误判。
     */
    public static boolean isDownloadDirWritable(Context context) {
        File dir = new File(DOWNLOAD_VIDEO_PATH);
        if (!dir.exists() && !dir.mkdirs()) {
            File parent = new File(ROOT_FOLDER);
            if (!parent.exists() && !parent.mkdirs()) {
                return false;
            }
            if (!dir.mkdirs()) {
                return false;
            }
        }
        File test = new File(dir, ".write_test_" + System.currentTimeMillis() + ".tmp");
        FileOutputStream os = null;
        try {
            os = new FileOutputStream(test);
            os.write(1);
            return test.delete();
        } catch (IOException e) {
            return false;
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
