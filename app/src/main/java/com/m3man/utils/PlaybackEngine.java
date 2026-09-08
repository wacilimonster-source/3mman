package com.m3man.utils;

import android.content.Context;
import android.content.Intent;

import com.m3man.ui.mman9video.play.ExoMediaPlayerActivity;

/**
 * 播放引擎入口。
 *
 * <p>历史上这里提供 Exo / JiaoZi 两套播放页供用户在设置里切换。
 * v1.0.120 起 JiaoZi 播放页（JiaoZiVideoPlayerActivity + Mman9VideoPlayer）已移除，统一走 Exo：
 * <ul>
 *   <li>JiaoZi 播放页的倍速仅在 MediaSystem 通道且处于播放态才生效，暂停态点会静默失败；</li>
 *   <li>HLS / m3u8（如 91porny 源）本来就强制回退 Exo，JiaoZi 侧不可靠；</li>
 *   <li>Exo 侧能力为超集：控制条倍速面板、滑动调进度/音量/亮度、起播看门狗与失败自动重新解析直链。</li>
 * </ul>
 * 注意：jiaozivideoplayer 依赖本身仍需保留 —— 推荐流内嵌的 RecoVideoPlayer 还在用它。
 *
 * @author flymegoc
 * @date 2018/1/2
 */

public class PlaybackEngine {

    private PlaybackEngine() {
    }

    /**
     * 获取播放页 Intent（当前唯一引擎：Exo）
     *
     * @param context 上下文
     * @return intent
     */
    public static Intent getPlaybackEngineIntent(Context context) {
        return new Intent(context, ExoMediaPlayerActivity.class);
    }
}
