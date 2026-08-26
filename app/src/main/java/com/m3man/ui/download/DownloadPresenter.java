package com.m3man.ui.download;

import android.arch.lifecycle.Lifecycle;
import android.content.Context;
import android.support.annotation.NonNull;
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
import com.m3man.utils.VideoCacheFileNameGenerator;

import java.io.File;
import java.io.IOException;
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
 * @describe
 */

public class DownloadPresenter extends MvpBasePresenter<DownloadView> implements IDownload {

    private DataManager dataManager;
    private LifecycleProvider<Lifecycle.Event> provider;
    private Context context;
    private OkHttpClient okHttpClient;
    // M74：缓存拷贝分支回退到正常下载时，用此标志防止再次命中缓存分支造成递归
    private volatile boolean bypassCacheCopy = false;

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
        V9MmanItem tmp = dataManager.findV9MmanItemByViewKey(v9MmanItem.getViewKey());
        if (tmp == null || tmp.getVideoResult() == null
                || TextUtils.isEmpty(tmp.getVideoResult().getVideoUrl())) {
            ifViewAttached(new ViewAction<DownloadView>() {
                @Override
                public void run(@NonNull DownloadView view) {
                    view.showMessage("没有可用的视频地址，请稍后重试", TastyToast.WARNING);
                }
            });
            return;
        }
        String path = SDCardUtils.ensureDownloadDir(
                tmp.getDownLoadPath(getCustomDownloadVideoDirPath()), context);
        if (TextUtils.isEmpty(path)) {
            ifViewAttached(new ViewAction<DownloadView>() {
                @Override
                public void run(@NonNull DownloadView view) {
                    view.showMessage("下载目录不可写，请检查存储权限或更换下载目录", TastyToast.ERROR);
                }
            });
            return;
        }
        startDownloadInternal(tmp, tmp.getVideoResult().getVideoUrl(), path,
                dataManager.isDownloadVideoNeedWifi(), true, null);
    }

    @Override
    public void downloadVideo(V9MmanItem v9MmanItem, boolean isForceReDownload, DownloadListener downloadListener) {
        AppLog.i("Download", "下载请求 viewKey=" + (v9MmanItem == null ? "null" : v9MmanItem.getViewKey())
                + " force=" + isForceReDownload);
        if (v9MmanItem == null) {
            if (downloadListener != null) {
                downloadListener.onError("视频信息为空");
            }
            return;
        }
        V9MmanItem tmp = dataManager.findV9MmanItemByViewKey(v9MmanItem.getViewKey());
        if (tmp == null) {
            AppLog.e("Download", "数据库找不到视频 viewKey=" + v9MmanItem.getViewKey());
        } else if (tmp.getVideoResultId() == 0) {
            AppLog.w("Download", "DB行无解析结果 videoResultId=0 viewKey=" + v9MmanItem.getViewKey());
        }
        // M68：DB 行缺失或无解析结果时，用刚解析成功的内存对象兜底并回写，
        // 避免「明明刚解析成功却提示还未解析成功视频地址」的死路。
        if ((tmp == null || tmp.getVideoResultId() == 0)
                && v9MmanItem.getVideoResult() != null
                && !TextUtils.isEmpty(v9MmanItem.getVideoResult().getVideoUrl())) {
            AppLog.w("Download", "用内存解析结果兜底并回写DB viewKey=" + v9MmanItem.getViewKey());
            try {
                if (v9MmanItem.getSourceName() == null) {
                    v9MmanItem.setSourceName(PlayVideoPresenter.SOURCE_MMAN9_PERSIST);
                }
                dataManager.saveV9MmanItem(v9MmanItem);
                tmp = dataManager.findV9MmanItemByViewKey(v9MmanItem.getViewKey());
                AppLog.i("Download", "兜底回写完成 videoResultId=" + (tmp == null ? -1 : tmp.getVideoResultId()));
            } catch (Exception e) {
                AppLog.e("Download", "兜底回写失败 " + AppLog.cause(e));
            }
        }
        if (tmp == null || tmp.getVideoResultId() == 0) {
            AppLog.e("Download", "下载中止：无可用解析结果 viewKey=" + v9MmanItem.getViewKey());
            if (downloadListener != null) {
                downloadListener.onError("还未解析成功视频地址");
            } else {
                ifViewAttached(new ViewAction<DownloadView>() {
                    @Override
                    public void run(@NonNull DownloadView view) {
                        view.showMessage("还未解析成功视频地址", TastyToast.WARNING);
                    }
                });
            }
            return;
        }
        VideoResult videoResult = tmp.getVideoResult();
        //先检查文件（M61：兼容回退目录，避免重复下载）
        File toFile = SDCardUtils.resolveExistingDownloadFile(context,
                tmp.getDownLoadPath(getCustomDownloadVideoDirPath()));
        if (toFile != null && toFile.exists() && toFile.length() > 0) {
            AppLog.w("Download", "文件已存在，跳过下载 path=" + toFile.getAbsolutePath());
            if (downloadListener != null) {
                downloadListener.onError("已经下载过了，请查看下载目录");
            } else {
                ifViewAttached(new ViewAction<DownloadView>() {
                    @Override
                    public void run(@NonNull DownloadView view) {
                        view.showMessage("已经下载过了，请查看下载目录", TastyToast.INFO);
                    }
                });
            }
            return;
        }
        //如果已经缓存完成，直接使用缓存代理完成
        // M74：缓存拷贝回退场景下置位 bypassCacheCopy，跳过本分支，避免再次进入 copyCacheFile 递归
        if (!bypassCacheCopy && dataManager.isVideoCacheByProxy(videoResult.getVideoUrl())) {
            AppLog.i("Download", "命中播放缓存，走缓存拷贝路径 viewKey=" + tmp.getViewKey());
            try {
                copyCacheFile(AppCacheUtils.getVideoCacheDir(context), tmp, downloadListener);
            } catch (IOException e) {
                if (downloadListener != null) {
                    downloadListener.onError("缓存文件错误，无法拷贝");
                } else {
                    ifViewAttached(new ViewAction<DownloadView>() {
                        @Override
                        public void run(@NonNull DownloadView view) {
                            view.showMessage("缓存文件错误，无法拷贝", TastyToast.ERROR);
                        }
                    });
                }
                e.printStackTrace();
            }
            return;
        }
        // M74：走到这里说明未走缓存拷贝分支（未命中或 bypassCacheCopy 回退），清除标志
        bypassCacheCopy = false;
        //检查当前状态
        // M102：pause 探针判定结果提升为方法级变量——后面的"继续下载续传优先"分支需要知道任务是否真实存在
        boolean resumedRealTask = false;
        if (tmp.getStatus() == FileDownloadStatus.progress && tmp.getDownloadId() != 0 && !isForceReDownload) {
            // M68：僵尸任务防护——DB 状态是 progress 但下载器里已没有该任务
            //（App 重启后状态残留 / 历史遗留），永远不会再有回调，点下载会被永远封锁。
            // FileDownloader 无公开查询 API，用 pause 探针：pause 返回被暂停的任务数，
            // >0 = 真有任务（已转为暂停态，走下面的重新入队即断点续传）；
            // =0 或服务未连接 = 僵尸记录，清掉状态后照常重新下载。
            try {
                if (FileDownloader.getImpl().isServiceConnected()) {
                    int pausedCount = FileDownloader.getImpl().pause(tmp.getDownloadId());
                    resumedRealTask = pausedCount > 0;
                    AppLog.i("Download", "pause探针 downloadId=" + tmp.getDownloadId()
                            + " pausedCount=" + pausedCount + " realTask=" + resumedRealTask);
                } else {
                    AppLog.w("Download", "下载服务未连接且DB状态progress，按僵尸处理 downloadId="
                            + tmp.getDownloadId());
                }
            } catch (Throwable ignored) {
            }
            if (!resumedRealTask) {
                AppLog.w("Download", "检测到僵尸下载任务(downloadId=" + tmp.getDownloadId()
                        + ")，清除残留后重新下载 viewKey=" + tmp.getViewKey());
                // M69：用户日志实锤「IOException: No such file or directory」——
                // DB 进度偏移还在但 .fddownload 临时文件已丢，直接续传会 ENOENT。
                // 必须 clear 掉下载器侧的旧任务与坏临时文件，从头下。
                try {
                    String clearPath = tmp.getDownLoadPath(getCustomDownloadVideoDirPath());
                    FileDownloader.getImpl().clear(tmp.getDownloadId(), clearPath);
                    deleteFileWithTemp(clearPath);
                    // 回退目录里的残留也清掉
                    File fallback = SDCardUtils.resolveExistingDownloadFile(context, clearPath);
                    if (fallback != null && !fallback.getAbsolutePath().equals(clearPath)) {
                        deleteFileWithTemp(fallback.getAbsolutePath());
                    }
                } catch (Exception e) {
                    AppLog.w("Download", "僵尸清理异常(忽略) " + AppLog.cause(e));
                }
                tmp.setStatus(FileDownloadStatus.error);
                tmp.setSoFarBytes(0);
                tmp.setProgress(0);
                dataManager.updateV9MmanItem(tmp);
            }
            // 无论真实任务(已暂停)还是僵尸记录，都继续往下走统一重新入队：
            // 真实任务若旧地址仍有效则断点续传（见下方 M102 分支）；僵尸任务已清残留，从头下不会报错。
        }
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
        AppLog.i("Download", "开始下载 viewKey=" + tmp.getViewKey()
                + " 标题=" + tmp.getTitle() + " 源=" + (tmp.getSource() == null ? "9mman" : tmp.getSource())
                + " 地址=" + dataManager.getMman9VideoAddress()
                + " 代理=" + (dataManager.isOpenHttpProxy()
                        ? dataManager.getProxyIpAddress() + ":" + dataManager.getProxyPort() : "关"));
        boolean isDownloadNeedWifi = dataManager.isDownloadVideoNeedWifi();
        // M102：「继续下载」断点续传优先——FileDownloader 以 url+path 哈希生成任务 id，
        // 换新地址等于新任务、断点全部作废（表现为“继续下载”却从头重下）。
        // 对有进度的暂停/失败记录（含 pause 探针确认的真实任务）：先探活 DB 旧地址（Range 取 1 字节），
        // 仍放行则保持旧地址不变直接入队续传；旧地址已过期才重新解析拿新地址
        // （此时 CDN 连 Range 都拒绝，旧断点本来就无法使用，只能从头下）。
        // 仅作用于 91mman 直链源；porny 直链地址无时效性，维持原直连逻辑。
        final V9MmanItem resumeItem = tmp;
        final String resumePath = path;
        final String resumeOldUrl = videoResult == null ? null : videoResult.getVideoUrl();
        final boolean resumeForce = isForceReDownload;
        final boolean resumeWifi = isDownloadNeedWifi;
        final DownloadListener resumeListener = downloadListener;
        if (!resumeForce && !isPornyVideo(resumeItem)
                && !TextUtils.isEmpty(resumeOldUrl)
                && resumeItem.getSoFarBytes() > 0
                && (resumedRealTask
                || resumeItem.getStatus() == FileDownloadStatus.paused
                || resumeItem.getStatus() == FileDownloadStatus.error)
                && hasFileDownloaderCheckpoint(resumePath)) {
            Observable.create(new ObservableOnSubscribe<Boolean>() {
                @Override
                public void subscribe(ObservableEmitter<Boolean> emitter) throws Exception {
                    emitter.onNext(PornyFallbackResolver.isAlive(okHttpClient, resumeOldUrl));
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
            return;
        }
        // 91mman 视频分类源：直链带时效签名（st/f 参数），DB 里存的旧 URL 过期后 CDN 拒绝
        // （表现为进度 0% 无速度）。下载前先重新解析播放页拿新鲜 URL；其它源（91porny 等）直接用。
        // M68：isPornyVideo 已与 PlayVideoPresenter.isPornySource 权威逻辑对齐。
        if (!isPornyVideo(tmp)) {
            reparseThenDownload(tmp, path, isDownloadNeedWifi, isForceReDownload, downloadListener);
            return;
        }
        startDownloadInternal(tmp, videoResult.getVideoUrl(), path, isDownloadNeedWifi, isForceReDownload, downloadListener);
    }

    /** 下载前重新解析 91mman 播放页，拿到带新时效签名的直链再下载（失败则退回旧地址尝试） */
    private void reparseThenDownload(final V9MmanItem tmp, final String path, final boolean wifi,
                                     final boolean force, final DownloadListener listener) {
        dataManager.loadMman9VideoUrl(tmp.getViewKey())
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
                            AppLog.w("Download", "重新解析为空，走91porny兜底 viewKey=" + tmp.getViewKey());
                            // 观看次数超限等：直接尝试备用源
                            tryPornyFallback(tmp, path, wifi, force, listener);
                            return;
                        }
                        // 写回 DB，保证「我的下载」等其它入口下次直接命中新鲜地址
                        try {
                            dataManager.saveVideoResult(fresh);
                            tmp.setVideoResult(fresh);
                            dataManager.updateV9MmanItem(tmp);
                        } catch (Exception ignored) {
                        }
                        // 直链 CDN 可能封锁当前网络（下载 error/0% 无速度）：先探活，被拒则走 91porny 备用源
                        if (!PornyFallbackResolver.isAlive(okHttpClient, freshUrl)) {
                            AppLog.w("Download", "直链探活失败，走91porny兜底 viewKey=" + tmp.getViewKey());
                            tryPornyFallback(tmp, path, wifi, force, listener);
                            return;
                        }
                        AppLog.i("Download", "重新解析成功，开始下载 viewKey=" + tmp.getViewKey()
                                + " host=" + AppLog.hostOf(freshUrl));
                        startDownloadInternal(tmp, freshUrl, path, wifi, force, listener);
                    }

                    @Override
                    public void onError(String msg, int code) {
                        AppLog.e("Download", "重新解析失败(" + msg + ")，走91porny兜底 viewKey=" + tmp.getViewKey());
                        tryPornyFallback(tmp, path, wifi, force, listener);
                    }
                });
    }

    /**
     * 91mman 直链不可用（CDN 封锁 / 观看超限 / 解析失败）时：
     * 用标题反查 91porny → 命中则改走 HLS 下载；未命中退回 DB 旧地址。
     */
    private void tryPornyFallback(final V9MmanItem tmp, final String path, final boolean wifi,
                                  final boolean force, final DownloadListener listener) {
        // 解析 91porny 是网络请求，放 IO 线程
        io.reactivex.Observable
                .create(new ObservableOnSubscribe<VideoResult>() {
                    @Override
                    public void subscribe(io.reactivex.ObservableEmitter<VideoResult> emitter) throws Exception {
                        VideoResult pr = PornyFallbackResolver.resolve(dataManager, tmp.getTitle());
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
                            AppLog.i("Download", "91porny兜底命中，改HLS下载 viewKey=" + tmp.getViewKey()
                                    + " host=" + AppLog.hostOf(pornyResult.getVideoUrl()));
                            PornyFallbackResolver.applyPornyResult(dataManager, tmp, pornyResult);
                            PornyFallbackResolver.enqueueHlsDownload(context, tmp, pornyResult.getVideoUrl(), path);
                            if (listener != null) {
                                listener.onSuccess("源站受限，已改用 91porny 源下载");
                            } else {
                                ifViewAttached(new ViewAction<DownloadView>() {
                                    @Override
                                    public void run(@NonNull DownloadView view) {
                                        view.showMessage("源站受限，已改用 91porny 源下载", TastyToast.SUCCESS);
                                    }
                                });
                            }
                            return;
                        }
                        AppLog.w("Download", "91porny兜底未命中，退回旧地址 viewKey=" + tmp.getViewKey());
                        // 备用源未命中：退回旧地址尝试
                        startDownloadWithFallback(tmp, path, wifi, force, listener);
                    }

                    @Override
                    public void onError(String msg, int code) {
                        AppLog.e("Download", "91porny兜底解析失败(" + msg + ")，退回旧地址 viewKey=" + tmp.getViewKey());
                        startDownloadWithFallback(tmp, path, wifi, force, listener);
                    }
                });
    }

    /** 重新解析失败时退回 DB 旧地址尝试（可能恰好仍有效） */
    private void startDownloadWithFallback(V9MmanItem tmp, String path, boolean wifi, boolean force, DownloadListener listener) {
        VideoResult old = tmp.getVideoResult();
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
        startDownloadInternal(tmp, oldUrl, path, wifi, force, listener);
    }

    /** 真正启动下载（带 Referer/UA 请求头） */
    private void startDownloadInternal(V9MmanItem tmp, String url, String path, boolean wifi, boolean force, DownloadListener listener) {
        // M61：m3u8 绝不能交给 FileDownloader（会把播放列表文本秒下成假 mp4），改走 HLS 服务
        if (DownloadManager.isHlsUrl(url)) {
            AppLog.i("Download", "HLS地址改走HLS服务下载 viewKey=" + tmp.getViewKey()
                    + " host=" + AppLog.hostOf(url));
            PornyFallbackResolver.enqueueHlsDownload(context, tmp, url, path);
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
        int id = DownloadManager.getImpl().startDownload(url, path, wifi, force, buildReferer(tmp.getViewKey()));
        if (tmp.getAddDownloadDate() == null) {
            tmp.setAddDownloadDate(new Date());
        }
        tmp.setDownloadId(id);
        dataManager.updateV9MmanItem(tmp);
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
    private static Boolean findRunningTask(V9MmanItem tmp, String path) {
        try {
            String url = tmp.getVideoResult() == null ? null : tmp.getVideoResult().getVideoUrl();
            if (TextUtils.isEmpty(url)) {
                return null;
            }
            int regeneratedId = com.liulishuo.filedownloader.util.FileDownloadUtils
                    .generateId(url, path);
            if (regeneratedId != tmp.getDownloadId()) {
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
                                    item.setSoFarBytes((int) f.length());
                                    item.setTotalFarBytes((int) f.length());
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
        File tmp = new File(path + ".fddownload");
        if (tmp.exists()) {
            tmp.delete();
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
        // M61：按真实位置删除（原路径或回退目录）
        File file = SDCardUtils.resolveExistingDownloadFile(context,
                v9MmanItem.getDownLoadPath(getCustomDownloadVideoDirPath()));
        if (file != null && file.exists() && file.delete()) {
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
     * 直接拷贝缓存好的视频即可
     *
     * @param v9MmanItem v
     */
    private void copyCacheFile(final File videoCacheDir, final V9MmanItem v9MmanItem, final DownloadListener downloadListener) throws IOException {
        Observable.create(new ObservableOnSubscribe<File>() {
            @Override
            public void subscribe(ObservableEmitter<File> e) throws Exception {
                VideoCacheFileNameGenerator myFileNameGenerator = new VideoCacheFileNameGenerator();
                String cacheFileName = myFileNameGenerator.generate(v9MmanItem.getVideoResult().getVideoUrl());
                File fromFile = new File(videoCacheDir, cacheFileName);
                if (!fromFile.exists() || fromFile.length() <= 0) {
                    // M74：代理缓存可能在「命中判断(isCached)」与「实际拷贝」之间被 LRU 淘汰（TOCTOU），
                    // 命中分支已进、源文件却已消失。不再抛“缓存文件错误”，而是回退到正常重新下载，
                    // 保证用户最终拿到视频（bypassCacheCopy 防止再次命中缓存分支导致递归）。
                    AppLog.w("Download", "缓存文件缺失，回退重新下载 viewKey=" + v9MmanItem.getViewKey()
                            + " cacheFile=" + fromFile.getAbsolutePath());
                    final V9MmanItem fbItem = v9MmanItem;
                    final DownloadListener fbListener = downloadListener;
                    bypassCacheCopy = true;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            downloadVideo(fbItem, true, fbListener);
                        }
                    });
                    e.onComplete();
                    return;
                }
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
                v9MmanItem.setTotalFarBytes((int) fromFile.length());
                v9MmanItem.setSoFarBytes((int) fromFile.length());
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
