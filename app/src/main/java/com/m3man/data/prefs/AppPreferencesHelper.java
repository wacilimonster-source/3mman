package com.m3man.data.prefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import java.util.Locale;

import com.m3man.di.ApplicationContext;
import com.m3man.di.PreferenceInfo;
import com.m3man.utils.AppLog;
import com.m3man.utils.PasswordVault;
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

    private static final String TAG = "AppPrefsHelper";

    public final static String KEY_SP_PORN_91_VIDEO_ADDRESS = "key_sp_custom_address";
    /** 视频分类源站默认地址（用户未配置时使用；以 / 结尾保证 Referer 拼接正确） */
    private final static String DEFAULT_MAN9_VIDEO_ADDRESS = "https://www.91porn.com/";
    public final static String KEY_SP_PORN_COOKIE_PROXY = "key_sp_mman_cookie_proxy";
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
    private final static String KEY_SP_NIGHT_MODE = "key_sp_night_mode";
    private final static String KEY_SP_PORNY_SEARCH_SORT = "key_sp_porny_search_sort";
    private final static String KEY_SP_PORNY_SEARCH_TIME = "key_sp_porny_search_time";
    private final static String KEY_SP_PORNY_SEARCH_VIEWS = "key_sp_porny_search_views";
    private final static String KEY_SP_PROXY_IP_ADDRESS = "key_sp_proxy_ip_address";
    private final static String KEY_SP_PROXY_PORT = "key_sp_proxy_port";
    private final static String KEY_SP_NEVER_ASK_FOR_WATCH_DOWNLOAD_TIP = "key_sp_never_ask_for_watch_download_tip";
    private final static String KEY_SP_IGNORE_THIS_VERSION_UPDATE_TIP = "key_sp_ignore_this_version_update_tip";
    private final static String KEY_SP_FORBIDDEN_AUTO_RELEASE_MEMORY_WHEN_LOW_MEMORY = "key_sp_forbidden_auto_release_memory_when_low_memory";
    private final static String KEY_SP_NOTICE_VERSION_CODE = "key_sp_notice_version_code";
    private final static String KEY_SP_FIRST_TAB_SHOW = "key_sp_first_tab_show_str";
    private final static String KEY_SP_SECOND_TAB_SHOW = "key_sp_second_tab_show_str";
    private final static String KEY_SP_SETTING_SCROLLVIEW_SCROLL_POSITION = "key_sp_setting_scrollview_scroll_position";
    private final static String KEY_SP_CUSTOM_DOWNLOAD_VIDEO_DIR_PATH = "key_sp_custom_download_video_dir_path";
    private final static String KEY_SP_LOCAL_FAVORITE_MODE = "key_sp_local_favorite_mode";
    /** 推荐流时长上限（分钟）。0 = 不限；1/2/3/5/10 分别对应 ≤ 1/2/3/5/10 分钟的视频。 */
    public final static String KEY_SP_RECO_MAX_DURATION_MINUTES = "key_sp_reco_max_duration_minutes";
    private final static int DEFAULT_RECO_MAX_DURATION_MINUTES = 0;

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
        // 默认填充 91porn 官方站；空串或历史误保存的 GitHub 默认域名回退，避免视频请求落到 GitHub。
        String addr = mPrefs.getString(KEY_SP_PORN_91_VIDEO_ADDRESS, DEFAULT_MAN9_VIDEO_ADDRESS);
        if (TextUtils.isEmpty(addr)) {
            return DEFAULT_MAN9_VIDEO_ADDRESS;
        }
        String normalized = addr.trim().toLowerCase(Locale.US);
        if ("https://github.com".equals(normalized)
                || "https://github.com/".equals(normalized)
                || "http://github.com".equals(normalized)
                || "http://github.com/".equals(normalized)) {
            mPrefs.edit().putString(KEY_SP_PORN_91_VIDEO_ADDRESS, DEFAULT_MAN9_VIDEO_ADDRESS).apply();
            return DEFAULT_MAN9_VIDEO_ADDRESS;
        }
        return addr;
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
            String toSave;
            try {
                // M94：优先 Keystore AES/GCM 加密，"v1:" 前缀标识新格式
                toSave = PasswordVault.PREFIX_V1 + PasswordVault.encrypt(passWord);
            } catch (Exception e) {
                // M94：加密失败（API<23 / Keystore 不可用等）降级旧 Base64 形态，保证功能不中断
                AppLog.w(TAG, "密码加密失败，降级Base64存储 " + AppLog.cause(e));
                toSave = Base64.encodeToString(passWord.getBytes(), Base64.DEFAULT);
            }
            mPrefs.edit().putString(KEY_SP_USER_LOGIN_PASSWORD, toSave).apply();
        }
    }

    @Override
    public String getMman9VideoLoginUserPassword() {
        String scPassWord = mPrefs.getString(KEY_SP_USER_LOGIN_PASSWORD, "");
        if (TextUtils.isEmpty(scPassWord)) {
            return "";
        }
        if (PasswordVault.hasV1Prefix(scPassWord)) {
            // M94：新格式（v1: 前缀）→ Keystore 解密；失败清坏键返回空，不向上抛
            try {
                return PasswordVault.decrypt(scPassWord.substring(PasswordVault.PREFIX_V1.length()));
            } catch (Exception e) {
                AppLog.w(TAG, "密码解密失败，清理坏键 " + AppLog.cause(e));
                mPrefs.edit().remove(KEY_SP_USER_LOGIN_PASSWORD).apply();
                return "";
            }
        }
        // M94：legacy Base64 形态——非法输入不再抛 IllegalArgumentException（合并评审问题）
        try {
            String plain = new String(Base64.decode(scPassWord.getBytes(), Base64.DEFAULT));
            // M94 惰性升级：老数据解码成功即按新格式回写（内部失败会自动回落 Base64，无递归风险）
            setMman9VideoLoginUserPassWord(plain);
            return plain;
        } catch (IllegalArgumentException e) {
            AppLog.w(TAG, "密码Base64解码失败(非法输入)，清理坏键 " + AppLog.cause(e));
            mPrefs.edit().remove(KEY_SP_USER_LOGIN_PASSWORD).apply();
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
    public void setNightMode(int nightMode) {
        mPrefs.edit().putInt(KEY_SP_NIGHT_MODE, nightMode).apply();
    }

    @Override
    public int getNightMode() {
        if (!mPrefs.contains(KEY_SP_NIGHT_MODE)) {
            int migrated = mPrefs.getBoolean(KEY_SP_OPEN_NIGHT_MODE, false) ? 1 : 2;
            mPrefs.edit().putInt(KEY_SP_NIGHT_MODE, migrated).apply();
        }
        return mPrefs.getInt(KEY_SP_NIGHT_MODE, 0);
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
    public void setPornyAddress(String address) {
        mPrefs.edit().putString(KEY_SP_PORNY_ADDRESS, address).apply();
    }

    @Override
    public String getPornyAddress() {
        String addr = mPrefs.getString(KEY_SP_PORNY_ADDRESS, "https://91porny.com/");
        if (TextUtils.isEmpty(addr)) {
            return "https://91porny.com/";
        }
        // M65b：与 9mman 同样防护——GitHub 默认域名会导致请求先打 GitHub 再被重定向
        String normalized = addr.trim().toLowerCase(Locale.US);
        if ("https://github.com".equals(normalized)
                || "https://github.com/".equals(normalized)
                || "http://github.com".equals(normalized)
                || "http://github.com/".equals(normalized)) {
            mPrefs.edit().putString(KEY_SP_PORNY_ADDRESS, "https://91porny.com/").apply();
            return "https://91porny.com/";
        }
        return addr;
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
    public void setLocalFavoriteMode(boolean localFavoriteMode) {
        mPrefs.edit().putBoolean(KEY_SP_LOCAL_FAVORITE_MODE, localFavoriteMode).apply();
    }

    @Override
    public boolean isLocalFavoriteMode() {
        // 默认本地收藏（无需登录，与分分钟一致）
        return mPrefs.getBoolean(KEY_SP_LOCAL_FAVORITE_MODE, true);
    }

    @Override
    public void setPornySearchSort(String sort) {
        mPrefs.edit().putString(KEY_SP_PORNY_SEARCH_SORT, sort == null ? "" : sort).apply();
    }

    @Override
    public String getPornySearchSort() {
        return mPrefs.getString(KEY_SP_PORNY_SEARCH_SORT, "");
    }

    @Override
    public void setPornySearchTime(String time) {
        mPrefs.edit().putString(KEY_SP_PORNY_SEARCH_TIME, time == null ? "" : time).apply();
    }

    @Override
    public String getPornySearchTime() {
        return mPrefs.getString(KEY_SP_PORNY_SEARCH_TIME, "");
    }

    @Override
    public void setPornySearchViews(String views) {
        mPrefs.edit().putString(KEY_SP_PORNY_SEARCH_VIEWS, views == null ? "" : views).apply();
    }

    @Override
    public String getPornySearchViews() {
        return mPrefs.getString(KEY_SP_PORNY_SEARCH_VIEWS, "");
    }

    @Override
    public void setRecoMaxDurationMinutes(int minutes) {
        // 合法值：0（不限）/ 1/2/3/5/10；其余写入时归零，避免脏值
        int safe;
        if (minutes == 1 || minutes == 2 || minutes == 3 || minutes == 5 || minutes == 10) {
            safe = minutes;
        } else {
            safe = 0;
        }
        mPrefs.edit().putInt(KEY_SP_RECO_MAX_DURATION_MINUTES, safe).apply();
    }

    @Override
    public int getRecoMaxDurationMinutes() {
        int v = mPrefs.getInt(KEY_SP_RECO_MAX_DURATION_MINUTES, DEFAULT_RECO_MAX_DURATION_MINUTES);
        if (v != 1 && v != 2 && v != 3 && v != 5 && v != 10) {
            return 0;
        }
        return v;
    }
}
