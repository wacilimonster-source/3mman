package com.m3man.ui.mman9video.search;

import android.arch.lifecycle.Lifecycle;
import android.support.annotation.NonNull;

import com.hannesdorfmann.mosby3.mvp.MvpBasePresenter;
import com.trello.rxlifecycle2.LifecycleProvider;
import com.m3man.data.DataManager;
import com.m3man.data.model.BaseResult;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.exception.MessageException;
import com.m3man.rxjava.CallBackWrapper;
import com.m3man.rxjava.RetryWhenProcess;
import com.m3man.rxjava.RxSchedulersHelper;

import java.util.List;

import javax.inject.Inject;

import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;

/**
 * @author flymegoc
 * @date 2018/1/7
 */

public class SearchPresenter extends MvpBasePresenter<SearchView> implements ISearch {

    private static final String TAG = SearchPresenter.class.getSimpleName();
    private LifecycleProvider<Lifecycle.Event> provider;
    private int page = 1;
    private Integer totalPage;
    /** M100：在途搜索请求，新搜索发起前先取消，防止旧响应晚到串位覆盖新结果 */
    private Disposable inFlightSearch;
    /** 请求代际：取消旧订阅后仍可能有回调排队，代际校验阻止其改写新搜索状态。 */
    private volatile long searchGeneration;
    private DataManager dataManager;

    @Inject
    public SearchPresenter(LifecycleProvider<Lifecycle.Event> provider, DataManager dataManager) {
        this.provider = provider;
        this.dataManager = dataManager;
    }

    @Override
    public void searchVideos(String searchId, String sort, final boolean pullToRefresh) {
        String viewType = "basic";
        String searchType = "search_videos";
        if (pullToRefresh) {
            page = 1;
        }
        // M100：串位修复——发起本次搜索前先取消上一条在途请求（保留 ON_DESTROY 绑定）
        disposeInFlightSearch();
        final long generation = ++searchGeneration;
        final int requestedPage = page;
        dataManager.searchMman9Videos(viewType, requestedPage, searchType, searchId, sort)
                .map(new Function<BaseResult<List<V9MmanItem>>, List<V9MmanItem>>() {
                    @Override
                    public List<V9MmanItem> apply(BaseResult<List<V9MmanItem>> baseResult) throws Exception {
                        if (baseResult.getCode() == BaseResult.ERROR_CODE) {
                            throw new MessageException(baseResult.getMessage());
                        }
                        // M73：任意页响应都刷新 totalPage，避免首屏后站点总页数变化/初始为 null 时误判
                        // M100：totalPage 判空兜底——接口未返回或非法时按 1 处理。
                        Integer tp = baseResult.getTotalPage();
                        totalPage = (tp == null || tp < 1) ? 1 : tp;
                        return baseResult.getData();
                    }
                })
                .retryWhen(new RetryWhenProcess(2))
                .compose(RxSchedulersHelper.<List<V9MmanItem>>ioMainThread())
                .compose(provider.<List<V9MmanItem>>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<List<V9MmanItem>>() {
                    @Override
                    public void onBegin(Disposable d) {
                        // M100：记录本次在途请求，供下一次搜索发起前取消
                        if (generation != searchGeneration) {
                            d.dispose();
                            return;
                        }
                        inFlightSearch = d;
                        ifViewAttached(new ViewAction<SearchView>() {
                            @Override
                            public void run(@NonNull SearchView view) {
                                if (generation == searchGeneration && requestedPage == 1 && pullToRefresh) {
                                    view.showLoading(pullToRefresh);
                                }
                            }
                        });
                    }

                    @Override
                    public void onSuccess(final List<V9MmanItem> v9MmanItems) {
                        if (generation != searchGeneration) {
                            return;
                        }
                        ifViewAttached(new ViewAction<SearchView>() {
                            @Override
                            public void run(@NonNull SearchView view) {
                                if (generation != searchGeneration) {
                                    return;
                                }
                                if (requestedPage == 1) {
                                    view.setData(v9MmanItems);
                                    view.showContent();
                                } else {
                                    view.loadMoreDataComplete();
                                    view.setMoreData(v9MmanItems);
                                }
                                if (requestedPage >= totalPage) {
                                    view.noMoreData();
                                } else {
                                    page = requestedPage + 1;
                                }
                                view.showContent();
                            }
                        });
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        if (generation != searchGeneration) {
                            return;
                        }
                        //首次加载失败，显示重试页
                        ifViewAttached(new ViewAction<SearchView>() {
                            @Override
                            public void run(@NonNull SearchView view) {
                                if (generation != searchGeneration) {
                                    return;
                                }
                                if (requestedPage == 1) {
                                    view.showError(msg);
                                } else {
                                    view.loadMoreFailed();
                                }
                            }
                        });
                    }
                });
    }

    /** M100：取消在途搜索请求（若存在） */
    private void disposeInFlightSearch() {
        if (inFlightSearch != null && !inFlightSearch.isDisposed()) {
            inFlightSearch.dispose();
        }
        inFlightSearch = null;
    }

    @Override
    public int getPlayBackEngine() {
        return dataManager.getPlaybackEngine();
    }

    @Override
    public boolean isFirstInSearchMman91Video() {
        boolean isFirst = dataManager.isFirstInSearchMman91Video();
        if (isFirst) {
            dataManager.setFirstInSearchMman91Video(false);
        }
        return isFirst;
    }
}
