package com.m3man.ui.mman9video.history;

/**
 * @author flymegoc
 * @date 2017/12/22
 */

public interface IHistory {
    void loadHistoryData(boolean pullToRefresh);

    int getPlayBackEngine();
}
