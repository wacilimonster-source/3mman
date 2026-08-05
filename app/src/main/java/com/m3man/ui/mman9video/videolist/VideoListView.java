package com.m3man.ui.mman9video.videolist;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.BaseView;

import java.util.List;

/**
 * @author flymegoc
 * @date 2017/11/16
 * @describe
 */

public interface VideoListView extends BaseView {
    void loadMoreDataComplete();

    void loadMoreFailed();

    void noMoreData();

    void setMoreData(List<V9MmanItem> v9MmanItemList);

    void loadData(boolean pullToRefresh, boolean cleanCache, int skipPage);

    void setData(List<V9MmanItem> data);

    void setPageData(List<Integer> pageData);

    void updateCurrentPage(int currentPage);

    void showSkipPageLoading();

    void hideSkipPageLoading();
}
