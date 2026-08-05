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
import android.text.TextUtils;

import com.m3man.R;
import com.m3man.utils.HlsDownloader;
import com.m3man.utils.SDCardUtils;

import java.io.File;

/**
 * 91porny HLS 视频后台下载服务（前台服务）。
 *
 * 在通知栏常驻并实时显示下载进度，不阻塞视频播放。
 * 下载完成后通知可点击打开文件/下载目录。
 */
public class HlsDownloadService extends Service {

    public static final String ACTION_START = "com.m3man.service.action.HLS_START";
    public static final String ACTION_CANCEL = "com.m3man.service.action.HLS_CANCEL";
    public static final String EXTRA_VIDEO_URL = "extra_video_url";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_FILE_NAME = "extra_file_name";

    private static final int NOTIFICATION_ID = 10086;
    private static final String CHANNEL_ID = "hls_download";

    private NotificationManager notificationManager;
    private HlsDownloader downloader;
    private int lastProgress = -1;
    private String notifyTitle = "";
    private String targetMp4Path = "";

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
            if (TextUtils.isEmpty(url)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            notifyTitle = TextUtils.isEmpty(title) ? "视频下载" : title;
            targetMp4Path = SDCardUtils.DOWNLOAD_VIDEO_PATH + fileName + ".mp4";
            startForeground(NOTIFICATION_ID, buildProgressNotification(0, 1));
            startDownload(url, SDCardUtils.DOWNLOAD_VIDEO_PATH, fileName);
        } else if (ACTION_CANCEL.equals(action)) {
            if (downloader != null) {
                downloader.cancel();
            }
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startDownload(String url, String saveDir, String fileName) {
        downloader = new HlsDownloader(this);
        downloader.download(url, saveDir, fileName, new HlsDownloader.HlsDownloadListener() {
            @Override
            public void onProgress(int done, int total) {
                updateProgress(done, total);
            }

            @Override
            public void onSuccess(File mp4File) {
                showCompletedNotification(mp4File);
                stopForeground(true);
                stopSelf();
            }

            @Override
            public void onError(String message) {
                showErrorNotification(message);
                stopForeground(true);
                stopSelf();
            }
        });
    }

    private void updateProgress(int done, int total) {
        int percent = total <= 0 ? 0 : (int) (done * 100L / total);
        if (percent == lastProgress) {
            return;
        }
        lastProgress = percent;
        Notification notification = buildProgressNotification(percent, total);
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private Notification buildProgressNotification(int percent, int total) {
        Intent cancelIntent = new Intent(this, HlsDownloadService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelPi = PendingIntent.getService(this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(notifyTitle)
                .setContentText("下载中 " + percent + "%")
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelPi);
        return builder.build();
    }

    private void showCompletedNotification(File mp4File) {
        Intent openIntent = new Intent(Intent.ACTION_VIEW);
        openIntent.setDataAndType(Uri.fromFile(mp4File), "video/*");
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT);
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
