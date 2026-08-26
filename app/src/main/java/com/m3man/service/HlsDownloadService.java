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
    // M62：用户取消栅栏——worker 迟到回调（成功/失败）不得再写库或弹通知
    private volatile boolean cancelledByUser;
    private String notifyTitle = "";

    /**
     * M97：任务状态不可变快照——原 downloader/viewKey/savePath/targetMp4Path/
     * pseudoDownloadId/lastProgress 为多个独立共享字段，新任务逐个覆盖的瞬间，
     * 旧 worker 线程可能读到“半个新半个旧”的混合状态。收敛为单个 volatile 引用：
     * 读端取一次局部快照保证一致，写端构造新对象整体替换。cancelledByUser 保持独立。
     */
    private static final class TaskState {
        final HlsDownloader downloader;
        final String viewKey;
        final String savePath;
        final String targetMp4Path;
        final int pseudoDownloadId;
        final int lastProgress;

        TaskState(HlsDownloader downloader, String viewKey, String savePath,
                  String targetMp4Path, int pseudoDownloadId, int lastProgress) {
            this.downloader = downloader;
            this.viewKey = viewKey == null ? "" : viewKey;
            this.savePath = savePath == null ? "" : savePath;
            this.targetMp4Path = targetMp4Path == null ? "" : targetMp4Path;
            this.pseudoDownloadId = pseudoDownloadId;
            this.lastProgress = lastProgress;
        }
    }

    private volatile TaskState state = new TaskState(null, "", "", "", 0, -1);

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
            // M62/M97：先快照"被替换的旧任务"上下文——
            // 旧 worker 线程的迟到回调必须按它自己的归属落库，不得读写新任务的共享字段
            final TaskState previous = state;
            final HlsDownloader replacedDownloader = previous.downloader;
            final String replacedViewKey = previous.viewKey;
            final int replacedPseudoId = previous.pseudoDownloadId;
            // M97：新任务替换旧任务前，若旧记录仍挂在“正在下载”，先置 ERROR 并发一次刷新广播——
            // 旧 worker 即将被取消、永远等不到终态回调，不处理则旧行一直停留在下载中（幽灵行）
            if (replacedDownloader != null && replacedPseudoId > 0) {
                markReplacedRecordError(replacedViewKey, replacedPseudoId);
            }
            String newViewKey = intent.getStringExtra(EXTRA_VIEW_KEY);
            String newSavePath = intent.getStringExtra(EXTRA_SAVE_PATH);
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
            String newTargetMp4Path;
            if (TextUtils.isEmpty(newSavePath)) {
                newTargetMp4Path = SDCardUtils.DOWNLOAD_VIDEO_PATH + fileName + ".mp4";
            } else {
                newTargetMp4Path = newSavePath;
            }
            // M61：目录不可写时回退到应用专属目录，避免 Android 11+ 公共目录写入直接失败
            String ensured = SDCardUtils.ensureDownloadDir(newTargetMp4Path, this);
            if (!TextUtils.isEmpty(ensured)) {
                newTargetMp4Path = ensured;
                AppLog.i("HlsDownload", "下载目录 ensured=" + ensured);
            } else {
                AppLog.e("HlsDownload", "下载目录不可写且无 fallback path=" + newTargetMp4Path);
            }
            // 稳定的伪 downloadId，使「我的下载」查询（DownloadId!=0）能命中本记录。
            // M73：Math.abs(hashCode) 对 Integer.MIN_VALUE 会返回负数，且不同 URL 可能碰撞；
            // 改为拼接 viewKey 哈希的高低位构造正数，并兜底保证 >0。
            int newPseudoId = stablePositiveId(url);
            cancelledByUser = false;
            startForeground(NOTIFICATION_ID, buildProgressNotification("下载中 0%", 0, 1));
            // M97：以不可变快照一次性发布本任务上下文（downloader 暂沿用旧值，
            // 防止发布与 startDownload 替换之间 PAUSE/CANCEL 取不到旧下载器）
            state = new TaskState(replacedDownloader, newViewKey, newSavePath, newTargetMp4Path, newPseudoId, -1);
            // M62：统一在此落伪 downloadId + status=progress（修复 2.5）——
            // 各启动入口（推荐流/兜底/重新下载等）此前不写 downloadId，导致整个下载期间
            // 记录在「正在下载」「下载完成」两列表均不可见、进度广播空转。
            markRecordDownloading();
            startDownload(url, newTargetMp4Path, replacedDownloader, replacedViewKey, replacedPseudoId);
        } else if (ACTION_PAUSE.equals(action)) {
            // M97：读端取一次快照，PAUSE/CANCEL 全程使用同一份上下文
            TaskState s = state;
            if (s.downloader == null) {
                return START_NOT_STICKY;
            }
            String pauseViewKey = intent.getStringExtra(EXTRA_VIEW_KEY);
            if (!TextUtils.isEmpty(pauseViewKey) && !pauseViewKey.equals(s.viewKey)) {
                return START_NOT_STICKY;
            }
            cancelledByUser = true;
            // M102：暂停≠取消——保留已下分片，下次 ACTION_START 时同目录分片级续传
            s.downloader.stop(false);
            s.downloader.shutdown();
            state = new TaskState(null, s.viewKey, s.savePath, s.targetMp4Path, s.pseudoDownloadId, s.lastProgress);
            V9MmanItem item = findItem();
            if (item != null) {
                item.setStatus(FileDownloadStatus.paused);
                getDataManager().updateV9MmanItem(item);
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_HLS_PROGRESS)
                    .putExtra(EXTRA_VIEW_KEY, s.viewKey).putExtra(EXTRA_PROGRESS, s.lastProgress < 0 ? 0 : s.lastProgress));
            stopForeground(true);
            stopSelf();
        } else if (ACTION_CANCEL.equals(action)) {
            // M62：竞态防护——
            // ① 无活跃下载（downloader==null）时忽略取消，防止陈旧 viewKey 清错已完成记录；
            // ② 带 viewKey 的取消请求与本任务不匹配时同样忽略（防误杀无关记录/当前下载）。
            TaskState s = state;
            String cancelViewKey = intent.getStringExtra(EXTRA_VIEW_KEY);
            if (s.downloader == null) {
                return START_NOT_STICKY;
            }
            if (!TextUtils.isEmpty(cancelViewKey) && !cancelViewKey.equals(s.viewKey)) {
                return START_NOT_STICKY;
            }
            cancelledByUser = true;
            // M102：取消=彻底丢弃，已下分片一并删除
            s.downloader.stop(true);
            s.downloader.shutdown();
            state = new TaskState(null, s.viewKey, s.savePath, s.targetMp4Path, s.pseudoDownloadId, s.lastProgress);
            // 当前 stop(true) 只清理本任务临时目录，不再全局删除其他 HLS 任务。
            // 复位 DB 记录，使其从「正在下载」列表移除
            resetRecord();
            // M62：取消后清掉半成品 mp4，避免残留不可播文件
            deleteQuietly(new File(s.targetMp4Path));
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    /**
     * M97：被新任务顶替的旧任务，其 DB 记录若仍为“下载中”则置 ERROR 并广播刷新。
     * 沿用现有 DataManager 更新方法与 ACTION_HLS_DONE 刷新通道；
     * 仅在 status==progress 时才改写，不碰已完成/已暂停的记录。
     */
    private void markReplacedRecordError(String oldViewKey, int oldPseudoId) {
        try {
            V9MmanItem item = TextUtils.isEmpty(oldViewKey)
                    ? null : getDataManager().findV9MmanItemByViewKey(oldViewKey);
            if (item == null && oldPseudoId != 0) {
                item = getDataManager().findV9MmanItemByDownloadId(oldPseudoId);
            }
            if (item != null && item.getStatus() == FileDownloadStatus.progress) {
                item.setStatus(FileDownloadStatus.error);
                getDataManager().updateV9MmanItem(item);
                Intent i = new Intent(ACTION_HLS_DONE);
                i.putExtra(EXTRA_VIEW_KEY, oldViewKey);
                LocalBroadcastManager.getInstance(this).sendBroadcast(i);
                AppLog.i("HlsDownload", "被替换的旧任务记录已置 error viewKey=" + oldViewKey);
            }
        } catch (Exception e) {
            AppLog.e("HlsDownload", "markReplacedRecordError failed: " + e.getMessage());
        }
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
        // M102：被顶替任务保留已下分片——记录置 error 后用户点重试可分片级续传
        if (replacedDownloader != null) {
            replacedDownloader.stop(false);
            replacedDownloader.shutdown();
        }
        File target = new File(mp4Path);
        String saveDir = target.getParent();
        String fileName = target.getName();
        if (fileName != null && fileName.toLowerCase().endsWith(".mp4") && fileName.length() > 4) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }
        // M62/M97：per-task 归属校验——闭包持有"本任务专属的下载器实例"，
        // 回调到达时与当前状态快照中的 downloader 比对，非当前实例的一律按旧任务上下文处理或丢弃。
        // 修复：连发两个 HLS 下载时，被程序化 cancel 的旧 worker 迟到回调
        // 会把新任务记录写成 error 并删掉新任务目标 mp4（cancelledByUser 栅栏管不住这条路径）。
        final HlsDownloader activeDownloader = new HlsDownloader(this);
        // M97：把活跃下载器写入快照整体发布（其余字段沿用当前快照值）
        TaskState cur = state;
        state = new TaskState(activeDownloader, cur.viewKey, cur.savePath, cur.targetMp4Path, cur.pseudoDownloadId, -1);
        // M43：传入播放缓存目录，若该视频播放过（分片已缓存）则直接复用，避免重复下载
        activeDownloader.download(url, saveDir, fileName, AppCacheUtils.getVideoCacheDir(this), new HlsDownloader.HlsDownloadListener() {
            @Override
            public void onProgress(int done, int total) {
                if (activeDownloader != state.downloader) {
                    // 旧任务的迟到进度：不写库、不刷通知，直接丢弃
                    return;
                }
                handleProgress(done, total);
            }

            @Override
            public void onSuccess(File mp4File) {
                if (activeDownloader == state.downloader && !cancelledByUser) {
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
                if (activeDownloader != state.downloader) {
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
                // M99：字段已改 long，直接存真实字节数，去掉 int 钳制
                item.setStatus(FileDownloadStatus.completed);
                item.setProgress(100);
                item.setFinishedDownloadDate(new Date());
                // M99：实体字段保持 int（greenDAO 插件对 long 改造不友好，见评审遗留），>2GB 截断风险已知
                item.setSoFarBytes((int) len);
                item.setTotalFarBytes((int) len);
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
        // M97：从状态快照读 viewKey，避免读到被新任务覆盖一半的字段
        String vk = state.viewKey;
        if (TextUtils.isEmpty(vk)) {
            return null;
        }
        return getDataManager().findV9MmanItemByViewKey(vk);
    }

    /**
     * M41：优先按 viewKey 查找；查不到时按伪 downloadId 兜底
     * （部分场景 viewKey 与 DB 不一致会导致 handleSuccess 找不到记录、无法进“下载完成”）。
     */
    private V9MmanItem findItemOrByDownloadId() {
        V9MmanItem item = findItem();
        int pseudoId = state.pseudoDownloadId;
        if (item == null && pseudoId != 0) {
            item = getDataManager().findV9MmanItemByDownloadId(pseudoId);
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
                item.setDownloadId(state.pseudoDownloadId);
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
        i.putExtra(EXTRA_VIEW_KEY, state.viewKey);
        i.putExtra(EXTRA_PROGRESS, percent);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void handleSuccess(File mp4File) {
        // M97：读端取一次快照，成功处理全程使用同一份任务上下文
        TaskState s = state;
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
            // M99：字段已改 long，直接存真实字节数，去掉 int 钳制（原 >2GB 时钳到 Integer.MAX_VALUE）
            long len = mp4File.length();
            // M99：同上 int 截断说明
            item.setSoFarBytes((int) len);
            item.setTotalFarBytes((int) len);
            item.setDownloadId(s.pseudoDownloadId);
            getDataManager().updateV9MmanItem(item);
        }
        Intent i = new Intent(ACTION_HLS_DONE);
        i.putExtra(EXTRA_VIEW_KEY, s.viewKey);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
        showCompletedNotification(mp4File);
        stopForeground(true);
        releaseDownloader();
        stopSelf();
    }

    private void handleError(String message) {
        // M97：读端取一次快照，失败处理全程使用同一份任务上下文
        TaskState s = state;
        // M62：已取消的任务不得复活为"下载错误"（worker 阻塞在 socket 读时用户取消，迟到回调到达此处）
        if (cancelledByUser) {
            AppLog.i("HlsDownload", "已取消，丢弃迟到错误回调 msg=" + message);
            deleteQuietly(new File(s.targetMp4Path));
            cleanupHlsTemp();
            stopForeground(true);
            releaseDownloader();
            stopSelf();
            return;
        }
        // M62：删除"remux 半成品提升为成功"的旧逻辑——remux 直接写最终路径，
        // 失败时残留的是无 moov 的不可播文件，判 >0 字节就标 completed 只会产出损坏"完成"记录。
        // 真失败：清掉半成品 + 记录置 error（可重试/删除）。
        deleteQuietly(new File(s.targetMp4Path));
        // M41：失败时保留记录（status=error、downloadId 不变），
        // 使「正在下载」列表显示“下载错误”并可重试/删除；
        // 不再 resetRecord 把 downloadId 置 0 导致记录从两个列表都消失。
        V9MmanItem item = findItemOrByDownloadId();
        if (item != null) {
            item.setStatus(FileDownloadStatus.error);
            item.setProgress(100);
            item.setDownloadId(s.pseudoDownloadId);
            getDataManager().updateV9MmanItem(item);
        }
        // 清理临时分片，避免残留
        cleanupHlsTemp();
        showErrorNotification(message);
        stopForeground(true);
        releaseDownloader();
        stopSelf();
    }

    /** 临时目录由每个 HlsDownloader 自己按任务唯一目录管理，服务不再全局清理。 */
    private void cleanupHlsTemp() {
        // 保留方法兼容历史调用点；当前不扫描并删除其他任务目录。
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
        // M97：经快照整体替换置空，不再直接写共享字段
        TaskState s = state;
        if (s.downloader != null) {
            s.downloader.shutdown();
            state = new TaskState(null, s.viewKey, s.savePath, s.targetMp4Path, s.pseudoDownloadId, s.lastProgress);
        }
    }

    private void updateProgress(String text, int percent, int total) {
        // M97：lastProgress 收敛进快照，读-改-写以“复制+替换”完成
        TaskState s = state;
        if (percent == s.lastProgress) {
            return;
        }
        state = new TaskState(s.downloader, s.viewKey, s.savePath, s.targetMp4Path, s.pseudoDownloadId, percent);
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
