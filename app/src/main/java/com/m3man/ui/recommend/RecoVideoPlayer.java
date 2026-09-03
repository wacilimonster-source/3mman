package com.m3man.ui.recommend;

import android.content.Context;
import android.graphics.Rect;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import cn.jzvd.JZMediaManager;
import cn.jzvd.JZVideoPlayerManager;
import cn.jzvd.JZVideoPlayerStandard;

/**
 * 推荐流专用播放器。
 * <p>
 * 相比标准播放器做了三处改造：
 * <ol>
 *   <li><b>沉浸式</b>：常驻隐藏顶栏 / 底部控制栏，只留一条底部细进度条，交互靠手势。</li>
 *   <li><b>循环播放</b>：播完自动从头再来，与短视频流的体感一致。</li>
 *   <li><b>单击暂停 / 双击点赞</b>：单击延迟 {@link #DOUBLE_TAP_TIMEOUT} 执行，
 *       期间若来第二次点击则升级为双击，回调给外部做点赞。</li>
 * </ol>
 *
 * @author 3mman
 */
public class RecoVideoPlayer extends JZVideoPlayerStandard {

    /** 双击判定窗口（同时也是单击延迟） */
    private static final long DOUBLE_TAP_TIMEOUT = 260L;

    public interface OnTapListener {
        /** 双击（传入本次双击的横坐标，用于左 1/3 点赞 / 其余区域快进） */
        void onDoubleTap(RecoVideoPlayer player, float x);

        /** 单击（一般用于播放 / 暂停） */
        void onSingleTap(RecoVideoPlayer player);
    }

    private final Handler tapHandler = new Handler(Looper.getMainLooper());
    private OnTapListener tapListener;
    private long lastTapTime;
    private float pendingTapX;
    /** 是否循环播放 */
    private boolean loopEnabled = true;
    /**
     * M98：单击延迟 Runnable 是否已投递。
     * 原 longPressRunnable 用 tapHandler.hasMessages(0) 做守卫，但 Handler 消息队列里
     * what==0 的消息不止本类手势（框架/父类也会投），守卫会误判失效；改用显式标志位。
     */
    private boolean singleTapPosted;

    private final Runnable singleTapRunnable = new Runnable() {
        @Override
        public void run() {
            // M98：延迟到期真正执行，标志复位
            singleTapPosted = false;
            if (tapListener != null) {
                tapListener.onSingleTap(RecoVideoPlayer.this);
            } else {
                togglePlayPause();
            }
        }
    };

    public RecoVideoPlayer(Context context) {
        super(context);
    }

    public RecoVideoPlayer(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setOnTapListener(OnTapListener listener) {
        this.tapListener = listener;
    }

    public void setLoopEnabled(boolean loopEnabled) {
        this.loopEnabled = loopEnabled;
    }

    /** 播放 / 暂停切换 */
    public void togglePlayPause() {
        if (startButton != null) {
            startButton.performClick();
        }
    }

    public boolean isPlaying() {
        return currentState == CURRENT_STATE_PLAYING;
    }

    /** 本实例是否是 JZVD 当前持有解码器的播放器（判断播放归属，防串台） */
    public boolean isCurrentPlayer() {
        return JZVideoPlayerManager.getCurrentJzvd() == this;
    }

    /** 播放器是否处于可读进度的状态 */
    public boolean isSeekable() {
        return isCurrentPlayer()
                && (currentState == CURRENT_STATE_PLAYING || currentState == CURRENT_STATE_PAUSE);
    }

    /** 安全取总时长，异常/未就绪返回 0 */
    public long safeDuration() {
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
    public long safePosition() {
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

    /**
     * 按外部 SeekBar 的刻度跳转。
     *
     * @param progress 当前刻度
     * @param max      刻度上限
     * @return 是否真正执行了跳转
     */
    public boolean seekToProgress(int progress, int max) {
        if (max <= 0 || !isSeekable()) {
            return false;
        }
        long duration = safeDuration();
        if (duration <= 0) {
            return false;
        }
        try {
            long time = duration * progress / max;
            if (time < 0) {
                time = 0;
            } else if (time > duration - 1000) {
                // 贴到末尾容易直接触发 completion，回退 1s
                time = Math.max(0, duration - 1000);
            }
            JZMediaManager.seekTo(time);
            startProgressTimer();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 已观看比例，用于隐式反馈打分。取值 0~1，拿不到时长时返回 0。
     */
    public float watchedRatio() {
        try {
            if (!isSeekable()) {
                return 0f;
            }
            long duration = safeDuration();
            if (duration <= 0) {
                return 0f;
            }
            long position = safePosition();
            float ratio = position * 1.0f / duration;
            if (ratio < 0f) {
                return 0f;
            }
            return ratio > 1f ? 1f : ratio;
        } catch (Exception e) {
            return 0f;
        }
    }

    // ==================== 沉浸式 UI ====================

    /**
     * 顶栏与底部控制栏永远不显示；其余（大播放按钮 / 菊花 / 封面 / 细进度条 / 重试）保持原逻辑。
     */
    @Override
    public void setAllControlsVisiblity(int topCon, int bottomCon, int startBtn, int loadingPro,
                                        int thumbImg, int bottomPro, int retryLayout) {
        super.setAllControlsVisiblity(View.INVISIBLE, View.INVISIBLE, startBtn, loadingPro,
                thumbImg, bottomPro, retryLayout);
    }

    // ==================== 手势 ====================

    @Override
    public void onClickUiToggle() {
        long now = SystemClock.uptimeMillis();
        if (lastTapTime > 0 && now - lastTapTime < DOUBLE_TAP_TIMEOUT) {
            lastTapTime = 0;
            tapHandler.removeCallbacks(singleTapRunnable);
            // M98：单击已升级为双击，投递标志复位
            singleTapPosted = false;
            if (tapListener != null) {
                tapListener.onDoubleTap(this, pendingTapX);
            }
            return;
        }
        lastTapTime = now;
        pendingTapX = getLastTouchX();
        tapHandler.removeCallbacks(singleTapRunnable);
        // M98：post 前先置位，保证长按守卫能正确看到「有挂起的单击」
        singleTapPosted = true;
        tapHandler.postDelayed(singleTapRunnable, DOUBLE_TAP_TIMEOUT);
    }

    /** 长按判定阈值（ms） */
    private static final long LONG_PRESS_TIMEOUT = 400L;
    /** 长按快进倍速 */
    private static final float LONG_PRESS_SPEED = 2.0f;

    private float lastTouchX = -1f;
    private boolean longPressTriggered;
    private float speedBeforeLongPress = 1.0f;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            // M98：守卫改用 singleTapPosted——hasMessages(0) 会把队列里其它 what==0 的
            // 消息（框架/父类投递）误判成本类挂起单击，导致长按快进失效
            if (longPressTriggered || singleTapPosted) {
                return;
            }
            float touchX = getLastTouchX();
            if (touchX < getWidth() / 2f) {
                longPressTriggered = true;
                speedBeforeLongPress = readCurrentSpeed();
                setPlaybackSpeed(LONG_PRESS_SPEED);
            }
        }
    };

    private float getLastTouchX() {
        return lastTouchX < 0f ? getWidth() / 2f : lastTouchX;
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

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null) {
            return super.onTouchEvent(event);
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            lastTouchX = event.getX();
            longPressTriggered = false;
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

    /** 双击快进：总时长的 1/8，贴尾钳制到 duration-1s，返回实际跳过毫秒数。 */

    /** 倍速不可用：系统版本过低（API < 23）。本项目 minSdk 28，理论上不会触发。 */
    public static final int SPEED_UNSUPPORTED_OS = 1;
    /** 倍速不可用：当前并非「正在播放」态。 */
    public static final int SPEED_UNSUPPORTED_NOT_PLAYING = 2;
    /** 倍速不可用：当前播放通道不是 JZMediaSystem，拿不到 MediaPlayer。 */
    public static final int SPEED_UNSUPPORTED_ENGINE = 3;

    /**
     * 返回倍速不可用的原因码：0 = 可用。
     * 让 UI 能给出一句明确原因，而不是笼统的「不支持」。
     */
    public int getSpeedUnsupportedReason() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return SPEED_UNSUPPORTED_OS;
        }
        if (!isCurrentPlayer()) {
            return SPEED_UNSUPPORTED_NOT_PLAYING;
        }
        if (currentState != CURRENT_STATE_PLAYING) {
            return SPEED_UNSUPPORTED_NOT_PLAYING;
        }
        JZMediaManager manager = JZMediaManager.instance();
        if (manager == null || !(manager.jzMediaInterface instanceof cn.jzvd.JZMediaSystem)
                || ((cn.jzvd.JZMediaSystem) manager.jzMediaInterface).mediaPlayer == null) {
            return SPEED_UNSUPPORTED_ENGINE;
        }
        return 0;
    }

    /**
     * 设置当前 MediaPlayer 倍速；推荐流每次绑定新视频时由外部恢复为 1x。
     * <p>
     * 注意：本方法在「暂停态 / 非 MediaPlayer 通道」下会返回 false，
     * 调用方必须先判断返回值再改 UI 标签，否则会出现「显示 2x、实际 1x」的状态欺骗。
     */
    public boolean setPlaybackSpeed(float speed) {
        if (!isCurrentPlayer() || Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || speed <= 0f) {
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
            // JZMediaManager.seekTo() 在部分 Android/MediaPlayer 版本上会使底层
            // MediaPlayer 进入暂停态；快进前若正在播放，必须显式恢复，否则表现为
            // “进度跳过去了但快进后不再播放”。原来就是暂停时则保持暂停。
            if (wasPlaying && isCurrentPlayer()) {
                JZMediaManager.start();
            }
            startProgressTimer();
            return actual;
        } catch (Exception e) {
            return 0L;
        }
    }

    // ==================== 循环播放 ====================
    @Override
    public void onAutoCompletion() {
        super.onAutoCompletion();
        if (!loopEnabled) {
            return;
        }
        // 交给主线程下一帧再起播，避免和 release 流程抢资源
        tapHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    // 必须仍然「基本占满屏幕」才续播。
                    // 已滑走但尚未回收的页面若在这里重新 startVideo，
                    // 会通过 completeAll() 抢走当前页的解码器，
                    // 表现为「画面是上一条、标题是当前条」。
                    if (loopEnabled && getParent() != null && isMostlyVisible()) {
                        startVideo();
                    }
                } catch (Exception ignored) {
                    // 页面已回收
                }
            }
        });
    }

    /** 视图在屏幕上的可见高度是否过半（用来判断这一页是不是「当前页」） */
    private boolean isMostlyVisible() {
        if (!isShown()) {
            return false;
        }
        int height = getHeight();
        if (height <= 0) {
            return false;
        }
        Rect rect = new Rect();
        if (!getGlobalVisibleRect(rect)) {
            return false;
        }
        return rect.height() * 2 >= height;
    }

    /**
     * M98：无条件取消所有挂起手势并复位相关标志，但**不** release 播放器。
     * 供 Adapter 回收 ViewHolder 时对每一页调用（含非当前播放页），
     * 防止延迟中的单击/长按/双击回调在 Holder 复用后误触发到新视频上。
     */
    public void cancelPendingGestures() {
        tapHandler.removeCallbacksAndMessages(null);
        lastTapTime = 0;
        pendingTapX = 0f;
        longPressTriggered = false;
        singleTapPosted = false;
    }

    @Override
    public void release() {
        tapHandler.removeCallbacksAndMessages(null);
        lastTapTime = 0;
        // M98：与 cancelPendingGestures 保持一致，复位全部手势标志
        pendingTapX = 0f;
        longPressTriggered = false;
        singleTapPosted = false;
        super.release();
    }
}
