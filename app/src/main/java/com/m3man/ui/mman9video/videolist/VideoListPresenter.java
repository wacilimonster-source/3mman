package com.m3man.ui.mman9video.videolist;

import android.arch.lifecycle.Lifecycle;

import com.hannesdorfmann.mosby3.mvp.MvpBasePresenter;
import com.orhanobut.logger.Logger;
import com.trello.rxlifecycle2.LifecycleProvider;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.rxjava.CallBackWrapper;
import com.m3man.rxjava.RetryWhenProcess;
import com.m3man.rxjava.RxSchedulersHelper;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;

/**
 * @author flymegoc
 * @date 2017/11/16
 */

public class VideoListPresenter extends MvpBasePresenter<VideoListView> implements IVideoList {

    private static final String TAG = VideoListFragment.class.getSimpleName();
    private Integer totalPage = 1;
    private int page = 1;
    // M73：请求在途标志，防刷新/加载更多/跳页并发乱序
    private volatile boolean isLoading = false;
    private LifecycleProvider<Lifecycle.Event> provider;
    /**
     * 本次强制刷新过那下面的请求也一起刷新
     */
    private boolean isLoadMoreCleanCache = false;
    private DataManager dataManager;
    private String m;

    @Inject
    public VideoListPresenter(LifecycleProvider<Lifecycle.Event> provider, DataManager dataManager) {
        this.provider = provider;
        this.dataManager = dataManager;
        Logger.t(TAG).d("VideoListPresenter init______________");
    }

    @Override
    public void loadVideoListData(final boolean pullToRefresh, boolean cleanCache, String category, int skipPage) {
        String viewType = "basic";
        //如果刷新则重置页数
        if (pullToRefresh) {
            page = 1;
            isLoadMoreCleanCache = true;
        }
        if (skipPage > 0) {
            page = skipPage;
        }
        // M73：请求在途守卫——刷新/加载更多/跳页并发时响应乱序会错插数据、页码错乱
        if (isLoading) {
            return;
        }
        isLoading = true;
        if ("watch".equalsIgnoreCase(category)) {
            //最近更新
            action(dataManager.loadMman9VideoRecentUpdates(category, page, cleanCache, isLoadMoreCleanCache)
                    .map(baseResult -> {
                        // M73：totalPage 不再只在 page==1 时更新——首次进入即跳第 N 页时
                        // 旧逻辑 totalPage 保持 1，page>=totalPage 恒成立误判"没有更多"
                        if (baseResult.getTotalPage() != null && baseResult.getTotalPage() > 0) {
                            totalPage = baseResult.getTotalPage();
                        }
                        return baseResult.getData();
                    }), pullToRefresh, skipPage);
        } else {
            //其他栏目
            if (!"top1".equals(category)) {
                m = null;
            } else {
                //上月最热
                category="top";
                m = "-1";
            }
            Observable<List<V9MmanItem>> ob = dataManager.loadMman9VideoByCategory(category, viewType, page, m, cleanCache, isLoadMoreCleanCache)
                    .map(baseResult -> {
                        // M73：同上，任意页响应都刷新 totalPage（含跳页场景）
                        if (baseResult.getTotalPage() != null && baseResult.getTotalPage() > 0) {
                            totalPage = baseResult.getTotalPage();
                        }
                        return baseResult.getData();
                    });
            action(ob, pullToRefresh, skipPage);
        }
    }

    @Override
    public int getPlayBackEngine() {
        return dataManager.getPlaybackEngine();
    }

    private void action(Observable<List<V9MmanItem>> observable, boolean pullToRefresh, int skipPage) {
        observable.retryWhen(new RetryWhenProcess(RetryWhenProcess.PROCESS_TIME))
                .compose(RxSchedulersHelper.ioMainThread())
                .compose(provider.bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<List<V9MmanItem>>() {
                    @Override
                    public void onBegin(Disposable d) {
                        //首次加载显示加载页
                        ifViewAttached(view -> {
                            if (page == 1 && !pullToRefresh && skipPage != 1) {
                                view.showLoading(pullToRefresh);
                            }
                            if (skipPage > 0) {
                                view.showSkipPageLoading();
                            }
                        });
                    }

                    @Override
                    public void onSuccess(final List<V9MmanItem> v9MmanItems) {
                        isLoading = false;
                        ifViewAttached(view -> {
                            if (page == 1 || skipPage > 0) {
                                view.setData(v9MmanItems);
                                view.showContent();
                                if (page == 1) {
                                    view.setPageData(getPageList());
                                }
                                view.updateCurrentPage(page);
                            } else {
                                view.loadMoreDataComplete();
                                view.setMoreData(v9MmanItems);
                                view.updateCurrentPage(page);
                            }
                            if (skipPage > 0) {
                                view.hideSkipPageLoading();
                            }
                            //已经最后一页了
                            if (page >= totalPage) {
                                view.noMoreData();
                            } else {
                                page++;
                            }
                        });
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        isLoading = false;
                        //首次加载失败，显示重试页
                        ifViewAttached(view -> {
                            if (page == 1) {
                                view.showError(msg);
                            } else {
                                view.loadMoreFailed();
                            }
                            if (skipPage > 0) {
                                view.hideSkipPageLoading();
                            }
                        });
                    }
                });
    }

    public List<Integer> getPageList() {
        List<Integer> pageList = new ArrayList<>();
        for (int i = 1; i <= totalPage; i++) {
            pageList.add(i);
        }
        return pageList;
    }

    public int getPage() {
        return page - 1;
    }
}
