package com.m3man.ui.mman9video.play;

import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;

import com.m3man.R;
import com.m3man.utils.GlideApp;

import cn.jzvd.JZVideoPlayer;
import cn.jzvd.JZVideoPlayerStandard;

/**
 * @author flymegoc
 */
public class JiaoZiVideoPlayerActivity extends BasePlayVideo {

    JZVideoPlayerStandard jzVideoPlayerStandard;

    @Override
    public void initPlayerView() {
        videoPlayerContainer.removeAllViews();
        View view = LayoutInflater.from(this).inflate(R.layout.playback_engine_jiao_zi, videoPlayerContainer, true);
        jzVideoPlayerStandard = view.findViewById(R.id.videoplayer);
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
        JZVideoPlayer.releaseAllVideos();
    }
}
