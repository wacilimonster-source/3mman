package com.m3man.ui.main;

/**
 * @author flymegoc
 * @date 2017/12/23
 */

public interface IMain {

    void saveNoticeVersionCode(int versionCode);

    int getNoticeVersionCode();

    void setIgnoreUpdateVersionCode(int versionCode);

    int getIgnoreUpdateVersionCode();

    void checkUpdate(int versionCode);

    void checkNewNotice();

    void setMainSecondTabShow(String tabId);

    String getMainSecondTabShow();

    void setMainFirstTabShow(String tabId);

    String getMainFirstTabShow();

    boolean haveNotSetV9pronAddress();

    boolean isPornyEnabled();

    boolean isUserLogin();

    void setMman9VideoAddress(String mman9VideoAddress);

    boolean isFixMainNavigation();
}
