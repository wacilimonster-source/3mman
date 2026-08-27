package com.m3man.ui.mman9video.play;

import android.content.Context;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import cn.jzvd.JZMediaManager;
import cn.jzvd.JZVideoPlayer;
import cn.jzvd.JZVideoPlayerManager;
import cn.jzvd.JZVideoPlayerStandard;

import java.io.IOException;
import java.net.ProxySelector;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * mman9video 专用播放器。
 * <p>
 * 针对「部分视频一直转圈 / 无法播放」做了两处增强：
 * <ol>
 *   <li><b>真实错误诊断（A）</b>：播放失败进入错误态、或长时间停留在「准备中」时，
 *       用 OkHttp 直接探测当前直链，把真实 HTTP 状态（403 过期 / 404 不存在 / 无法连接等）
 *       通过 Toast 告诉用户，而不是只转圈。</li>
 *   <li><b>重试重新解析</b>：原播放器的「重试」只会用旧地址重播（很可能仍是过期的 token）；
 *       这里拦截 retry 按钮，改为通过外部设置的动作（一般为 presenter 重新解析视频地址）
 *       拿到新的有效直链后再重播。</li>
 * </ol>
 *
 * @author 3mman
 */
public class Mman9VideoPlayer extends JZVideoPlayerStandard {

    /** 准备阶段超时阈值：超过该时长仍在「准备中」视为加载卡死，主动弹出重试。 */
    private static final long PREPARE_TIMEOUT_MS = 20_000L;

    /** 双击判定窗口（同时也是单击延迟） */
    private static final long DOUBLE_TAP_TIMEOUT = 260L;
    /** 长按判定阈值（ms） */
    private static final long LONG_PRESS_TIMEOUT = 400L;
    /** 长按快进倍速 */
    private static final float LONG_PRESS_SPEED = 2.0f;

    /** 外部设置：点击重试时执行的动作（一般为重新解析视频地址）。 */
    private Runnable mRetryAction;

    /** 当前播放页的重试回调由宿主在全屏克隆时显式复制，禁止静态持有 Activity 回调。 */

    /** 当前正在播放的直链，用于失败时诊断。 */
    private String mCurrentUrl;

    /** 是否已经做过一次诊断，避免错误态与看门狗重复弹 Toast。 */
    private boolean mDiagnosed;

    private OkHttpClient mClient;

    private final Handler mWatchdog = new Handler(Looper.getMainLooper());

    private final Runnable mWatchdogTask = new Runnable() {
        @Override
        public void run() {
            handleWatchdogTimeout();
        }
    };

    // ==================== 双击快进 ====================

    private final Handler tapHandler = new Handler(Looper.getMainLooper());
    private long lastTapTime;

    // ==================== 长按左1/2快进 ====================

    private long lastTouchDownTime;
    private float lastTouchX;
    private boolean longPressTriggered;
    private float speedBeforeLongPress = 1.0f;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (longPressTriggered) {
                return;
            }
            long elapsed = SystemClock.uptimeMillis() - lastTouchDownTime;
            if (elapsed < LONG_PRESS_TIMEOUT) {
                tapHandler.postDelayed(this, LONG_PRESS_TIMEOUT - elapsed);
                return;
            }
            float touchX = lastTouchX;
            if (touchX < getWidth() / 2f) {
                // M96：仅播放中允许长按变速，暂停态触发会造成隐式开播
                if (currentState != CURRENT_STATE_PLAYING) {
                    return;
                }
                longPressTriggered = true;
                speedBeforeLongPress = readCurrentSpeed();
                setPlaybackSpeed(LONG_PRESS_SPEED);
            }
        }
    };

    public Mman9VideoPlayer(Context context) {
        super(context);
        // M96：构造完成后控件已 inflate，同步全屏按钮显隐（覆盖克隆实例等入口）
        syncFullscreenButtonVisibility();
    }

    public Mman9VideoPlayer(Context context, AttributeSet attrs) {
        super(context, attrs);
        syncFullscreenButtonVisibility();
    }

    /** 设置「重试」按钮的回调（重新解析视频地址）。 */
    public void setRetryAction(Runnable action) {
        mRetryAction = action;
    }

    /** M96：按当前屏幕形态统一全屏按钮显隐（竖屏可见、全屏隐藏）。 */
    private void syncFullscreenButtonVisibility() {
        if (fullscreenButton != null) {
            fullscreenButton.setVisibility(currentScreen == SCREEN_WINDOW_FULLSCREEN
                    ? View.GONE : View.VISIBLE);
        }
    }

    /** 设置当前 MediaPlayer 倍速。 */
    public boolean setPlaybackSpeed(float speed) {
        if (!isCurrentPlayer() || Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || speed <= 0f) {
            return false;
        }
        // M96：非播放态直接拒绝——暂停态下 setPlaybackParams 会让 MediaPlayer 隐式恢复播放
        if (currentState != CURRENT_STATE_PLAYING) {
            return false;
        }
        try {
            if (!(JZMediaManager.instance().jzMediaInterface instanceof cn.jzvd.JZMediaSystem)) {
                return false;
            }
            MediaPlayer mediaPlayer = ((cn.jzvd.JZMediaSystem)
                    JZMediaManager.instance().jzMediaInterface).mediaPlayer;
            if (mediaPlayer == null) {
                return false;
            }
            PlaybackParams params = mediaPlayer.getPlaybackParams();
            params.setSpeed(speed);
            mediaPlayer.setPlaybackParams(params);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 本实例是否是 JZVD 当前持有解码器的播放器 */
    private boolean isCurrentPlayer() {
        return JZVideoPlayerManager.getCurrentJzvd() == this;
    }

    /** 播放器是否处于可读进度的状态 */
    private boolean isSeekable() {
        return isCurrentPlayer()
                && (currentState == CURRENT_STATE_PLAYING || currentState == CURRENT_STATE_PAUSE);
    }

    /** 安全取总时长，异常/未就绪返回 0 */
    private long safeDuration() {
        if (!isCurrentPlayer()) {
            return 0L;
        }
        try {
            long d = getDuration();
            return d > 0 ? d : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 安全取当前进度，异常/未就绪返回 0 */
    private long safePosition() {
        if (!isCurrentPlayer()) {
            return 0L;
        }
        try {
            long p = getCurrentPositionWhenPlaying();
            return p > 0 ? p : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 读取 MediaPlayer 当前真实播放速度；异常或不支持时返回 1.0f。 */
    private float readCurrentSpeed() {
        if (!isCurrentPlayer() || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return 1.0f;
        }
        try {
            if (!(JZMediaManager.instance().jzMediaInterface instanceof cn.jzvd.JZMediaSystem)) {
                return 1.0f;
            }
            MediaPlayer mediaPlayer = ((cn.jzvd.JZMediaSystem)
                    JZMediaManager.instance().jzMediaInterface).mediaPlayer;
            if (mediaPlayer == null) {
                return 1.0f;
            }
            return mediaPlayer.getPlaybackParams().getSpeed();
        } catch (Exception e) {
            return 1.0f;
        }
    }

    /** 双击快进：总时长的 1/8，贴尾钳制到 duration-1s，返回实际跳过毫秒数。 */
    public long seekForwardOneEighth() {
        if (!isSeekable()) {
            return 0L;
        }
        long duration = safeDuration();
        long current = safePosition();
        if (duration <= 0L) {
            return 0L;
        }
        long step = Math.max(1000L, duration / 8L);
        long target = Math.min(Math.max(0L, duration - 1000L), current + step);
        long actual = Math.max(0L, target - current);
        if (actual <= 0L) {
            return 0L;
        }
        try {
            boolean wasPlaying = currentState == CURRENT_STATE_PLAYING;
            JZMediaManager.seekTo(target);
            // seekTo 在部分系统实现中会暂停底层 MediaPlayer；双击快进应保持
            // 原有播放状态。原来就是暂停时则保持暂停。
            if (wasPlaying && isCurrentPlayer()) {
                JZMediaManager.start();
            }
            startProgressTimer();
            return actual;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    // ==================== 手势：双击快进 + 长按左1/2快进 ====================

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null) {
            return super.onTouchEvent(event);
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            lastTouchDownTime = SystemClock.uptimeMillis();
            lastTouchX = event.getX();
            longPressTriggered = false;
            tapHandler.removeCallbacks(longPressRunnable);
            tapHandler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            tapHandler.removeCallbacks(longPressRunnable);
            if (longPressTriggered) {
                longPressTriggered = false;
                setPlaybackSpeed(speedBeforeLongPress);
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            long now = SystemClock.uptimeMillis();
            float x = event.getX();
            if (now - lastTapTime < DOUBLE_TAP_TIMEOUT) {
                doubleTapDetected = true;
                onDoubleTapFastForward(x);
                lastTapTime = 0;
            } else {
                doubleTapDetected = false;
                lastTapTime = now;
            }
        }
        return super.onTouch(v, event);
    }

    private void onDoubleTapFastForward(float x) {
        long skipped = seekForwardOneEighth();
        if (skipped > 0) {
            showFastForwardToast(skipped);
        }
    }

    private void showFastForwardToast(long ms) {
        long sec = ms / 1000;
        String text = sec > 0 ? "快进 " + sec + " 秒" : "快进";
        Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show();
    }

    /** 双击检测标记：双击时 JZ 自带的 onClickUiToggle 需被抑制，防止同时触发全屏切换 */
    private boolean doubleTapDetected;

    @Override
    public void onClickUiToggle() {
        if (doubleTapDetected) {
            doubleTapDetected = false;
            return;
        }
        super.onClickUiToggle();
    }

    // ==================== 全屏：隐藏全屏按钮 ====================

    @Override
    public void startWindowFullscreen() {
        try {
            super.startWindowFullscreen();
        } catch (Exception e) {
            // M100：全屏创建失败（Activity 已销毁/Window 无效等），安全回退
            return;
        }
        // JZVD 会反射创建全屏克隆实例；把当前实例回调复制到克隆实例，
        // 不使用 static，避免跨页面引用旧 Activity。
        cn.jzvd.JZVideoPlayer current = JZVideoPlayerManager.getCurrentJzvd();
        if (current instanceof Mman9VideoPlayer && current != this) {
            ((Mman9VideoPlayer) current).setRetryAction(mRetryAction);
        }
        if (fullscreenButton != null) {
            fullscreenButton.setVisibility(View.GONE);
        }
    }

    @Override
    public void clearFullscreenLayout() {
        try {
            super.clearFullscreenLayout();
        } catch (Exception ignored) {
        }
        if (fullscreenButton != null) {
            fullscreenButton.setVisibility(View.VISIBLE);
        }
    }

    // ==================== 拦截重试按钮：改为重新解析 ====================

    @Override
    public void onClick(View v) {
        // mRetryBtn 在 JZVideoPlayerStandard.init() 中设置了点击监听
        if (v == mRetryBtn) {
            // 仅执行当前播放器实例绑定的动作，避免跨页面调用旧 Activity。
            if (mRetryAction != null) {
                mRetryAction.run();
            }
            return;
        }
        super.onClick(v);
    }

    // ==================== 准备 / 状态切换：看门狗 + 诊断 ====================

    @Override
    public void setUp(String url, int screen, Object... objects) {
        mCurrentUrl = url;
        mDiagnosed = false;
        super.setUp(url, screen, objects);
        // M96：setUp 会走到全屏克隆实例，这里统一纠正全屏按钮显隐
        syncFullscreenButtonVisibility();
        startWatchdog();
    }

    @Override
    public void onStatePreparing() {
        // 进入准备（含重试后重新播放），重置看门狗
        super.onStatePreparing();
        startWatchdog();
    }

    @Override
    public void onStatePrepared() {
        cancelWatchdog();
        super.onStatePrepared();
    }

    @Override
    public void onStatePlaying() {
        cancelWatchdog();
        super.onStatePlaying();
    }

    @Override
    public void onStatePause() {
        cancelWatchdog();
        super.onStatePause();
    }

    @Override
    public void onStateNormal() {
        cancelWatchdog();
        super.onStateNormal();
    }

    @Override
    public void onStateAutoComplete() {
        cancelWatchdog();
        super.onStateAutoComplete();
    }

    @Override
    public void onStateError() {
        cancelWatchdog();
        super.onStateError(); // 显示重试 UI
        diagnose();
    }

    // ==================== 视图脱离窗口：清空回调 ====================

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // M96：脱离窗口时清空看门狗与手势延迟任务，并复位长按标记，
        // 防止回收的实例在 detached 后仍被 Handler 唤醒（误弹 Toast/误变速）。
        cancelWatchdog();
        mWatchdog.removeCallbacksAndMessages(null);
        tapHandler.removeCallbacksAndMessages(null);
        longPressTriggered = false;
    }

    // ==================== 看门狗：准备超时主动报错 ====================

    private void handleWatchdogTimeout() {
        if (currentState == JZVideoPlayer.CURRENT_STATE_PREPARING
                || currentState == JZVideoPlayer.CURRENT_STATE_PREPARING_CHANGING_URL) {
            // 绕过自身 onStateError（避免重复诊断），直接显示错误/重试 UI
            super.onStateError();
            diagnose();
        }
    }

    private void startWatchdog() {
        cancelWatchdog();
        mWatchdog.postDelayed(mWatchdogTask, PREPARE_TIMEOUT_MS);
    }

    private void cancelWatchdog() {
        mWatchdog.removeCallbacks(mWatchdogTask);
    }

    /**
     * 用 OkHttp 探测当前直链的真实响应，把原因以 Toast 反馈给用户。
     */
    private void diagnose() {
        if (mDiagnosed) {
            return;
        }
        mDiagnosed = true;

        final String url = mCurrentUrl;
        if (TextUtils.isEmpty(url)) {
            showToast("视频地址为空，无法播放");
            return;
        }

        getClient().newCall(new Request.Builder()
                .url(url)
                .header("Range", "bytes=0-1")
                .build())
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        // DNS / 连接被拒 / 超时 / 被墙 等
                        String reason = e.getClass().getSimpleName();
                        showToast("无法连接视频服务器（" + reason + "），点击「重试」可重新解析地址");
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        int code = response.code();
                        response.close();
                        String msg;
                        if (code == 200 || code == 206) {
                            msg = "视频地址有效（HTTP " + code + "），但播放器加载失败，点击「重试」重新解析";
                        } else if (code == 403) {
                            msg = "视频地址已过期或无权限（HTTP 403），点击「重试」重新解析获取新地址";
                        } else if (code == 404) {
                            msg = "视频文件不存在（HTTP 404）";
                        } else {
                            msg = "视频地址返回异常状态 HTTP " + code + "，点击「重试」重新解析";
                        }
                        showToast(msg);
                    }
                });
    }

    private void showToast(final String msg) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
        } else {
            mWatchdog.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private OkHttpClient getClient() {
        if (mClient == null) {
            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS);
            // 诊断请求不能绕过应用代理，否则会把“代理可达”误判成“源站不可达”。
            ProxySelector selector = ProxySelector.getDefault();
            if (selector != null) {
                builder.proxySelector(selector);
            }
            mClient = builder.build();
        }
        return mClient;
    }
}
