package com.m3man.ui.download;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.BaseView;

import java.util.List;

/**
 * @author flymegoc
 * @date 2017/11/27
 * @describe 下载管理视图接口，定义下载列表数据展示、完成列表展示等 UI 更新方法
 */

public interface DownloadView extends BaseView {
    void setDownloadingData(List<V9MmanItem> v9MmanItems);

    void setFinishedData(List<V9MmanItem> v9MmanItems);
}
