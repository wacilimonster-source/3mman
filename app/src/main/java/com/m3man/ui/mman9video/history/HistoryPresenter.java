package com.m3man.ui.mman9video.history;

import androidx.lifecycle.Lifecycle;

import com.hannesdorfmann.mosby3.mvp.MvpBasePresenter;
import com.orhanobut.logger.Logger;
import com.m3man.data.DataManager;
import com.m3man.data.db.Page;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.perf.PerfFlags;
import com.trello.rxlifecycle2.LifecycleProvider;

import java.util.List;

import javax.inject.Inject;

/**
 * 浏览历史，只有观看视频，并解析出视频地址保存之后才会被记录
 * <p>
 * T08：默认走游标分页 {@code loadHistoryAfter(date, id, limit)}（方案 §5-T08）——
 * cursor 取最后一行 (viewHistoryDate, _id)，同时间按 id 消歧；refresh 生成新
 * generation，旧分页回调不得污染刷新结果；动态历史更新时间移动允许刷新后重排
 * （不做跨变更快照一致声明）。pagedLocalList 关闭时回退旧 offset 分页（行为不变）。
 *
 * @author flymegoc
 * @date 2017/12/22
 */

public class HistoryPresenter extends MvpBasePresenter<HistoryView> implements IHistory {

    private static final String TAG = HistoryPresenter.class.getSimpleName();
    private DataManager dataManager;
    /** 分页大小：与旧 offset 接口一致 */
    private static final int PAGE_SIZE = 10;
    /** T08 游标状态（主线程串行访问：每次请求发起时捕获局部快照） */
    private Long lastDate;
    private Long lastId;
    /** 刷新代际：pullToRefresh 递增，旧回调按代际丢弃 */
    private int generation;
    /** 视图首屏/加载更多区分沿用旧 page 语义 */
    private int page = 1;
    private final LifecycleProvider<Lifecycle.Event> provider;

    @Inject
    public HistoryPresenter(DataManager dataManager, LifecycleProvider<Lifecycle.Event> provider) {
        this.dataManager = dataManager;
        this.provider = provider;
    }

    @Override
    public void loadHistoryData(boolean pullToRefresh) {
        if (pullToRefresh) {
            // 刷新：重置 cursor 与代际（旧分页回调不得污染刷新结果）
            lastDate = null;
            lastId = null;
            page = 1;
            generation++;
        }
        final int gen = generation;
        final int currentPage = page;
        final Long cursorDate = lastDate;
        final Long cursorId = lastId;
        // M73：DB 查询切到 IO 线程，历史记录多时避免主线程卡顿/ANR
        io.reactivex.Observable.just(1)
                .map(integer -> {
                    if (PerfFlags.pagedLocalList) {
                        // T08：游标分页（首页 cursor=null）
                        return dataManager.loadHistoryAfter(cursorDate, cursorId, PAGE_SIZE);
                    }
                    // flag 关闭：旧 offset 分页，行为完全不变
                    return new Page<>(dataManager.loadHistoryData(currentPage, PAGE_SIZE), false);
                })
                .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .compose(provider.<Page<V9MmanItem>>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(pageResult -> {
                    if (gen != generation) {
                        return; // 过期分页回调丢弃
                    }
                    final List<V9MmanItem> v9MmanItemList = pageResult.items;
                    ifViewAttached(view -> {
                        if (currentPage == 1) {
                            Logger.t(TAG).d("加载首页");
                            view.setData(v9MmanItemList);
                        } else {
                            Logger.t(TAG).d("加载更多");
                            view.setMoreData(v9MmanItemList);
                            view.loadMoreDataComplete();
                        }
                        page++;
                        // T08：游标推进取最后一行 (date, id)；失败分页不推进（重试不漏不重）
                        if (PerfFlags.pagedLocalList && v9MmanItemList != null && !v9MmanItemList.isEmpty()) {
                            V9MmanItem last = v9MmanItemList.get(v9MmanItemList.size() - 1);
                            if (last != null && last.getViewHistoryDate() != null && last.getId() != null) {
                                lastDate = last.getViewHistoryDate().getTime();
                                lastId = last.getId();
                            }
                        }
                        if (v9MmanItemList == null || v9MmanItemList.size() == 0 || !pageResult.hasMore) {
                            Logger.t(TAG).d("没有更多");
                            view.noMoreData();
                        }
                    });
                    // M97：补错误回调——此前单参 subscribe 出错直接走 onError 致崩溃
                }, e -> {
                    Logger.t(TAG).e(e, "加载浏览历史失败：" + e.getMessage());
                    ifViewAttached(view -> {
                        if (currentPage == 1) {
                            view.showError(e.getMessage());
                        } else {
                            // HistoryView 已声明 loadMoreFailed，加载更多出错时恢复上拉状态
                            view.loadMoreFailed();
                        }
                    });
                });
    }

}
