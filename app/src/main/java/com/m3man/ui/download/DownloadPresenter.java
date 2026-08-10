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
import com.m3man.rxjava.CallBackWrapper;
import com.m3man.rxjava.RxSchedulersHelper;
import com.m3man.utils.AppCacheUtils;
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

    @Override
    public void downloadVideo(V9MmanItem v9MmanItem, boolean isForceReDownload, DownloadListener downloadListener) {
        V9MmanItem tmp = dataManager.findV9MmanItemByViewKey(v9MmanItem.getViewKey());
        if (tmp == null || tmp.getVideoResultId() == 0) {
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
        //先检查文件
        File toFile = new File(tmp.getDownLoadPath(getCustomDownloadVideoDirPath()));
        if (toFile.exists() && toFile.length() > 0) {
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
        if (dataManager.isVideoCacheByProxy(videoResult.getVideoUrl())) {
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
        //检查当前状态
        if (tmp.getStatus() == FileDownloadStatus.progress && tmp.getDownloadId() != 0 && !isForceReDownload) {
            if (downloadListener != null) {
                downloadListener.onError("已经在下载了");
            } else {
                ifViewAttached(new ViewAction<DownloadView>() {
                    @Override
                    public void run(@NonNull DownloadView view) {
                        view.showMessage("已经在下载了", TastyToast.SUCCESS);
                    }
                });
            }
            return;
        }
        Logger.d("视频连接：" + videoResult.getVideoUrl());
        String path = v9MmanItem.getDownLoadPath(getCustomDownloadVideoDirPath());
        Logger.d(path);
        boolean isDownloadNeedWifi = dataManager.isDownloadVideoNeedWifi();
        // 91mman 视频分类源：直链带时效签名（st/f 参数），DB 里存的旧 URL 过期后 CDN 拒绝
        // （表现为进度 0% 无速度）。下载前先重新解析播放页拿新鲜 URL；其它源（91porny 等）直接用。
        if (!Parse91PornyVideo.SOURCE.equals(tmp.getSource())) {
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
                            tryPornyFallback(tmp, path, wifi, force, listener);
                            return;
                        }
                        startDownloadInternal(tmp, freshUrl, path, wifi, force, listener);
                    }

                    @Override
                    public void onError(String msg, int code) {
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
                            PornyFallbackResolver.applyPornyResult(dataManager, tmp, pornyResult);
                            PornyFallbackResolver.enqueueHlsDownload(context, tmp, pornyResult.getVideoUrl(), path);
                            if (listener != null) {
                                listener.onSuccess("源站受限，已改用分分钟源下载");
                            } else {
                                ifViewAttached(new ViewAction<DownloadView>() {
                                    @Override
                                    public void run(@NonNull DownloadView view) {
                                        view.showMessage("源站受限，已改用分分钟源下载", TastyToast.SUCCESS);
                                    }
                                });
                            }
                            return;
                        }
                        // 备用源未命中：退回旧地址尝试
                        startDownloadWithFallback(tmp, path, wifi, force, listener);
                    }

                    @Override
                    public void onError(String msg, int code) {
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

    /** 91mman 源下载 Referer：指向播放页，部分 CDN 校验该头 */
    private String buildReferer(String viewKey) {
        try {
            String addr = dataManager.getMman9VideoAddress();
            if (TextUtils.isEmpty(addr)) {
                return null;
            }
            return addr + "view_video.php?viewkey=" + viewKey;
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
                                File f = new File(item.getDownLoadPath(customDir));
                                if (SDCardUtils.isDownloadFileComplete(f, item.getTotalFarBytes())) {
                                    item.setStatus(FileDownloadStatus.completed);
                                    item.setProgress(100);
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
    public void deleteDownloadingTask(V9MmanItem v9MmanItem) {
        String path = v9MmanItem.getDownLoadPath(getCustomDownloadVideoDirPath());
        // 1) 尽量通过 FileDownloader 暂停并清除（需服务已连接）
        try {
            if (FileDownloader.getImpl().isServiceConnected()) {
                FileDownloader.getImpl().pause(v9MmanItem.getDownloadId());
                FileDownloader.getImpl().clear(v9MmanItem.getDownloadId(), path);
            }
        } catch (Exception ignored) {
        }
        // 2) 兜底：直接删除目标文件及其临时文件（不依赖下载服务，确保“正在下载”也能删除）
        deleteFileWithTemp(path);
        v9MmanItem.setDownloadId(0);
        dataManager.updateV9MmanItem(v9MmanItem);
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
        File file = new File(v9MmanItem.getDownLoadPath(getCustomDownloadVideoDirPath()));
        if (file.delete()) {
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
                    e.onError(new Exception("缓存文件错误，无法拷贝"));
                }
                e.onNext(fromFile);
                e.onComplete();
            }
        }).map(new Function<File, V9MmanItem>() {
            @Override
            public V9MmanItem apply(File fromFile) throws Exception {
                File toFile = new File(v9MmanItem.getDownLoadPath(getCustomDownloadVideoDirPath()));
                if (toFile.exists() && toFile.length() > 0) {
                    throw new Exception("已经下载过了");
                } else {
                    if (!toFile.createNewFile()) {
                        throw new Exception("创建文件失败");
                    }
                }
                FileUtils.copyFile(fromFile, toFile);
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

    public interface DownloadListener {
        void onSuccess(String message);

        void onError(String message);
    }
}
