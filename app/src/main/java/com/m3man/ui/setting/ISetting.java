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

    boolean isOpenSkipPage();

    void setOpenSkipPage(boolean openSkipPage);

    String getVideo9MmanAddress();

    boolean isShowUrlRedirectTipDialog();

    void setShowUrlRedirectTipDialog(boolean showUrlRedirectTipDialog);
    boolean isFixMainNavigation();

    void setFixMainNavigation(boolean fixMainNavigation);

    List<String> getAutoCompleteNames(int type);

    void saveAutoComplete(String name, int type);
}
