package com.m3man.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.BuildConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Scoped Storage（targetSdk 34）下载改造：把「下载完成的本地文件」归档进 MediaStore 公共媒体库。
 *
 * 背景：Android 10+ 强制 Scoped Storage 后，应用无法直接写 /sdcard 公共目录。
 * 本项目下载仍写应用可写目录（ensureDownloadDir 回退到应用专属目录），完成后把成品
 * 迁入 MediaStore 公共 Movies/3mman，使成品在系统相册/文件管理器可见（零副本，文件只保留一份）。
 *
 * 设计要点（向后兼容，低风险）：
 * - 仅 API>=29（Scoped Storage 强制）才归档；API<=28 走旧路径逻辑，不归档。
 * - 全程 best-effort：任何异常都返回 null 并保留私有副本，播放/删除回退到原路径解析，功能不降级。
 * - 成功后返回成品在公共目录的绝对路径，调用方存入 V9MmanItem.localFilePath 供播放/删除直读
 *  （自己的 MediaStore 贡献物可路径直读，无需改播放器）。
 */
public final class MediaStoreArchiver {

    private MediaStoreArchiver() {
    }

    /** 公共相册子目录（相对路径，配合 RELATIVE_PATH 使用） */
    public static final String RELATIVE_SUB_DIR = Environment.DIRECTORY_MOVIES + "/" + "3mman";

    /**
     * 把 src 归档进 MediaStore 公共 Movies/3mman。
     *
     * @param ctx      上下文
     * @param src      下载完成的本地文件（私有/公共均可）
     * @param title    视频标题（用于生成展示文件名）
     * @param viewKey  唯一键（拼进文件名避免碰撞）
     * @return 归档成品的公共绝对路径；无需归档或归档失败时返回 null（调用方保留 src 走旧逻辑）
     */
    public static String archiveVideo(Context ctx, File src, String title, String viewKey) {
        if (ctx == null || src == null || !src.exists() || src.length() <= 0) {
            return null;
        }
        // API<=28 无 Scoped Storage 限制，沿用旧路径逻辑（文件已可写公共目录，可被系统扫描到）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        String displayName = SDCardUtils.sanitizeFileName(title);
        if (viewKey != null && viewKey.length() > 0) {
            displayName = displayName + "_" + viewKey;
        }
        if (!displayName.toLowerCase(java.util.Locale.US).endsWith(".mp4")) {
            displayName = displayName + ".mp4";
        }
        ContentResolver resolver = ctx.getContentResolver();
        Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri pendingUri = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_SUB_DIR);
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            pendingUri = resolver.insert(collection, values);
            if (pendingUri == null) {
                return null;
            }
            OutputStream out = resolver.openOutputStream(pendingUri, "w");
            if (out == null) {
                deleteQuietly(resolver, pendingUri);
                return null;
            }
            InputStream in = null;
            try {
                in = new FileInputStream(src);
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } finally {
                try {
                    if (in != null) in.close();
                } catch (Exception ignored) {
                }
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
            // 完成写入后发布（App 之外立即可见）
            ContentValues publish = new ContentValues();
            publish.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(pendingUri, publish, null, null);
            // 读取真实物理路径供播放/删除直读（自己的 MediaStore 贡献物可路径访问）
            String path = queryPath(resolver, pendingUri);
            if (path == null || path.length() == 0) {
                path = pendingUri.toString();
            }
            // 归档成功 → 删除私有副本，实现「零副本」
            deleteQuietly(src);
            return path;
        } catch (Exception e) {
            // 失败：删除可能残留的半成品 MediaStore 行，保留私有副本走旧逻辑
            try {
                if (pendingUri != null) {
                    resolver.delete(pendingUri, null, null);
                }
            } catch (Exception ignored) {
            }
            if (BuildConfig.DEBUG) {
                AppLog.w("MediaStoreArchiver", "archiveVideo failed，已保留私有副本: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * V13：把文本内容归档进 MediaStore 公共 Downloads/3mman（系统文件管理器可见）。
     * 仅 API>=29（Scoped Storage 强制）；API<29 返回 false，由调用方走老公共路径。
     *
     * @return true=已写入系统下载目录（可见）；false=无需归档或写入失败
     */
    public static boolean archiveTextToDownloads(Context ctx, String content, String displayName) {
        if (ctx == null || content == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false;
        }
        ContentResolver resolver = ctx.getContentResolver();
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        Uri uri = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + "3mman");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            uri = resolver.insert(collection, values);
            if (uri == null) {
                return false;
            }
            OutputStream out = resolver.openOutputStream(uri, "w");
            if (out == null) {
                deleteQuietly(resolver, uri);
                return false;
            }
            try {
                byte[] bytes = content.getBytes("UTF-8");
                out.write(bytes);
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
            // 发布（App 之外立即可见）
            ContentValues publish = new ContentValues();
            publish.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, publish, null, null);
            return true;
        } catch (Exception e) {
            try {
                if (uri != null) {
                    resolver.delete(uri, null, null);
                }
            } catch (Exception ignored) {
            }
            if (BuildConfig.DEBUG) {
                AppLog.w("MediaStoreArchiver", "archiveTextToDownloads failed: " + e.getMessage());
            }
            return false;
        }
    }

    private static String queryPath(ContentResolver resolver, Uri uri) {
        try {
            String[] projection = {MediaStore.Video.Media.DATA};
            android.database.Cursor c = resolver.query(uri, projection, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int idx = c.getColumnIndex(MediaStore.Video.Media.DATA);
                        if (idx >= 0) {
                            return c.getString(idx);
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 删除本地已归档文件（含 MediaStore 行，使相册/文件管理器同步移除）。
     * best-effort：MediaStore 行删除失败则退化为直接删文件。
     */
    public static void deleteArchived(Context ctx, V9MmanItem item) {
        if (ctx == null || item == null) {
            return;
        }
        String path = item.getLocalFilePath();
        if (path == null || path.length() == 0) {
            return;
        }
        // 记录的是物理路径 → 删除 MediaStore 行（按 DATA 匹配）
        try {
            if (path.startsWith("content://")) {
                ctx.getContentResolver().delete(Uri.parse(path), null, null);
            } else {
                String[] selArgs = {path};
                ctx.getContentResolver().delete(
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        MediaStore.Video.Media.DATA + "=?", selArgs);
            }
        } catch (Exception ignored) {
        }
        deleteQuietly(new File(path));
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** 删除 MediaStore 半成品行（best-effort） */
    private static void deleteQuietly(ContentResolver resolver, Uri uri) {
        if (resolver == null || uri == null) {
            return;
        }
        try {
            resolver.delete(uri, null, null);
        } catch (Exception ignored) {
        }
    }
}