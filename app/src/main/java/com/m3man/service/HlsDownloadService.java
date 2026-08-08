package com.m3man.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.FileProvider;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;

import com.liulishuo.filedownloader.model.FileDownloadStatus;
import com.m3man.BuildConfig;
import com.m3man.MyApplication;
import com.m3man.R;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.utils.HlsDownloader;
import com.m3man.utils.SDCardUtils;

import java.io.File;
import java.util.Date;

/**
 * 91porny HLS 视频后台下载服务（前台服务）。
 *
 * 在通知栏常驻并实时显示下载进度，不阻塞视频播放。
 * 下载完成后把记录写入 V9MmanItem（status=completed, downloadId=伪id），
 * 使「我的下载」页面（LoadFinishedData 查询 Status=completed & DownloadId!=0）能正常展示，
 * 并通过 LocalBroadcast 通知「我的下载」页面实时刷新（HLS 不走 FileDownloader 回调链）。
 */
public class HlsDownloadService extends Service {

    public static final String ACTION_START = "com.m3man.service.action.HLS_START";
    public static final String ACTION_CANCEL = "com.m3man.service.action.HLS_CANCEL";
    public static final String EXTRA_VIDEO_URL = "extra_video_url";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_FILE_NAME = "extra_file_name";
    public static final String EXTRA_VIEW_KEY = "extra_view_key";
    public static final String EXTRA_SAVE_PATH = "extra_save_path";

    /** 下载进度/完成广播：让「我的下载」页面实时刷新 */
    public static final String ACTION_HLS_PROGRESS = "com.m3man.service.action.HLS_PROGRESS";
    public static final String ACTION_HLS_DONE = "com.m3man.service.action.HLS_DONE";
    public static final String EXTRA_PROGRESS = "extra_progress";

    private static final int NOTIFICATION_ID = 10086;
    private static final String CHANNEL_ID = "hls_download";

    private NotificationManager notificationManager;
    private HlsDownloader downloader;
    private int lastProgress = -1;
    private String notifyTitle = "";
    private String targetMp4Path = "";
    private String viewKey = "";
    private String savePath = "";
    private int pseudoDownloadId = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            String url = intent.getStringExtra(EXTRA_VIDEO_URL);
            String title = intent.getStringExtra(EXTRA_TITLE);
            String fileName = intent.getStringExtra(EXTRA_FILE_NAME);
            viewKey = intent.getStringExtra(EXTRA_VIEW_KEY);
            savePath = intent.getStringExtra(EXTRA_SAVE_PATH);
            if (TextUtils.isEmpty(url)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            notifyTitle = TextUtils.isEmpty(title) ? "视频下载" : title;
            // 优先使用调用方算好的完整路径（与「我的下载」播放路径一致），否则回退旧 fileName 逻辑
            if (TextUtils.isEmpty(savePath)) {
                targetMp4Path = SDCardUtils.DOWNLOAD_VIDEO_PATH + fileName + ".mp4";
            } else {
                targetMp4Path = savePath;
            }
            // 稳定的伪 downloadId，使「我的下载」查询（DownloadId!=0）能命中本记录
            pseudoDownloadId = Math.abs(url.hashCode());
            startForeground(NOTIFICATION_ID, buildProgressNotification("下载中 0%", 0, 1));
            startDownload(url, targetMp4Path);
        } else if (ACTION_CANCEL.equals(action)) {
            if (downloader != null) {
                downloader.cancel();
                downloader.shutdown();
                downloader = null;
            }
            // M40：清理下载过程中产生的临时分片（getCacheDir 下的 hls_* 目录）
            cleanupHlsTemp();
            // 复位 DB 记录，使其从「正在下载」列表移除
            resetRecord();
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startDownload(String url, String mp4Path) {
        // M25：若已有下载在跑，先取消并释放旧下载器，避免孤儿线程 / 进度串台
        if (downloader != null) {
            downloader.cancel();
            downloader.shutdown();
            downloader = null;
        }
        File target = new File(mp4Path);
        String saveDir = target.getParent();
        String fileName = target.getName();
        if (fileName != null && fileName.toLowerCase().endsWith(".mp4") && fileName.length() > 4) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }
        downloader = new HlsDownloader(this);
        downloader.download(url, saveDir, fileName, new HlsDownloader.HlsDownloadListener() {
            @Override
            public void onProgress(int done, int total) {
                handleProgress(done, total);
            }

            @Override
            public void onSuccess(File mp4File) {
                handleSuccess(mp4File);
            }

            @Override
            public void onError(String message) {
                handleError(message);
            }
        });
    }

    private DataManager getDataManager() {
        return MyApplication.getInstance().getDataManager();
    }

    private V9MmanItem findItem() {
        if (TextUtils.isEmpty(viewKey)) {
            return null;
        }
        return getDataManager().findV9MmanItemByViewKey(viewKey);
    }

    /**
     * M41：优先按 viewKey 查找；查不到时按伪 downloadId 兜底
     * （部分场景 viewKey 与 DB 不一致会导致 handleSuccess 找不到记录、无法进“下载完成”）。
     */
    private V9MmanItem findItemOrByDownloadId() {
        V9MmanItem item = findItem();
        if (item == null && pseudoDownloadId != 0) {
            item = getDataManager().findV9MmanItemByDownloadId(pseudoDownloadId);
        }
        return item;
    }

    private void handleProgress(int done, int total) {
        int percent = total <= 0 ? 0 : (int) (done * 100L / total);
        // M41：分片全部下载完成后进入合并/转码阶段，提示用户（避免误以为卡死）
        if (total > 0 && done >= total) {
            updateProgress("分片下载完成，正在转码", percent, total);
        } else {
            updateProgress("下载中 " + percent + "%", percent, total);
        }
        V9MmanItem item = findItem();
        if (item != null) {
            item.setProgress(percent);
            item.setStatus(FileDownloadStatus.progress);
            item.setSoFarBytes(done);
            item.setTotalFarBytes(total);
            getDataManager().updateV9MmanItem(item);
        }
        Intent i = new Intent(ACTION_HLS_PROGRESS);
        i.putExtra(EXTRA_VIEW_KEY, viewKey);
        i.putExtra(EXTRA_PROGRESS, percent);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void handleSuccess(File mp4File) {
        V9MmanItem item = findItemOrByDownloadId();
        if (item != null) {
            item.setStatus(FileDownloadStatus.completed);
            item.setProgress(100);
            item.setFinishedDownloadDate(new Date());
            item.setSoFarBytes((int) mp4File.length());
            item.setTotalFarBytes((int) mp4File.length());
            item.setDownloadId(pseudoDownloadId);
            getDataManager().updateV9MmanItem(item);
        }
        Intent i = new Intent(ACTION_HLS_DONE);
        i.putExtra(EXTRA_VIEW_KEY, viewKey);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
        showCompletedNotification(mp4File);
        stopForeground(true);
        releaseDownloader();
        stopSelf();
    }

    private void handleError(String message) {
        // M40：若最终 mp4 已生成且非空，视为成功（避免“能播放却提示下载失败”）
        File mp4 = new File(targetMp4Path);
        if (mp4.exists() && mp4.length() > 0) {
            handleSuccess(mp4);
            return;
        }
        // M41：失败时保留记录（status=error、downloadId 不变），
        // 使「正在下载」列表显示“下载错误”并可重试/删除；
        // 不再 resetRecord 把 downloadId 置 0 导致记录从两个列表都消失。
        V9MmanItem item = findItemOrByDownloadId();
        if (item != null) {
            item.setStatus(FileDownloadStatus.error);
            item.setProgress(100);
            item.setDownloadId(pseudoDownloadId);
            getDataManager().updateV9MmanItem(item);
        }
        // 清理临时分片，避免残留
        cleanupHlsTemp();
        showErrorNotification(message);
        stopForeground(true);
        releaseDownloader();
        stopSelf();
    }

    /** M40：取消 HLS 下载时清理 getCacheDir 下的 hls_* 临时分片目录 */
    private void cleanupHlsTemp() {
        try {
            File cache = getCacheDir();
            File[] files = cache.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory() && f.getName().startsWith("hls_")) {
                        deleteRecursively(f);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void deleteRecursively(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }

    private void resetRecord() {
        V9MmanItem item = findItem();
        if (item != null) {
            // downloadId 置 0 使「我的下载」两列表（DownloadId!=0）均排除该记录
            item.setDownloadId(0);
            getDataManager().updateV9MmanItem(item);
        }
    }

    // M25/M26：释放下载器并关闭其线程池，防止 4 个工作线程泄漏
    private void releaseDownloader() {
        if (downloader != null) {
            downloader.shutdown();
            downloader = null;
        }
    }

    private void updateProgress(String text, int percent, int total) {
        if (percent == lastProgress) {
            return;
        }
        lastProgress = percent;
        Notification notification = buildProgressNotification(text, percent, total);
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private Notification buildProgressNotification(String text, int percent, int total) {
        Intent cancelIntent = new Intent(this, HlsDownloadService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelPi = PendingIntent.getService(this, 0, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(notifyTitle)
                .setContentText(text)
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelPi);
        return builder.build();
    }

    private void showCompletedNotification(File mp4File) {
        Intent openIntent = new Intent(Intent.ACTION_VIEW);
        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Android 7+ 禁止在 Intent 中暴露 file://，必须使用 FileProvider
            uri = FileProvider.getUriForFile(getApplicationContext(),
                    BuildConfig.APPLICATION_ID + ".fileprovider", mp4File);
            openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            uri = Uri.fromFile(mp4File);
        }
        openIntent.setDataAndType(uri, "video/*");
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(notifyTitle)
                .setContentText("下载完成，点击播放")
                .setAutoCancel(true)
                .setContentIntent(contentPi)
                .build();
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private void showErrorNotification(String message) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(notifyTitle)
                .setContentText("下载失败：" + message)
                .setAutoCancel(true)
                .build();
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "视频下载", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
