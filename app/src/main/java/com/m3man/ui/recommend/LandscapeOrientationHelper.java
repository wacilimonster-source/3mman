package com.m3man.ui.recommend;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Build;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.m3man.R;
import com.m3man.data.reco.RecoCandidate;
import com.m3man.ui.BaseAppCompatActivity;
import com.m3man.utils.PlayUiPrefs;
import cn.jzvd.JZVideoPlayer;

/**
 * 推荐页横屏/沉浸模式逻辑，从 RecommendFeedFragment 提取。
 * <p>
 * 职责：自动旋转判定、横屏全屏沉浸、竖屏恢复、系统栏颜色管理。
 */
class LandscapeOrientationHelper {

    /** 宿主必须实现的接口，用于访问 Fragment 的 Activity、View 和 Adapter */
    interface Host {
        Activity getActivity();
        RecommendFeedAdapter getAdapter();
        RecyclerView getRecyclerView();
        View getLandscapeBackView();
        int getNormalContentBottomMargin();
        void setNormalContentBottomMargin(int value);
    }

    private final Host host;
    // M-05: JZVideoPlayer.NORMAL_ORIENTATION 是静态可变字段，多线程访问需同步
    private static final Object JZ_NORMAL_ORIENTATION_LOCK = new Object();
    private int appliedPlaybackOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private boolean landscapeLock;

    LandscapeOrientationHelper(Host host) {
        this.host = host;
    }

    boolean isLandscapeLock() {
        return landscapeLock;
    }

    /** 进程重建后恢复横屏锁 / 全屏点击时置位（M82） */
    void setLandscapeLock(boolean locked) {
        landscapeLock = locked;
    }

    /**
     * 根据候选视频方向自动旋转屏幕。
     * M79：只有方向类别发生变化时才请求旋转。
     * M82：处于横屏即锁——一旦 landscapeLock 为真，翻页、旋转重算、回前台都只重断言横屏。
     */
    void applyAutoRotation(RecoCandidate candidate) {
        Activity activity = host.getActivity();
        if (activity == null || candidate == null) {
            return;
        }
        if (landscapeLock) {
            enterLandscapeFullscreen();
            return;
        }
        if (!PlayUiPrefs.isAutoRotateLandscape(activity)) {
            restorePortrait();
            return;
        }
        int targetOrientation;
        if (candidate.orientation == RecoCandidate.ORIENT_LANDSCAPE) {
            targetOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            landscapeLock = true;
        } else if (candidate.orientation == RecoCandidate.ORIENT_PORTRAIT) {
            targetOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        } else {
            return;
        }
        if (appliedPlaybackOrientation == targetOrientation) {
            return;
        }
        appliedPlaybackOrientation = targetOrientation;
        activity.setRequestedOrientation(targetOrientation);
        if (targetOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            enterLandscapeFullscreen();
        } else {
            restorePortrait();
        }
    }

    /** 进入横屏全屏沉浸式 */
    void enterLandscapeFullscreen() {
        Activity activity = host.getActivity();
        if (activity == null) {
            return;
        }
        // M-05: 同步访问 JZVideoPlayer 静态字段，避免竞态
        synchronized (JZ_NORMAL_ORIENTATION_LOCK) {
            JZVideoPlayer.NORMAL_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        }

        if (appliedPlaybackOrientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            appliedPlaybackOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }

        Window window = activity.getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.BLACK);
        }
        applyImmersiveNavigationBar(window);
        applyFeedContentFullBleed(activity);

        View landscapeBackView = host.getLandscapeBackView();
        if (landscapeBackView != null) {
            landscapeBackView.setVisibility(View.VISIBLE);
        }
        View bottomNav = bottomNavigationBarView(activity);
        if (bottomNav != null) {
            bottomNav.setVisibility(View.GONE);
        }
        View content = activity.findViewById(R.id.content);
        if (content != null && content.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) content.getLayoutParams();
            if (host.getNormalContentBottomMargin() < 0) {
                host.setNormalContentBottomMargin(lp.bottomMargin);
            }
            lp.bottomMargin = 0;
            content.setLayoutParams(lp);
        }
        View fab = floatingActionButtonView(activity);
        if (fab != null) {
            fab.setVisibility(View.GONE);
        }
        if (activity instanceof BaseAppCompatActivity) {
            ((BaseAppCompatActivity) activity).setNavigationBarOverlap(true);
        }

        RecommendFeedAdapter adapter = host.getAdapter();
        RecyclerView recyclerView = host.getRecyclerView();
        if (adapter != null && recyclerView != null) {
            adapter.setLandscapeMode(recyclerView, true);
            recyclerView.post(() -> {
                if (adapter != null && recyclerView != null && landscapeLock) {
                    adapter.applyOrientationUi(recyclerView, true);
                }
            });
        }
    }

    /** 仅重新应用沉浸式系统栏（不改动方向） */
    void reapplyImmersive() {
        Activity activity = host.getActivity();
        if (activity == null) {
            return;
        }
        Window window = activity.getWindow();
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.BLACK);
        }
        applyImmersiveNavigationBar(window);
        applyFeedContentFullBleed(activity);
    }

    /** 恢复竖屏 */
    void restorePortrait() {
        landscapeLock = false;
        Activity activity = host.getActivity();
        if (activity == null) {
            return;
        }
        // M-05: 同步访问 JZVideoPlayer 静态字段，避免竞态
        synchronized (JZ_NORMAL_ORIENTATION_LOCK) {
            JZVideoPlayer.NORMAL_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }

        if (appliedPlaybackOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            appliedPlaybackOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        Window window = activity.getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setNavigationBarColor(Color.BLACK);
        }
        applyFeedContentFullBleed(activity);
        ViewGroup contentParent = (ViewGroup) activity.findViewById(android.R.id.content);
        if (contentParent != null && contentParent.getChildCount() > 0) {
            contentParent.getChildAt(0).setBackgroundColor(Color.WHITE);
        }
        View landscapeBackView = host.getLandscapeBackView();
        if (landscapeBackView != null) {
            landscapeBackView.setVisibility(View.GONE);
        }
        View bottomNav = bottomNavigationBarView(activity);
        if (bottomNav != null) {
            bottomNav.setVisibility(View.VISIBLE);
        }
        View content = activity.findViewById(R.id.content);
        if (content != null && content.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                && host.getNormalContentBottomMargin() >= 0) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) content.getLayoutParams();
            lp.bottomMargin = host.getNormalContentBottomMargin();
            content.setLayoutParams(lp);
        }
        if (activity instanceof BaseAppCompatActivity) {
            ((BaseAppCompatActivity) activity).setNavigationBarOverlap(false);
        }
        RecommendFeedAdapter adapter = host.getAdapter();
        RecyclerView recyclerView = host.getRecyclerView();
        if (adapter != null && recyclerView != null) {
            adapter.setLandscapeMode(recyclerView, false);
            recyclerView.post(() -> {
                if (adapter != null && recyclerView != null && !landscapeLock) {
                    adapter.applyOrientationUi(recyclerView, false);
                }
            });
        }
    }

    /** 离开推荐流时恢复 App 默认紫色状态栏 */
    void restoreAppStatusBar() {
        landscapeLock = false;
        Activity activity = host.getActivity();
        if (activity == null) {
            return;
        }
        // M-05: 同步访问 JZVideoPlayer 静态字段，避免竞态
        synchronized (JZ_NORMAL_ORIENTATION_LOCK) {
            JZVideoPlayer.NORMAL_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }
        if (appliedPlaybackOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            appliedPlaybackOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        Window window = activity.getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setNavigationBarColor(Color.BLACK);
        }
        ViewGroup contentParent = (ViewGroup) activity.findViewById(android.R.id.content);
        if (contentParent != null && contentParent.getChildCount() > 0) {
            contentParent.getChildAt(0).setBackgroundColor(Color.WHITE);
        }
        if (activity instanceof BaseAppCompatActivity) {
            ((BaseAppCompatActivity) activity)
                    .setStatusBarColor(ContextCompat.getColor(activity, R.color.colorPrimary));
        }
        View landscapeBackView = host.getLandscapeBackView();
        if (landscapeBackView != null) {
            landscapeBackView.setVisibility(View.GONE);
        }
        View bottomNav = bottomNavigationBarView(activity);
        if (bottomNav != null) {
            bottomNav.setVisibility(View.VISIBLE);
        }
        View content = activity.findViewById(R.id.content);
        if (content != null && content.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                && host.getNormalContentBottomMargin() >= 0) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) content.getLayoutParams();
            lp.bottomMargin = host.getNormalContentBottomMargin();
            content.setLayoutParams(lp);
        }
        if (activity instanceof BaseAppCompatActivity) {
            ((BaseAppCompatActivity) activity).setNavigationBarOverlap(false);
        }
    }

    // ---- 私有工具方法 ----

    private void applyImmersiveNavigationBar(Window window) {
        if (window == null) {
            return;
        }
        View decor = window.getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.navigationBars());
            }
        }
    }

    /** M89：供宿主在首个 item 布局完成后调用——仅做满铺与透明系统栏，不改方向不锁横屏 */
    void applyFeedContentFullBleed() {
        applyFeedContentFullBleed(host.getActivity());
    }

    private void applyFeedContentFullBleed(Activity activity) {
        if (activity == null) {
            return;
        }
        Window window = activity.getWindow();
        ViewGroup contentParent = (ViewGroup) activity.findViewById(android.R.id.content);
        if (contentParent != null) {
            contentParent.setBackgroundColor(Color.BLACK);
            contentParent.setPadding(0, 0, 0, 0);
            if (contentParent.getChildCount() > 0) {
                View activityRoot = contentParent.getChildAt(0);
                activityRoot.setBackgroundColor(Color.BLACK);
                activityRoot.setPadding(0, 0, 0, 0);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.BLACK);
        }
    }

    private View bottomNavigationBarView(Activity activity) {
        return activity == null ? null : activity.findViewById(R.id.bottom_navigation_bar);
    }

    private View floatingActionButtonView(Activity activity) {
        // 复查修正：主界面 FAB 的真实 id 是 fab_search（floatingActionButton 不存在）
        return activity == null ? null : activity.findViewById(R.id.fab_search);
    }
}
