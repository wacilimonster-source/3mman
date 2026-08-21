package com.m3man.data.prefs;

/**
 * @author flymegoc
 * @date 2018/2/12
 */

public interface PreferencesHelper {
    void setMman9VideoAddress(String address);

    String getMman9VideoAddress();

    void setMman9ProxyCookie(String cookie);

    String getMman9ProxyCookie();

    void setMman9VideoLoginUserName(String userName);

    String getMman9VideoLoginUserName();

    void setMman9VideoLoginUserPassWord(String passWord);

    String getMman9VideoLoginUserPassword();

    void setMman9VideoUserAutoLogin(boolean autoLogin);

    boolean isMman9VideoUserAutoLogin();

    void setFavoriteNeedRefresh(boolean needRefresh);

    boolean isFavoriteNeedRefresh();

    void setPlaybackEngine(int playbackEngine);

    int getPlaybackEngine();

    void setFirstInSearchMman91Video(boolean firstInSearchMman91Video);

    boolean isFirstInSearchMman91Video();

    void setDownloadVideoNeedWifi(boolean downloadVideoNeedWifi);

    boolean isDownloadVideoNeedWifi();

    void setOpenHttpProxy(boolean openHttpProxy);

    boolean isOpenHttpProxy();

    void setOpenNightMode(boolean openNightMode);

    boolean isOpenNightMode();

    void setNightMode(int nightMode);

    int getNightMode();

    void setProxyIpAddress(String proxyIpAddress);

    String getProxyIpAddress();

    void setProxyPort(int port);

    int getProxyPort();

    void setIgnoreUpdateVersionCode(int versionCode);

    int getIgnoreUpdateVersionCode();

    void setForbiddenAutoReleaseMemory(boolean autoReleaseMemory);

    boolean isForbiddenAutoReleaseMemory();



    void setNoticeVersionCode(int noticeVersionCode);

    int getNoticeVersionCode();

    void setMainFirstTabShow(String firstTabShow);

    String getMainFirstTabShow();

    void setMainSecondTabShow(String secondTabShow);

    String getMainSecondTabShow();

    void setSettingScrollViewScrollPosition(int position);

    int getSettingScrollViewScrollPosition();

    void setCustomDownloadVideoDirPath(String customDirPath);

    String getCustomDownloadVideoDirPath();

    void setPornyAddress(String address);

    String getPornyAddress();

    void setPornyEnabled(boolean enabled);

    boolean isPornyEnabled();


    /** M42：收藏方式——true=本地收藏（无需登录，与分分钟一致）；false=服务器收藏 */
    void setLocalFavoriteMode(boolean localFavoriteMode);

    boolean isLocalFavoriteMode();

    /** 91porny 搜索筛选持久化：排序 / 发布时间 / 播放量 */
    void setPornySearchSort(String sort);

    String getPornySearchSort();

    void setPornySearchTime(String time);

    String getPornySearchTime();

    void setPornySearchViews(String views);

    String getPornySearchViews();
}
