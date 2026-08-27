package com.m3man.ui.download;

import com.m3man.data.db.entity.V9MmanItem;

import java.util.List;

/**
 * @author flymegoc
 * @date 2017/11/27
 * @describe 下载管理接口，定义下载视频、暂停/恢复、删除等核心操作
 */

public interface IDownload extends IBaseDownload {
    void downloadVideo(V9MmanItem v9MmanItem, boolean isForceReDownload, DownloadPresenter.DownloadListener downloadListener);

    void loadDownloadingData();

    void loadFinishedData();

    void deleteDownloadingTask(V9MmanItem v9MmanItem);

    void deleteDownloadedTask(V9MmanItem v9MmanItem, boolean isDeleteFile);

    V9MmanItem findUnLimit91MmanItemByDownloadId(int downloadId);

    List<V9MmanItem> loadDownloadingDatas();

    void updateV9MmanItem(V9MmanItem v9MmanItem);

    String getCustomDownloadVideoDirPath();
}
