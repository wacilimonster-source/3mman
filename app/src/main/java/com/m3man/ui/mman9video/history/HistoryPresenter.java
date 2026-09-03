package com.m3man.ui.mman9video.history;

import androidx.lifecycle.Lifecycle;

import com.hannesdorfmann.mosby3.mvp.MvpBasePresenter;
import com.orhanobut.logger.Logger;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;
import com.trello.rxlifecycle2.LifecycleProvider;

import java.util.List;

import javax.inject.Inject;

/**
 * 浏览历史，只有观看视频，并解析出视频地址保存之后才会被记录
 *
 * @author flymegoc
 * @date 2017/12/22
 */

public class HistoryPresenter extends MvpBasePresenter<HistoryView> implements IHistory {

    private static final String TAG = HistoryPresenter.class.getSimpleName();
    private DataManager dataManager;
    private int page = 1;
    private int pageSize = 10;
    private final LifecycleProvider<Lifecycle.Event> provider;

    @Inject
    public HistoryPresenter(DataManager dataManager, LifecycleProvider<Lifecycle.Event> provider) {
        this.dataManager = dataManager;
        this.provider = provider;
    }

    @Override
    public void loadHistoryData(boolean pullToRefresh) {
        //如果刷新则重置页数
        if (pullToRefresh) {
            page = 1;
        }
        // M73：DB 查询切到 IO 线程，历史记录多时避免主线程卡顿/ANR
        io.reactivex.Observable.just(1)
                .map(integer -> dataManager.loadHistoryData(page, pageSize))
                .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .compose(provider.<List<V9MmanItem>>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(v9MmanItemList -> {
                    ifViewAttached(view -> {
                        if (page == 1) {
                            Logger.t(TAG).d("加载首页");
                            view.setData(v9MmanItemList);
                        } else {
                            Logger.t(TAG).d("加载更多");
                            view.setMoreData(v9MmanItemList);
                            view.loadMoreDataComplete();
                        }
                        page++;
                        if (v9MmanItemList.size() == 0 || v9MmanItemList.size() < pageSize) {
                            Logger.t(TAG).d("没有更多");
                            view.noMoreData();
                        }
                    });
                    // M97：补错误回调——此前单参 subscribe 出错直接走 onError 致崩溃
                }, e -> {
                    Logger.t(TAG).e(e, "加载浏览历史失败：" + e.getMessage());
                    ifViewAttached(view -> {
                        if (page == 1) {
                            view.showError(e.getMessage());
                        } else {
                            // HistoryView 已声明 loadMoreFailed，加载更多出错时恢复上拉状态
                            view.loadMoreFailed();
                        }
                    });
                });
    }

    @Override
    public int getPlayBackEngine() {
        return dataManager.getPlaybackEngine();
    }
}
