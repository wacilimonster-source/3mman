package com.m3man.ui.recommend;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.liulishuo.filedownloader.model.FileDownloadStatus;
import com.m3man.R;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.VideoResult;
import com.m3man.service.DownloadVideoService;
import com.m3man.utils.AppLog;
import com.m3man.utils.SDCardUtils;
import com.m3man.utils.DownloadDiag;
// 复查修正：DownloadManager/PornyFallbackResolver 实际都在 utils 包，旧 service 路径不存在
import com.m3man.utils.DownloadManager;
import com.m3man.utils.PornyFallbackResolver;
import com.orhanobut.logger.Logger;
import com.sdsmdg.tastytoast.TastyToast;

import java.io.File;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import okhttp3.OkHttpClient;

/**
 * 推荐页下载入队逻辑，从 RecommendFeedFragment 提取。
 * <p>
 * 职责：查重 → 检查文件/下载状态 → 解析/探活 → 入队 FileDownloader/HLS。
 */
class DownloadEnqueuer {

    private final DataManager dataManager;
    private final OkHttpClient okHttpClient;
    private final Context context;
    private final CompositeDisposable disposables;
    private final Set<String> downloadInFlight = new HashSet<>();

    /** 下载入队结果（成功/失败 + 提示消息） */
    static final class DownloadResult {
        final boolean ok;
        final String message;

        DownloadResult(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    /** 回调：通知 UI 显示消息和启动服务 */
    interface Callback {
        boolean isUsable();
        void showMessage(String msg, int type);
        void startDownloadService();
    }

    DownloadEnqueuer(DataManager dataManager, OkHttpClient okHttpClient,
                     Context context, CompositeDisposable disposables) {
        this.dataManager = dataManager;
        this.okHttpClient = okHttpClient;
        this.context = context;
        this.disposables = disposables;
    }

    /**
     * 入队下载（IO 线程执行，响应式链式调用，无阻塞）。
     * 由 {@link #enqueueDownload} 调用。
     */
    Single<DownloadResult> doEnqueueDownload(String viewKey, V9MmanItem fallback) {
        return Single.fromCallable(() -> {
            DownloadDiag.reset(viewKey);
            V9MmanItem target = null;
            try {
                target = dataManager.findV9MmanItemByViewKey(viewKey);
            } catch (Exception ignored) {
            }
            if (target == null) {
                target = fallback;
            }
            if (target == null || target.getVideoResultId() == 0) {
                AppLog.e("DownloadEnqueuer", "推荐下载缺少VideoResult viewKey=" + viewKey);
                DownloadDiag.append(viewKey, "enqueue=无已解析的 VideoResult → 失败");
                throw new IllegalStateException("还未解析成功视频地址");
            }
            VideoResult videoResult;
            try {
                videoResult = target.getVideoResult();
            } catch (Exception e) {
                videoResult = null;
            }
            if (videoResult == null || TextUtils.isEmpty(videoResult.getVideoUrl())) {
                DownloadDiag.append(viewKey, "enqueue=videoResult.videoUrl 为空 → 失败");
                throw new IllegalStateException("还未解析成功视频地址");
            }
            String path = target.getDownLoadPath(dataManager.getCustomDownloadVideoDirPath());
            File file = SDCardUtils.resolveExistingDownloadFile(context, path);
            if (file != null && file.exists() && file.length() > 0) {
                DownloadDiag.append(viewKey, "enqueue=目标文件已存在(" + file.length() + "B) → 跳过");
                throw new IllegalStateException("已经下载过了，请查看下载目录");
            }
            if ((target.getStatus() == FileDownloadStatus.pending
                    || target.getStatus() == FileDownloadStatus.started
                    || target.getStatus() == FileDownloadStatus.connected
                    || target.getStatus() == FileDownloadStatus.progress)
                    && target.getDownloadId() != 0) {
                DownloadDiag.append(viewKey, "enqueue=已在下载(status=" + target.getStatus()
                        + ", downloadId=" + target.getDownloadId() + ") → 跳过");
                throw new IllegalStateException("已经在下载了");
            }
            return target;
        }).flatMap(target -> {
            String url = target.getVideoResult().getVideoUrl();
            // 复查修正：path 原在 fromCallable 局部作用域内，本 lambda 内不可见；按同一规则重算
            final String path = target.getDownLoadPath(dataManager.getCustomDownloadVideoDirPath());
            DownloadDiag.append(viewKey, "enqueue=旧URL host=" + DownloadDiag.hostOf(url));

            // 91porny hex viewKey → HLS 下载
            boolean isPornyHex = viewKey != null && viewKey.matches("[0-9a-fA-F]{16,32}");
            if (isPornyHex) {
                return dataManager.loadPornyVideoUrl(viewKey)
                        .firstOrError()
                        .onErrorResumeNext(throwable -> {
                            DownloadDiag.append(viewKey, "91porny=直链异常(" + throwable.getMessage() + ") → 退回原直链");
                            return Single.error(throwable);
                        })
                        .flatMap(porny -> {
                            if (porny != null && !TextUtils.isEmpty(porny.getVideoUrl())) {
                                DownloadDiag.append(viewKey, "91porny=直链host=" + DownloadDiag.hostOf(url)
                                        + " → 命中m3u8=" + DownloadDiag.hostOf(porny.getVideoUrl()) + " → HLS下载");
                                PornyFallbackResolver.applyPornyResult(dataManager, target, porny);
                                String hlsPath = path;
                                try {
                                    String ensured = SDCardUtils.ensureDownloadDir(path, context);
                                    if (ensured != null) hlsPath = ensured;
                                } catch (Exception ignored) {}
                                PornyFallbackResolver.enqueueHlsDownload(context, target, porny.getVideoUrl(), hlsPath);
                                return Single.just(new DownloadResult(true, "已改用 91porny 源下载"));
                            } else {
                                DownloadDiag.append(viewKey, "91porny=直链m3u8为空 → 退回原直链");
                                return Single.error(new IllegalStateException("porny empty"));
                            }
                        })
                        .onErrorResumeNext(throwable -> continueWithOriginalUrl(target, url, path, viewKey, fallback));
            }

            // 非 porny 源 → 重新解析 URL → 探活 → fallback 91porny
            if (!com.m3man.ui.mman9video.play.PlayVideoPresenter.isPornySource(target)) {
                return dataManager.loadMman9VideoUrl(viewKey)
                        .firstOrError()
                        .flatMap(fresh -> {
                            if (fresh != null && !TextUtils.isEmpty(fresh.getVideoUrl())) {
                                dataManager.saveVideoResult(fresh);
                                target.setVideoResult(fresh);
                                dataManager.updateV9MmanItem(target);
                                DownloadDiag.append(viewKey, "reparse=成功 host=" + DownloadDiag.hostOf(fresh.getVideoUrl()));
                                return Single.just(fresh.getVideoUrl());
                            } else {
                                DownloadDiag.append(viewKey, "reparse=空URL(观看超限/解析失败) → 尝试91porny备用源");
                                return Single.error(new IllegalStateException("reparse empty"));
                            }
                        })
                        .onErrorResumeNext(throwable -> {
                            Logger.t("DownloadEnqueuer").d("recommend re-parse failed, fallback old url: " + throwable.getMessage());
                            DownloadDiag.append(viewKey, "reparse=异常(" + throwable.getMessage() + ") → 退回旧URL");
                            return Single.just(url);
                        })
                        .flatMap(resolvedUrl -> {
                            boolean alive = PornyFallbackResolver.isAlive(okHttpClient, resolvedUrl);
                            DownloadDiag.append(viewKey, "isAlive=" + alive + " host=" + DownloadDiag.hostOf(resolvedUrl));
                            if (!alive) {
                                return tryPornyFallback(target, path, viewKey)
                                        .onErrorResumeNext(e -> continueWithOriginalUrl(target, resolvedUrl, path, viewKey, fallback));
                            }
                            return continueWithOriginalUrl(target, resolvedUrl, path, viewKey, fallback);
                        });
            }

            return continueWithOriginalUrl(target, url, path, viewKey, fallback);
        }).onErrorReturn(throwable -> new DownloadResult(false, throwable.getMessage()));
    }

    /**
     * 继续使用原直链下载
     */
    private Single<DownloadResult> continueWithOriginalUrl(V9MmanItem target, String url, String path, String viewKey, V9MmanItem fallback) {
        String referer = buildReferer(viewKey);
        String requestedPath = path;
        path = SDCardUtils.ensureDownloadDir(path, context);
        AppLog.i("DownloadEnqueuer", "推荐下载目录 requested=" + requestedPath + " actual=" + path);
        if (path == null) {
            DownloadDiag.append(viewKey, "download=目录不可写且无 fallback（requested=" + requestedPath + "）");
            return Single.just(new DownloadResult(false, "下载目录不可写，请检查存储权限或更换下载目录"));
        }
        DownloadDiag.append(viewKey, "startDownload url.host=" + DownloadDiag.hostOf(url) + " referer=" + DownloadDiag.hostOf(referer) + " path.dir=" + path);

        if (DownloadManager.isHlsUrl(url)) {
            DownloadDiag.append(viewKey, "HLS=改走HlsDownloadService");
            PornyFallbackResolver.enqueueHlsDownload(context, target, url, path);
            return Single.just(new DownloadResult(true, "已加入后台下载"));
        }

        int predictedId = DownloadManager.predictDownloadId(url, path);
        if (predictedId > 0) {
            if (target.getAddDownloadDate() == null) {
                target.setAddDownloadDate(new Date());
            }
            target.setDownloadId(predictedId);
            target.setStatus(FileDownloadStatus.pending);
            target.setProgress(0);
            dataManager.updateV9MmanItem(target);
        }
        int id = DownloadManager.getImpl().startDownload(url, path,
                dataManager.isDownloadVideoNeedWifi(), false, referer);
        if (target.getAddDownloadDate() == null) {
            target.setAddDownloadDate(new Date());
        }
        target.setDownloadId(id);
        if (target.getStatus() == FileDownloadStatus.error
                || target.getStatus() == FileDownloadStatus.completed) {
            target.setStatus(FileDownloadStatus.pending);
        }
        dataManager.updateV9MmanItem(target);
        // 复查修正：方法签名返回 Single<DownloadResult>，原语句直接 return 实体导致类型不匹配
        return Single.just(new DownloadResult(true, "已加入后台下载"));
    }

    /**
     * 尝试 91porny 备用源
     */
    private Single<DownloadResult> tryPornyFallback(V9MmanItem target, String path, String viewKey) {
        return Single.fromCallable(() -> PornyFallbackResolver.resolve(dataManager, target.getTitle()))
                .flatMap(porny -> {
                    if (porny != null && !TextUtils.isEmpty(porny.getVideoUrl())) {
                        DownloadDiag.append(viewKey, "91porny=命中 host=" + DownloadDiag.hostOf(porny.getVideoUrl()) + " → HLS下载");
                        PornyFallbackResolver.applyPornyResult(dataManager, target, porny);
                        String hlsPath2 = path;
                        try {
                            String ensured = SDCardUtils.ensureDownloadDir(path, context);
                            if (ensured != null) hlsPath2 = ensured;
                        } catch (Exception ignored) {}
                        PornyFallbackResolver.enqueueHlsDownload(context, target, porny.getVideoUrl(), hlsPath2);
                        return Single.just(new DownloadResult(true, "源站受限，已改用 91porny 源下载"));
                    } else {
                        DownloadDiag.append(viewKey, "91porny=未命中 → 退回原直链");
                        return Single.error(new IllegalStateException("porny not found"));
                    }
                });
    }

    /**
     * 构建 Referer
     */
    private String buildReferer(String viewKey) {
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

    /**
     * 提交下载任务（IO → 主线程），完全响应式无阻塞。
     */
    void enqueueDownload(String viewKey, V9MmanItem fallback, Callback callback) {
        synchronized (downloadInFlight) {
            if (!downloadInFlight.add(viewKey)) {
                callback.showMessage("下载任务正在启动，请勿重复点击", TastyToast.INFO);
                return;
            }
        }
        disposables.add(doEnqueueDownload(viewKey, fallback)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    downloadInFlight.remove(viewKey);
                    callback.showMessage(result.message, result.ok ? TastyToast.SUCCESS : TastyToast.INFO);
                    if (result.ok) {
                        callback.startDownloadService();
                    }
                }, throwable -> {
                    downloadInFlight.remove(viewKey);
                    callback.showMessage("下载失败: " + throwable.getMessage(), TastyToast.ERROR);
                }));
    }
}
