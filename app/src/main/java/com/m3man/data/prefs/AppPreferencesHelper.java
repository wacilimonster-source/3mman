package com.m3man.data.prefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import com.m3man.di.ApplicationContext;
import com.m3man.di.PreferenceInfo;
import com.m3man.utils.PlaybackEngine;
import com.m3man.utils.SDCardUtils;
import com.m3man.utils.Tags;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * @author flymegoc
 * @date 2018/2/12
 */
@SuppressLint("ApplySharedPref")
@Singleton
public class AppPreferencesHelper implements PreferencesHelper {

    public final static String KEY_SP_PORN_91_VIDEO_ADDRESS = "key_sp_custom_address";
    /** 视频分类源站默认地址（用户未配置时使用；以 / 结尾保证 Referer 拼接正确） */
    private final static String DEFAULT_MAN9_VIDEO_ADDRESS = "https://www.91porn.com/";
    public final static String KEY_SP_PORN_COOKIE_PROXY = "key_sp_mman_cookie_proxy";
    public final static String KEY_SP_PIG_AV_ADDRESS = "key_sp_pig_av_address";
    public final static String KEY_SP_AXGLE_ADDRESS = "key_sp_axgle_address";
    public final static String KEY_SP_KE_DOU_WO_ADDRESS = "key_sp_ke_dou_wo_address";
    public final static String KEY_SP_PORNY_ADDRESS = "key_sp_porny_address";
    public final static String KEY_SP_PORNY_ENABLED = "key_sp_porny_enabled";
    private final static String KEY_SP_USER_LOGIN_USERNAME = "key_sp_user_login_username";
    private final static String KEY_SP_USER_LOGIN_PASSWORD = "key_sp_user_login_password";
    private final static String KEY_SP_USER_AUTO_LOGIN = "key_sp_user_auto_login";
    private final static String KEY_SP_USER_FAVORITE_NEED_REFRESH = "key_sp_user_favorite_need_refresh";
    private final static String KEY_SP_PLAYBACK_ENGINE = "key_sp_playback_engine";
    private final static String KEY_SP_FIRST_IN_SEARCH_VIDEO = "key_sp_first_in_search_video";
    private final static String KEY_SP_DOWNLOAD_VIDEO_NEED_WIFI = "key_sp_download_video_need_wifi";
    private final static String KEY_SP_OPEN_HTTP_PROXY = "key_sp_open_http_proxy";
    private final static String KEY_SP_OPEN_NIGHT_MODE = "key_sp_open_night_mode";
    private final static String KEY_SP_PROXY_IP_ADDRESS = "key_sp_proxy_ip_address";
    private final static String KEY_SP_PROXY_PORT = "key_sp_proxy_port";
    private final static String KEY_SP_NEVER_ASK_FOR_WATCH_DOWNLOAD_TIP = "key_sp_never_ask_for_watch_download_tip";
    private final static String KEY_SP_IGNORE_THIS_VERSION_UPDATE_TIP = "key_sp_ignore_this_version_update_tip";
    private final static String KEY_SP_FORBIDDEN_AUTO_RELEASE_MEMORY_WHEN_LOW_MEMORY = "key_sp_forbidden_auto_release_memory_when_low_memory";
    private final static String KEY_SP_NOTICE_VERSION_CODE = "key_sp_notice_version_code";
    private final static String KEY_SP_FIRST_TAB_SHOW = "key_sp_first_tab_show_str";
    private final static String KEY_SP_SECOND_TAB_SHOW = "key_sp_second_tab_show_str";
    private final static String KEY_SP_SETTING_SCROLLVIEW_SCROLL_POSITION = "key_sp_setting_scrollview_scroll_position";
    private final static String KEY_SP_OPEN_SKIP_PAGE = "key_sp_open_skip_page";
    private final static String KEY_SP_CUSTOM_DOWNLOAD_VIDEO_DIR_PATH = "key_sp_custom_download_video_dir_path";
    private final static String KEY_SP_SHOW_URL_REDIRECT_TIP_DIALOG = "key_sp_show_url_redirect_tip_dialog";
    private final static String KEY_SP_FIX_MAIN_NAVIGATION = "key_sp_fix_main_navigation";
    private final static String KEY_SP_LOCAL_FAVORITE_MODE = "key_sp_local_favorite_mode";

    private final SharedPreferences mPrefs;

    @Inject
    AppPreferencesHelper(@ApplicationContext Context context,
                         @PreferenceInfo String prefFileName) {
        mPrefs = context.getSharedPreferences(prefFileName, Context.MODE_PRIVATE);
    }

    @Override
    public void setMman9VideoAddress(String address) {
        mPrefs.edit().putString(KEY_SP_PORN_91_VIDEO_ADDRESS, address).apply();
    }

    @Override
    public String getMman9VideoAddress() {
        // 默认填充 91porn 官方站；空串（含旧版本曾保存空值）同样回退默认，保证开箱即用
        String addr = mPrefs.getString(KEY_SP_PORN_91_VIDEO_ADDRESS, DEFAULT_MAN9_VIDEO_ADDRESS);
        return TextUtils.isEmpty(addr) ? DEFAULT_MAN9_VIDEO_ADDRESS : addr;
    }

    @Override
    public void setMman9ProxyCookie(String cookie) {
        mPrefs.edit().putString(KEY_SP_PORN_COOKIE_PROXY,cookie).apply();
    }

    @Override
    public String getMman9ProxyCookie() {
        return mPrefs.getString(KEY_SP_PORN_COOKIE_PROXY,"");
    }

    @Override
    public void setPavAddress(String address) {
        mPrefs.edit().putString(KEY_SP_PIG_AV_ADDRESS, address).apply();
    }

    @Override
    public String getPavAddress() {
        return mPrefs.getString(KEY_SP_PIG_AV_ADDRESS, "");
    }

    @Override
    public void setMman9VideoLoginUserName(String userName) {
        mPrefs.edit().putString(KEY_SP_USER_LOGIN_USERNAME, userName).apply();
    }

    @Override
    public String getMman9VideoLoginUserName() {
        return mPrefs.getString(KEY_SP_USER_LOGIN_USERNAME, "");
    }

    @Override
    public void setMman9VideoLoginUserPassWord(String passWord) {
        if (TextUtils.isEmpty(passWord)) {
            mPrefs.edit().remove(KEY_SP_USER_LOGIN_PASSWORD).apply();
        } else {
            mPrefs.edit().putString(KEY_SP_USER_LOGIN_PASSWORD, Base64.encodeToString(passWord.getBytes(), Base64.DEFAULT)).apply();
        }
    }

    @Override
    public String getMman9VideoLoginUserPassword() {
        String scPassWord = mPrefs.getString(KEY_SP_USER_LOGIN_PASSWORD, "");
        if (!TextUtils.isEmpty(scPassWord)) {
            return new String(Base64.decode(scPassWord.getBytes(), Base64.DEFAULT));
        } else {
            return "";
        }
    }

    @Override
    public void setMman9VideoUserAutoLogin(boolean autoLogin) {
        mPrefs.edit().putBoolean(KEY_SP_USER_AUTO_LOGIN, autoLogin).apply();
    }

    @Override
    public boolean isMman9VideoUserAutoLogin() {
        return mPrefs.getBoolean(KEY_SP_USER_AUTO_LOGIN, false);
    }

    @Override
    public void setFavoriteNeedRefresh(boolean needRefresh) {
        mPrefs.edit().putBoolean(KEY_SP_USER_FAVORITE_NEED_REFRESH, needRefresh).apply();
    }

    @Override
    public boolean isFavoriteNeedRefresh() {
        return mPrefs.getBoolean(KEY_SP_USER_FAVORITE_NEED_REFRESH, false);
    }

    @Override
    public void setPlaybackEngine(int playbackEngine) {
        mPrefs.edit().putInt(KEY_SP_PLAYBACK_ENGINE, playbackEngine).apply();
    }

    @Override
    public int getPlaybackEngine() {
        return mPrefs.getInt(KEY_SP_PLAYBACK_ENGINE, PlaybackEngine.DEFAULT_PLAYER_ENGINE);
    }

    @Override
    public void setFirstInSearchMman91Video(boolean firstInSearchMman91Video) {
        mPrefs.edit().putBoolean(KEY_SP_FIRST_IN_SEARCH_VIDEO, firstInSearchMman91Video).apply();
    }

    @Override
    public boolean isFirstInSearchMman91Video() {
        return mPrefs.getBoolean(KEY_SP_FIRST_IN_SEARCH_VIDEO, true);
    }

    @Override
    public void setDownloadVideoNeedWifi(boolean downloadVideoNeedWifi) {
        mPrefs.edit().putBoolean(KEY_SP_DOWNLOAD_VIDEO_NEED_WIFI, downloadVideoNeedWifi).apply();
    }

    @Override
    public boolean isDownloadVideoNeedWifi() {
        return mPrefs.getBoolean(KEY_SP_DOWNLOAD_VIDEO_NEED_WIFI, false);
    }

    @Override
    public void setOpenHttpProxy(boolean openHttpProxy) {
        mPrefs.edit().putBoolean(KEY_SP_OPEN_HTTP_PROXY, openHttpProxy).commit();
    }

    @Override
    public boolean isOpenHttpProxy() {
        return mPrefs.getBoolean(KEY_SP_OPEN_HTTP_PROXY, false);
    }

    @Override
    public void setOpenNightMode(boolean openNightMode) {
        mPrefs.edit().putBoolean(KEY_SP_OPEN_NIGHT_MODE, openNightMode).apply();
    }

    @Override
    public boolean isOpenNightMode() {
        return mPrefs.getBoolean(KEY_SP_OPEN_NIGHT_MODE, false);
    }

    @Override
    public void setProxyIpAddress(String proxyIpAddress) {
        mPrefs.edit().putString(KEY_SP_PROXY_IP_ADDRESS, proxyIpAddress).apply();
    }

    @Override
    public String getProxyIpAddress() {
        return mPrefs.getString(KEY_SP_PROXY_IP_ADDRESS, "");
    }

    @Override
    public void setProxyPort(int port) {
        mPrefs.edit().putInt(KEY_SP_PROXY_PORT, port).apply();
    }

    @Override
    public int getProxyPort() {
        return mPrefs.getInt(KEY_SP_PROXY_PORT, 0);
    }

    @Override
    public void setIgnoreUpdateVersionCode(int versionCode) {
        mPrefs.edit().putInt(KEY_SP_IGNORE_THIS_VERSION_UPDATE_TIP, versionCode).apply();
    }

    @Override
    public int getIgnoreUpdateVersionCode() {
        return mPrefs.getInt(KEY_SP_IGNORE_THIS_VERSION_UPDATE_TIP, 0);
    }

    @Override
    public void setForbiddenAutoReleaseMemory(boolean autoReleaseMemory) {
        mPrefs.edit().putBoolean(KEY_SP_FORBIDDEN_AUTO_RELEASE_MEMORY_WHEN_LOW_MEMORY, autoReleaseMemory).apply();
    }

    @Override
    public boolean isForbiddenAutoReleaseMemory() {
        return mPrefs.getBoolean(KEY_SP_FORBIDDEN_AUTO_RELEASE_MEMORY_WHEN_LOW_MEMORY, false);
    }

    @Override
    public void setNoticeVersionCode(int noticeVersionCode) {
        mPrefs.edit().putInt(KEY_SP_NOTICE_VERSION_CODE, noticeVersionCode).apply();
    }

    @Override
    public int getNoticeVersionCode() {
        return mPrefs.getInt(KEY_SP_NOTICE_VERSION_CODE, 0);
    }

    @Override
    public void setMainFirstTabShow(String firstTabShow) {
        mPrefs.edit().putString(KEY_SP_FIRST_TAB_SHOW, firstTabShow).apply();
    }

    @Override
    public String getMainFirstTabShow() {
        return mPrefs.getString(KEY_SP_FIRST_TAB_SHOW, Tags.TAG_PRON_9_VIDEO);
    }

    @Override
    public void setMainSecondTabShow(String secondTabShow) {
        mPrefs.edit().putString(KEY_SP_SECOND_TAB_SHOW, secondTabShow).apply();
    }

    @Override
    public String getMainSecondTabShow() {
        return mPrefs.getString(KEY_SP_SECOND_TAB_SHOW, Tags.TAG_MEI_ZI_TU);
    }

    @Override
    public void setSettingScrollViewScrollPosition(int position) {
        mPrefs.edit().putInt(KEY_SP_SETTING_SCROLLVIEW_SCROLL_POSITION, position).apply();
    }

    @Override
    public int getSettingScrollViewScrollPosition() {
        return mPrefs.getInt(KEY_SP_SETTING_SCROLLVIEW_SCROLL_POSITION, 0);
    }

    @Override
    public void setOpenSkipPage(boolean openSkipPage) {
        mPrefs.edit().putBoolean(KEY_SP_OPEN_SKIP_PAGE, openSkipPage).apply();
    }

    @Override
    public boolean isOpenSkipPage() {
        return mPrefs.getBoolean(KEY_SP_OPEN_SKIP_PAGE, false);
    }

    @Override
    public void setCustomDownloadVideoDirPath(String customDirPath) {
        mPrefs.edit().putString(KEY_SP_CUSTOM_DOWNLOAD_VIDEO_DIR_PATH, customDirPath).commit();
    }

    @Override
    public String getCustomDownloadVideoDirPath() {
        String path = mPrefs.getString(KEY_SP_CUSTOM_DOWNLOAD_VIDEO_DIR_PATH, "");
        if (TextUtils.isEmpty(path)) {
            return SDCardUtils.DOWNLOAD_VIDEO_PATH;
        }
        if (path.endsWith("/")) {
            return path;
        }
        return path + "/";
    }

    @Override
    public boolean isShowUrlRedirectTipDialog() {
        return mPrefs.getBoolean(KEY_SP_SHOW_URL_REDIRECT_TIP_DIALOG, true);
    }

    @Override
    public void setShowUrlRedirectTipDialog(boolean showUrlRedirectTipDialog) {
        mPrefs.edit().putBoolean(KEY_SP_SHOW_URL_REDIRECT_TIP_DIALOG, showUrlRedirectTipDialog).apply();
    }

    @Override
    public void setAxgleAddress(String address) {
        mPrefs.edit().putString(KEY_SP_AXGLE_ADDRESS, address).apply();
    }

    @Override
    public String getAxgleAddress() {
        return mPrefs.getString(KEY_SP_AXGLE_ADDRESS, "");
    }

    @Override
    public void setKeDouWoAddress(String address) {
        mPrefs.edit().putString(KEY_SP_KE_DOU_WO_ADDRESS,address).apply();
    }

    @Override
    public String getKeDouWoAddress() {
        return mPrefs.getString(KEY_SP_KE_DOU_WO_ADDRESS, "");
    }

    @Override
    public void setPornyAddress(String address) {
        mPrefs.edit().putString(KEY_SP_PORNY_ADDRESS, address).apply();
    }

    @Override
    public String getPornyAddress() {
        return mPrefs.getString(KEY_SP_PORNY_ADDRESS, "https://91porny.com/");
    }

    @Override
    public void setPornyEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_SP_PORNY_ENABLED, enabled).apply();
    }

    @Override
    public boolean isPornyEnabled() {
        // 默认开启，装好即可用 91porny 无限制搜索
        return mPrefs.getBoolean(KEY_SP_PORNY_ENABLED, true);
    }

    @Override
    public boolean isFixMainNavigation() {
        return mPrefs.getBoolean(KEY_SP_FIX_MAIN_NAVIGATION, false);
    }

    @Override
    public void setFixMainNavigation(boolean fixMainNavigation) {
        mPrefs.edit().putBoolean(KEY_SP_FIX_MAIN_NAVIGATION, fixMainNavigation).apply();
    }

    @Override
    public void setLocalFavoriteMode(boolean localFavoriteMode) {
        mPrefs.edit().putBoolean(KEY_SP_LOCAL_FAVORITE_MODE, localFavoriteMode).apply();
    }

    @Override
    public boolean isLocalFavoriteMode() {
        // 默认本地收藏（无需登录，与分分钟一致）
        return mPrefs.getBoolean(KEY_SP_LOCAL_FAVORITE_MODE, true);
    }
}
