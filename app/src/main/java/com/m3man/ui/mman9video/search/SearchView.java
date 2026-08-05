package com.m3man.ui.mman9video.search;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.BaseView;

import java.util.List;

/**
 * @author flymegoc
 * @date 2018/1/7
 */

public interface SearchView extends BaseView {
    void loadMoreDataComplete();

    void loadMoreFailed();

    void noMoreData();

    void setMoreData(List<V9MmanItem> v9MmanItemList);

    void setData(List<V9MmanItem> data);
}
