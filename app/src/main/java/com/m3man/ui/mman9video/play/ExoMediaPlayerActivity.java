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
    /** M96：直链诊断探测客户端（带系统代理，避免把代理可达误判为源站不可达）；
     *  改为类级 static volatile 单例缓存，避免每个 Activity 实例重建连接池 */
    private static volatile OkHttpClient probeClient;
    /** M96：上一次触发自愈的直链。同一直链至多自愈一次：
     *  解析后拿到新地址（≠lastHealUrl）自然解锁下一次机会；
     *  若解析器仍返回旧地址则不再重试，杜绝「失败→重新解析→失败」死循环。
     *  用户手动下拉刷新/重进页面会带来新 URL，自动获得新的自愈机会。 */
    private String lastHealUrl;

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
                // M96：日志脱敏——直链只留 scheme://host/path，不打印 query（含签名/token）
                AppLog.e("Player", "播放失败 viewKey=" + getViewKeyForLog()
                        + " url=" + maskUrl(currentPlayUrl)
                        + " err=" + e.getClass().getName()
                        + " msg=" + e.getMessage()
                        + " stack=" + stack.substring(0, Math.min(800, stack.length())));
                // M96：转发给控件库，恢复被覆盖的原监听所维护的内部状态机（错误图标/收起转圈）
                videoPlayer.notifyControlsPlaybackError();
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
                                + " url=" + maskUrl(currentPlayUrl));
                    }
                } else {
                    bufferZeroLogged = false;
                }
                // M96：转发缓冲进度给控件库，维持其「转圈」显隐状态机（原库内监听已被本回调覆盖）
                videoPlayer.notifyControlsBufferUpdate(percent);
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
                + " url=" + maskUrl(currentPlayUrl)
                + " 同址已自愈=" + (currentPlayUrl != null && currentPlayUrl.equals(lastHealUrl)));
        diagnose(currentPlayUrl);
        attemptReparseOnce("加载超时");
    }

    /**
     * 自动重新解析视频地址拿新直链。
     * M96：改为「同一直链至多自愈一次」（lastHealUrl 判定）——解析成功后 BasePlayVideo
     * 流程回调 playVideo(...) 重设播放器；若仍失败且地址未变化则不再重复尝试，防止死循环。
     */
    private void attemptReparseOnce(final String reason) {
        if (v9MmanItem == null || playVideoPresenter == null) {
            return;
        }
        // M96：同一 URL 只自愈一次，避免持续失败时无限「错误→重新解析」
        if (TextUtils.isEmpty(currentPlayUrl) || currentPlayUrl.equals(lastHealUrl)) {
            return;
        }
        lastHealUrl = currentPlayUrl;
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

    /**
     * M96：日志脱敏——直链只保留 scheme://host/path（丢弃 query 中的签名/token 等），
     * 并追加原始长度便于排查；解析失败时兜底截断，绝不打印完整直链。
     */
    private static String maskUrl(String u) {
        if (u == null) {
            return "";
        }
        int len = u.length();
        String head = u;
        try {
            java.net.URI uri = java.net.URI.create(u);
            StringBuilder sb = new StringBuilder();
            if (uri.getScheme() != null) {
                sb.append(uri.getScheme()).append("://");
            }
            if (uri.getHost() != null) {
                sb.append(uri.getHost());
            }
            if (uri.getPath() != null) {
                sb.append(uri.getPath());
            }
            if (sb.length() > 0) {
                head = sb.toString();
            }
        } catch (Exception ignored) {
            int q = u.indexOf('?');
            if (q >= 0) {
                head = u.substring(0, q);
            }
        }
        if (head.length() > 96) {
            head = head.substring(0, 96) + "...";
        }
        return head + "|len=" + len;
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
        // M96：自愈机会由 lastHealUrl 与新 URL 的差异自然判定，这里不再无条件重置；
        // 仅启动准备看门狗。
        startWatchdog();
        AppLog.i("Player", "起播请求 viewKey=" + getViewKeyForLog() + " url=" + maskUrl(videoUrl));
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
        // M96：仅当确实处于播放中才视为「由页面事件暂停」；
        // 用户手动暂停后切后台再回来，不再被 onResume 强制续播。
        boolean wasPlaying = false;
        try {
            wasPlaying = videoPlayer.isPlaying();
        } catch (Exception ignored) {
        }
        if (wasPlaying) {
            videoPlayer.pause();
            isPauseByActivityEvent = true;
        } else {
            isPauseByActivityEvent = false;
        }
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        // M108-fix：修正 v1.0.106（M105-fix）把返回值语义读反的问题。
        // 本项目 fork 的 ExoVideoControlsMobile.onBackPressed() 实际语义是：
        //   全屏时 → exitFullScreen() 并返回 false（已消费，本次不退出页面）；
        //   非全屏时 → 返回 true（未消费，应退出页面）。
        // v1.0.106 按「true=已消费」处理，导致非全屏按返回永远走不到 super.onBackPressed()，
        // 播放页无法返回；全屏按返回反而直接结束页面。此处按实际语义反转判断：
        //   false（已退全屏）→ 结束本次返回；true（非全屏）→ 继续走 super 正常退出。
        // 判空与异常保护沿用 M105-fix，控件库异常不让返回键崩溃整个 app。
        if (videoControlsMobile != null) {
            try {
                if (!videoControlsMobile.onBackPressed()) {
                    // 已消费：退出了全屏，本次不结束 Activity，等下一次返回再退出
                    return;
                }
            } catch (Throwable t) {
                // 控件库异常绝不让返回键崩溃整个 app
                AppLog.w(TAG, "videoControlsMobile.onBackPressed 异常 " + AppLog.cause(t));
            }
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        // M96：销毁时清空看门狗与 Toast 等主线程回调，防止泄漏与迟到弹窗
        cancelWatchdog();
        watchdog.removeCallbacksAndMessages(null);
        // M105-fix：销毁链 Null 保护，避免 removeView/release 抛异常导致 app 闪退
        try {
            if (videoPlayer != null && videoPlayerContainer != null && videoPlayer.getParent() != null) {
                videoPlayerContainer.removeView(videoPlayer);
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "removeView 异常 " + AppLog.cause(t));
        }
        try {
            if (videoPlayer != null) {
                videoPlayer.release();
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "videoPlayer.release 异常 " + AppLog.cause(t));
        }
        super.onDestroy();
    }
}
