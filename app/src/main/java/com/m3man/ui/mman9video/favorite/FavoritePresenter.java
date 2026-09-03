package com.m3man.ui.mman9video.favorite;

import androidx.lifecycle.Lifecycle;
import androidx.annotation.NonNull;
import android.os.Build;
import android.text.TextUtils;

import com.hannesdorfmann.mosby3.mvp.MvpBasePresenter;
import com.sdsmdg.tastytoast.TastyToast;
import com.trello.rxlifecycle2.LifecycleProvider;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.model.BaseResult;
import com.m3man.data.model.User;
import com.m3man.exception.ApiException;
import com.m3man.rxjava.CallBackWrapper;
import com.m3man.rxjava.RetryWhenProcess;
import com.m3man.rxjava.RxSchedulersHelper;
import com.m3man.MyApplication;
import com.m3man.utils.SDCardUtils;
import com.m3man.utils.MediaStoreArchiver;

import java.io.File;
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
 * @date 2017/11/25
 * @describe
 */
public class FavoritePresenter extends MvpBasePresenter<FavoriteView> implements IFavorite {
    private static final String TAG = FavoriteListener.class.getSimpleName();

    private Integer totalPage = 1;
    private int page = 1;
    private LifecycleProvider<Lifecycle.Event> provider;
    /**
     * 本次强制刷新过那下面的请求也一起刷新
     */
    private boolean cleanCache = false;

    private DataManager dataManager;

    @Inject
    public FavoritePresenter(DataManager dataManager, LifecycleProvider<Lifecycle.Event> provider) {
        this.dataManager = dataManager;
        this.provider = provider;
    }

    @Override
    public void favorite(String uId, String videoId, String ownnerId) {
        favorite(uId, videoId, ownnerId, null);
    }

    public void favorite(String uId, String videoId, String ownnerId, final FavoriteListener favoriteListener) {

        dataManager.favoriteMman9Video(uId, videoId, ownnerId)
                .retryWhen(new RetryWhenProcess(RetryWhenProcess.PROCESS_TIME))
                .compose(RxSchedulersHelper.<String>ioMainThread())
                .compose(provider.<String>bindUntilEvent(Lifecycle.Event.ON_STOP))
                .subscribe(new CallBackWrapper<String>() {
                    @Override
                    public void onBegin(Disposable d) {

                    }

                    @Override
                    public void onSuccess(final String msg) {
                        if (favoriteListener != null) {
                            favoriteListener.onSuccess(msg);

                        } else {
                            ifViewAttached(new ViewAction<FavoriteView>() {
                                @Override
                                public void run(@NonNull FavoriteView view) {
                                    view.showMessage(msg, TastyToast.SUCCESS);
                                }
                            });
                        }
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        if (code == ApiException.Error.NULLPOINTER_EXCEPTION) {
                            final String message = "收藏失败";
                            if (favoriteListener != null) {
                                favoriteListener.onError(message);
                            } else {
                                ifViewAttached(new ViewAction<FavoriteView>() {
                                    @Override
                                    public void run(@NonNull FavoriteView view) {
                                        view.showMessage(message, TastyToast.ERROR);
                                    }
                                });
                            }
                        } else {
                            if (favoriteListener != null) {
                                favoriteListener.onError(msg);
                            } else {
                                ifViewAttached(new ViewAction<FavoriteView>() {
                                    @Override
                                    public void run(@NonNull FavoriteView view) {
                                        view.showMessage(msg, TastyToast.ERROR);
                                    }
                                });
                            }
                        }
                    }
                });
    }


    @Override
    public void loadRemoteFavoriteData(final boolean pullToRefresh) {
        //如果刷新则重置页数
        if (pullToRefresh) {
            page = 1;
            cleanCache = true;
        }
        //RxCache条件区别
        String condition = null;
        final User user = dataManager.getUser();
        if (user != null) {
            condition = user.getUserName();
        }
        if (TextUtils.isEmpty(condition)) {
            ifViewAttached(new ViewAction<FavoriteView>() {
                @Override
                public void run(@NonNull FavoriteView view) {
                    if (user != null) {
                       // Bugsnag.notify(new Throwable(TAG + " user info: " + user.toString()), Severity.WARNING);
                    }
                    view.showError("用户信息不完整，请重新登录后重试！");
                }
            });
            return;
        }
        dataManager.loadMman9MyFavoriteVideos(condition, page, cleanCache)
                .map(new Function<BaseResult<List<V9MmanItem>>, List<V9MmanItem>>() {
                    @Override
                    public List<V9MmanItem> apply(BaseResult<List<V9MmanItem>> baseResult) throws Exception {
                        if (page == 1) {
                            // M97：totalPage 判空兜底（与 VideoListPresenter 同款写法）——
                            // 服务端缺字段/返回 0 时保持 1，避免 page>=totalPage 误判“没有更多”
                            Integer tp = baseResult.getTotalPage();
                            totalPage = (tp == null || tp < 1) ? 1 : tp;
                            // M73：本次已绕过缓存，后续页恢复走缓存
                            cleanCache = false;
                        }
                        return baseResult.getData();
                    }
                })
                .retryWhen(new RetryWhenProcess(RetryWhenProcess.PROCESS_TIME))
                .compose(RxSchedulersHelper.<List<V9MmanItem>>ioMainThread())
                .compose(provider.<List<V9MmanItem>>bindUntilEvent(Lifecycle.Event.ON_STOP))
                .subscribe(new CallBackWrapper<List<V9MmanItem>>() {
                    @Override
                    public void onBegin(Disposable d) {
                        //首次加载显示加载页
                        ifViewAttached(new ViewAction<FavoriteView>() {
                            @Override
                            public void run(@NonNull FavoriteView view) {
                                if (page == 1 && !pullToRefresh) {
                                    view.showLoading(pullToRefresh);
                                }
                            }
                        });
                    }

                    @Override
                    public void onSuccess(final List<V9MmanItem> v9MmanItems) {
                        ifViewAttached(new ViewAction<FavoriteView>() {
                            @Override
                            public void run(@NonNull FavoriteView view) {
                                if (page == 1) {
                                    view.setFavoriteData(v9MmanItems);
                                    view.showContent();
                                } else {
                                    view.loadMoreDataComplete();
                                    view.setMoreData(v9MmanItems);
                                }
                                //已经最后一页了
                                if (page >= totalPage) {
                                    view.noMoreData();
                                } else {
                                    page++;
                                }
                            }
                        });
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        //首次加载失败，显示重试页
                        ifViewAttached(new ViewAction<FavoriteView>() {
                            @Override
                            public void run(@NonNull FavoriteView view) {
                                if (page == 1) {
                                    view.showError(msg);
                                } else {
                                    view.loadMoreFailed();
                                }
                            }
                        });
                    }
                });
    }

    @Override
    public void deleteFavorite(String rvid) {
        dataManager.deleteMman9MyFavoriteVideo(rvid)
                .retryWhen(new RetryWhenProcess(RetryWhenProcess.PROCESS_TIME))
                .compose(RxSchedulersHelper.<List<V9MmanItem>>ioMainThread())
                .compose(provider.<List<V9MmanItem>>bindUntilEvent(Lifecycle.Event.ON_STOP))
                .subscribe(new CallBackWrapper<List<V9MmanItem>>() {
                    @Override
                    public void onBegin(Disposable d) {
                        ifViewAttached(new ViewAction<FavoriteView>() {
                            @Override
                            public void run(@NonNull FavoriteView view) {
                                view.showDeleteDialog();
                            }
                        });
                    }

                    @Override
                    public void onSuccess(final List<V9MmanItem> v9MmanItemList) {
                        ifViewAttached(new ViewAction<FavoriteView>() {
                            @Override
                            public void run(@NonNull FavoriteView view) {
                                //顺序很重要，涉及缓存
                                view.setFavoriteData(v9MmanItemList);
                                view.deleteFavoriteSucc("删除成功");
                            }
                        });
                        // M73：删除成功后强制逐出收藏页缓存（cleanCache=true 触发 EvictDynamicKey），
                        // 否则 15 分钟内重新进入收藏页会从 RxCache 磁盘缓存读到已删条目
                        loadRemoteFavoriteData(true);
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        ifViewAttached(new ViewAction<FavoriteView>() {
                            @Override
                            public void run(@NonNull FavoriteView view) {
                                view.deleteFavoriteError(msg);
                            }
                        });
                    }
                })
        ;
    }

    @Override
    public void exportData(final boolean onlyUrl) {
        Observable.create(new ObservableOnSubscribe<List<V9MmanItem>>() {
            @Override
            public void subscribe(ObservableEmitter<List<V9MmanItem>> e) throws Exception {
                List<V9MmanItem> v9MmanItems = dataManager.loadV9MmanItems();
                e.onNext(v9MmanItems);
                e.onComplete();
            }
        }).map(new Function<List<V9MmanItem>, String>() {
            @Override
            public String apply(List<V9MmanItem> v9MmanItems) throws Exception {
                StringBuilder sb = new StringBuilder();
                if (onlyUrl) {
                    for (V9MmanItem v9MmanItem : v9MmanItems) {
                        String url = v9MmanItem.getVideoResult() != null ? v9MmanItem.getVideoResult().getVideoUrl() : null;
                        if (TextUtils.isEmpty(url)) {
                            continue;
                        }
                        sb.append(url).append("\r\n\r\n");
                    }
                } else {
                    for (V9MmanItem v9MmanItem : v9MmanItems) {
                        String title = v9MmanItem.getTitle();
                        String videoUrl = v9MmanItem.getVideoResult() != null ? v9MmanItem.getVideoResult().getVideoUrl() : null;
                        if (TextUtils.isEmpty(videoUrl)) {
                            continue;
                        }
                        sb.append(title).append("\r\n").append(videoUrl).append("\r\n\r\n");
                    }
                }
                String content = sb.toString();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // V13：Scoped Storage，导出写到公共 下载/3mman（系统文件管理器可见）
                    if (MediaStoreArchiver.archiveTextToDownloads(MyApplication.getInstance(),
                            content, "m3man收藏导出.txt")) {
                        return "导出成功，已保存到 下载/3mman";
                    }
                    throw new Exception("导出失败,写入系统下载目录失败");
                }
                // API<29（Android 9 及以下，仍可写老公共路径）：写 /sdcard/3mman/export.txt
                File file = new File(SDCardUtils.EXPORT_FILE);
                // M74：确保父目录存在（与下载路径一致）。EXPORT_FILE 位于 /3mman/ 下，
                // 此前直接 createNewFile 而父目录可能未创建，导致 IOException: No such file or directory。
                File exportParent = file.getParentFile();
                if (exportParent != null && !exportParent.exists() && !exportParent.mkdirs()) {
                    throw new Exception("导出失败,无法创建导出目录");
                }
                if (file.exists() && !file.delete()) {
                    throw new Exception("导出失败,因为删除原文件失败了");
                }
                if (!file.createNewFile()) {
                    throw new Exception("导出失败,创建新文件失败了");
                }
                FileUtils.writeChars(file, "UTF-8", content);
                return "导出成功";
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(provider.<String>bindUntilEvent(Lifecycle.Event.ON_STOP))
                .subscribe(new CallBackWrapper<String>() {
                    @Override
                    public void onBegin(Disposable d) {

                    }

                    @Override
                    public void onSuccess(final String s) {
                        ifViewAttached(new ViewAction<FavoriteView>() {
                            @Override
                            public void run(@NonNull FavoriteView view) {
                                view.showMessage(s, TastyToast.SUCCESS);
                            }
                        });
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        ifViewAttached(new ViewAction<FavoriteView>() {
                            @Override
                            public void run(@NonNull FavoriteView view) {
                                view.showMessage(msg, TastyToast.ERROR);
                            }
                        });
                    }
                });
    }

    @Override
    public int getPlayBackEngine() {
        return dataManager.getPlaybackEngine();
    }

    @Override
    public boolean isFavoriteNeedRefresh() {
        return dataManager.isFavoriteNeedRefresh();
    }

    /**
     * 本地收藏：把 91porny 视频落库并标记收藏。
     * 需要在 IO 线程调用。
     */
    public boolean addLocalFavorite(V9MmanItem v9MmanItem) {
        if (v9MmanItem == null) {
            return false;
        }
        // 同步持久化来源标记，避免重启后无法识别
        if (v9MmanItem.getSourceName() == null) {
            v9MmanItem.setSourceName(v9MmanItem.getSource());
        }
        if (v9MmanItem.getSourceName() == null) {
            v9MmanItem.setSourceName(com.m3man.parser.Parse91PornyVideo.SOURCE);
        }
        v9MmanItem.setIsLocalFavorite(true);
        if (v9MmanItem.getVideoResult() != null) {
            dataManager.saveVideoResult(v9MmanItem.getVideoResult());
        }
        dataManager.saveV9MmanItem(v9MmanItem);
        return true;
    }

    /**
     * 取消本地收藏（保留播放记录，仅取消收藏标记）。
     * 需要在 IO 线程调用。
     */
    public boolean deleteLocalFavorite(V9MmanItem v9MmanItem) {
        if (v9MmanItem == null) {
            return false;
        }
        v9MmanItem.setIsLocalFavorite(false);
        dataManager.updateV9MmanItem(v9MmanItem);
        return true;
    }

    /**
     * 查询本地收藏列表。需要在 IO 线程调用。
     */
    public List<V9MmanItem> loadLocalFavoriteItems() {
        return dataManager.loadLocalFavoriteItems();
    }

    /** M42：是否本地收藏模式（true=本地收藏，与分分钟合并展示；false=服务器收藏） */
    public boolean isLocalFavoriteMode() {
        return dataManager.isLocalFavoriteMode();
    }

    @Override
    public void setFavoriteNeedRefresh(boolean needRefresh) {
        dataManager.setFavoriteNeedRefresh(needRefresh);
    }

    public interface FavoriteListener {
        void onSuccess(String message);

        void onError(String message);
    }
}
