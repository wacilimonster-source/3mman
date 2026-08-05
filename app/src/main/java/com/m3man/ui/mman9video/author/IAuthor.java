package com.m3man.ui.mman9video.author;

/**
 * @author flymegoc
 * @date 2018/1/8
 */

public interface IAuthor {
    void authorVideos(String uid,boolean pullToRefresh);
    boolean isUserLogin();
}
