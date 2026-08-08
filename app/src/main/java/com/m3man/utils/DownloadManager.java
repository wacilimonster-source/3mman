package com.m3man.utils;

import com.liulishuo.filedownloader.BaseDownloadTask;
import com.liulishuo.filedownloader.FileDownloadListener;
import com.liulishuo.filedownloader.FileDownloader;
import com.liulishuo.filedownloader.model.FileDownloadStatus;
import com.orhanobut.logger.Logger;
import com.m3man.BuildConfig;
import com.m3man.MyApplication;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;


/**
 * @author flymegoc
 * @date 2017/11/23
 * @describe
 */

public class DownloadManager {
    private static final String TAG = DownloadManager.class.getSimpleName();

    protected DataManager dataManager;

    private DownloadManager() {
        dataManager = MyApplication.getInstance().getDataManager();
    }

    private final static class HolderClass {
        private final static DownloadManager INSTANCE = new DownloadManager();
    }

    public static DownloadManager getImpl() {
        return HolderClass.INSTANCE;
    }

    /**
     * M29：监听器会被 Service/Activity 在不同线程增删，同时下载回调线程在遍历。
     * 普通 ArrayList 会 ConcurrentModificationException，改用写时复制容器。
     */
    private final CopyOnWriteArrayList<DownloadStatusUpdater> updaterList = new CopyOnWriteArrayList<>();


    public int startDownload(String url, final String path, boolean isDownloadNeedWifi, boolean isForceReDownload) {
        Logger.t(TAG).d("url::" + url);
        Logger.t(TAG).d("path::" + path);
        Logger.t(TAG).d("isDownloadNeedWifi::" + isDownloadNeedWifi);
        Logger.t(TAG).d("isForceReDownload::" + isForceReDownload);
        int id = FileDownloader.getImpl().create(url)
                .setPath(path)
                .setListener(lis)
                .setWifiRequired(isDownloadNeedWifi)
                .setAutoRetryTimes(3)
                .setForceReDownload(isForceReDownload)
                .asInQueueTask()
                .enqueue();
        FileDownloader.getImpl().start(lis, false);
        return id;
    }

    public void addUpdater(final DownloadStatusUpdater updater) {
        if (!updaterList.contains(updater)) {
            updaterList.add(updater);
        }
    }

    public boolean removeUpdater(final DownloadStatusUpdater updater) {
        return updaterList.remove(updater);
    }


    private FileDownloadListener lis = new FileDownloadListener() {
        @Override
        protected void pending(BaseDownloadTask task, int soFarBytes, int totalBytes) {
            Logger.t(TAG).d("pending:" + "--status:" + task.getStatus() + "--:soFarBytes：" + soFarBytes + "--:totalBytes：" + totalBytes);
            saveDownloadInfo(task);
        }

        @Override
        protected void started(BaseDownloadTask task) {
            super.started(task);
            Logger.t(TAG).d("started:" + "--status:" + task.getStatus() + "--:soFarBytes：" + task.getSmallFileSoFarBytes() + "--:totalBytes：" + task.getSmallFileTotalBytes());
            saveDownloadInfo(task);
        }

        @Override
        protected void connected(BaseDownloadTask task, String etag, boolean isContinue, int soFarBytes, int totalBytes) {
            super.connected(task, etag, isContinue, soFarBytes, totalBytes);
            Logger.t(TAG).d("connected:" + "--status:" + task.getStatus() + "--:soFarBytes：" + soFarBytes + "--:totalBytes：" + totalBytes);
            saveDownloadInfo(task);
        }

        @Override
        protected void progress(BaseDownloadTask task, int soFarBytes, int totalBytes) {
            saveDownloadInfo(task);
        }

        @Override
        protected void blockComplete(BaseDownloadTask task) {
            Logger.t(TAG).d("complete:" + "--status:" + task.getStatus() + "--:soFarBytes：" + task.getSmallFileSoFarBytes() + "--:totalBytes：" + task.getSmallFileTotalBytes());
        }

        @Override
        protected void completed(BaseDownloadTask task) {
            Logger.t(TAG).d("completed:" + "--status:" + task.getStatus() + "--:soFarBytes：" + task.getSmallFileSoFarBytes() + "--:totalBytes：" + task.getSmallFileTotalBytes());
            Logger.d("completed");
            saveDownloadInfo(task);
        }

        @Override
        protected void paused(BaseDownloadTask task, int soFarBytes, int totalBytes) {
            Logger.t(TAG).d("paused:" + "--status:" + task.getStatus() + "--:soFarBytes：" + soFarBytes + "--:totalBytes：" + totalBytes);
            saveDownloadInfo(task);
        }

        @Override
        protected void error(BaseDownloadTask task, Throwable e) {
            Logger.t(TAG).d("error:" + "--status:" + task.getStatus() + "--:soFarBytes：" + task.getSmallFileSoFarBytes() + "--:totalBytes：" + task.getSmallFileTotalBytes());
            // M40：部分机型/系统下文件已完整下载却误报 error（如末尾 sync/重命名失败）。
            // 若目标文件已存在且字节数足够，则按“完成”处理，避免“能播放却提示下载失败”。
            try {
                File f = new File(task.getPath());
                long total = task.getSmallFileTotalBytes();
                long soFar = task.getSmallFileSoFarBytes();
                if (f.exists() && f.length() > 0 && (total <= 0 || soFar >= total)) {
                    V9MmanItem item = dataManager.findV9MmanItemByDownloadId(task.getId());
                    if (item != null) {
                        item.setStatus(FileDownloadStatus.completed);
                        item.setProgress(100);
                        item.setSoFarBytes((int) f.length());
                        item.setTotalFarBytes((int) f.length());
                        item.setFinishedDownloadDate(new Date());
                        dataManager.updateV9MmanItem(item);
                    }
                    complete(task);
                    return;
                }
            } catch (Exception ignored) {
            }
            saveDownloadInfo(task);
        }

        @Override
        protected void warn(BaseDownloadTask task) {
            Logger.t(TAG).d("warn:" + "--status:" + task.getStatus() + "--:soFarBytes：" + task.getSmallFileSoFarBytes() + "--:totalBytes：" + task.getSmallFileTotalBytes());
            saveDownloadInfo(task);
        }
    };

    /**
     * 实时保存下载信息
     *
     * @param task 任务信息
     */
    private void saveDownloadInfo(BaseDownloadTask task) {
        V9MmanItem v9MmanItem = dataManager.findV9MmanItemByDownloadId(task.getId());
        if (v9MmanItem == null) {
            //不存在的任务清除掉
            FileDownloader.getImpl().clear(task.getId(), task.getPath());
            if (!BuildConfig.DEBUG) {
              //  Bugsnag.notify(new Throwable(TAG + "::save download info failure:" + task.getUrl()), Severity.WARNING);
            }
            return;
        }
        int soFarBytes = task.getSmallFileSoFarBytes();
        int totalBytes = task.getSmallFileTotalBytes();
        if (soFarBytes > 0) {
            v9MmanItem.setSoFarBytes(soFarBytes);
        }

        if (totalBytes > 0) {
            v9MmanItem.setTotalFarBytes(totalBytes);
        }
        if (totalBytes > 0) {
            int p = (int) (((float) soFarBytes / totalBytes) * 100);
            v9MmanItem.setProgress(p);
        }
        if (task.getStatus() == FileDownloadStatus.completed) {
            v9MmanItem.setFinishedDownloadDate(new Date());
        }
        v9MmanItem.setSpeed(task.getSpeed());
        v9MmanItem.setStatus(task.getStatus());
        dataManager.updateV9MmanItem(v9MmanItem);
        if (task.getStatus() == FileDownloadStatus.completed) {
            complete(task);
        } else {
            update(task);
        }
    }

    private void complete(final BaseDownloadTask task) {
        //CopyOnWriteArrayList 的迭代器本身是快照，无需再clone
        for (DownloadStatusUpdater downloadStatusUpdater : updaterList) {
            downloadStatusUpdater.complete(task);
        }
    }

    private void update(final BaseDownloadTask task) {
        for (DownloadStatusUpdater downloadStatusUpdater : updaterList) {
            downloadStatusUpdater.update(task);
        }
    }

    public interface DownloadStatusUpdater {
        void complete(BaseDownloadTask task);

        void update(BaseDownloadTask task);
    }
}
