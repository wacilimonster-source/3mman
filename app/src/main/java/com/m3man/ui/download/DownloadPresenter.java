package com.m3man.ui.download;

import androidx.lifecycle.Lifecycle;
import android.content.Context;
import androidx.annotation.NonNull;
import android.text.TextUtils;

import com.hannesdorfmann.mosby3.mvp.MvpBasePresenter;
import com.liulishuo.filedownloader.FileDownloader;
import com.liulishuo.filedownloader.model.FileDownloadStatus;
import com.liulishuo.filedownloader.util.FileDownloadUtils;
import com.orhanobut.logger.Logger;
import com.sdsmdg.tastytoast.TastyToast;
import com.trello.rxlifecycle2.LifecycleProvider;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.VideoResult;
import com.m3man.di.ApplicationContext;
import com.m3man.parser.Parse91PornyVideo;
import com.m3man.ui.mman9video.play.PlayVideoPresenter;
import com.m3man.rxjava.CallBackWrapper;
import com.m3man.rxjava.RxSchedulersHelper;
import com.m3man.utils.AppCacheUtils;
import com.m3man.utils.AppLog;
import com.m3man.utils.DownloadManager;
import com.m3man.utils.PornyFallbackResolver;
import com.m3man.utils.SDCardUtils;
import com.m3man.utils.MediaStoreArchiver;
import com.m3man.utils.VideoCacheFileNameGenerator;

import java.io.File;

import java.util.Date;
import java.util.Iterator;
import java.util.List;

import javax.inject.Inject;

import de.greenrobot.common.io.FileUtils;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import okhttp3.OkHttpClient;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

/**
 * @author flymegoc
 * @date 2017/11/27
 * @describe 下载管理 Presenter，负责视频下载任务的创建、监控、暂停/恢复、断点续传、缓存拷贝及重试降级逻辑
 */

public class DownloadPresenter extends MvpBasePresenter<DownloadView> implements IDownload {

    private DataManager dataManager;
    private LifecycleProvider<Lifecycle.Event> provider;
    private Context context;
    private OkHttpClient okHttpClient;

    @Inject
    public DownloadPresenter(DataManager dataManager, LifecycleProvider<Lifecycle.Event> provider,
                             @ApplicationContext Context context, OkHttpClient okHttpClient) {
        this.dataManager = dataManager;
        this.provider = provider;
        this.context = context;
        this.okHttpClient = okHttpClient;
    }

    @Override
    public void favorite(String uId, String videoId, String ownnerId) {
        // L-17：IBaseFavorite 经 IDownload → IBaseDownload 继承链要求实现本方法，
        // 但下载模块不承担收藏职责（收藏统一由 FavoritePresenter 处理），刻意留空。
    }

    /**
     * 校验参数、从 DB 查找并用内存对象兜底回写。
     * @return 校验通过的 DB 对象；null 表示应中止下载（已通知 listener）
     */
    private V9MmanItem validateAndResolveItem(V9MmanItem item, DownloadListener listener) {
        if (item == null) {
            if (listener != null) {
                listener.onError("视频信息为空");
            }
            return null;
        }
        V9MmanItem resolvedItem = dataManager.findV9MmanItemByViewKey(item.getViewKey());
        if (resolvedItem == null) {
            AppLog.e("Download", "数据库找不到视频 viewKey=" + item.getViewKey());
        } else if (resolvedItem.getVideoResultId() == 0) {
            AppLog.w("Download", "DB行无解析结果 videoResultId=0 viewKey=" + item.getViewKey());
        }
        // M68：DB 行缺失或无解析结果时，用刚解析成功的内存对象兜底并回写，
        // 避免「明明刚解析成功却提示还未解析成功视频地址」的死路。
        if ((resolvedItem == null || resolvedItem.getVideoResultId() == 0)
                && item.getVideoResult() != null
                && !TextUtils.isEmpty(item.getVideoResult().getVideoUrl())) {
            AppLog.w("Download", "用内存解析结果兜底并回写DB viewKey=" + item.getViewKey());
            try {
                if (item.getSourceName() == null) {
                    item.setSourceName(PlayVideoPresenter.SOURCE_MMAN9_PERSIST);
                }
                dataManager.saveV9MmanItem(item);
                resolvedItem = dataManager.findV9MmanItemByViewKey(item.getViewKey());
                AppLog.i("Download", "兜底回写完成 videoResultId=" + (resolvedItem == null ? -1 : resolvedItem.getVideoResultId()));
            } catch (Exception e) {
                AppLog.e("Download", "兜底回写失败 " + AppLog.cause(e));
            }
        }
        if (resolvedItem == null || resolvedItem.getVideoResultId() == 0) {
            AppLog.e("Download", "下载中止：无可用解析结果 viewKey=" + item.getViewKey());
            if (listener != null) {
                listener.onError("还未解析成功视频地址");
            } else {
                ifViewAttached(new ViewAction<DownloadView>() {
                    @Override
                    public void run(@NonNull DownloadView view) {
                        view.showMessage("还未解析成功视频地址", TastyToast.WARNING);
                    }
                });
            }
            return null;
        }
        return resolvedItem;
    }

    /**
     * M61：检查文件是否已下载（兼容回退目录），已下载则通知 listener 并返回 true
     */
    private boolean isAlreadyDownloaded(V9MmanItem item, DownloadListener listener) {
        File toFile = SDCardUtils.resolveExistingDownloadFile(context,
                item.getDownLoadPath(getCustomDownloadVideoDirPath()));
        if (toFile != null && toFile.exists() && toFile.length() > 0) {
            AppLog.w("Download", "文件已存在，跳过下载 path=" + toFile.getAbsolutePath());
            if (listener != null) {
                listener.onError("已经下载过了，请查看下载目录");
            } else {
                ifViewAttached(new ViewAction<DownloadView>() {
                    @Override
                    public void run(@NonNull DownloadView view) {
                        view.showMessage("已经下载过了，请查看下载目录", TastyToast.INFO);
                    }
                });
            }
            return true;
        }
        return false;
    }

    @Override
    public void downloadVideo(V9MmanItem v9MmanItem, boolean isForceReDownload) {
        downloadVideo(v9MmanItem, isForceReDownload, null);
    }

    /**
     * 立即使用数据库中已有的视频地址重新建立下载任务。
     * 用于“本地文件丢失后重新下载”场景，避免确认后还要等待一次播放页解析。
     */
    public void downloadVideoImmediately(V9MmanItem v9MmanItem) {
        if (v9MmanItem == null) {
            return;
        }
        V9MmanItem resolvedItem = dataManager.findV9MmanItemByViewKey(v9MmanItem.getViewKey());
        if (resolvedItem == null || resolvedItem.getVideoResult() == null
                || TextUtils.isEmpty(resolvedItem.getVideoResult().getVideoUrl())) {
            ifViewAttached(new ViewAction<DownloadView>() {
                @Override
                public void run(@NonNull DownloadView view) {
                    view.showMessage("没有可用的视频地址，请稍后重试", TastyToast.WARNING);
                }
            });
            return;
        }
        String path = SDCardUtils.ensureDownloadDir(
                resolvedItem.getDownLoadPath(getCustomDownloadVideoDirPath()), context);
        if (TextUtils.isEmpty(path)) {
            ifViewAttached(new ViewAction<DownloadView>() {
                @Override
                public void run(@NonNull DownloadView view) {
                    view.showMessage("下载目录不可写，请检查存储权限或更换下载目录", TastyToast.ERROR);
                }
            });
            return;
        }
        startDownloadInternal(resolvedItem, resolvedItem.getVideoResult().getVideoUrl(), path,
                dataManager.isDownloadVideoNeedWifi(), true, null);
    }

    /**
     * M68/M69：pause 探针检测僵尸下载任务并清理残留。
     * @return true = 真实任务已暂停（后续可断点续传）；false = 僵尸已清理或无任务
     */
    private boolean probeAndCleanupZombieTask(V9MmanItem item, boolean force) {
        if (item.getStatus() != FileDownloadStatus.progress || item.getDownloadId() == 0 || force) {
            return false;
        }
        boolean resumedRealTask = false;
        try {
            if (FileDownloader.getImpl().isServiceConnected()) {
                int pausedCount = FileDownloader.getImpl().pause(item.getDownloadId());
                resumedRealTask = pausedCount > 0;
                AppLog.i("Download", "pause探针 downloadId=" + item.getDownloadId()
                        + " pausedCount=" + pausedCount + " realTask=" + resumedRealTask);
            } else {
                AppLog.w("Download", "下载服务未连接且DB状态progress，按僵尸处理 downloadId="
                        + item.getDownloadId());
            }
        } catch (Throwable ignored) {
        }
        if (!resumedRealTask) {
            AppLog.w("Download", "检测到僵尸下载任务(downloadId=" + item.getDownloadId()
                    + ")，清除残留后重新下载 viewKey=" + item.getViewKey());
            try {
                String clearPath = item.getDownLoadPath(getCustomDownloadVideoDirPath());
                FileDownloader.getImpl().clear(item.getDownloadId(), clearPath);
                deleteFileWithTemp(clearPath);
                File fallback = SDCardUtils.resolveExistingDownloadFile(context, clearPath);
                if (fallback != null && !fallback.getAbsolutePath().equals(clearPath)) {
                    deleteFileWithTemp(fallback.getAbsolutePath());
                }
            } catch (Exception e) {
                AppLog.w("Download", "僵尸清理异常(忽略) " + AppLog.cause(e));
            }
            item.setStatus(FileDownloadStatus.error);
            item.setSoFarBytes(0);
            item.setProgress(0);
            dataManager.updateV9MmanItem(item);
        }
        return resumedRealTask;
    }

    /**
     * M102：断点续传优先——探活旧 URL，有效则续传，失效则重解析。
     * @return true 表示已处理（续传或重解析已发起），调用方应 return；false 表示不满足续传条件
     */
    private boolean tryResumeDownload(V9MmanItem item, String path, boolean resumedRealTask,
                                      boolean force, DownloadListener listener) {
        if (force || isPornyVideo(item)) {
            return false;
        }
        String oldUrl = item.getVideoResult() == null ? null : item.getVideoResult().getVideoUrl();
        if (TextUtils.isEmpty(oldUrl) || item.getSoFarBytes() <= 0) {
            return false;
        }
        if (!resumedRealTask
                && item.getStatus() != FileDownloadStatus.paused
                && item.getStatus() != FileDownloadStatus.error) {
            return false;
        }
        if (!hasFileDownloaderCheckpoint(path)) {
            return false;
        }
        final V9MmanItem resumeItem = item;
        final String resumePath = path;
        final String resumeOldUrl = oldUrl;
        final boolean resumeForce = force;
        final boolean resumeWifi = dataManager.isDownloadVideoNeedWifi();
        final DownloadListener resumeListener = listener;
        Observable.create(new ObservableOnSubscribe<Boolean>() {
            @Override
            public void subscribe(ObservableEmitter<Boolean> emitter) throws Exception {
                emitter.onNext(PornyFallbackResolver.isAlive(okHttpClient, resumeOldUrl, buildReferer(item.getViewKey())));
                emitter.onComplete();
            }
        })
                .compose(RxSchedulersHelper.<Boolean>ioMainThread())
                .compose(provider.<Boolean>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<Boolean>() {
                    @Override
                    public void onBegin(Disposable d) {
                    }

                    @Override
                    public void onSuccess(Boolean alive) {
                        if (alive != null && alive) {
                            AppLog.i("Download", "继续下载：旧地址仍有效，断点续传 viewKey="
                                    + resumeItem.getViewKey()
                                    + " 已下=" + resumeItem.getSoFarBytes() + "B");
                            startDownloadInternal(resumeItem, resumeOldUrl, resumePath,
                                    resumeWifi, false, resumeListener);
                        } else {
                            AppLog.i("Download", "继续下载：旧地址已失效，重解析后下载 viewKey="
                                    + resumeItem.getViewKey());
                            reparseThenDownload(resumeItem, resumePath, resumeWifi,
                                    resumeForce, resumeListener);
                        }
                    }

                    @Override
                    public void onError(String msg, int code) {
                        AppLog.e("Download", "续传探活失败(" + msg + ")，重解析后下载 viewKey="
                                + resumeItem.getViewKey());
                        reparseThenDownload(resumeItem, resumePath, resumeWifi,
                                resumeForce, resumeListener);
                    }
                });
        return true;
    }

    @Override
    public void downloadVideo(V9MmanItem v9MmanItem, boolean isForceReDownload, DownloadListener downloadListener) {
        AppLog.i("Download", "下载请求 viewKey=" + (v9MmanItem == null ? "null" : v9MmanItem.getViewKey())
                + " force=" + isForceReDownload);
        V9MmanItem item = validateAndResolveItem(v9MmanItem, downloadListener);
        if (item == null) {
            return;
        }
        VideoResult videoResult = item.getVideoResult();
        //先检查文件（M61：兼容回退目录，避免重复下载）
        if (isAlreadyDownloaded(item, downloadListener)) {
            return;
        }
        //如果已经缓存完成，直接使用缓存代理完成
        // M74：先同步检查缓存文件是否存在，存在再异步拷贝，避免 TOCTOU 递归 hack
        File cacheFile = getCacheFileIfExists(item);
        if (cacheFile != null) {
            AppLog.i("Download", "命中播放缓存，走缓存拷贝路径 viewKey=" + item.getViewKey());
            copyCacheFile(item, cacheFile, downloadListener);
            return;
        }
        //检查当前状态 — M68 僵尸任务防护 + M102 pause 探针
        boolean resumedRealTask = probeAndCleanupZombieTask(item, isForceReDownload);
        Logger.d("视频连接：" + videoResult.getVideoUrl());
        String path = v9MmanItem.getDownLoadPath(getCustomDownloadVideoDirPath());
        String requestedPath = path;
        path = SDCardUtils.ensureDownloadDir(path, context);
        if (TextUtils.isEmpty(path)) {
            AppLog.e("Download", "下载目录不可写 requestedPath=" + requestedPath);
            if (downloadListener != null) {
                downloadListener.onError("下载目录不可写，请检查存储权限或更换下载目录");
            }
            return;
        }
        Logger.d(path);
        AppLog.i("Download", "开始下载 viewKey=" + item.getViewKey()
                + " 标题=" + item.getTitle() + " 源=" + (item.getSource() == null ? "9mman" : item.getSource())
                + " 地址=" + dataManager.getMman9VideoAddress()
                + " 代理=" + (dataManager.isOpenHttpProxy()
                        ? dataManager.getProxyIpAddress() + ":" + dataManager.getProxyPort() : "关"));
        boolean isDownloadNeedWifi = dataManager.isDownloadVideoNeedWifi();
        // M102：断点续传优先——探活旧 URL，有效则续传，失效则重解析（详见 tryResumeDownload 文档）
        if (tryResumeDownload(item, path, resumedRealTask, isForceReDownload, downloadListener)) {
            return;
        }
        // 91mman 视频分类源：直链带时效签名（st/f 参数），DB 里存的旧 URL 过期后 CDN 拒绝
        // （表现为进度 0% 无速度）。下载前先重新解析播放页拿新鲜 URL；其它源（91porny 等）直接用。
        // M68：isPornyVideo 已与 PlayVideoPresenter.isPornySource 权威逻辑对齐。
        if (!isPornyVideo(item)) {
            reparseThenDownload(item, path, isDownloadNeedWifi, isForceReDownload, downloadListener);
            return;
        }
        startDownloadInternal(item, videoResult.getVideoUrl(), path, isDownloadNeedWifi, isForceReDownload, downloadListener);
    }

    /** 下载前重新解析 91mman 播放页，拿到带新时效签名的直链再下载（失败则退回旧地址尝试） */
    private void reparseThenDownload(final V9MmanItem item, final String path, final boolean wifi,
                                     final boolean force, final DownloadListener listener) {
        dataManager.loadMman9VideoUrl(item.getViewKey())
                .compose(RxSchedulersHelper.<VideoResult>ioMainThread())
                .compose(provider.<VideoResult>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<VideoResult>() {
                    @Override
                    public void onBegin(Disposable d) {
                    }

                    @Override
                    public void onSuccess(VideoResult fresh) {
                        String freshUrl = fresh == null ? null : fresh.getVideoUrl();
                        if (TextUtils.isEmpty(freshUrl)) {
                            AppLog.w("Download", "重新解析为空，走91porny兜底 viewKey=" + item.getViewKey());
                            // 观看次数超限等：直接尝试备用源
                            tryPornyFallback(item, path, wifi, force, listener);
                            return;
                        }
                        // 写回 DB，保证「我的下载」等其它入口下次直接命中新鲜地址
                        try {
                            dataManager.saveVideoResult(fresh);
                            item.setVideoResult(fresh);
                            dataManager.updateV9MmanItem(item);
                        } catch (Exception ignored) {
                        }
                        // 直链 CDN 可能封锁当前网络（下载 error/0% 无速度）：先探活，被拒则走 91porny 备用源
                        if (!PornyFallbackResolver.isAlive(okHttpClient, freshUrl, buildReferer(item.getViewKey()))) {
                            AppLog.w("Download", "直链探活失败，走91porny兜底 viewKey=" + item.getViewKey());
                            tryPornyFallback(item, path, wifi, force, listener);
                            return;
                        }
                        AppLog.i("Download", "重新解析成功，开始下载 viewKey=" + item.getViewKey()
                                + " host=" + AppLog.hostOf(freshUrl));
                        startDownloadInternal(item, freshUrl, path, wifi, force, listener);
                    }

                    @Override
                    public void onError(String msg, int code) {
                        AppLog.e("Download", "重新解析失败(" + msg + ")，走91porny兜底 viewKey=" + item.getViewKey());
                        tryPornyFallback(item, path, wifi, force, listener);
                    }
                });
    }

    /**
     * 91mman 直链不可用（CDN 封锁 / 观看超限 / 解析失败）时：
     * 用标题反查 91porny → 命中则改走 HLS 下载；未命中退回 DB 旧地址。
     */
    private void tryPornyFallback(final V9MmanItem item, final String path, final boolean wifi,
                                  final boolean force, final DownloadListener listener) {
        // 解析 91porny 是网络请求，放 IO 线程
        io.reactivex.Observable
                .create(new ObservableOnSubscribe<VideoResult>() {
                    @Override
                    public void subscribe(io.reactivex.ObservableEmitter<VideoResult> emitter) throws Exception {
                        VideoResult pr = PornyFallbackResolver.resolve(dataManager, item.getTitle());
                        if (pr == null) {
                            emitter.onNext(null);
                        } else {
                            emitter.onNext(pr);
                        }
                        emitter.onComplete();
                    }
                })
                .compose(RxSchedulersHelper.<VideoResult>ioMainThread())
                .compose(provider.<VideoResult>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<VideoResult>() {
                    @Override
                    public void onBegin(Disposable d) {
                    }

                    @Override
                    public void onSuccess(VideoResult pornyResult) {
                        if (pornyResult != null && !TextUtils.isEmpty(pornyResult.getVideoUrl())) {
                            AppLog.i("Download", "91porny兜底命中，改HLS下载 viewKey=" + item.getViewKey()
                                    + " host=" + AppLog.hostOf(pornyResult.getVideoUrl()));
                            PornyFallbackResolver.applyPornyResult(dataManager, item, pornyResult);
                            PornyFallbackResolver.enqueueHlsDownload(context, item, pornyResult.getVideoUrl(), path);
                            if (listener != null) {
                                listener.onSuccess("原站视频地址不可访问，已自动改用备用源继续下载");
                            } else {
                                ifViewAttached(new ViewAction<DownloadView>() {
                                    @Override
                                    public void run(@NonNull DownloadView view) {
                                        view.showMessage("原站视频地址不可访问，已自动改用备用源继续下载", TastyToast.SUCCESS);
                                    }
                                });
                            }
                            return;
                        }
                        AppLog.w("Download", "91porny兜底未命中，退回旧地址 viewKey=" + item.getViewKey());
                        // 备用源未命中：退回旧地址尝试
                        startDownloadWithFallback(item, path, wifi, force, listener);
                    }

                    @Override
                    public void onError(String msg, int code) {
                        AppLog.e("Download", "91porny兜底解析失败(" + msg + ")，退回旧地址 viewKey=" + item.getViewKey());
                        startDownloadWithFallback(item, path, wifi, force, listener);
                    }
                });
    }

    /** 重新解析失败时退回 DB 旧地址尝试（可能恰好仍有效） */
    private void startDownloadWithFallback(V9MmanItem item, String path, boolean wifi, boolean force, DownloadListener listener) {
        VideoResult old = item.getVideoResult();
        String oldUrl = old == null ? null : old.getVideoUrl();
        if (TextUtils.isEmpty(oldUrl)) {
            if (listener != null) {
                listener.onError("解析视频地址失败，请稍后重试");
            } else {
                ifViewAttached(new ViewAction<DownloadView>() {
                    @Override
                    public void run(@NonNull DownloadView view) {
                        view.showMessage("解析视频地址失败，请稍后重试", TastyToast.ERROR);
                    }
                });
            }
            return;
        }
        startDownloadInternal(item, oldUrl, path, wifi, force, listener);
    }

    /** 真正启动下载（带 Referer/UA 请求头） */
    private void startDownloadInternal(V9MmanItem item, String url, String path, boolean wifi, boolean force, DownloadListener listener) {
        // M61：m3u8 绝不能交给 FileDownloader（会把播放列表文本秒下成假 mp4），改走 HLS 服务
        if (DownloadManager.isHlsUrl(url)) {
            AppLog.i("Download", "HLS地址改走HLS服务下载 viewKey=" + item.getViewKey()
                    + " host=" + AppLog.hostOf(url));
            PornyFallbackResolver.enqueueHlsDownload(context, item, url, path);
            if (listener != null) {
                listener.onSuccess("已加入后台下载");
            } else {
                ifViewAttached(new ViewAction<DownloadView>() {
                    @Override
                    public void run(@NonNull DownloadView view) {
                        view.showMessage("已加入后台下载", TastyToast.SUCCESS);
                    }
                });
            }
            return;
        }
        int id = DownloadManager.getImpl().startDownload(url, path, wifi, force, buildReferer(item.getViewKey()));
        if (item.getAddDownloadDate() == null) {
            item.setAddDownloadDate(new Date());
        }
        item.setDownloadId(id);
        dataManager.updateV9MmanItem(item);
        if (listener != null) {
            listener.onSuccess("开始下载");
        } else {
            ifViewAttached(new ViewAction<DownloadView>() {
                @Override
                public void run(@NonNull DownloadView view) {
                    view.showMessage("开始下载", TastyToast.SUCCESS);
                }
            });
        }
    }

    /**
     * M68：查询 FileDownloader 中是否真的存在该 downloadId 的运行中任务。
     * FileDownloadList.copy() 是包私有无法访问；保留此方法供日志/调试参考，
     * 实际僵尸判定用 pause 探针（见 downloadVideo）。
     */
    @SuppressWarnings("unused")
    private static Boolean findRunningTask(V9MmanItem item, String path) {
        try {
            String url = item.getVideoResult() == null ? null : item.getVideoResult().getVideoUrl();
            if (TextUtils.isEmpty(url)) {
                return null;
            }
            int regeneratedId = com.liulishuo.filedownloader.util.FileDownloadUtils
                    .generateId(url, path);
            if (regeneratedId != item.getDownloadId()) {
                return null;
            }
            return Boolean.TRUE;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 91mman 源下载 Referer：指向播放页，部分 CDN 校验该头 */
    private String buildReferer(String viewKey) {
        try {
            String addr = dataManager.getMman9VideoAddress();
            if (TextUtils.isEmpty(addr)) {
                return null;
            }
            // M62：DB 中的 viewKey 带 "viewkey=" 前缀，直接拼会产生 viewkey=viewkey=XXX
            String bareKey = viewKey != null && viewKey.startsWith("viewkey=")
                    ? viewKey.substring(8) : viewKey;
            return addr + "view_video.php?viewkey=" + bareKey;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void loadDownloadingData() {

        Observable
                .create(new ObservableOnSubscribe<List<V9MmanItem>>() {
                    @Override
                    public void subscribe(ObservableEmitter<List<V9MmanItem>> emitter) throws Exception {
                        List<V9MmanItem> v9MmanItems = dataManager.loadDownloadingData();
                        // M41：展示时兜底纠错——error 记录若文件实际已完整，则自动修正为已完成并移出“正在下载”，
                        // 覆盖历史遗留/异常路径导致的“能播放却提示下载错误”。
                        if (v9MmanItems != null && !v9MmanItems.isEmpty()) {
                            String customDir = getCustomDownloadVideoDirPath();
                            Iterator<V9MmanItem> it = v9MmanItems.iterator();
                            while (it.hasNext()) {
                                V9MmanItem item = it.next();
                                if (item == null || item.getStatus() != FileDownloadStatus.error) {
                                    continue;
                                }
                                File f = SDCardUtils.resolveExistingDownloadFile(context,
                                        item.getDownLoadPath(customDir));
                                if (SDCardUtils.isDownloadFileComplete(f, item.getTotalFarBytes())) {
                                    item.setStatus(FileDownloadStatus.completed);
                                    item.setProgress(100);
                                    // M99：字段已改 long，去掉 int 强转避免 >2GB 文件尺寸截断
                                    item.setSoFarBytes(f.length());
                                    item.setTotalFarBytes(f.length());
                                    item.setFinishedDownloadDate(new Date());
                                    dataManager.updateV9MmanItem(item);
                                    it.remove();
                                }
                            }
                        }
                        emitter.onNext(v9MmanItems);
                        emitter.onComplete();
                    }
                })
                .compose(RxSchedulersHelper.<List<V9MmanItem>>ioMainThread())
                .compose(provider.<List<V9MmanItem>>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<List<V9MmanItem>>() {
                    @Override
                    public void onSuccess(final List<V9MmanItem> v9MmanItemList) {
                        ifViewAttached(new ViewAction<DownloadView>() {
                            @Override
                            public void run(@NonNull DownloadView view) {
                                view.setDownloadingData(v9MmanItemList);
                            }
                        });
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        ifViewAttached(new ViewAction<DownloadView>() {
                            @Override
                            public void run(@NonNull DownloadView view) {
                                view.showError(msg);
                            }
                        });
                    }
                });

    }

    @Override
    public void loadFinishedData() {

        Observable
                .create(new ObservableOnSubscribe<List<V9MmanItem>>() {
                    @Override
                    public void subscribe(ObservableEmitter<List<V9MmanItem>> emitter) throws Exception {
                        List<V9MmanItem> v9MmanItems = dataManager.loadFinishedData();
                        emitter.onNext(v9MmanItems);
                        emitter.onComplete();
                    }
                })
                .compose(RxSchedulersHelper.<List<V9MmanItem>>ioMainThread())
                .compose(provider.<List<V9MmanItem>>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<List<V9MmanItem>>() {
                    @Override
                    public void onSuccess(final List<V9MmanItem> v9MmanItemList) {
                        ifViewAttached(new ViewAction<DownloadView>() {
                            @Override
                            public void run(@NonNull DownloadView view) {
                                view.setFinishedData(v9MmanItemList);
                            }
                        });
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        ifViewAttached(new ViewAction<DownloadView>() {
                            @Override
                            public void run(@NonNull DownloadView view) {
                                view.showError(msg);
                            }
                        });
                    }
                });
    }

    @Override
    public void deleteDownloadingTask(final V9MmanItem v9MmanItem) {
        // M97：整体异步化——原实现在主线程同步做 IPC(pause/clear)+删文件+写库，易卡顿/ANR；
        // 且删除开始即标记 deletingIds，DownloadManager 的进度回调命中即跳过写库，
        // 防止迟到的 progress 回调把刚删的行复活成“下载中”幽灵行。
        final int downloadId = v9MmanItem == null ? 0 : v9MmanItem.getDownloadId();
        final String path = v9MmanItem == null ? "" : v9MmanItem.getDownLoadPath(getCustomDownloadVideoDirPath());
        if (v9MmanItem == null) {
            return;
        }
        // 删除开始：先加入删除集合（成功/失败都会移除）
        DownloadManager.markDeleting(downloadId);
        Observable.fromCallable(() -> {
            // 1) 尽量通过 FileDownloader 暂停并清除（需服务已连接）
            try {
                if (FileDownloader.getImpl().isServiceConnected()) {
                    FileDownloader.getImpl().pause(downloadId);
                    FileDownloader.getImpl().clear(downloadId, path);
                }
            } catch (Exception ignored) {
            }
            // 2) 兜底：直接删除目标文件及其临时文件（不依赖下载服务，确保“正在下载”也能删除）
            deleteFileWithTemp(path);
            // M61：文件可能被写进应用专属回退目录，一并清理
            File fallback = SDCardUtils.resolveExistingDownloadFile(context, path);
            if (fallback != null && !fallback.getAbsolutePath().equals(path)) {
                deleteFileWithTemp(fallback.getAbsolutePath());
            }
            v9MmanItem.setDownloadId(0);
            dataManager.updateV9MmanItem(v9MmanItem);
            return Boolean.TRUE;
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(provider.<Boolean>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(ok -> {
                    // 删除落库完成后才解除标记，窗口期内迟到回调已被过滤
                    DownloadManager.unmarkDeleting(downloadId);
                    // M97：异步化后调用方原有的立即 loadDownloadingData 可能早于真实删除完成，
                    // 这里在删除真正结束后再刷一次列表保证 UI 一致
                    loadDownloadingData();
                }, err -> {
                    DownloadManager.unmarkDeleting(downloadId);
                    Logger.e("删除下载任务失败：" + (err == null ? "" : err.getMessage()));
                    ifViewAttached(new ViewAction<DownloadView>() {
                        @Override
                        public void run(@NonNull DownloadView view) {
                            view.showError("删除失败，请重试");
                        }
                    });
                });
    }

    /**
     * M102：FileDownloader 的断点临时文件（path + ".fddownload"）是否还在。
     * 临时文件丢失时续传无从谈起（M69 的 ENOENT 场景），只能走重下/重解析路径。
     */
    private static boolean hasFileDownloaderCheckpoint(String targetPath) {
        if (TextUtils.isEmpty(targetPath)) {
            return false;
        }
        File temp = new File(targetPath + ".fddownload");
        return temp.exists() && temp.length() > 0;
    }

    /**
     * M40：直接删除下载文件及其 FileDownloader 临时文件（.fddownload / .fddownload.soload）。
     */
    private void deleteFileWithTemp(String path) {
        if (path == null) {
            return;
        }
        File f = new File(path);
        if (f.exists()) {
            f.delete();
        }
        // FileDownloader 断点临时文件句柄，属真·临时变量
        File fdTempFile = new File(path + ".fddownload");
        if (fdTempFile.exists()) {
            fdTempFile.delete();
        }
        File soload = new File(path + ".fddownload.soload");
        if (soload.exists()) {
            soload.delete();
        }
    }

    @Override
    public void deleteDownloadedTask(V9MmanItem v9MmanItem, boolean isDeleteFile) {
        if (!isDeleteFile) {
            deleteWithoutFile(v9MmanItem);
        } else {
            deleteWithFile(v9MmanItem);
        }
    }

    @Override
    public V9MmanItem findUnLimit91MmanItemByDownloadId(int downloadId) {
        return dataManager.findV9MmanItemByDownloadId(downloadId);
    }

    @Override
    public List<V9MmanItem> loadDownloadingDatas() {
        return dataManager.loadDownloadingData();
    }

    @Override
    public void updateV9MmanItem(V9MmanItem v9MmanItem) {
        dataManager.updateV9MmanItem(v9MmanItem);
    }

    @Override
    public String getCustomDownloadVideoDirPath() {
        return dataManager.getCustomDownloadVideoDirPath();
    }

    /**
     * 只删除记录，不删除文件
     *
     * @param v9MmanItem v
     */
    private void deleteWithoutFile(V9MmanItem v9MmanItem) {
        v9MmanItem.setDownloadId(0);
        dataManager.updateV9MmanItem(v9MmanItem);
    }

    /**
     * 连同文件一起删除
     *
     * @param v9MmanItem v
     */
    private void deleteWithFile(V9MmanItem v9MmanItem) {
        boolean deleted;
        // V13：已归档（MediaStore 公共目录）的成品走 MediaStore 删除（同步清相册行 + 文件）；
        // 未归档的按原逻辑删真实文件（原路径或回退目录）。
        if (!TextUtils.isEmpty(v9MmanItem.getLocalFilePath())) {
            try {
                MediaStoreArchiver.deleteArchived(context, v9MmanItem);
                deleted = true;
            } catch (Exception e) {
                deleted = false;
            }
        } else {
            File file = SDCardUtils.resolveExistingDownloadFile(context,
                    v9MmanItem.getDownLoadPath(getCustomDownloadVideoDirPath()));
            deleted = file != null && file.exists() && file.delete();
        }
        if (deleted) {
            v9MmanItem.setDownloadId(0);
            dataManager.updateV9MmanItem(v9MmanItem);
        } else {
            ifViewAttached(new ViewAction<DownloadView>() {
                @Override
                public void run(@NonNull DownloadView view) {
                    view.showMessage("删除文件失败", TastyToast.ERROR);
                }
            });
        }
    }


    /**
     * 同步检查播放缓存文件是否存在（用于 downloadVideo 分支决策，避免 TOCTOU 递归）
     */
    private File getCacheFileIfExists(V9MmanItem item) {
        if (item == null || item.getVideoResult() == null
                || TextUtils.isEmpty(item.getVideoResult().getVideoUrl())) {
            return null;
        }
        VideoCacheFileNameGenerator gen = new VideoCacheFileNameGenerator();
        String cacheFileName = gen.generate(item.getVideoResult().getVideoUrl());
        File file = new File(AppCacheUtils.getVideoCacheDir(context), cacheFileName);
        return (file.exists() && file.length() > 0) ? file : null;
    }

    /**
     * 拷贝播放缓存到下载目录。
     * M74：缓存文件在 isVideoCacheByProxy 判定后可能被 LRU 淘汰（TOCTOU），
     * 不再用递归 + bypassCacheCopy hack，改为调用方先同步检查缓存文件是否存在，
     * 存在再调用本方法，缺失时直接走正常下载。
     */
    private void copyCacheFile(final V9MmanItem v9MmanItem, final File fromFile,
                               final DownloadListener downloadListener) {
        Observable.create(new ObservableOnSubscribe<File>() {
            @Override
            public void subscribe(ObservableEmitter<File> e) throws Exception {
                e.onNext(fromFile);
                e.onComplete();
            }
        }).map(new Function<File, V9MmanItem>() {
            @Override
            public V9MmanItem apply(File fromFile) throws Exception {
                // M74：修复缓存拷贝分支漏掉 ensureDownloadDir 的缺陷——
                // 常规下载路径(246-248)会先确保父目录，但本分支此前直接 createNewFile，
                // 当自定义下载目录未物化、或首次即走缓存拷贝分支时，父目录不存在导致
                // IOException: No such file or directory。此处补齐目录创建（与常规路径一致）。
                String preferredPath = v9MmanItem.getDownLoadPath(getCustomDownloadVideoDirPath());
                File toFile = new File(preferredPath);
                File parent = toFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    // 父目录创建失败：复用常规下载的 ensureDownloadDir 回退到可写目录
                    String ensured = SDCardUtils.ensureDownloadDir(preferredPath, context);
                    if (!TextUtils.isEmpty(ensured)) {
                        toFile = new File(ensured);
                    }
                }
                if (toFile.exists() && toFile.length() > 0) {
                    throw new Exception("已经下载过了");
                }
                File toParent = toFile.getParentFile();
                if (toParent == null || !toParent.exists() || !toParent.canWrite()) {
                    // 仍不可用：再次尝试 ensureDownloadDir 回退；若仍失败给出友好提示而非裸 IOException
                    String ensured = SDCardUtils.ensureDownloadDir(preferredPath, context);
                    if (TextUtils.isEmpty(ensured)) {
                        throw new Exception("下载目录不可写，请检查存储权限或更换下载目录");
                    }
                    toFile = new File(ensured);
                }
                if (!toFile.createNewFile()) {
                    throw new Exception("创建文件失败");
                }
                FileUtils.copyFile(fromFile, toFile);
                // M99：字段已改 long，去掉 int 强转避免 >2GB 文件尺寸截断
                v9MmanItem.setTotalFarBytes(fromFile.length());
                v9MmanItem.setSoFarBytes(fromFile.length());
                return v9MmanItem;
            }
        }).map(new Function<V9MmanItem, String>() {
            @Override
            public String apply(V9MmanItem v9MmanItem) throws Exception {
                v9MmanItem.setStatus(FileDownloadStatus.completed);
                v9MmanItem.setProgress(100);
                v9MmanItem.setFinishedDownloadDate(new Date());
                v9MmanItem.setDownloadId(FileDownloadUtils.generateId(v9MmanItem.getVideoResult().getVideoUrl(), v9MmanItem.getDownLoadPath(getCustomDownloadVideoDirPath())));
                dataManager.updateV9MmanItem(v9MmanItem);
                return "下载完成";
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(provider.<String>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<String>() {
                    @Override
                    public void onBegin(Disposable d) {

                    }

                    @Override
                    public void onSuccess(final String s) {
                        if (downloadListener != null) {
                            downloadListener.onSuccess(s);
                        } else {
                            ifViewAttached(new ViewAction<DownloadView>() {
                                @Override
                                public void run(@NonNull DownloadView view) {
                                    view.showMessage(s, TastyToast.SUCCESS);
                                }
                            });
                        }
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        if (downloadListener != null) {
                            downloadListener.onError(msg);
                        } else {
                            ifViewAttached(new ViewAction<DownloadView>() {
                                @Override
                                public void run(@NonNull DownloadView view) {
                                    view.showMessage(msg, TastyToast.ERROR);
                                }
                            });
                        }
                    }
                });
    }

    public int getPlaybackEngine(){
        return dataManager.getPlaybackEngine();
    }

    /**
     * M68：判断是否 91porny 源。与 PlayVideoPresenter.isPornySource 保持同一权威逻辑：
     * 持久化标记优先（mman9 标记直接短路为 false），无标记时才做格式推断兜底。
     * v1.0.67 及之前此方法是纯 hex 正则，会把带 hex key 的 9mman 视频误判成 porny，
     * 跳过下载前的重新解析、拿过期签名直链去下导致必然失败。
     */
    private static boolean isPornyVideo(V9MmanItem item) {
        if (item == null) {
            return false;
        }
        boolean markedPorny = Parse91PornyVideo.SOURCE.equals(item.getSource())
                || Parse91PornyVideo.SOURCE.equals(item.getSourceName());
        if (markedPorny) {
            return true;
        }
        // 权威短路：明确标 mman9 的不走格式推断
        if (PlayVideoPresenter.SOURCE_MMAN9_PERSIST.equals(item.getSourceName())
                || PlayVideoPresenter.SOURCE_MMAN9_PERSIST.equals(item.getSource())) {
            return false;
        }
        // 兜底：带前缀恒为 9mman，裸 hex 才可能是 porny
        String viewKey = item.getViewKey();
        if (TextUtils.isEmpty(viewKey)) {
            return false;
        }
        boolean prefixed = viewKey.startsWith("viewkey=");
        String bare = prefixed ? viewKey.substring(8) : viewKey;
        if (bare.matches("[0-9a-fA-F]{16,32}")) {
            return !prefixed;
        }
        return false;
    }

    public interface DownloadListener {
        void onSuccess(String message);

        void onError(String message);
    }
}
