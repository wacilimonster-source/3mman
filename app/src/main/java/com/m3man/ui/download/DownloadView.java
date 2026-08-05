package com.m3man.ui.download;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.BaseView;

import java.util.List;

/**
 * @author flymegoc
 * @date 2017/11/27
 * @describe
 */

public interface DownloadView extends BaseView {
    void setDownloadingData(List<V9MmanItem> v9MmanItems);

    void setFinishedData(List<V9MmanItem> v9MmanItems);
}
