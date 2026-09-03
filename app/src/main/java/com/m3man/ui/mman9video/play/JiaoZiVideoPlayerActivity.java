package com.m3man.ui.mman9video.play;

import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.m3man.R;
import com.m3man.utils.GlideApp;
import com.sdsmdg.tastytoast.TastyToast;

import cn.jzvd.JZVideoPlayer;
import cn.jzvd.JZVideoPlayerStandard;

/**
 * @author flymegoc
 */
public class JiaoZiVideoPlayerActivity extends BasePlayVideo {

    private static final float[] SPEED_OPTIONS = {1.0f, 1.5f, 2.0f, 2.5f, 3.0f};
    private static final String[] SPEED_LABELS = {"1x", "1.5x", "2x", "2.5x", "3x"};

    Mman9VideoPlayer jzVideoPlayerStandard;
    private TextView speedBtn;
    private int speedIndex = 0;

    @Override
    public void initPlayerView() {
        videoPlayerContainer.removeAllViews();
        View view = LayoutInflater.from(this).inflate(R.layout.playback_engine_jiao_zi, videoPlayerContainer, true);
        jzVideoPlayerStandard = view.findViewById(R.id.videoplayer);
        speedBtn = view.findViewById(R.id.speed_btn);
        // 重试按钮改为「重新解析视频地址」拿新直链，而不是用旧的（很可能已过期的）地址重播
        jzVideoPlayerStandard.setRetryAction(() -> {
            if (v9MmanItem != null) {
                playVideoPresenter.loadVideoUrl(v9MmanItem);
            }
        });
        speedBtn.setOnClickListener(v -> cyclePlaybackSpeed());
    }

    /**
     * 切到下一档倍速。
     * <p>
     * 修复「状态欺骗」：旧实现无条件改按钮标签再尝试设置，
     * 而 {@link Mman9VideoPlayer#setPlaybackSpeed} 在「暂停态 / 非 MediaPlayer 通道」
     * 下会静默返回 false，导致按钮显示 1.5x、实际仍是 1x。
     * 现在先探测能力，失败则保持原标签并给出明确原因。
     */
    private void cyclePlaybackSpeed() {
        if (jzVideoPlayerStandard == null || speedBtn == null) {
            return;
        }
        int reason = jzVideoPlayerStandard.getSpeedUnsupportedReason();
        if (reason != 0) {
            showSpeedUnsupportedHint(reason);
            return;
        }
        int next = (speedIndex + 1) % SPEED_OPTIONS.length;
        if (!jzVideoPlayerStandard.setPlaybackSpeed(SPEED_OPTIONS[next])) {
            // 极端情况：探测通过但实际设置失败，同样回滚，绝不让标签与实际速度脱节
            showSpeedUnsupportedHint(jzVideoPlayerStandard.getSpeedUnsupportedReason());
            return;
        }
        speedIndex = next;
        speedBtn.setText(SPEED_LABELS[speedIndex]);
    }

    private void showSpeedUnsupportedHint(int reason) {
        int resId;
        switch (reason) {
            case Mman9VideoPlayer.SPEED_UNSUPPORTED_NOT_PLAYING:
                resId = R.string.speed_only_while_playing;
                break;
            case Mman9VideoPlayer.SPEED_UNSUPPORTED_ENGINE:
                resId = R.string.speed_unsupported_engine;
                break;
            default:
                resId = R.string.speed_unsupported;
                break;
        }
        showMessage(getString(resId), TastyToast.INFO);
    }

    @Override
    public void playVideo(String title, String videoUrl, String name, String thumImgUrl) {
        jzVideoPlayerStandard.setVisibility(View.VISIBLE);
        jzVideoPlayerStandard.setUp(videoUrl, JZVideoPlayerStandard.SCREEN_WINDOW_NORMAL, title);
        //自动播放
        jzVideoPlayerStandard.startButton.performClick();
        if (!TextUtils.isEmpty(thumImgUrl)) {
            // M27：thumbImageView.setImageURI 不支持网络图，改用 Glide 加载（与 ExoMediaPlayerActivity 一致）
            GlideApp.with(this).load(Uri.parse(thumImgUrl)).into(jzVideoPlayerStandard.thumbImageView);
        }
        // minSdk 28 > M，无需再判版本；倍速真正的可用性在点击时由
        // Mman9VideoPlayer#getSpeedUnsupportedReason() 实时探测。
        speedBtn.setVisibility(View.VISIBLE);
        // 新视频起播时播放器本身就是 1x，这里只同步 UI 状态。
        // 不要在此时调用 setPlaybackSpeed —— 此刻尚未进入播放态，调用必然失败。
        speedIndex = 0;
        speedBtn.setText(SPEED_LABELS[speedIndex]);
    }

    @Override
    public void onBackPressed() {
        if (JZVideoPlayer.backPress()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // M96：releaseAllVideos 前先显式暂停解码器——退全屏后 300ms 内切后台时，
        // release 流程可能来不及停音，导致页面已不可见但音频仍在播放。
        cn.jzvd.JZMediaManager.pause();
        JZVideoPlayer.releaseAllVideos();
    }
}
