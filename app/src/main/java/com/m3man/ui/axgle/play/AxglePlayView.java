package com.m3man.ui.axgle.play;

import com.m3man.data.model.axgle.AxgleVideo;
import com.m3man.ui.BaseView;

import java.util.List;

public interface AxglePlayView extends BaseView {
    void showLoading();

    void getVideoUrlSuccess(String videoUrl);

    void getVideoUrlError();

    void setData(List<AxgleVideo> axgleVideoList);

    void loadMoreFailed();

    void noMoreData();

    void setMoreData(List<AxgleVideo> axgleVideoList);
}
