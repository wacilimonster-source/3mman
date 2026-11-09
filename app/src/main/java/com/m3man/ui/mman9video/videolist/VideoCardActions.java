package com.m3man.ui.mman9video.videolist;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.liulishuo.filedownloader.model.FileDownloadStatus;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.VideoResult;
import com.m3man.data.network.Source441447Client;
import com.m3man.parser.Parse91PornyVideo;
import com.m3man.service.HlsDownloadService;
import com.m3man.ui.mman9video.play.PlayVideoPresenter;
import com.m3man.utils.DownloadManager;
import com.m3man.utils.SDCardUtils;

import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

/**
 * 列表卡片「更多」菜单的真实动作（spec EX-02 / EX-05）。
 * <p>
 * 只做两件事：切换本地收藏、把当前 viewKey 加入现有下载通道。
 * 所有网络解析、HLS 下载、FileDownloader 入队都复用项目已有实现，
 * 不新建服务、不写假数据；同一 viewKey 在途时拒绝重复提交。
 */
public final class VideoCardActions {

    public interface Callback {
        /**
         * @param message       展示给用户的真实结果
         * @param success       是否成功
         * @param openDownloads 是否建议提供“查看下载”入口
         */
        void onResult(String message, boolean success, boolean openDownloads);
    }

    private static final Set<String> FAVORITE_IN_FLIGHT =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> DOWNLOAD_IN_FLIGHT =
            Collections.synchronizedSet(new HashSet<String>());

    private VideoCardActions() {
    }

    /** 切换本地收藏（写入成功后才回调成功）。 */
    public static void toggleFavorite(final DataManager dataManager, final V9MmanItem item,
                                      final Callback callback) {
        if (dataManager == null || item == null || TextUtils.isEmpty(item.getViewKey())) {
            return;
        }
        final String key = item.getViewKey();
        if (!FAVORITE_IN_FLIGHT.add(key)) {
            return;
        }
        Observable.fromCallable(() -> {
            V9MmanItem target = dataManager.findV9MmanItemByViewKey(key);
            if (target == null) {
                target = item;
            }
            boolean nowFavorite = !Boolean.TRUE.equals(target.getIsLocalFavorite());
            target.setIsLocalFavorite(nowFavorite);
            if (TextUtils.isEmpty(target.getSourceName())) {
                target.setSourceName(PlayVideoPresenter.isPornySource(target)
                        ? Parse91PornyVideo.SOURCE : PlayVideoPresenter.SOURCE_MMAN9_PERSIST);
            }
            try {
                VideoResult result = target.getVideoResult();
                if (result != null) {
                    dataManager.saveVideoResult(result);
                }
            } catch (Exception ignored) {
                // 实体脱离 DaoSession 时 getVideoResult 会抛 DaoException
            }
            dataManager.saveV9MmanItem(target);
            return nowFavorite;
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(nowFavorite -> {
                    FAVORITE_IN_FLIGHT.remove(key);
                    // 同步列表内存对象的选中态，供再次打开菜单时显示正确动作。
                    item.setIsLocalFavorite(nowFavorite);
                    if (callback != null) {
                        callback.onResult(nowFavorite ? "已加入收藏" : "已取消收藏", true, false);
                    }
                }, throwable -> {
                    FAVORITE_IN_FLIGHT.remove(key);
                    if (callback != null) {
                        callback.onResult("收藏失败，请重试", false, false);
                    }
                });
    }

    /**
     * 加入下载：已有解析结果直接入队；没有则先用现有解析接口取地址。
     * HLS（分分钟）走 HlsDownloadService，直链走 FileDownloader。
     */
    public static void download(final Context context, final DataManager dataManager,
                                final V9MmanItem item, final Callback callback) {
        if (Source441447Client.isSource(item)) {
            if (callback != null) callback.onResult("该来源暂不支持下载", false, false);
            return;
        }
        if (context == null || dataManager == null || item == null
                || TextUtils.isEmpty(item.getViewKey())) {
            return;
        }
        final String key = item.getViewKey();
        if (!DOWNLOAD_IN_FLIGHT.add(key)) {
            if (callback != null) {
                callback.onResult("下载任务正在启动，请勿重复点击", false, false);
            }
            return;
        }
        final Context appContext = context.getApplicationContext();
        Observable.fromCallable(() -> doEnqueue(appContext, dataManager, item))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    DOWNLOAD_IN_FLIGHT.remove(key);
                    if (callback != null) {
                        callback.onResult(result.message, result.ok, result.ok);
                    }
                }, throwable -> {
                    DOWNLOAD_IN_FLIGHT.remove(key);
                    if (callback != null) {
                        callback.onResult(TextUtils.isEmpty(throwable.getMessage())
                                ? "下载失败，请稍后重试" : throwable.getMessage(), false, false);
                    }
                });
    }

    private static final class Result {
        final boolean ok;
        final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private static Result doEnqueue(Context context, DataManager dataManager, V9MmanItem item)
            throws Exception {
        final String key = item.getViewKey();
        V9MmanItem target = dataManager.findV9MmanItemByViewKey(key);
        if (target == null) {
            target = item;
        }
        if (target.getDownloadId() != 0
                && (target.getStatus() == FileDownloadStatus.pending
                || target.getStatus() == FileDownloadStatus.started
                || target.getStatus() == FileDownloadStatus.connected
                || target.getStatus() == FileDownloadStatus.progress)) {
            return new Result(false, "已在下载中 · 查看");
        }
        if (target.getDownloadId() != 0
                && target.getStatus() == FileDownloadStatus.completed) {
            return new Result(false, "已经下载过了，请查看下载目录");
        }

        VideoResult videoResult = null;
        try {
            videoResult = target.getVideoResult();
        } catch (Exception ignored) {
        }
        String url = videoResult == null ? null : videoResult.getVideoUrl();
        if (TextUtils.isEmpty(url)) {
            boolean porny = PlayVideoPresenter.isPornySource(target);
            videoResult = (porny
                    ? dataManager.loadPornyVideoUrl(key)
                    : dataManager.loadMman9VideoUrl(key))
                    .firstOrError()
                    .blockingGet();
            if (videoResult == null || TextUtils.isEmpty(videoResult.getVideoUrl())) {
                throw new IllegalStateException("还未解析成功视频地址");
            }
            dataManager.saveVideoResult(videoResult);
            target.setVideoResult(videoResult);
            target.setSourceName(porny
                    ? Parse91PornyVideo.SOURCE : PlayVideoPresenter.SOURCE_MMAN9_PERSIST);
            target.setSource(target.getSourceName());
            dataManager.saveV9MmanItem(target);
            url = videoResult.getVideoUrl();
        }

        String requestedPath = target.getDownLoadPath(dataManager.getCustomDownloadVideoDirPath());
        String path = SDCardUtils.ensureDownloadDir(requestedPath, context);
        if (path == null) {
            throw new IllegalStateException("下载目录不可写，请检查存储权限或更换下载目录");
        }

        if (DownloadManager.isHlsUrl(url)) {
            return enqueueHls(context, dataManager, target, url, path);
        }
        return enqueueDirect(context, dataManager, target, url, path);
    }

    private static Result enqueueHls(Context context, DataManager dataManager,
                                     V9MmanItem target, String url, String path) {
        // 与播放页一致：先落「下载中」记录，使任务出现在「我的下载」，伪 id 避开 FileDownloader。
        int pseudoId = url.hashCode() & 0x7FFFFFFF;
        target.setDownloadId(pseudoId);
        target.setStatus(FileDownloadStatus.progress);
        if (target.getAddDownloadDate() == null) {
            target.setAddDownloadDate(new Date());
        }
        dataManager.saveV9MmanItem(target);

        Intent progressIntent = new Intent(HlsDownloadService.ACTION_HLS_PROGRESS);
        progressIntent.putExtra(HlsDownloadService.EXTRA_VIEW_KEY, target.getViewKey());
        progressIntent.putExtra(HlsDownloadService.EXTRA_RUNNING, true);
        LocalBroadcastManager.getInstance(context).sendBroadcast(progressIntent);

        Intent serviceIntent = new Intent(context, HlsDownloadService.class);
        serviceIntent.setAction(HlsDownloadService.ACTION_START);
        serviceIntent.putExtra(HlsDownloadService.EXTRA_VIDEO_URL, url);
        serviceIntent.putExtra(HlsDownloadService.EXTRA_TITLE, target.getTitle());
        serviceIntent.putExtra(HlsDownloadService.EXTRA_FILE_NAME,
                SDCardUtils.sanitizeFileName(target.getTitle()));
        serviceIntent.putExtra(HlsDownloadService.EXTRA_VIEW_KEY, target.getViewKey());
        serviceIntent.putExtra(HlsDownloadService.EXTRA_SAVE_PATH, path);
        try {
            context.startForegroundService(serviceIntent);
        } catch (Exception e) {
            // M165：服务起不来时回滚“下载中”记录，否则我的下载会残留 0% 幽灵任务。
            target.setDownloadId(0);
            target.setStatus(FileDownloadStatus.INVALID_STATUS);
            target.setProgress(0);
            dataManager.saveV9MmanItem(target);
            progressIntent.putExtra(HlsDownloadService.EXTRA_RUNNING, false);
            LocalBroadcastManager.getInstance(context).sendBroadcast(progressIntent);
            throw new IllegalStateException("无法启动下载服务，请稍后重试");
        }
        return new Result(true, "已加入下载队列 · 查看");
    }

    private static Result enqueueDirect(Context context, DataManager dataManager,
                                        V9MmanItem target, String url, String path) {
        String referer = buildReferer(dataManager, target.getViewKey());
        int predictedId = DownloadManager.predictDownloadId(url, path);
        if (predictedId > 0) {
            target.setDownloadId(predictedId);
            target.setStatus(FileDownloadStatus.pending);
            target.setProgress(0);
            if (target.getAddDownloadDate() == null) {
                target.setAddDownloadDate(new Date());
            }
            dataManager.saveV9MmanItem(target);
        }
        int id = DownloadManager.getImpl().startDownload(url, path,
                dataManager.isDownloadVideoNeedWifi(), false, referer);
        target.setDownloadId(id);
        if (target.getStatus() == FileDownloadStatus.error
                || target.getStatus() == FileDownloadStatus.completed) {
            target.setStatus(FileDownloadStatus.pending);
        }
        if (target.getAddDownloadDate() == null) {
            target.setAddDownloadDate(new Date());
        }
        dataManager.saveV9MmanItem(target);
        return new Result(true, "已加入下载队列 · 查看");
    }

    private static String buildReferer(DataManager dataManager, String viewKey) {
        try {
            String addr = dataManager.getMman9VideoAddress();
            if (!TextUtils.isEmpty(addr)) {
                String bareKey = viewKey != null && viewKey.startsWith("viewkey=")
                        ? viewKey.substring(8) : viewKey;
                return addr + "view_video.php?viewkey=" + bareKey;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
