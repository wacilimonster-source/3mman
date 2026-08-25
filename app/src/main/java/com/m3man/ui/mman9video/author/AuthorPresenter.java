package com.m3man.ui.mman9video.author;

import android.arch.lifecycle.Lifecycle;
import android.support.annotation.NonNull;
import android.text.TextUtils;

import com.hannesdorfmann.mosby3.mvp.MvpBasePresenter;
import com.orhanobut.logger.Logger;
import com.trello.rxlifecycle2.LifecycleProvider;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.AuthorFavorite;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.VideoResult;
import com.m3man.data.model.BaseResult;
import com.m3man.exception.MessageException;
import com.m3man.rxjava.CallBackWrapper;
import com.m3man.rxjava.RetryWhenProcess;
import com.m3man.rxjava.RxSchedulersHelper;

import java.util.Date;
import java.util.List;

import javax.inject.Inject;

import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;

/**
 * @author flymegoc
 * @date 2018/1/8
 */

public class AuthorPresenter extends MvpBasePresenter<AuthorView> implements IAuthor {

    private static final String TAG = AuthorPresenter.class.getSimpleName();
    private LifecycleProvider<Lifecycle.Event> provider;
    private int page = 1;
    private int pullCount = 0;
    private Integer totalPage;
    private boolean cleanCache;
    private DataManager dataManager;

    @Inject
    public AuthorPresenter(LifecycleProvider<Lifecycle.Event> provider, DataManager dataManager) {
        this.provider = provider;
        this.dataManager = dataManager;
        Logger.t(TAG).d("AuthorPresenter初始化了.....");
    }

    /**
     * 91porny 作者视频加载（authorId 为作者名，如 liguvipa）。
     * 作者页结构与搜索页一致，复用 parseSearchVideos 解析。
     */
    public void pornyAuthorVideos(String authorId, final boolean pullToRefresh) {
        if (pullToRefresh) {
            page = 1;
        }
        dataManager.loadPornyAuthorVideos(authorId, page)
                .map(new Function<BaseResult<List<V9MmanItem>>, List<V9MmanItem>>() {
                    @Override
                    public List<V9MmanItem> apply(BaseResult<List<V9MmanItem>> baseResult) throws Exception {
                        if (baseResult.getCode() == BaseResult.ERROR_CODE) {
                            throw new MessageException(baseResult.getMessage());
                        }
                        if (page == 1) {
                            totalPage = baseResult.getTotalPage();
                            // M73：本次刷新已绕过缓存，后续页恢复走缓存
                            cleanCache = false;
                        }
                        return baseResult.getData();
                    }
                })
                .retryWhen(new RetryWhenProcess(RetryWhenProcess.PROCESS_TIME))
                .compose(RxSchedulersHelper.<List<V9MmanItem>>ioMainThread())
                .compose(provider.<List<V9MmanItem>>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<List<V9MmanItem>>() {
                    @Override
                    public void onBegin(Disposable d) {
                        ifViewAttached(new ViewAction<AuthorView>() {
                            @Override
                            public void run(@NonNull AuthorView view) {
                                if (page == 1 && !pullToRefresh) {
                                    view.showLoading(pullToRefresh);
                                }
                            }
                        });
                    }

                    @Override
                    public void onSuccess(final List<V9MmanItem> v9MmanItems) {
                        ifViewAttached(new ViewAction<AuthorView>() {
                            @Override
                            public void run(@NonNull AuthorView view) {
                                if (v9MmanItems == null || v9MmanItems.isEmpty()) {
                                    view.noMoreData();
                                    view.showContent();
                                    return;
                                }
                                if (page == 1) {
                                    view.setData(v9MmanItems);
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
                                view.showContent();
                            }
                        });
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        ifViewAttached(new ViewAction<AuthorView>() {
                            @Override
                            public void run(@NonNull AuthorView view) {
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
    public void authorVideos(String uid, final boolean pullToRefresh) {
        String type = "public";
        if (pullToRefresh) {
            page = 1;
            // M73：cleanCache 只在本次刷新生效一次——此前置 true 永不复位，
            // 导致后续加载更多全部强制绕过缓存
            cleanCache = true;
        }
        dataManager.loadMman9authorVideos(uid, type, page, cleanCache)
                .map(new Function<BaseResult<List<V9MmanItem>>, List<V9MmanItem>>() {
                    @Override
                    public List<V9MmanItem> apply(BaseResult<List<V9MmanItem>> baseResult) throws Exception {
                        if (baseResult.getCode() == BaseResult.ERROR_CODE) {
                            throw new MessageException(baseResult.getMessage());
                        }
                        if (page == 1) {
                            totalPage = baseResult.getTotalPage();
                            // M73：本次刷新已绕过缓存，后续页恢复走缓存
                            cleanCache = false;
                        }
                        return baseResult.getData();
                    }
                })
                .retryWhen(new RetryWhenProcess(RetryWhenProcess.PROCESS_TIME))
                .compose(RxSchedulersHelper.<List<V9MmanItem>>ioMainThread())
                .compose(provider.<List<V9MmanItem>>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<List<V9MmanItem>>() {
                    @Override
                    public void onBegin(Disposable d) {
                        ifViewAttached(new ViewAction<AuthorView>() {
                            @Override
                            public void run(@NonNull AuthorView view) {
                                if (page == 1 && !pullToRefresh) {
                                    view.showLoading(pullToRefresh);
                                }
                            }
                        });
                    }

                    @Override
                    public void onSuccess(final List<V9MmanItem> v9MmanItems) {
                        ifViewAttached(new ViewAction<AuthorView>() {
                            @Override
                            public void run(@NonNull AuthorView view) {
                                if (page == 1) {
                                    view.setData(v9MmanItems);
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
                                view.showContent();
                            }
                        });
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        //首次加载失败，显示重试页
                        ifViewAttached(new ViewAction<AuthorView>() {
                            @Override
                            public void run(@NonNull AuthorView view) {
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

    /**
     * M92：UID 过期自愈——9mman 的作者 UID 是加密临时 token（DB 里缓存的会过期，
     * 请求 uvideos.php 返回 404）。重拉视频详情页获取新 ownerId，回写 DB 后重试作者列表。
     * 仅 9mman 源调用；由 Fragment 在首页加载失败时触发一次。
     */
    public void reloadOwnerThenAuthorVideos(V9MmanItem item, final boolean pullToRefresh) {
        if (item == null || TextUtils.isEmpty(item.getViewKey())) {
            ifViewAttached(new ViewAction<AuthorView>() {
                @Override
                public void run(@NonNull AuthorView view) {
                    view.showError("作者视频加载失败");
                }
            });
            return;
        }
        final String viewKey = item.getViewKey();
        // M92g：记录旧 ownerId，自愈成功后同步收藏行
        currentHealStaleKey = item.getVideoResult() != null
                ? item.getVideoResult().getOwnerId() : null;
        dataManager.loadMman9VideoUrl(viewKey)
                .map(new Function<VideoResult, VideoResult>() {
                    @Override
                    public VideoResult apply(VideoResult fresh) throws Exception {
                        if (fresh == null || TextUtils.isEmpty(fresh.getOwnerId())) {
                            throw new IllegalStateException("详情页未取得作者 UID");
                        }
                        return fresh;
                    }
                })
                .compose(RxSchedulersHelper.<VideoResult>ioMainThread())
                .compose(provider.<VideoResult>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<VideoResult>() {
                    @Override
                    public void onBegin(Disposable d) {
                    }

                    @Override
                    public void onSuccess(VideoResult fresh) {
                        // 新 ownerId 回写持久化，后续进入不再用过期 token
                        try {
                            dataManager.saveVideoResult(fresh);
                            item.setVideoResult(fresh);
                            dataManager.saveV9MmanItem(item);
                            // M92g：同步刷新收藏行的 authorKey（若该作者已被收藏），收藏直进不再失效
                            syncFavoriteAuthorKey(fresh.getOwnerId(), sourceOf(item));
                            Logger.t(TAG).d("自愈成功：新 ownerId=" + fresh.getOwnerId());
                        } catch (Exception e) {
                            Logger.t(TAG).e(TAG + ": 回写新 ownerId 失败 " + e.getMessage());
                        }
                        authorVideos(fresh.getOwnerId(), pullToRefresh);
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        Logger.t(TAG).e(TAG + ": 自愈刷新 ownerId 失败 viewKey=" + viewKey + " msg=" + msg);
                        ifViewAttached(new ViewAction<AuthorView>() {
                            @Override
                            public void run(@NonNull AuthorView view) {
                                view.showError("作者视频加载失败，请下拉重试");
                            }
                        });
                    }
                });
    }

    /**
     * M92g：作者收藏列表路径的自愈——无 V9MmanItem 实体，直接用关联作品 viewKey
     * 重拉详情换取新 ownerId；成功后同步收藏行 authorKey 再重试作者列表。
     */
    public void reloadOwnerFromViewKey(final String viewKey, final String staleAuthorKey,
                                       final String source, final boolean pullToRefresh) {
        if (TextUtils.isEmpty(viewKey)) {
            ifViewAttached(new ViewAction<AuthorView>() {
                @Override
                public void run(@NonNull AuthorView view) {
                    view.showError("作者视频加载失败");
                }
            });
            return;
        }
        currentHealStaleKey = staleAuthorKey;
        dataManager.loadMman9VideoUrl(viewKey)
                .map(new Function<VideoResult, VideoResult>() {
                    @Override
                    public VideoResult apply(VideoResult fresh) throws Exception {
                        if (fresh == null || TextUtils.isEmpty(fresh.getOwnerId())) {
                            throw new IllegalStateException("详情页未取得作者 UID");
                        }
                        return fresh;
                    }
                })
                .compose(RxSchedulersHelper.<VideoResult>ioMainThread())
                .compose(provider.<VideoResult>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<VideoResult>() {
                    @Override
                    public void onBegin(Disposable d) {
                    }

                    @Override
                    public void onSuccess(VideoResult fresh) {
                        try {
                            syncFavoriteAuthorKey(fresh.getOwnerId(), source);
                            Logger.t(TAG).d("收藏路径自愈成功：新 ownerId=" + fresh.getOwnerId());
                        } catch (Exception e) {
                            Logger.t(TAG).e(TAG + ": 收藏行 authorKey 同步失败 " + e.getMessage());
                        }
                        authorVideos(fresh.getOwnerId(), pullToRefresh);
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        Logger.t(TAG).e(TAG + ": 收藏路径自愈失败 viewKey=" + viewKey + " msg=" + msg);
                        ifViewAttached(new ViewAction<AuthorView>() {
                            @Override
                            public void run(@NonNull AuthorView view) {
                                view.showError("作者视频加载失败，请下拉重试");
                            }
                        });
                    }
                });
    }

    /** 把（可能过期的）authorKey 对应的收藏行更新为新 token；未收藏则跳过。IO 线程调用 */
    private void syncFavoriteAuthorKey(String newAuthorKey, String source) {
        if (TextUtils.isEmpty(newAuthorKey) || TextUtils.isEmpty(currentHealStaleKey)) {
            return;
        }
        AuthorFavorite row = dataManager.findAuthorFavorite(currentHealStaleKey, source);
        if (row != null && !newAuthorKey.equals(row.getAuthorKey())) {
            row.setAuthorKey(newAuthorKey);
            dataManager.updateAuthorFavorite(row);
        }
    }

    /** 播放页自愈路径的旧 key 传递（reloadOwnerThenAuthorVideos 调用前赋值） */
    private String currentHealStaleKey;

    /** 条目来源归一化：sourceName 优先，source 兜底，默认按 mman9 处理 */
    private static String sourceOf(V9MmanItem item) {
        String sn = item != null ? item.getSourceName() : null;
        if (TextUtils.isEmpty(sn) && item != null) {
            sn = item.getSource();
        }
        return AuthorFavorite.SOURCE_PORNY.equals(sn)
                ? AuthorFavorite.SOURCE_PORNY : AuthorFavorite.SOURCE_MMAN9;
    }

    @Override
    public boolean isUserLogin() {
        return dataManager.isUserLogin();
    }

    public int getPlayBackEngine() {
        return dataManager.getPlaybackEngine();
    }

    // ---- 作者收藏（本地数据库，需在 IO 线程调用） ----

    public boolean isAuthorFavorited(String authorKey, String source) {
        return dataManager.isAuthorFavorited(authorKey, source);
    }

    public void addAuthorFavorite(String authorKey, String authorName, String source) {
        // 入库前查重，避免竞态 / 多入口并发写入产生 (authorKey, source) 重复行
        if (dataManager.isAuthorFavorited(authorKey, source)) {
            return;
        }
        AuthorFavorite favorite = new AuthorFavorite();
        favorite.setAuthorKey(authorKey);
        favorite.setAuthorName(authorName);
        favorite.setSource(source);
        favorite.setFavoriteDate(new Date());
        dataManager.saveAuthorFavorite(favorite);
    }

    public void removeAuthorFavorite(String authorKey, String source) {
        AuthorFavorite favorite = dataManager.findAuthorFavorite(authorKey, source);
        if (favorite != null) {
            dataManager.deleteAuthorFavorite(favorite);
        }
    }
}
