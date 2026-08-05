package com.m3man.ui.mman9video.search;

/**
 * @author flymegoc
 * @date 2018/1/7
 */

public interface ISearch {
    void searchVideos(String searchId, String sort,boolean pullToRefresh);
    int getPlayBackEngine();
    boolean isFirstInSearchMman91Video();
}
