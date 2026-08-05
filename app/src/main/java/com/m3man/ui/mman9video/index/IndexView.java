package com.m3man.ui.mman9video.index;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.BaseView;

import java.util.List;

/**
 * @author flymegoc
 * @date 2017/11/15
 * @describe
 */

public interface IndexView extends BaseView {

    void loadData(boolean pullToRefresh,boolean cleanCache);

    void setData(List<V9MmanItem> data);
}
