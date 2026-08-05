package com.m3man.ui.download;

import com.m3man.data.db.entity.V9MmanItem;

import java.util.List;

/**
 * @author flymegoc
 * @date 2017/11/27
 * @describe
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
