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
        dataManager.searchMman9Videos(viewType, page, searchType, searchId, sort)
                .map(new Function<BaseResult<List<V9MmanItem>>, List<V9MmanItem>>() {
                    @Override
                    public List<V9MmanItem> apply(BaseResult<List<V9MmanItem>> baseResult) throws Exception {
                        if (baseResult.getCode() == BaseResult.ERROR_CODE) {
                            throw new MessageException(baseResult.getMessage());
                        }
                        if (page == 1) {
                            totalPage = baseResult.getTotalPage();
                        }
                        return baseResult.getData();
                    }
                })
                .retryWhen(new RetryWhenProcess(2))
                .compose(RxSchedulersHelper.<List<V9MmanItem>>ioMainThread())
                .compose(provider.<List<V9MmanItem>>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<List<V9MmanItem>>() {
                    @Override
                    public void onBegin(Disposable d) {
                        ifViewAttached(new ViewAction<SearchView>() {
                            @Override
                            public void run(@NonNull SearchView view) {
                                if (page == 1 && pullToRefresh) {
                                    view.showLoading(pullToRefresh);
                                }
                            }
                        });
                    }

                    @Override
                    public void onSuccess(final List<V9MmanItem> v9MmanItems) {
                        ifViewAttached(new ViewAction<SearchView>() {
                            @Override
                            public void run(@NonNull SearchView view) {
                                if (page == 1) {
                                    view.setData(v9MmanItems);
                                    view.showContent();
                                } else {
                                    view.loadMoreDataComplete();
                                    view.setMoreData(v9MmanItems);
                                }
                                //已经最后一页了
                                if (page == totalPage) {
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
                        ifViewAttached(new ViewAction<SearchView>() {
                            @Override
                            public void run(@NonNull SearchView view) {
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
