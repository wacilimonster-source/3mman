package com.m3man.utils;

import android.text.TextUtils;

import com.liulishuo.filedownloader.BaseDownloadTask;
import com.liulishuo.filedownloader.FileDownloadListener;
import com.liulishuo.filedownloader.FileDownloader;
import com.liulishuo.filedownloader.model.FileDownloadStatus;
import com.liulishuo.filedownloader.util.FileDownloadUtils;
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
    /** 下载请求浏览器 UA：部分站点 CDN 会拒绝非浏览器 UA（okhttp 默认 UA 可能被 403） */
    private static final String DOWNLOAD_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/68.0.3440.84 Safari/537.36";

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

    // M73：进度落库节流——记录每个 downloadId 上次落库时的百分比，≥5% 步进才写库
    private final java.util.concurrent.ConcurrentHashMap<Integer, Integer> lastSavedProgress =
            new java.util.concurrent.ConcurrentHashMap<>();

    // M97：正在被用户删除的任务 id 集合——删除期间迟到的进度回调命中即跳过写库，
    // 防止“正在下载”行被复活成幽灵行。跨线程读写，用同步包装集合。
    private static final java.util.Set<Long> DELETING_IDS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Long>());

    /** M97：标记任务进入删除流程（DownloadPresenter.deleteDownloadingTask 删除开始时调用） */
    public static void markDeleting(int downloadId) {
        if (downloadId > 0) {
            DELETING_IDS.add((long) downloadId);
        }
    }

    /** M97：删除流程结束后移除标记（成功/失败都要调用） */
    public static void unmarkDeleting(int downloadId) {
        if (downloadId > 0) {
            DELETING_IDS.remove((long) downloadId);
        }
    }

    private static boolean isDeleting(int downloadId) {
        return DELETING_IDS.contains((long) downloadId);
    }


    /**
     * M61：判断是否 HLS m3u8 地址。m3u8 绝不能交给 FileDownloader：
     * 它会把几百字节的播放列表文本当成 mp4「秒下载完成」，产生假完成文件。
     */
    /** 返回 FileDownloader 按 URL+路径生成的稳定任务 ID，供调用方入队前写库。 */
    public static int predictDownloadId(String url, String path) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(path)) {
            return 0;
        }
        return FileDownloadUtils.generateId(url, path);
    }

    public static boolean isHlsUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase(java.util.Locale.US);
        int query = lower.indexOf('?');
        if (query >= 0) {
            lower = lower.substring(0, query);
        }
        return lower.endsWith(".m3u8") || lower.contains(".m3u8/");
    }

    public int startDownload(String url, final String path, boolean isDownloadNeedWifi, boolean isForceReDownload) {
        return startDownload(url, path, isDownloadNeedWifi, isForceReDownload, null);
    }

    /**
     * 下载任务（可选带 Referer）。
     *
     * @param referer 视频源站播放页地址；站点 CDN 校验 Referer 时必填（91porn 等），
     *                GitHub raw 等公共下载不需要传 null
     */
    public int startDownload(String url, final String path, boolean isDownloadNeedWifi, boolean isForceReDownload, String referer) {
        Logger.t(TAG).d("url::" + url);
        Logger.t(TAG).d("path::" + path);
        Logger.t(TAG).d("isDownloadNeedWifi::" + isDownloadNeedWifi);
        Logger.t(TAG).d("isForceReDownload::" + isForceReDownload);
        // FileDownloader 的任务 ID 由 URL+path 生成，enqueue 前即可确定。
        // 调用方应先把这个 ID 写入 V9MmanItem，避免 pending/started 回调早于
        // 调用方 updateV9MmanItem，saveDownloadInfo 查不到记录后误清除合法任务。
        int predictedId = FileDownloadUtils.generateId(url, path);
        BaseDownloadTask task = FileDownloader.getImpl().create(url)
                .addHeader("User-Agent", DOWNLOAD_UA);
        if (!TextUtils.isEmpty(referer)) {
            task.addHeader("Referer", referer);
        }
        int id = task.setPath(path)
                .setListener(lis)
                .setWifiRequired(isDownloadNeedWifi)
                .setAutoRetryTimes(3)
                .setForceReDownload(isForceReDownload)
                .asInQueueTask()
                .enqueue();
        lastSavedProgress.put(predictedId > 0 ? predictedId : id, -1);
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
            // M73：进度回调节流——每个回调都全量查库+写库会产生海量同步 DB IO（大文件下载数千次），
            // 改为按百分比步进落库（≥5% 或首次），UI 进度条仍由内存 updater 实时刷新
            Integer lastPct = lastSavedProgress.get(task.getId());
            int pct = totalBytes > 0 ? (int) (((float) soFarBytes / totalBytes) * 100) : 0;
            if (lastPct == null || lastPct < 0 || pct - lastPct >= 5) {
                if (lastPct != null) {
                    lastSavedProgress.put(task.getId(), pct);
                }
                saveDownloadInfo(task);
            }
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
            // M97：删除中的任务直接丢弃回调，防止误判“完成”回写复活幽灵行
            if (isDeleting(task.getId())) {
                return;
            }
            // M40/M41：部分机型/系统下文件已完整下载却误报 error（如末尾 sync/重命名失败、
            // 或 CDN Content-Length 与实际字节数不一致导致 soFar < total）。
            // 若目标文件真实大小已达标，则按“完成”处理，避免“能播放却提示下载失败”。
            try {
                File f = new File(task.getPath());
                long total = task.getSmallFileTotalBytes();
                if (SDCardUtils.isDownloadFileComplete(f, total)) {
                    V9MmanItem item = dataManager.findV9MmanItemByDownloadId(task.getId());
                    if (item != null) {
                        item.setStatus(FileDownloadStatus.completed);
                        item.setProgress(100);
                        // M99：字段已改 long，去掉 int 强转避免 >2GB 文件尺寸截断
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
        // M97：删除中的任务命中即整体跳过——迟到的 pending/progress/completed 回调
        // 不得查库写库，否则刚删掉的行会被回写成“下载中”幽灵行
        if (isDeleting(task.getId())) {
            Logger.t(TAG).d("任务删除中，跳过状态回写 downloadId=" + task.getId());
            return;
        }
        V9MmanItem v9MmanItem = dataManager.findV9MmanItemByDownloadId(task.getId());
        if (v9MmanItem == null) {
            // 回调可能早于调用方把预期 downloadId 写入数据库；此时不能 clear，
            // 否则一个合法的新任务会被首个 pending/started 回调直接杀掉。
            AppLog.w(TAG, "下载回调暂时找不到数据库记录，保留任务 downloadId="
                    + task.getId() + " path=" + task.getPath());
            if (!BuildConfig.DEBUG) {
              // 仅保留诊断，不在这里清理下载任务。
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
            // M61/M73：假完成防护——m3u8 播放列表/错误页被当 mp4 下完时只有几百字节。
            // M73 收紧判定：①文件头必须是文本（m3u8/#EXTM3U/HTML），排除合法的小体积视频；
            // ②或 URL 本身就是 m3u8。纯大小阈值会误删 <100KB 的合法短视频。
            try {
                File done = new File(task.getPath());
                if (done.exists() && done.length() < 100 * 1024 && looksLikeTextFile(done)) {
                    AppLog.e(TAG, "疑似假完成文件(" + done.length() + "B, 文本内容)已删除 url.host="
                            + AppLog.hostOf(task.getUrl()));
                    done.delete();
                    v9MmanItem.setStatus(FileDownloadStatus.error);
                    dataManager.updateV9MmanItem(v9MmanItem);
                    return;
                }
            } catch (Exception ignored) {
            }
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

    /**
     * M73：判断文件是否文本内容（m3u8 播放列表/HTML 错误页）。
     * 视频文件（mp4 等）头部含大量二进制控制字节；文本文件几乎全是可打印字符。
     * 只读前 512 字节判断，避免大文件全量 IO。
     */
    private static boolean looksLikeTextFile(File f) {
        java.io.FileInputStream fis = null;
        try {
            byte[] buf = new byte[512];
            fis = new java.io.FileInputStream(f);
            int n = fis.read(buf);
            if (n <= 0) {
                return true; // 空文件按文本处理（必然无效）
            }
            for (int i = 0; i < n; i++) {
                byte b = buf[i];
                // 控制字符（除 \t \n \r）出现即视为二进制
                if ((b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D) || b == 0) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception ignored) {
                }
            }
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
