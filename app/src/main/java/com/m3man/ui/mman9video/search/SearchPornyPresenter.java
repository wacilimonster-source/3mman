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
import com.m3man.utils.AppLog;

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
    /** M43：当前展示页（onSuccess 后记录，供底部页码栏高亮/定位） */
    private int currentPageShown = 1;
    private Integer totalPage;
    /** M100：在途搜索请求，新搜索/跳页发起前先取消，防止旧响应晚到串位覆盖新结果 */
    private Disposable inFlightSearch;
    /** 请求代际：取消旧订阅后仍可能有回调排队，代际校验阻止其改写新搜索状态。 */
    private volatile long searchGeneration;
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
        doSearch(page, searchId, sort, time, views, false);
    }

    /** M42：分页跳转——跳转到指定页并用该页结果替换当前列表。 */
    public void jumpToPage(int targetPage, String searchId, String sort, String time, String views) {
        if (targetPage < 1) {
            return;
        }
        page = targetPage;
        doSearch(page, searchId, sort, time, views, true);
    }

    /** M42：当前关键词搜索结果总页数（供跳页对话框提示范围）。 */
    public int getTotalPage() {
        return totalPage == null ? 0 : totalPage;
    }

    /** M43：当前展示页（供底部页码栏高亮当前页）。 */
    public int getCurrentPage() {
        return currentPageShown;
    }

    private void doSearch(final int currentPage, String searchId, String sort, String time, String views, final boolean jumpMode) {
        AppLog.i(TAG, "分分钟搜索 page=" + currentPage + " keyword=" + searchId);
        // M100：串位修复——searchVideos/jumpToPage 均汇入本方法，发起前先取消上一条在途请求（保留 ON_DESTROY 绑定）
        disposeInFlightSearch();
        final long generation = ++searchGeneration;
        dataManager.searchPornyVideos(searchId, currentPage, normalizeFilter(sort), normalizeFilter(time), normalizeFilter(views))
                .map(new Function<BaseResult<List<V9MmanItem>>, List<V9MmanItem>>() {
                    @Override
                    public List<V9MmanItem> apply(BaseResult<List<V9MmanItem>> baseResult) throws Exception {
                        if (baseResult.getCode() == BaseResult.ERROR_CODE) {
                            throw new MessageException(baseResult.getMessage());
                        }
                        if (generation != searchGeneration) {
                            return baseResult.getData();
                        }
                        if (currentPage == 1) {
                            totalPage = baseResult.getTotalPage();
                        } else if (totalPage == null || totalPage <= 0) {
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
                        // M100：记录本次在途请求，供下一次搜索/跳页发起前取消
                        if (generation != searchGeneration) {
                            d.dispose();
                            return;
                        }
                        inFlightSearch = d;
                        ifViewAttached(new ViewAction<SearchView>() {
                            @Override
                            public void run(@NonNull SearchView view) {
                                if (currentPage == 1 || jumpMode) {
                                    view.showLoading(true);
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
                                // 空结果直接判定为到底（应对分页总数估计偏大的情况）
                                if (v9MmanItems == null || v9MmanItems.isEmpty()) {
                                    view.noMoreData();
                                    view.showContent();
                                    return;
                                }
                                currentPageShown = currentPage;
                                if (currentPage == 1 || jumpMode) {
                                    // 首页/跳页：替换整个列表
                                    view.loadMoreDataComplete();
                                    view.setData(v9MmanItems);
                                    view.showContent();
                                } else {
                                    view.loadMoreDataComplete();
                                    view.setMoreData(v9MmanItems);
                                }
                                if (totalPage != null && currentPage >= totalPage) {
                                    view.noMoreData();
                                } else {
                                    page = currentPage + 1;
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
                        AppLog.e(TAG, "分分钟搜索失败 page=" + currentPage + " msg=" + msg);
                        ifViewAttached(new ViewAction<SearchView>() {
                            @Override
                            public void run(@NonNull SearchView view) {
                                if (generation != searchGeneration) {
                                    return;
                                }
                                if (currentPage == 1) {
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
        return false;
    }
}
