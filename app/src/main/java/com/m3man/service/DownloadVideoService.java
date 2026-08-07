package com.m3man.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.text.format.Formatter;

import com.liulishuo.filedownloader.BaseDownloadTask;
import com.liulishuo.filedownloader.model.FileDownloadStatus;
import com.m3man.R;
import com.m3man.constants.Constants;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.download.DownloadActivity;
import com.m3man.utils.DownloadManager;
import com.m3man.utils.NotificationChannelHelper;

import java.util.List;

import javax.inject.Inject;

import dagger.android.DaggerService;

/**
 * @author flymegoc
 */
public class DownloadVideoService extends DaggerService implements DownloadManager.DownloadStatusUpdater {

    @Inject
    protected DataManager dataManager;

    public DownloadVideoService() {

    }

    @Override
    public IBinder onBind(Intent intent) {
        //M30：本Service不提供绑定通道，返回null即可（原实现为模板残留的 throw null）
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        //确保渠道存在，避免进程被单独拉起时 startForeground 崩溃
        NotificationChannelHelper.initChannel(this);
        DownloadManager.getImpl().addUpdater(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        //M24：Android 8+ 要求启动后立即进入前台，否则约1分钟后被系统停止导致下载中断
        startForeground(Constants.VIDEO_DOWNLOAD_NOTIFICATION_ID, buildNotification("正在准备下载", 0, "", 0));
        return START_NOT_STICKY;
    }

    private void startNotification(String videoName, int progress, String fileSize, int speed) {
        startForeground(Constants.VIDEO_DOWNLOAD_NOTIFICATION_ID,
                buildNotification(videoName, progress, fileSize, speed));
    }

    private Notification buildNotification(String videoName, int progress, String fileSize, int speed) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NotificationChannelHelper.CHANNEL_ID_FOR_DOWNLOAD);
        builder.setContentTitle("正在下载");
        //只响铃震动一次
        builder.setOnlyAlertOnce(true);
        builder.setSmallIcon(R.mipmap.ic_launcher_round);
        builder.setProgress(100, progress, false);
        builder.setContentText(fileSize + "--" + speed + "KB/s");
        builder.setContentInfo(videoName);
        Intent intent = new Intent(this, DownloadActivity.class);
        //M31：API31+ 必须显式指定可变性
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 1, intent, flags);
        builder.setContentIntent(pendingIntent);
        return builder.build();
    }

    @Override
    public void onDestroy() {
        DownloadManager.getImpl().removeUpdater(this);
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public void complete(BaseDownloadTask task) {
        updateNotification(task, task.getSmallFileSoFarBytes(), task.getSmallFileTotalBytes());
    }

    @Override
    public void update(BaseDownloadTask task) {
        updateNotification(task, task.getSmallFileSoFarBytes(), task.getSmallFileTotalBytes());
    }

    private void updateNotification(BaseDownloadTask task, int soFarBytes, int totalBytes) {
        //totalBytes 在文件大小未知时为0，直接相除会得到NaN甚至异常，这里做保护
        int progress = totalBytes > 0 ? (int) (((float) soFarBytes / totalBytes) * 100) : 0;
        String fileSize = Formatter.formatFileSize(DownloadVideoService.this, soFarBytes).replace("MB", "") + "/ " + Formatter.formatFileSize(DownloadVideoService.this, totalBytes);
        V9MmanItem v9MmanItem = dataManager.findV9MmanItemByDownloadId(task.getId());
        if (v9MmanItem != null) {
            if (task.getStatus() == FileDownloadStatus.completed) {
                List<V9MmanItem> v9MmanItemList = dataManager.findV9MmanItemByDownloadStatus(FileDownloadStatus.progress);
                if (v9MmanItemList.size() == 0) {
                    stopForeground(true);
                }
            } else {
                startNotification(v9MmanItem.getTitle(), progress, fileSize, task.getSpeed());
            }
        } else {
            List<V9MmanItem> v9MmanItemList = dataManager.loadDownloadingData();
            if (v9MmanItemList.size() == 0) {
                stopForeground(true);
            }
        }
    }
}
