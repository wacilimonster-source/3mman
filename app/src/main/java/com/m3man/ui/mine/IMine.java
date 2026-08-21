package com.m3man.ui.mine;

import com.m3man.data.model.User;

public interface IMine {
    boolean isUserLogin();

    User getLoginUser();

    void existLogin();

    boolean isOpenHttpProxy();

    void setOpenHttpProxy(boolean openHttpProxy);

    String getProxyIpAddress();

    int getProxyPort();

    boolean isOpenNightMode();

    void setOpenNightMode(boolean openNightMode);

    void setSettingScrollViewScrollPosition(int settingScrollViewScrollPosition);

    int getSettingScrollViewScrollPosition();

    /** M42：是否本地收藏模式 */
    boolean isLocalFavoriteMode();
}
