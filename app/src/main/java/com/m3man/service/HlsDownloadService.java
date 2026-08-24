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
import com.m3man.utils.AppCacheUtils;
import com.m3man.utils.AppLog;
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
    public static final String ACTION_PAUSE = "com.m3man.service.action.HLS_PAUSE";
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
    // M62：用户取消栅栏——worker 迟到回调（成功/失败）不得再写库或弹通知
    private volatile boolean cancelledByUser;
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
        // M73：startForegroundService 启动时系统要求 5 秒内必须调用 startForeground，
        // 否则 ANR/crash。非 START 动作（PAUSE/CANCEL/null）也需先满足该契约再走业务。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                if (ACTION_START.equals(intent == null ? null : intent.getAction())) {
                    // START 分支下方原有 startForeground 调用会立即执行，这里不重复
                } else {
                    startForeground(NOTIFICATION_ID, buildProgressNotification("处理中", 0, 1));
                    stopForeground(true);
                }
            } catch (Exception ignored) {
            }
        }
        if (intent == null) {
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            String url = intent.getStringExtra(EXTRA_VIDEO_URL);
            String title = intent.getStringExtra(EXTRA_TITLE);
            String fileName = intent.getStringExtra(EXTRA_FILE_NAME);
            // M62：覆盖字段前先快照"被替换的旧任务"上下文——
            // 旧 worker 线程的迟到回调必须按它自己的归属落库，不得读写新任务的共享字段
            final HlsDownloader replacedDownloader = downloader;
            final String replacedViewKey = viewKey;
            final int replacedPseudoId = pseudoDownloadId;
            viewKey = intent.getStringExtra(EXTRA_VIEW_KEY);
            savePath = intent.getStringExtra(EXTRA_SAVE_PATH);
            if (TextUtils.isEmpty(url)) {
                // M73：startForegroundService 路径下也需先履行 startForeground 契约
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    try {
                        startForeground(NOTIFICATION_ID, buildProgressNotification("下载中 0%", 0, 1));
                        stopForeground(true);
                    } catch (Exception ignored) {
                    }
                }
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
            // M61：目录不可写时回退到应用专属目录，避免 Android 11+ 公共目录写入直接失败
            String ensured = SDCardUtils.ensureDownloadDir(targetMp4Path, this);
            if (!TextUtils.isEmpty(ensured)) {
                targetMp4Path = ensured;
                AppLog.i("HlsDownload", "下载目录 ensured=" + ensured);
            } else {
                AppLog.e("HlsDownload", "下载目录不可写且无 fallback path=" + targetMp4Path);
            }
            // 稳定的伪 downloadId，使「我的下载」查询（DownloadId!=0）能命中本记录。
            // M73：Math.abs(hashCode) 对 Integer.MIN_VALUE 会返回负数，且不同 URL 可能碰撞；
            // 改为拼接 viewKey 哈希的高低位构造正数，并兜底保证 >0。
            pseudoDownloadId = stablePositiveId(url);
            cancelledByUser = false;
            startForeground(NOTIFICATION_ID, buildProgressNotification("下载中 0%", 0, 1));
            // M62：统一在此落伪 downloadId + status=progress（修复 2.5）——
            // 各启动入口（推荐流/兜底/重新下载等）此前不写 downloadId，导致整个下载期间
            // 记录在「正在下载」「下载完成」两列表均不可见、进度广播空转。
            markRecordDownloading();
            startDownload(url, targetMp4Path, replacedDownloader, replacedViewKey, replacedPseudoId);
        } else if (ACTION_PAUSE.equals(action)) {
            if (downloader == null) {
                return START_NOT_STICKY;
            }
            String pauseViewKey = intent.getStringExtra(EXTRA_VIEW_KEY);
            if (!TextUtils.isEmpty(pauseViewKey) && !pauseViewKey.equals(viewKey)) {
                return START_NOT_STICKY;
            }
            cancelledByUser = true;
            downloader.cancel();
            downloader.shutdown();
            downloader = null;
            cleanupHlsTemp();
            V9MmanItem item = findItem();
            if (item != null) {
                item.setStatus(FileDownloadStatus.paused);
                getDataManager().updateV9MmanItem(item);
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_HLS_PROGRESS)
                    .putExtra(EXTRA_VIEW_KEY, viewKey).putExtra(EXTRA_PROGRESS, lastProgress < 0 ? 0 : lastProgress));
            stopForeground(true);
            stopSelf();
        } else if (ACTION_CANCEL.equals(action)) {
            // M62：竞态防护——
            // ① 无活跃下载（downloader==null）时忽略取消，防止陈旧 viewKey 清错已完成记录；
            // ② 带 viewKey 的取消请求与本任务不匹配时同样忽略（防误杀无关记录/当前下载）。
            String cancelViewKey = intent.getStringExtra(EXTRA_VIEW_KEY);
            if (downloader == null) {
                return START_NOT_STICKY;
            }
            if (!TextUtils.isEmpty(cancelViewKey) && !cancelViewKey.equals(viewKey)) {
                return START_NOT_STICKY;
            }
            cancelledByUser = true;
            downloader.cancel();
            downloader.shutdown();
            downloader = null;
            // M40：清理下载过程中产生的临时分片（getCacheDir 下的 hls_* 目录）
            cleanupHlsTemp();
            // 复位 DB 记录，使其从「正在下载」列表移除
            resetRecord();
            // M62：取消后清掉半成品 mp4，避免残留不可播文件
            deleteQuietly(new File(targetMp4Path));
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    /** M73：构造稳定且恒为正的伪 downloadId（Math.abs 对 MIN_VALUE 返回负数） */
    static int stablePositiveId(String url) {
        if (url == null) {
            return 1;
        }
        int h = url.hashCode();
        // 混入二次哈希减少碰撞；用无符号右移保证正数
        int mixed = h ^ (h >>> 16);
        int positive = mixed & 0x7FFFFFFF;
        return positive == 0 ? 1 : positive;
    }

    private void startDownload(String url, String mp4Path,
                               final HlsDownloader replacedDownloader,
                               final String replacedViewKey,
                               final int replacedPseudoId) {
        // M25：若已有下载在跑，先取消并释放旧下载器，避免孤儿线程（其上下文已由调用方快照）
        if (replacedDownloader != null) {
            replacedDownloader.cancel();
            replacedDownloader.shutdown();
            if (downloader == replacedDownloader) {
                downloader = null;
            }
        }
        File target = new File(mp4Path);
        String saveDir = target.getParent();
        String fileName = target.getName();
        if (fileName != null && fileName.toLowerCase().endsWith(".mp4") && fileName.length() > 4) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }
        // M62：per-task 归属校验——闭包持有"本任务专属的下载器实例"，
        // 回调到达时与当前 downloader 字段比对，非当前实例的一律按旧任务上下文处理或丢弃。
        // 修复：连发两个 HLS 下载时，被程序化 cancel 的旧 worker 迟到回调
        // 会把新任务记录写成 error 并删掉新任务目标 mp4（cancelledByUser 栅栏管不住这条路径）。
        final HlsDownloader activeDownloader = new HlsDownloader(this);
        downloader = activeDownloader;
        // M43：传入播放缓存目录，若该视频播放过（分片已缓存）则直接复用，避免重复下载
        activeDownloader.download(url, saveDir, fileName, AppCacheUtils.getVideoCacheDir(this), new HlsDownloader.HlsDownloadListener() {
            @Override
            public void onProgress(int done, int total) {
                if (activeDownloader != downloader) {
                    // 旧任务的迟到进度：不写库、不刷通知，直接丢弃
                    return;
                }
                handleProgress(done, total);
            }

            @Override
            public void onSuccess(File mp4File) {
                if (activeDownloader == downloader && !cancelledByUser) {
                    handleSuccess(mp4File);
                    return;
                }
                if (!cancelledByUser && replacedDownloader != null
                        && !TextUtils.isEmpty(replacedViewKey)) {
                    // 被新任务替换的旧下载器迟到成功：产物属旧任务目标路径，
                    // 按旧任务自身上下文落库，绝不触碰新任务的记录与生命周期
                    markTaskCompletedQuietly(replacedViewKey, replacedPseudoId, mp4File);
                } else {
                    // 用户已取消的任务迟到成功：产物不可信，删除
                    deleteQuietly(mp4File);
                }
            }

            @Override
            public void onError(String message) {
                if (activeDownloader != downloader) {
                    // 旧下载器的迟到失败（含被程序化取消）：只记日志——
                    // 不写库、不删任何文件，防止误伤新任务的记录与产物
                    AppLog.i("HlsDownload", "丢弃旧下载器迟到错误回调 msg=" + message);
                    return;
                }
                handleError(message);
            }
        });
    }

    /**
     * M62：被替换的旧任务在共享字段已被覆盖后迟到完成——
     * 按快照上下文静默写库并广播刷新，不弹通知、不动前台状态/服务生命周期（那些属于当前活跃任务）。
     */
    private void markTaskCompletedQuietly(String taskViewKey, int taskPseudoId, File mp4File) {
        AppLog.i("HlsDownload", "旧任务迟到完成 viewKey=" + taskViewKey + " path=" + mp4File.getPath());
        try {
            V9MmanItem item = getDataManager().findV9MmanItemByViewKey(taskViewKey);
            if (item == null && taskPseudoId != 0) {
                item = getDataManager().findV9MmanItemByDownloadId(taskPseudoId);
            }
            if (item != null) {
                long len = mp4File.length();
                int size = len > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, len);
                item.setStatus(FileDownloadStatus.completed);
                item.setProgress(100);
                item.setFinishedDownloadDate(new Date());
                item.setSoFarBytes(size);
                item.setTotalFarBytes(size);
                item.setDownloadId(taskPseudoId);
                getDataManager().updateV9MmanItem(item);
            }
        } catch (Exception e) {
            AppLog.e("HlsDownload", "markTaskCompletedQuietly failed: " + e.getMessage());
        }
        Intent i = new Intent(ACTION_HLS_DONE);
        i.putExtra(EXTRA_VIEW_KEY, taskViewKey);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
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

    /** M62：下载启动时把记录标记为 progress + 落伪 downloadId，使两列表可见、可取消 */
    private void markRecordDownloading() {
        try {
            V9MmanItem item = findItemOrByDownloadId();
            if (item != null) {
                item.setStatus(FileDownloadStatus.progress);
                item.setProgress(0);
                item.setDownloadId(pseudoDownloadId);
                item.setSoFarBytes(0);
                item.setTotalFarBytes(0);
                getDataManager().updateV9MmanItem(item);
            }
        } catch (Exception e) {
            AppLog.e("HlsDownload", "markRecordDownloading failed: " + e.getMessage());
        }
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
            // M62：soFarBytes/totalFarBytes 必须存字节而非分片数——
            // 分片数会被下游 isDownloadFileComplete 当字节阈值（95%×分片数 恒真），
            // 也会被列表按 formatFileSize 渲染成垃圾尺寸。分片阶段总量未知，存 0 走"≥100KB"兜底分支。
            item.setSoFarBytes(0);
            item.setTotalFarBytes(0);
            getDataManager().updateV9MmanItem(item);
        }
        Intent i = new Intent(ACTION_HLS_PROGRESS);
        i.putExtra(EXTRA_VIEW_KEY, viewKey);
        i.putExtra(EXTRA_PROGRESS, percent);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void handleSuccess(File mp4File) {
        // M62：已取消的任务不得复活为"下载完成"（取消发生在合并/转码阶段时到达此处）
        if (cancelledByUser) {
            AppLog.i("HlsDownload", "已取消，丢弃迟到成功回调 path=" + mp4File.getPath());
            deleteQuietly(mp4File);
            cleanupHlsTemp();
            stopForeground(true);
            releaseDownloader();
            stopSelf();
            return;
        }
        V9MmanItem item = findItemOrByDownloadId();
        if (item != null) {
            item.setStatus(FileDownloadStatus.completed);
            item.setProgress(100);
            item.setFinishedDownloadDate(new Date());
            // M62：long→int 截断防护（>2GB 时钳制到 Integer.MAX_VALUE，避免负数尺寸）
            long len = mp4File.length();
            int size = len > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) len;
            item.setSoFarBytes(size);
            item.setTotalFarBytes(size);
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
        // M62：已取消的任务不得复活为"下载错误"（worker 阻塞在 socket 读时用户取消，迟到回调到达此处）
        if (cancelledByUser) {
            AppLog.i("HlsDownload", "已取消，丢弃迟到错误回调 msg=" + message);
            deleteQuietly(new File(targetMp4Path));
            cleanupHlsTemp();
            stopForeground(true);
            releaseDownloader();
            stopSelf();
            return;
        }
        // M62：删除"remux 半成品提升为成功"的旧逻辑——remux 直接写最终路径，
        // 失败时残留的是无 moov 的不可播文件，判 >0 字节就标 completed 只会产出损坏"完成"记录。
        // 真失败：清掉半成品 + 记录置 error（可重试/删除）。
        deleteQuietly(new File(targetMp4Path));
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

    /** M62：静默删除单个文件（半成品 mp4 清理用） */
    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
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
