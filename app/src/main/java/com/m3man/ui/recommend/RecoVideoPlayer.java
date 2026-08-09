package com.m3man.ui.recommend;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
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
        /** 双击（一般用于点赞） */
        void onDoubleTap(RecoVideoPlayer player);

        /** 单击（一般用于播放 / 暂停） */
        void onSingleTap(RecoVideoPlayer player);
    }

    private final Handler tapHandler = new Handler(Looper.getMainLooper());
    private OnTapListener tapListener;
    private long lastTapTime;
    /** 是否循环播放 */
    private boolean loopEnabled = true;

    private final Runnable singleTapRunnable = new Runnable() {
        @Override
        public void run() {
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
            long duration = getDuration();
            if (duration <= 0) {
                return 0f;
            }
            long position = getCurrentPositionWhenPlaying();
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
            if (tapListener != null) {
                tapListener.onDoubleTap(this);
            }
            return;
        }
        lastTapTime = now;
        tapHandler.removeCallbacks(singleTapRunnable);
        tapHandler.postDelayed(singleTapRunnable, DOUBLE_TAP_TIMEOUT);
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

    @Override
    public void release() {
        tapHandler.removeCallbacksAndMessages(null);
        lastTapTime = 0;
        super.release();
    }
}
