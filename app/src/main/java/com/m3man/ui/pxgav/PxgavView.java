package com.m3man.ui.pxgav;

import com.m3man.data.model.pxgav.PxgavModel;
import com.m3man.ui.BaseView;

import java.util.List;

/**
 * @author flymegoc
 * @date 2018/1/30
 */

public interface PxgavView extends BaseView {
    void setData(List<PxgavModel> pxgavModelList);

    void loadMoreFailed();

    void noMoreData();

    void setMoreData(List<PxgavModel> pxgavModelList);
}
