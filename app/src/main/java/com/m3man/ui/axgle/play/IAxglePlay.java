package com.m3man.ui.axgle.play;

/**
 * @author megoc
 */
public interface IAxglePlay {
    void getPlayVideoUrl(String vid);

    void loadSimilarVideo(String keyWord, boolean pullToRefresh);
}
