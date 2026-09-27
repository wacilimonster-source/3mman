package com.m3man.ui.mman9video.porny;

import com.hannesdorfmann.mosby3.mvp.MvpView;
import com.m3man.data.db.entity.V9MmanItem;

import java.util.List;

/**
 * 91porny 频道列表视图接口（蝌蚪长视频 / 精品·大神共用）。
 *
 * 回调语义与 SearchView 一致：首页替换列表（setData），加载更多追加（setMoreData）。
 * 精品·大神频道额外有顶部大神横滑条数据回调。
 */
public interface PornyListView extends MvpView {

    void setData(List<V9MmanItem> data);

    void setMoreData(List<V9MmanItem> data);

    void loadMoreDataComplete();

    void loadMoreFailed();

    void noMoreData();

    void showLoading(boolean pullToRefresh);

    void showContent();

    void showError(String message);

    /** 大神横滑条数据（仅精品·大神频道实现） */
    void setDashen(List<String> names);
}
