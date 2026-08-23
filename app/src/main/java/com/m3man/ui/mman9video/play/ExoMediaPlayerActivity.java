package com.m3man.ui.mman9video.play;

import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;

import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.devbrackets.android.exomedia.listener.OnErrorListener;
import com.devbrackets.android.exomedia.listener.OnPreparedListener;
import com.devbrackets.android.exomedia.listener.OnBufferUpdateListener;
import com.flymegoc.exolibrary.widget.ExoVideoControlsMobile;
import com.flymegoc.exolibrary.widget.ExoVideoView;
import com.m3man.R;
import com.m3man.utils.AppLog;
import com.m3man.utils.GlideApp;

/**
 * @author flymegoc
 */
public class ExoMediaPlayerActivity extends BasePlayVideo implements OnPreparedListener {

    private static final String TAG = ExoMediaPlayerActivity.class.getSimpleName();
    private ExoVideoView videoPlayer;
    private ExoVideoControlsMobile videoControlsMobile;
    private boolean isPauseByActivityEvent = false;
    /** M70：当前播放 URL（用于错误日志定位是哪个请求失败） */
    private String currentPlayUrl = "";
    /** M70：缓冲 0% 只打一次日志的节流标记 */
    private boolean bufferZeroLogged = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void initPlayerView() {
        videoPlayerContainer.removeAllViews();
        View view = LayoutInflater.from(this).inflate(R.layout.playback_engine_exo_media, videoPlayerContainer, true);
        videoPlayer = view.findViewById(R.id.video_view);
        videoControlsMobile = (ExoVideoControlsMobile) videoPlayer.getVideoControls();
        videoPlayer.setOnPreparedListener(this);
        videoPlayer.setLeftPressSpeedEnabled(true);
        // M70：诊断埋点——播放器引擎的 HTTP 栈不经过 App 拦截器，[DIAG] 日志看不到拉流请求。
        // 这里补上错误与缓冲回调，转圈无画面时能从 logcat 直接看到具体失败原因。
        videoPlayer.setOnErrorListener(new OnErrorListener() {
            @Override
            public boolean onError(Exception e) {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                String stack = sw.toString();
                AppLog.e("Player", "播放失败 viewKey=" + getViewKeyForLog()
                        + " url=" + currentPlayUrl
                        + " err=" + e.getClass().getName()
                        + " msg=" + e.getMessage()
                        + " stack=" + stack.substring(0, Math.min(800, stack.length())));
                return false; // 不消费，交给库默认处理（弹窗/停止）
            }
        });
        videoPlayer.setOnBufferUpdateListener(new OnBufferUpdateListener() {
            @Override
            public void onBufferingUpdate(int percent) {
                if (percent <= 0) {
                    // 首帧前持续 0% 说明拉流没建立，只打一次避免刷屏
                    if (!bufferZeroLogged) {
                        bufferZeroLogged = true;
                        AppLog.i("Player", "起播缓冲 0%（拉流未建立或极慢） viewKey=" + getViewKeyForLog()
                                + " url=" + currentPlayUrl);
                    }
                } else {
                    bufferZeroLogged = false;
                }
            }
        });
    }

    private String getViewKeyForLog() {
        try {
            return v9MmanItem == null ? "local" : v9MmanItem.getViewKey();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Override
    public void playVideo(String title, String videoUrl, String name, String thumImgUrl) {

        if (isPauseByActivityEvent) {
            isPauseByActivityEvent = false;
            videoPlayer.reset();
        }
        videoControlsMobile.setOnBackButtonClickListener(new ExoVideoControlsMobile.OnBackButtonClickListener() {
            @Override
            public void onBackClick(View view) {
                onBackPressed();
            }
        });
        if (!TextUtils.isEmpty(thumImgUrl)) {
            GlideApp.with(this).load(Uri.parse(thumImgUrl)).transition(new DrawableTransitionOptions().crossFade(300)).into(videoPlayer.getPreviewImageView());
        }
        videoPlayer.setVideoURI(Uri.parse(videoUrl));
        videoControlsMobile.setTitle(title);
        // M70：记录本次播放 URL + 起播日志，配合错误/缓冲埋点定位转圈问题
        currentPlayUrl = videoUrl;
        bufferZeroLogged = false;
        AppLog.i("Player", "起播请求 viewKey=" + getViewKeyForLog() + " url=" + videoUrl);
    }

    @Override
    public void onPrepared() {
        videoPlayer.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!videoPlayer.isPlaying() && isPauseByActivityEvent) {
            isPauseByActivityEvent = false;
            videoPlayer.start();
        }
    }

    @Override
    protected void onPause() {
        videoPlayer.pause();
        isPauseByActivityEvent = true;
        super.onPause();

    }

    @Override
    public void onBackPressed() {
        if (videoControlsMobile.onBackPressed()) {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (videoPlayer.getParent() != null) {
            videoPlayerContainer.removeView(videoPlayer);
        }
        videoPlayer.release();
        super.onDestroy();
    }
}
