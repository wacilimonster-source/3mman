package com.m3man.ui.axgle;

import com.m3man.data.model.axgle.AxgleVideo;
import com.m3man.ui.BaseView;

import java.util.List;

/**
 * @author megoc
 */
public interface AxgleView extends BaseView{
    void setData(List<AxgleVideo> axgleVideoList);

    void loadMoreFailed();

    void noMoreData();

    void setMoreData(List<AxgleVideo> axgleVideoList);
}
