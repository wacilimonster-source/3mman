package com.m3man.ui.mman9video.play;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.devbrackets.android.exomedia.listener.OnErrorListener;
import com.devbrackets.android.exomedia.listener.OnPreparedListener;
import com.devbrackets.android.exomedia.listener.OnBufferUpdateListener;
import com.flymegoc.exolibrary.widget.ExoVideoControlsMobile;
import com.flymegoc.exolibrary.widget.ExoVideoView;
import com.m3man.R;
import com.m3man.utils.AppLog;
import com.m3man.utils.GlideApp;

import java.io.IOException;
import java.net.ProxySelector;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * @author flymegoc
 */
public class ExoMediaPlayerActivity extends BasePlayVideo implements OnPreparedListener {

    private static final String TAG = ExoMediaPlayerActivity.class.getSimpleName();
    /** M92f：准备阶段看门狗阈值（与 JiaoZi 引擎 Mman9VideoPlayer 对齐） */
    private static final long PREPARE_TIMEOUT_MS = 20_000L;
    private ExoVideoView videoPlayer;
    private ExoVideoControlsMobile videoControlsMobile;
    private boolean isPauseByActivityEvent = false;
    /** M70：当前播放 URL（用于错误日志定位是哪个请求失败） */
    private String currentPlayUrl = "";
    /** M70：缓冲 0% 只打一次日志的节流标记 */
    private boolean bufferZeroLogged = false;

    // ==================== M92f：错误自愈（与 JiaoZi 引擎能力对齐） ====================

    private final Handler watchdog = new Handler(Looper.getMainLooper());
    /** 直链诊断探测客户端（带系统代理，避免把代理可达误判为源站不可达） */
    private OkHttpClient probeClient;
    /** 本次播放是否已做过一次「重新解析」自愈，防止失败→解析→失败死循环 */
    private boolean healAttempted;

    private final Runnable prepareTimeoutTask = new Runnable() {
        @Override
        public void run() {
            onPrepareTimeout();
        }
    };

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
                cancelWatchdog();
                // M92f：错误时先诊断直链真实状态，再尝试自动重新解析一次
                diagnose(currentPlayUrl);
                attemptReparseOnce("播放出错");
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

    // ==================== M92f：看门狗 / 诊断 / 自动重新解析 ====================

    private void startWatchdog() {
        cancelWatchdog();
        watchdog.postDelayed(prepareTimeoutTask, PREPARE_TIMEOUT_MS);
    }

    private void cancelWatchdog() {
        watchdog.removeCallbacks(prepareTimeoutTask);
    }

    private void onPrepareTimeout() {
        AppLog.e("Player", "起播看门狗超时(20s) viewKey=" + getViewKeyForLog()
                + " url=" + currentPlayUrl + " 已尝试自愈=" + healAttempted);
        diagnose(currentPlayUrl);
        attemptReparseOnce("加载超时");
    }

    /**
     * 自动重新解析视频地址拿新直链（每次播放至多一次）。
     * 解析成功后 BasePlayVideo 流程会回调 playVideo(...) 重设播放器。
     */
    private void attemptReparseOnce(final String reason) {
        if (healAttempted || v9MmanItem == null || playVideoPresenter == null) {
            return;
        }
        healAttempted = true;
        Toast.makeText(this, reason + "，正在重新解析视频地址…", Toast.LENGTH_SHORT).show();
        watchdog.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                AppLog.i("Player", "自愈：重新解析 viewKey=" + getViewKeyForLog() + " 原因=" + reason);
                playVideoPresenter.loadVideoUrl(v9MmanItem);
            }
        }, 800L);
    }

    /** OkHttp 探测直链真实 HTTP 状态并 Toast 用户（403 过期 / 404 不存在 / 连接失败等） */
    private void diagnose(final String url) {
        if (TextUtils.isEmpty(url) || url.startsWith("file://")) {
            return;
        }
        getProbeClient().newCall(new Request.Builder()
                .url(url)
                .header("Range", "bytes=0-1")
                .build())
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        showToast("无法连接视频服务器（" + e.getClass().getSimpleName() + "），已自动重新解析");
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        int code = response.code();
                        response.close();
                        String msg;
                        if (code == 200 || code == 206) {
                            msg = "视频地址有效（HTTP " + code + "），正在自动重新解析";
                        } else if (code == 403) {
                            msg = "视频地址已过期（HTTP 403），正在获取新地址";
                        } else if (code == 404) {
                            msg = "视频文件不存在（HTTP 404）";
                        } else {
                            msg = "视频地址异常 HTTP " + code + "，正在重新解析";
                        }
                        showToast(msg);
                    }
                });
    }

    private void showToast(final String msg) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        } else {
            watchdog.post(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(ExoMediaPlayerActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                }
            });
        }
    }

    private OkHttpClient getProbeClient() {
        if (probeClient == null) {
            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS);
            ProxySelector selector = ProxySelector.getDefault();
            if (selector != null) {
                builder.proxySelector(selector);
            }
            probeClient = builder.build();
        }
        return probeClient;
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
        // M92f：每次新起播重置自愈标记并启动准备看门狗
        healAttempted = false;
        startWatchdog();
        AppLog.i("Player", "起播请求 viewKey=" + getViewKeyForLog() + " url=" + videoUrl);
    }

    @Override
    public void onPrepared() {
        cancelWatchdog();
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
