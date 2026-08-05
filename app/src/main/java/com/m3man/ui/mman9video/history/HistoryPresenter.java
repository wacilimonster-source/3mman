package com.m3man.ui.mman9video.history;

import com.hannesdorfmann.mosby3.mvp.MvpBasePresenter;
import com.orhanobut.logger.Logger;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;

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

    @Inject
    public HistoryPresenter(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Override
    public void loadHistoryData(boolean pullToRefresh) {
        //如果刷新则重置页数
        if (pullToRefresh) {
            page = 1;
        }
        final List<V9MmanItem> v9MmanItemList = dataManager.loadHistoryData(page, pageSize);
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
    }

    @Override
    public int getPlayBackEngine() {
        return dataManager.getPlaybackEngine();
    }
}
