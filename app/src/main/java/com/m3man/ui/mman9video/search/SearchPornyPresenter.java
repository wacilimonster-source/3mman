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
 * 91porny 搜索 Presenter（首版仅搜索，播放后续）
 */
public class SearchPornyPresenter extends MvpBasePresenter<SearchView> implements ISearch {

    private static final String TAG = SearchPornyPresenter.class.getSimpleName();
    private LifecycleProvider<Lifecycle.Event> provider;
    private int page = 1;
    private Integer totalPage;
    private DataManager dataManager;

    @Inject
    public SearchPornyPresenter(LifecycleProvider<Lifecycle.Event> provider, DataManager dataManager) {
        this.provider = provider;
        this.dataManager = dataManager;
    }

    /**
     * 兼容 ISearch 的旧接口（保留但不再使用）。
     */
    @Override
    public void searchVideos(String searchId, String sort, final boolean pullToRefresh) {
        searchVideos(searchId, "", "", "", pullToRefresh);
    }

    /**
     * 带筛选条件的搜索入口。
     *
     * @param searchId      关键词
     * @param sort          排序（默认/最新/最热）
     * @param time          发布时间（全部/1星期内/.../1年内）
     * @param views         播放量（全部/>1000/>5000/>1万/>5万/>10万）
     * @param pullToRefresh 是否下拉刷新
     */
    public void searchVideos(String searchId, String sort, String time, String views, final boolean pullToRefresh) {
        if (pullToRefresh) {
            page = 1;
        }
        dataManager.searchPornyVideos(searchId, page, normalizeFilter(sort), normalizeFilter(time), normalizeFilter(views))
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
                                // 空结果直接判定为到底（应对分页总数估计偏大的情况）
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

    /**
     * 将 UI 上的中文选项映射为 91porny 接口能识别的参数值。
     * 已通过实际请求验证（2026-08-03）：
     *   排序：sort=new / sort=hot
     *   发布时间：time=week1 / week2 / month1 / month3 / halfyear / year1
     *   播放量：views=1000 / 5000 / 10000 / 50000 / 100000
     * 注：接口发布时间仅支持到 year1，无"1年-2年"等更长时段选项。
     */
    private static String normalizeFilter(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "全部".equals(trimmed) || "默认".equals(trimmed)) {
            return "";
        }
        // 发布时间 → 接口真实编码
        switch (trimmed) {
            case "1星期内":
                return "week1";
            case "2星期内":
                return "week2";
            case "1个月内":
                return "month1";
            case "3个月内":
                return "month3";
            case "半年内":
                return "halfyear";
            case "1年内":
                return "year1";
            default:
                break;
        }
        // 播放量 → 纯数字（去掉 > 前缀、万换算）
        if (trimmed.startsWith(">")) {
            String num = trimmed.substring(1);
            switch (num) {
                case "1000":
                    return "1000";
                case "5000":
                    return "5000";
                case "1万":
                    return "10000";
                case "5万":
                    return "50000";
                case "10万":
                    return "100000";
                default:
                    return "";
            }
        }
        // 排序 → 接口真实编码
        if ("最新".equals(trimmed)) {
            return "new";
        }
        if ("最热".equals(trimmed)) {
            return "hot";
        }
        return trimmed;
    }

    @Override
    public int getPlayBackEngine() {
        return dataManager.getPlaybackEngine();
    }

    @Override
    public boolean isFirstInSearchMman91Video() {
        return false;
    }
}
