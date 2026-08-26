package com.m3man.ui.mman9video.play;

import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.m3man.R;
import com.m3man.utils.GlideApp;

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
        speedBtn.setOnClickListener(v -> {
            speedIndex = (speedIndex + 1) % SPEED_OPTIONS.length;
            speedBtn.setText(SPEED_LABELS[speedIndex]);
            jzVideoPlayerStandard.setPlaybackSpeed(SPEED_OPTIONS[speedIndex]);
        });
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            speedBtn.setVisibility(View.VISIBLE);
        }
        speedIndex = 0;
        speedBtn.setText(SPEED_LABELS[speedIndex]);
        jzVideoPlayerStandard.setPlaybackSpeed(SPEED_OPTIONS[speedIndex]);
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
