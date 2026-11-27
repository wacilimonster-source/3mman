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

    User getUser();

    boolean isUserLogin();

    /** 主站地址（用户可在设置里改），拼接站点页面 URL 用（如邮箱验证页）。 */
    String getMman9VideoAddress();

    /** 登录成功后把当前用户快照写入 SP，供进程重启后恢复登录态。 */
    void saveLoginUserSnapshot();
}
