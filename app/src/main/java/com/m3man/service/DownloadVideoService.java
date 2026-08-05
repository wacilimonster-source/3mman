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
        // TODO: Return the communication channel to the service.
        throw null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        DownloadManager.getImpl().addUpdater(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        return START_NOT_STICKY;
    }

    private void startNotification(String videoName, int progress, String fileSize, int speed) {
        int id = Constants.VIDEO_DOWNLOAD_NOTIFICATION_ID;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NotificationChannelHelper.CHANNEL_ID_FOR_DOWNLOAD);
        builder.setContentTitle("正在下载");
        //只响铃震动一次
        builder.setOnlyAlertOnce(true);
        builder.setSmallIcon(R.mipmap.ic_launcher_round);
        builder.setProgress(100, progress, false);
        builder.setContentText(fileSize + "--" + speed + "KB/s");
        builder.setContentInfo(videoName);
        Intent intent = new Intent(this, DownloadActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        builder.setContentIntent(pendingIntent);
        Notification notification = builder.build();
        startForeground(id, notification);
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
        int progress = (int) (((float) soFarBytes / totalBytes) * 100);
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
