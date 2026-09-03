package com.m3man.ui.setting;

import com.qmuiteam.qmui.widget.grouplist.QMUICommonListItemView;

import java.util.List;

/**
 * @author flymegoc
 * @date 2018/2/6
 */

public interface ISetting {

    void test9MmanVideo(String baseUrl, QMUICommonListItemView qmuiCommonListItemView, String key);


    void testPorny(String baseUrl, QMUICommonListItemView qmuiCommonListItemView, String key);

    boolean isPornyEnabled();

    void setPornyEnabled(boolean enabled);

    boolean isLocalFavoriteMode();

    void setLocalFavoriteMode(boolean localFavoriteMode);

    String getPornyAddress();

    void setPornyAddress(String address);

    boolean isOpenNightMode();

    void setOpenNightMode(boolean openNightMode);

    /** M112：夜间模式三档（0=跟随系统 / 1=始终夜间 / 2=始终日间），存储层已支持 */
    int getNightMode();

    void setNightMode(int nightMode);

    boolean isOpenHttpProxy();

    void setOpenHttpProxy(boolean openHttpProxy);

    String getProxyIpAddress();

    int getProxyPort();

    boolean isHaveUnFinishDownloadVideo();

    boolean isHaveFinishDownloadVideoFile();

    void moveOldDownloadVideoToNewDir(String newDirPath, QMUICommonListItemView qmuiCommonListItemView);

    boolean isUserLogin();

    void existLogin();

    int getPlaybackEngine();

    void setPlaybackEngine(int playbackEngine);

    void setMman9VideoAddress(String mman9VideoAddress);

    void setCustomDownloadVideoDirPath(String newDirPath);

    String getCustomDownloadVideoDirPath();

    boolean isForbiddenAutoReleaseMemory();

    void setForbiddenAutoReleaseMemory(boolean forbiddenAutoReleaseMemory);

    boolean isDownloadVideoNeedWifi();

    void setDownloadVideoNeedWifi(boolean downloadVideoNeedWifi);

    String getVideo9MmanAddress();


    List<String> getAutoCompleteNames(int type);

    void saveAutoComplete(String name, int type);
}
