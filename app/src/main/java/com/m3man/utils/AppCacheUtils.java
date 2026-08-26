package com.m3man.utils;

import android.content.Context;
import android.support.annotation.NonNull;
import android.text.format.Formatter;

import java.io.File;

/**
 * 应用缓存
 *
 * @author flymegoc
 * @date 2018/1/13
 */

public class AppCacheUtils {
    public final static long MAX_VIDEO_CACHE_SIZE = 800 * 1024 * 1024;
    private final static String RX_CACHE_DIR = "/rx_cache";
    private final static String VIDEO_CACHE_DIR = "/video_cache";
    private final static String GLIDE_DIS_CACHE_DIR = "/glide_cache_dir";

    /**
     * 获取RxCache 缓存目录
     *
     * @param context context
     * @return 缓存目录
     */
    @NonNull
    public static File getRxCacheDir(Context context) {
        String path;
        if (SDCardUtils.isSDCardMounted()) {
            // M99：getExternalCacheDir 可能返回 null（外存未挂载/被清空），回退内部缓存目录
            File externalCacheDir = context.getExternalCacheDir();
            path = (externalCacheDir != null ? externalCacheDir.getAbsolutePath() : context.getCacheDir().getAbsolutePath()) + RX_CACHE_DIR;
        } else {
            path = context.getCacheDir() + RX_CACHE_DIR;
        }
        File file = new File(path);
        if (!file.exists()) {
            file.mkdirs();
        }
        return file.getAbsoluteFile();
    }

    /**
     * 获取视频缓存目录
     *
     * @param context cotext
     * @return 缓存目录
     */
    @NonNull
    public static File getVideoCacheDir(Context context) {
        String path;
        if (SDCardUtils.isSDCardMounted()) {
            // M99：getExternalCacheDir 可能返回 null（外存未挂载/被清空），回退内部缓存目录
            File externalCacheDir = context.getExternalCacheDir();
            path = (externalCacheDir != null ? externalCacheDir.getAbsolutePath() : context.getCacheDir().getAbsolutePath()) + VIDEO_CACHE_DIR;
        } else {
            path = context.getCacheDir() + VIDEO_CACHE_DIR;
        }
        File file = new File(path);
        if (!file.exists()) {
            file.mkdirs();
        }
        return file.getAbsoluteFile();
    }

    /**
     * 获取glide缓存目录
     *
     * @param context context
     * @return 缓存目录
     */
    public static File getGlideDiskCacheDir(Context context) {
        String path;
        if (SDCardUtils.isSDCardMounted()) {
            // M99：getExternalCacheDir 可能返回 null（外存未挂载/被清空），回退内部缓存目录
            File externalCacheDir = context.getExternalCacheDir();
            path = (externalCacheDir != null ? externalCacheDir.getAbsolutePath() : context.getCacheDir().getAbsolutePath()) + GLIDE_DIS_CACHE_DIR;
        } else {
            path = context.getCacheDir() + GLIDE_DIS_CACHE_DIR;
        }
        File file = new File(path);
        if (!file.exists()) {
            file.mkdirs();
        }
        return file.getAbsoluteFile();
    }

    /**
     * 获取RxCache缓存大小
     *
     * @param context context
     * @return 缓存大小
     */
    public static String getGlidecacheFileSizeStr(Context context) {
        long fileSize = getGlidecacheFileSizeNum(context);
        return Formatter.formatFileSize(context, fileSize);
    }

    private static long getGlidecacheFileSizeNum(Context context) {
        return getDirSize(getGlideDiskCacheDir(context));
    }

    /**
     * 获取RxCache缓存大小
     *
     * @param context context
     * @return 缓存大小
     */
    public static String getRxcacheFileSizeStr(Context context) {
        long fileSize = getRxcacheFileSizeNum(context);
        return Formatter.formatFileSize(context, fileSize);
    }

    private static long getRxcacheFileSizeNum(Context context) {
        return getDirSize(getRxCacheDir(context));
    }

    /**
     * 获取videoCache缓存大小
     *
     * @param context context
     * @return 缓存大小
     */
    public static String getVideoCacheFileSizeStr(Context context) {
        long fileSize = getVideoCacheFileSizeNum(context);
        return Formatter.formatFileSize(context, fileSize);
    }

    private static long getVideoCacheFileSizeNum(Context context) {
        return getDirSize(getVideoCacheDir(context));
    }

    /**
     * 递归统计目录占用大小。
     * M28：原实现只统计一层，Glide/RxCache 实际使用多级子目录，导致缓存大小严重偏小。
     *
     * @param file 目录或文件
     * @return 字节数
     */
    private static long getDirSize(File file) {
        if (file == null || !file.exists()) {
            return 0;
        }
        if (file.isFile()) {
            return file.length();
        }
        File[] children = file.listFiles();
        if (children == null) {
            return 0;
        }
        long fileSize = 0;
        for (File childFile : children) {
            fileSize += getDirSize(childFile);
        }
        return fileSize;
    }

    public static String getAllCacheFileSizeStr(Context context) {
        long fileSize = getRxcacheFileSizeNum(context) + getVideoCacheFileSizeNum(context) + getGlidecacheFileSizeNum(context);
        return Formatter.formatFileSize(context, fileSize);
    }

    public static boolean cleanRxCache(Context context) {
        //M28：原实现误清视频缓存目录，导致RxCache永远清不掉
        File fileDir = getRxCacheDir(context);
        return deleteDirFile(fileDir);
    }

    public static boolean cleanVideoCache(Context context) {

        File fileDir = getVideoCacheDir(context);
        return deleteDirFile(fileDir);
    }

    public static boolean cleanGlideCache(Context context) {
        File fileDir = getGlideDiskCacheDir(context);
        return deleteDirFile(fileDir);
    }

    public static boolean cleanAllCache(Context context) {
        //注意：不要用 && 短路，否则前一个失败会导致后面的目录根本不清理
        boolean rx = cleanRxCache(context);
        boolean video = cleanVideoCache(context);
        boolean glide = cleanGlideCache(context);
        return rx && video && glide;
    }

    public static boolean cleanCacheFile(File fileDir) {
        return deleteDirFile(fileDir);
    }

    public static boolean cleanAllCacheFile(File[] fileDirs) {
        if (fileDirs == null) {
            return false;
        }
        boolean result = true;
        for (File file : fileDirs) {
            //M28：原实现每轮直接覆盖result，最终只反映最后一个目录的结果
            result &= deleteDirFile(file);
        }
        return result;
    }

    /**
     * 清空目录内容（保留目录本身），递归处理子目录。
     * M28：原实现只删一层普通文件，Glide/RxCache 的多级子目录清不掉。
     *
     * @param fileDir 目录
     * @return boolean
     */
    private static boolean deleteDirFile(File fileDir) {
        if (fileDir == null || !fileDir.isDirectory()) {
            return false;
        }
        File[] children = fileDir.listFiles();
        if (children == null) {
            return false;
        }
        boolean result = true;
        for (File childFile : children) {
            if (childFile.isDirectory()) {
                result &= deleteDir(childFile);
            } else {
                result &= childFile.delete();
            }
        }
        return result;
    }

    /**
     * 递归删除目录及其所有内容
     *
     * @param dir 目录
     * @return boolean
     */
    private static boolean deleteDir(File dir) {
        if (dir == null) {
            return false;
        }
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            //目录不可读时listFiles返回null，需判空
            if (children != null) {
                for (File child : children) {
                    if (!deleteDir(child)) {
                        return false;
                    }
                }
            }
        }
        return dir.delete();
    }
}
