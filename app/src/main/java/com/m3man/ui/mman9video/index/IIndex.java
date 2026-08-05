package com.m3man.ui.mman9video.index;

/**
 * @author flymegoc
 * @date 2017/11/27
 * @describe
 */

public interface IIndex {
    void loadIndexData(final boolean pullToRefresh, boolean cleanCache);

    int getPlayBackEngine();
}
