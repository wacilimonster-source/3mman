package com.m3man.ui.mman9video.history;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.BaseView;

import java.util.List;

/**
 * @author flymegoc
 * @date 2017/12/22
 */

public interface HistoryView extends BaseView {
    void loadMoreDataComplete();

    void loadMoreFailed();

    void noMoreData();

    void setData(List<V9MmanItem> v9MmanItemList);

    void setMoreData(List<V9MmanItem> v9MmanItemList);
}
