package com.m3man.utils;

import android.content.Context;
import android.content.Intent;

import com.m3man.ui.mman9video.play.ExoMediaPlayerActivity;
import com.m3man.ui.mman9video.play.JiaoZiVideoPlayerActivity;

/**
 * 播放引擎切换
 *
 * @author flymegoc
 * @date 2018/1/2
 */

public class PlaybackEngine {
    public static final String[] PLAY_ENGINE_ITEMS = new String[]{"Google Exoplayer Engine", "JiaoZiPlayer Engine",};
    private static final int EXOMEDIAPLAYER_ENGINE = 0;
    private static final int JIAOZIVIDEOPLAYER_ENGINE = 1;
    public static final int DEFAULT_PLAYER_ENGINE = EXOMEDIAPLAYER_ENGINE;

    /**
     * 获取播放引擎
     *
     * @param context 上下文
     * @return intent
     */
    public static Intent getPlaybackEngineIntent(Context context, int engine) {

        Intent intent = new Intent();
        switch (engine) {
            case EXOMEDIAPLAYER_ENGINE:
                intent.setClass(context, ExoMediaPlayerActivity.class);
                break;
            case JIAOZIVIDEOPLAYER_ENGINE:
                intent.setClass(context, JiaoZiVideoPlayerActivity.class);
                break;
            default:
                // M99：未知引擎值回退默认播放引擎，避免返回无 component 的空 Intent
                // 导致 startActivity 抛 ActivityNotFoundException
                intent.setClass(context, DEFAULT_PLAYER_ENGINE == EXOMEDIAPLAYER_ENGINE
                        ? ExoMediaPlayerActivity.class : JiaoZiVideoPlayerActivity.class);
                break;
        }
        return intent;
    }
}
