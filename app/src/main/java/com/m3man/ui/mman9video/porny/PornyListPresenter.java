package com.m3man.ui.mman9video.porny;

import androidx.lifecycle.Lifecycle;
import androidx.annotation.NonNull;

import com.hannesdorfmann.mosby3.mvp.MvpBasePresenter;
import com.m3man.data.DataManager;
import com.m3man.data.model.BaseResult;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.exception.MessageException;
import com.m3man.parser.Parse91PornyVideo;
import com.m3man.rxjava.CallBackWrapper;
import com.m3man.rxjava.RetryWhenProcess;
import com.m3man.rxjava.RxSchedulersHelper;
import com.m3man.utils.AppLog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;

/**
 * 91porny 频道列表 Presenter（蝌蚪长视频 / 精品·大神共用）。
 *
 * 分页模型与 SearchPornyPresenter 一致（首页替换 / 加载更多追加 / 空页判到底），
 * 差异点：
 *  1. 蝌蚪排序为路径式 /videos/{sort}/{page}（latest-updates / top-rated / most-popular）；
 *  2. 翻页设预取上限（防风控，产品设计 §P1），到上限按到底处理；
 *  3. 精品频道顶部"大神"横滑条：首页模块种子（站点无大神索引页）+ 本地库合并，
 *     失败静默降级为本地库，不阻塞精品列表。
 */
public class PornyListPresenter extends MvpBasePresenter<PornyListView> {

    private static final String TAG = PornyListPresenter.class.getSimpleName();

    public static final String MODE_TADPOLE = "tadpole";
    public static final String MODE_PREMIUM = "premium";
    public static final String MODE_DASHEN = "dashen";

    /** 蝌蚪排序值（2026-09-28 实测） */
    public static final String SORT_LATEST = "latest-updates";
    public static final String SORT_TOP_RATED = "top-rated";
    public static final String SORT_MOST_POPULAR = "most-popular";

    /** 翻页预取上限（蝌蚪 1701 页 / 精品 40 页，深翻页有风控风险） */
    static final int MAX_LOAD_PAGES = 10;

    private final com.trello.rxlifecycle2.LifecycleProvider<Lifecycle.Event> provider;
    private final DataManager dataManager;

    private String mode = MODE_TADPOLE;
    private String sort = SORT_LATEST;
    private String dashenName = "";
    private int page = 1;
    private Integer totalPage;
    private Disposable inFlight;
    private volatile long generation;
    private boolean dashenStripLoaded;

    @Inject
    public PornyListPresenter(com.trello.rxlifecycle2.LifecycleProvider<Lifecycle.Event> provider, DataManager dataManager) {
        this.provider = provider;
        this.dataManager = dataManager;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public void setSort(String sort) {
        this.sort = sort;
    }

    public String getSort() {
        return sort;
    }

    public int getPage() {
        return page;
    }

    /** 频道首屏 / 切换排序：回到第 1 页 */
    public void refresh() {
        page = 1;
        totalPage = null;
        load(false);
    }

    /** 加载更多（由 adapter loadMore 触发） */
    public void loadMore() {
        load(true);
    }

    /** 大神作者页（P3 大神主页复用精品列表视图时直接指定作者） */
    public void setDashenName(String name) {
        this.dashenName = name == null ? "" : name;
    }

    private void load(final boolean loadMore) {
        disposeInFlight();
        final long gen = ++generation;
        io.reactivex.Observable<BaseResult<List<V9MmanItem>>> request;
        if (MODE_TADPOLE.equals(mode)) {
            request = dataManager.loadPornyTadpoleVideos(sort, page);
        } else if (MODE_PREMIUM.equals(mode)) {
            request = dataManager.loadPornyPremiumVideos(page);
        } else {
            request = dataManager.loadPornyDashenVideos(dashenName, page);
        }
        request.map(new Function<BaseResult<List<V9MmanItem>>, List<V9MmanItem>>() {
                    @Override
                    public List<V9MmanItem> apply(BaseResult<List<V9MmanItem>> baseResult) throws Exception {
                        if (baseResult.getCode() == BaseResult.ERROR_CODE) {
                            throw new MessageException(baseResult.getMessage());
                        }
                        if (page == 1 || totalPage == null || totalPage <= 0) {
                            totalPage = baseResult.getTotalPage();
                        }
                        return baseResult.getData();
                    }
                })
                // 列表属前台即时操作，首败即回传（与搜索一致，不做后台静默重试）
                .retryWhen(new RetryWhenProcess(0))
                .compose(RxSchedulersHelper.<List<V9MmanItem>>ioMainThread())
                .compose(provider.<List<V9MmanItem>>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<List<V9MmanItem>>() {
                    @Override
                    public void onBegin(Disposable d) {
                        if (gen != generation) {
                            d.dispose();
                            return;
                        }
                        inFlight = d;
                        ifViewAttached(new ViewAction<PornyListView>() {
                            @Override
                            public void run(@NonNull PornyListView view) {
                                if (page == 1 && !loadMore) {
                                    view.showLoading(false);
                                }
                            }
                        });
                    }

                    @Override
                    public void onSuccess(final List<V9MmanItem> items) {
                        if (gen != generation) {
                            return;
                        }
                        ifViewAttached(new ViewAction<PornyListView>() {
                            @Override
                            public void run(@NonNull PornyListView view) {
                                if (gen != generation) {
                                    return;
                                }
                                if (items == null || items.isEmpty()) {
                                    if (page == 1 && !loadMore) {
                                        view.setData(new ArrayList<V9MmanItem>());
                                    } else {
                                        view.loadMoreDataComplete();
                                    }
                                    view.noMoreData();
                                    view.showContent();
                                    return;
                                }
                                if (page == 1 && !loadMore) {
                                    view.setData(items);
                                } else {
                                    view.loadMoreDataComplete();
                                    view.setMoreData(items);
                                }
                                boolean capped = page >= MAX_LOAD_PAGES && !MODE_DASHEN.equals(mode);
                                boolean lastPage = totalPage != null && page >= totalPage;
                                if (capped || lastPage) {
                                    view.noMoreData();
                                } else {
                                    page++;
                                    if (capped) {
                                        AppLog.i(TAG, "翻页达预取上限 mode=" + mode + " page=" + page);
                                    }
                                }
                                view.showContent();
                            }
                        });
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        if (gen != generation) {
                            return;
                        }
                        AppLog.e(TAG, "频道列表加载失败 mode=" + mode + " page=" + page + " msg=" + msg);
                        ifViewAttached(new ViewAction<PornyListView>() {
                            @Override
                            public void run(@NonNull PornyListView view) {
                                if (page == 1 && !loadMore) {
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
     * 大神横滑条：首页模块种子 + 本地库合并（去重保序，首页优先）。
     * 首页抓取失败时静默回退本地库，绝不阻塞精品列表加载。
     */
    public void loadDashenStrip() {
        ifViewAttached(new ViewAction<PornyListView>() {
            @Override
            public void run(@NonNull PornyListView view) {
                // 先用本地库立即渲染，再异步补种子
                view.setDashen(dataManager.getPornyDashenNames());
            }
        });
        if (dashenStripLoaded) {
            return;
        }
        dashenStripLoaded = true;
        final long gen = ++generation;
        dataManager.loadPornyHomepage()
                .map(new Function<String, List<String>>() {
                    @Override
                    public List<String> apply(String html) throws Exception {
                        List<String> seed = Parse91PornyVideo.parseDashenNames(html);
                        if (seed.isEmpty()) {
                            throw new IllegalStateException("首页未解析出大神模块");
                        }
                        Set<String> merged = new LinkedHashSet<>(seed);
                        merged.addAll(dataManager.getPornyDashenNames());
                        return new ArrayList<>(merged);
                    }
                })
                .compose(RxSchedulersHelper.<List<String>>ioMainThread())
                .compose(provider.<List<String>>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<List<String>>() {
                    @Override
                    public void onBegin(Disposable d) {
                        if (gen != generation) {
                            d.dispose();
                        }
                    }

                    @Override
                    public void onSuccess(final List<String> names) {
                        if (gen != generation) {
                            return;
                        }
                        // 合并结果落本地库（下次进频道秒出）
                        io.reactivex.Observable.fromCallable(() -> {
                            dataManager.setPornyDashenNames(names);
                            return true;
                        }).subscribeOn(io.reactivex.schedulers.Schedulers.io())
                                .subscribe(b -> { }, t -> AppLog.w(TAG, "大神本地库写入失败 " + AppLog.cause(t)));
                        ifViewAttached(new ViewAction<PornyListView>() {
                            @Override
                            public void run(@NonNull PornyListView view) {
                                view.setDashen(names);
                            }
                        });
                    }

                    @Override
                    public void onError(String msg, int code) {
                        // 静默降级：本地库已在首帧渲染
                        AppLog.w(TAG, "大神种子抓取失败（用本地库） msg=" + msg);
                    }
                });
    }

    /** 运行中积累大神名（精品条目 /dashen/ 链接解析结果），合并进本地库 */
    public void accumulateDashenNames(final List<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        io.reactivex.Observable.fromCallable(() -> {
            Set<String> merged = new LinkedHashSet<>(dataManager.getPornyDashenNames());
            boolean changed = merged.addAll(names);
            if (changed) {
                dataManager.setPornyDashenNames(new ArrayList<>(merged));
            }
            return changed;
        }).subscribeOn(io.reactivex.schedulers.Schedulers.io())
                .subscribe(b -> { }, t -> AppLog.w(TAG, "大神积累失败 " + AppLog.cause(t)));
    }

    private void disposeInFlight() {
        if (inFlight != null && !inFlight.isDisposed()) {
            inFlight.dispose();
        }
        inFlight = null;
    }
}
