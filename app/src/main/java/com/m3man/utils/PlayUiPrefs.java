package com.m3man.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * M78：播放界面相关偏好（推荐页播放 UI / 行为开关）。
 * <ul>
 *     <li>autoRotateLandscape：横屏视频自动横屏播放，默认关</li>
 *     <li>hideActionBar：隐藏播放页操作栏（右栏），默认关</li>
 *     <li>orientationFilter：方向筛选，0=全部 1=仅竖屏 2=仅横屏（方形封面归横屏），默认全部</li>
 * </ul>
 */
public final class PlayUiPrefs {

    private static final String PREFS_NAME = "play_ui_prefs";
    private static final String KEY_AUTO_ROTATE_LANDSCAPE = "auto_rotate_landscape";
    private static final String KEY_HIDE_ACTION_BAR = "hide_action_bar";
    private static final String KEY_ORIENTATION_FILTER = "orientation_filter";

    /** 方向筛选取值 */
    public static final int FILTER_ALL = 0;
    public static final int FILTER_PORTRAIT = 1;
    public static final int FILTER_LANDSCAPE = 2;

    private PlayUiPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isAutoRotateLandscape(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_ROTATE_LANDSCAPE, false);
    }

    public static void setAutoRotateLandscape(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_AUTO_ROTATE_LANDSCAPE, value).apply();
    }

    public static boolean isHideActionBar(Context context) {
        return prefs(context).getBoolean(KEY_HIDE_ACTION_BAR, false);
    }

    public static void setHideActionBar(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_HIDE_ACTION_BAR, value).apply();
    }

    public static int getOrientationFilter(Context context) {
        return prefs(context).getInt(KEY_ORIENTATION_FILTER, FILTER_ALL);
    }

    public static void setOrientationFilter(Context context, int value) {
        prefs(context).edit().putInt(KEY_ORIENTATION_FILTER, value).apply();
    }
}
