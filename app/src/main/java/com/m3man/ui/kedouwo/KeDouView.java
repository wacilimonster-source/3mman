package com.m3man.ui.kedouwo;

import com.m3man.data.model.kedouwo.KeDouModel;
import com.m3man.ui.BaseView;

import java.util.List;

/**
 * Created by alex
 * Des:
 * Date: 2019/8/27.
 */
public interface KeDouView extends BaseView {

    void setData(List<KeDouModel> keDouModelList);

    void loadMoreFailed();

    void noMoreData();

    void setMoreData(List<KeDouModel> keDouModelList);
}
