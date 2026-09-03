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
    /**
     * M99：根目录惰性解析。
     * 原实现把 Environment.getExternalStorageDirectory() 写死在静态常量初始化里：
     * 类一加载就访问外部存储（此时存储可能未挂载/受限），且无任何兜底。
     * 现改为内部 Holder 首次被触碰时才解析，失败时回退到公共存储的标准挂载点 /sdcard；
     * 公共方法签名保持不变（DOWNLOAD_VIDEO_PATH 等公共常量仍按原名编译内联引用），
     * 内部实现统一切换到 getRootFolder() 惰性取值。
     */
    private static final class LazyPaths {
        private static final String ROOT = resolveRootFolder();

        private static String resolveRootFolder() {
            try {
                File ext = Environment.getExternalStorageDirectory();
                if (ext != null && !TextUtils.isEmpty(ext.getAbsolutePath())) {
                    return ext.getAbsolutePath() + "/3mman/";
                }
            } catch (Throwable ignored) {
                // 存储未就绪/受限时走下方回退
            }
            // 回退：/sdcard 为 Android 公共存储的标准挂载点
            return "/sdcard/3mman/";
        }
    }

    /** M99：根目录惰性 getter */
    public static String getRootFolder() {
        return LazyPaths.ROOT;
    }

    /** M99：下载目录惰性 getter */
    public static String getDownloadVideoPath() {
        return DOWNLOAD_VIDEO_PATH;
    }

    public static final String DOWNLOAD_VIDEO_PATH = LazyPaths.ROOT + "video/";
    public static final String DOWNLOAD_IMAGE_PATH = LazyPaths.ROOT + "image/";
    public static final String DATE_FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND = "yyyy-MM-dd HH:mm:ss";
    public static final String EXPORT_FILE = LazyPaths.ROOT + "export.txt";

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
        // M99：内部实现切换到惰性 getter
        File dir = new File(getDownloadVideoPath());
        if (!dir.exists() && !dir.mkdirs()) {
            File parent = new File(getRootFolder());
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

    /**
     * 确保目标文件所在目录可写；外部公共目录不可写时回退到应用专属 externalFilesDir。
     */
    public static String ensureDownloadDir(String path, Context context) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        File target = new File(path);
        File parent = target.getParentFile();
        if (parent != null && (!parent.exists() ? parent.mkdirs() : true) && canWrite(parent)) {
            return path;
        }
        if (context == null) {
            return null;
        }
        File fallbackDir = new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "3mman/video");
        if (!fallbackDir.exists() && !fallbackDir.mkdirs()) {
            return null;
        }
        if (!canWrite(fallbackDir)) {
            return null;
        }
        return new File(fallbackDir, target.getName()).getAbsolutePath();
    }

    private static boolean canWrite(File dir) {
        File probe = new File(dir, ".write_probe_" + System.currentTimeMillis());
        FileOutputStream os = null;
        try {
            os = new FileOutputStream(probe);
            os.write(1);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (os != null) {
                try { os.close(); } catch (IOException ignored) { }
            }
            if (probe.exists()) {
                probe.delete();
            }
        }
    }

    /**
     * M61：解析已下载文件的真实路径。
     * 下载时 ensureDownloadDir 可能把文件写进应用专属回退目录（公共 /sdcard 不可写的机型），
     * 但 DB 记录只存了原始路径 → 播放/删除时按原路径找会“文件不存在”。
     * 这里按「原路径优先，回退目录兜底」解析，两处都不存在时返回原路径文件（保持旧提示行为）。
     */
    public static File resolveExistingDownloadFile(Context context, String preferredPath) {
        if (TextUtils.isEmpty(preferredPath)) {
            return null;
        }
        File preferred = new File(preferredPath);
        if (preferred.exists() && preferred.length() > 0) {
            return preferred;
        }
        if (context != null) {
            File fallback = new File(
                    new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "3mman/video"),
                    preferred.getName());
            if (fallback.exists() && fallback.length() > 0) {
                return fallback;
            }
        }
        return preferred;
    }

    /**
     * M41：判断文件是否“已完整、可视为下载完成”。
     *
     * 兼容部分 CDN 的 Content-Length 与实际字节数不一致（通常偏差 ≤5%）导致的
     * “文件已完整可播放、下载器却报 error”的情况：
     * - 总量已知：文件实际大小达到声明的 95% 以上即视为完成（避免因 soFar < total 误判失败）；
     * - 总量未知：要求文件非空且 ≥100KB，避免把错误页/极小残留判为完成。
     */
    public static boolean isDownloadFileComplete(File f, long totalBytes) {
        if (f == null || !f.exists() || f.length() <= 0) {
            return false;
        }
        long len = f.length();
        if (totalBytes <= 0) {
            return len >= 100 * 1024;
        }
        return len >= totalBytes || len >= totalBytes * 95 / 100;
    }

    /**
     * V13：解析「可播放/可删除」的成品真实路径，优先 MediaStore 归档路径，其次原路径/回退目录。
     * <p>
     * Scoped Storage 改造后，下载完成的文件在 Android 10+ 会归档进 MediaStore 公共 Movies/3mman，
     * 路径存 V9MmanItem.localFilePath。这里统一解析，避开各调用点重复判空。
     *
     * @param context       上下文（可为 null）
     * @param localFilePath MediaStore 归档路径（可为 null/空 = 未归档）
     * @param preferredPath 计算出的下载路径（未归档时兜底解析）
     * @return 真实存在的可播放文件路径；都不存在时返回 preferredPath（保持旧提示行为）
     */
    public static String resolvePlayablePath(Context context, String localFilePath, String preferredPath) {
        if (!TextUtils.isEmpty(localFilePath)) {
            File archived = new File(localFilePath);
            if (archived.exists() && archived.length() > 0) {
                return localFilePath;
            }
        }
        File fallback = resolveExistingDownloadFile(context, preferredPath);
        return fallback != null ? fallback.getAbsolutePath() : preferredPath;
    }
}
