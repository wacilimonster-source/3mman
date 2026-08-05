package com.m3man.data;

import com.m3man.data.db.DbHelper;
import com.m3man.data.model.User;
import com.m3man.data.network.ApiHelper;
import com.m3man.data.prefs.PreferencesHelper;

/**
 * @author flymegoc
 * @date 2018/3/4
 */

public interface DataManager extends DbHelper, ApiHelper, PreferencesHelper {
    String getVideoCacheProxyUrl(String originalVideoUrl);

    boolean isVideoCacheByProxy(String originalVideoUrl);

    void existLogin();

    void resetMman91VideoWatchTime(boolean reset);

    void resetKeDouWoVideoWatchTime();

    User getUser();

    boolean isUserLogin();
}
