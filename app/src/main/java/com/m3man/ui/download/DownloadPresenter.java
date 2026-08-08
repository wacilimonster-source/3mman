package com.m3man.ui.download;

import android.arch.lifecycle.Lifecycle;
import android.content.Context;
import android.support.annotation.NonNull;

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
import com.m3man.rxjava.CallBackWrapper;
import com.m3man.rxjava.RxSchedulersHelper;
import com.m3man.utils.AppCacheUtils;
import com.m3man.utils.DownloadManager;
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

    @Inject
    public DownloadPresenter(DataManager dataManager, LifecycleProvider<Lifecycle.Event> provider, @ApplicationContext Context context) {
        this.dataManager = dataManager;
        this.provider = provider;
        this.context = context;
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
        int id = DownloadManager.getImpl().startDownload(videoResult.getVideoUrl(), path, isDownloadNeedWifi, isForceReDownload);
        if (tmp.getAddDownloadDate() == null) {
            tmp.setAddDownloadDate(new Date());
        }
        tmp.setDownloadId(id);
        dataManager.updateV9MmanItem(tmp);
        if (downloadListener != null) {
            downloadListener.onSuccess("开始下载");
        } else {
            ifViewAttached(new ViewAction<DownloadView>() {
                @Override
                public void run(@NonNull DownloadView view) {
                    view.showMessage("开始下载", TastyToast.SUCCESS);
                }
            });
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
