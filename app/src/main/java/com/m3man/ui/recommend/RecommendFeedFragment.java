package com.m3man.ui.recommend;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Build;
import android.content.res.Configuration;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.PagerSnapHelper;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import com.liulishuo.filedownloader.model.FileDownloadStatus;
import com.m3man.R;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.VideoResult;
import com.m3man.data.reco.RecoCandidate;
import com.m3man.data.reco.RecoEngine;
import com.m3man.data.reco.RecoParams;
import com.m3man.data.reco.RecoRepository;
import com.m3man.data.reco.RecoStore;
import com.m3man.parser.Parse91PornyVideo;
import com.m3man.service.DownloadVideoService;
import com.m3man.utils.PornyFallbackResolver;
import com.m3man.ui.BaseFragment;
import com.m3man.ui.BaseAppCompatActivity;
import com.m3man.utils.AppLog;
import com.m3man.utils.DownloadDiag;
import com.m3man.utils.DownloadManager;
import com.m3man.utils.PlayUiPrefs;
import com.m3man.utils.SDCardUtils;
import com.orhanobut.logger.Logger;
import com.sdsmdg.tastytoast.TastyToast;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import cn.jzvd.JZVideoPlayer;
import cn.jzvd.JZVideoPlayerStandard;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;

/**
 * 推荐流（抖音式上下滑全屏播放）—— 以 Fragment 形式内嵌在主界面「推荐」Tab 下，
 * 点击 Tab 固定选中并直接展示本页（不再跳独立 Activity）。
 * 逻辑与原 RecommendFeedActivity 一致（引擎召回 / 预取 / 进度条 / 封面 watcher / 下载兜底）。
 */
public class RecommendFeedFragment extends BaseFragment
        implements RecommendFeedAdapter.Callback {

    private static final String TAG = "RecoFeed";

    /** 每批出队条数 */
    private static final int BATCH_SIZE = 6;
    /** 距列表尾部还剩几条时预取下一批 */
    private static final int LOAD_MORE_THRESHOLD = 3;
    /** 一批为空时最多换几个召回源重试 */
    private static final int MAX_EMPTY_RETRY = 3;
    /** 起播状态轮询间隔 / 超时 */
    private static final long PLAY_POLL_INTERVAL = 120L;
    private static final long PLAY_POLL_TIMEOUT = 15000L;
    /** 画像落盘节流 */
    private static final long PERSIST_INTERVAL = 2000L;
    /** 进度条刷新间隔 */
    private static final long PROGRESS_INTERVAL = 400L;
    /** 进度条刻度上限（越大拖动越顺滑） */
    private static final int PROGRESS_MAX = 1000;

    @Inject
    protected DataManager dataManager;

    @Inject
    protected okhttp3.OkHttpClient okHttpClient;

    private RecyclerView recyclerView;
    private ProgressBar globalLoading;
    private View emptyLayout;
    private TextView emptyText;
    private Button retryButton;

    private LinearLayoutManager layoutManager;
    private PagerSnapHelper snapHelper;
    private RecommendFeedAdapter adapter;

    private RecoEngine engine;
    private RecoRepository repository;
    private RecommendPrefetcher prefetcher;

    private final CompositeDisposable disposables = new CompositeDisposable();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int currentPosition = -1;
    private boolean loading = false;
    private boolean noMore = false;
    private int emptyRetryCount = 0;
    private long lastPersistTime = 0L;
    private Runnable coverWatcher;
    private Runnable progressTicker;
    /** 用户正在拖动进度条时暂停自动刷新，避免手指被「拽回去」 */
    private boolean userSeeking = false;

    /** M78：方向筛选（0=全部 1=仅竖屏 2=仅横屏） */
    private TextView orientationFilterView;
    /** M79：最近一次向 Activity 应用的播放方向，避免横屏→横屏重复触发旋转。 */
    private int appliedPlaybackOrientation = -1;
    /** M82：横屏锁。一旦进入横屏（全屏按钮或自动旋转），除非手动退出，否则始终横屏。 */
    private boolean landscapeLock = false;
    private ImageView landscapeBackView;
    private int normalContentBottomMargin = -1;
    /** 横屏锁定时系统栏被系统/用户临时唤出后自动收回，消除顶部紫条与底部留白复发 */
    private final View.OnSystemUiVisibilityChangeListener sysUiVisibilityListener =
            new View.OnSystemUiVisibilityChangeListener() {
                @Override
                public void onSystemUiVisibilityChange(int visibility) {
                    if (landscapeLock && getActivity() != null
                            && ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0
                            || (visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0)) {
                        reapplyImmersive();
                    }
                }
            };

    public static RecommendFeedFragment getInstance() {
        return new RecommendFeedFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_recommend_feed, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // M82：进程重建后恢复横屏锁，避免回到竖屏
        if (savedInstanceState != null) {
            landscapeLock = savedInstanceState.getBoolean("reco_landscape_lock", false);
        }
        if (getActivity() != null) {
            getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getActivity().getWindow().getDecorView()
                    .setOnSystemUiVisibilityChangeListener(sysUiVisibilityListener);
        }
        engine = RecoEngine.get(context);
        repository = new RecoRepository(dataManager, engine);
        prefetcher = new RecommendPrefetcher(context, dataManager);
        // M78：恢复持久化的方向筛选 / 自动横屏开关
        repository.setOrientationFilter(PlayUiPrefs.getOrientationFilter(context));
        repository.setAutoRotateLandscape(PlayUiPrefs.isAutoRotateLandscape(context));

        initViews();
        loadMore(true);
        // M89: 顶部胶囊/返回键必须避开状态栏区域，避免加载时「全部」胶囊侵入
        // 状态栏导致 12:05 / 右侧系统图标被遮挡；状态栏高度用系统资源反射读取，兼容各 ROM。
        applyTopBarInsets();
        // M89: 加载时（数据未到）保持紫色不透明状态栏，避免黑底覆盖状态栏区域后系统文字看不清。
        // 推迟到 RecyclerView 首个 item layout 之后再做满铺透明化。
        final RecyclerView rv = recyclerView;
        rv.post(new Runnable() {
            @Override
            public void run() {
                if (isAdded() && getActivity() != null) {
                    applyFeedContentFullBleed();
                }
            }
        });
    }

    /**
     * M89: 让 fragment_recommend_feed.xml 里顶部的「全部」胶囊和横屏返回箭头避开状态栏区域。
     * 读取系统 status_bar_height 资源；横屏时（landscapeLock）状态栏会被 SYSTEM_UI_FLAG_FULLSCREEN 隐藏，
     * 此处仍把竖屏作为基线，横屏下顶部会多 ~24dp 留白（无功能影响）。
     */
    private void applyTopBarInsets() {
        if (getActivity() == null) {
            return;
        }
        int statusBarHeight = 0;
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                statusBarHeight = getResources().getDimensionPixelSize(id);
            }
        } catch (Exception ignored) {
        }
        if (orientationFilterView != null) {
            ViewGroup.LayoutParams raw = orientationFilterView.getLayoutParams();
            if (raw instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
                lp.topMargin = statusBarHeight + dp(14);
                lp.topMargin = Math.max(lp.topMargin, dp(14));
                orientationFilterView.setLayoutParams(lp);
            }
        }
        // landscapeBackView 仅在横屏才 VISIBLE，留原始 6dp topMargin 即可，不强制改
    }

    private int dp(int value) {
        if (getResources() == null) {
            return value;
        }
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void initViews() {
        recyclerView = getView().findViewById(R.id.recyclerView_recommend);
        globalLoading = getView().findViewById(R.id.pb_recommend_loading);
        emptyLayout = getView().findViewById(R.id.ll_recommend_empty);
        emptyText = getView().findViewById(R.id.tv_recommend_empty);
        retryButton = getView().findViewById(R.id.btn_recommend_retry);
        landscapeBackView = getView().findViewById(R.id.iv_reco_landscape_back);
        landscapeBackView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restorePortrait();
            }
        });

        layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false);
        recyclerView.setLayoutManager(layoutManager);
        // 关掉默认动画，避免插入新页时当前页被重绘导致画面闪烁
        recyclerView.setItemAnimator(null);

        adapter = new RecommendFeedAdapter(engine, this);
        recyclerView.setAdapter(adapter);

        snapHelper = new PagerSnapHelper();
        snapHelper.attachToRecyclerView(recyclerView);

        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    return;
                }
                View snapView = snapHelper.findSnapView(layoutManager);
                if (snapView == null) {
                    return;
                }
                int position = layoutManager.getPosition(snapView);
                if (position != RecyclerView.NO_POSITION) {
                    onPageSelected(position);
                }
            }
        });

        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                noMore = false;
                emptyRetryCount = 0;
                repository.resetSession();
                loadMore(true);
            }
        });

        initOrientationFilterPill();
    }

    // ==================== 方向筛选（M78） ====================

    private void initOrientationFilterPill() {
        orientationFilterView = getView().findViewById(R.id.tv_reco_orientation_filter);
        updateOrientationPill(PlayUiPrefs.getOrientationFilter(context));
        orientationFilterView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOrientationFilterMenu();
            }
        });
    }

    private void updateOrientationPill(int filter) {
        if (orientationFilterView == null) {
            return;
        }
        int label = filter == PlayUiPrefs.FILTER_PORTRAIT ? R.string.reco_filter_portrait
                : filter == PlayUiPrefs.FILTER_LANDSCAPE ? R.string.reco_filter_landscape
                : R.string.reco_filter_all;
        orientationFilterView.setText(label);
    }

    /** 右上角胶囊三选菜单：全部 / 仅竖屏 / 仅横屏 */
    private void showOrientationFilterMenu() {
        final int current = PlayUiPrefs.getOrientationFilter(context);
        final String[] items = {"全部", "仅竖屏", "仅横屏"};
        new android.support.v7.app.AlertDialog.Builder(getActivity(), R.style.RecoOrientationDialogTheme)
                .setTitle(getString(R.string.reco_orientation_filter))
                .setSingleChoiceItems(items, current, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which == current) {
                            return;
                        }
                        applyOrientationFilter(which);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 切换方向筛选：严格过滤——立即重置会话并重新拉取，不符合方向的候选直接丢弃；
     * 池子被筛空时给出提示，用户可点「重试」或切回「全部」恢复。
     */
    private void applyOrientationFilter(int filter) {
        PlayUiPrefs.setOrientationFilter(context, filter);
        repository.setOrientationFilter(filter);
        updateOrientationPill(filter);
        // 严格过滤：清空当前流与已服务标记，从头按新方向拉取
        adapter.setData(new ArrayList<RecoCandidate>());
        currentPosition = -1;
        noMore = false;
        emptyRetryCount = 0;
        JZVideoPlayer.releaseAllVideos();
        stopCoverWatcher();
        stopProgressTicker();
        repository.resetSession();
        loadMore(true);
        showMessage(filter == PlayUiPrefs.FILTER_PORTRAIT ? "已切换：仅竖屏"
                        : filter == PlayUiPrefs.FILTER_LANDSCAPE ? "已切换：仅横屏"
                        : "已切换：全部",
                TastyToast.INFO);
    }

    // ==================== 数据 ====================

    private void loadMore(final boolean first) {
        if (loading || (noMore && !first)) {
            return;
        }
        loading = true;
        if (first) {
            globalLoading.setVisibility(View.VISIBLE);
            emptyLayout.setVisibility(View.GONE);
        }
        disposables.add(repository.nextBatch(BATCH_SIZE)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(list -> onBatchLoaded(list, first),
                        throwable -> onBatchFailed(first, throwable)));
    }

    private void onBatchLoaded(List<RecoCandidate> list, boolean first) {
        loading = false;
        globalLoading.setVisibility(View.GONE);

        if (list == null || list.isEmpty()) {
            // 某个召回源恰好没数据是常态，换一个源再试几次
            if (++emptyRetryCount < MAX_EMPTY_RETRY) {
                loadMore(first);
                return;
            }
            emptyRetryCount = 0;
            if (adapter.getItemCount() == 0) {
                showEmpty(getString(R.string.reco_empty));
            } else {
                noMore = true;
                showMessage(getString(R.string.reco_no_more), TastyToast.INFO);
            }
            return;
        }

        emptyRetryCount = 0;
        boolean wasEmpty = adapter.getItemCount() == 0;
        adapter.appendData(list);
        emptyLayout.setVisibility(View.GONE);
        if (wasEmpty) {
            recyclerView.post(new Runnable() {
                @Override
                public void run() {
                    onPageSelected(0);
                }
            });
        }
    }

    private void onBatchFailed(boolean first, Throwable throwable) {
        loading = false;
        globalLoading.setVisibility(View.GONE);
        String reason = throwable == null ? "unknown" : AppLog.cause(throwable);
        Logger.t(TAG).d("load batch failed: " + reason);
        AppLog.e(TAG, "推荐列表加载失败: " + reason);
        if (adapter.getItemCount() == 0) {
            showEmpty("加载失败，点击重试");
        } else {
            showMessage("加载失败", TastyToast.ERROR);
        }
    }

    private void showEmpty(String message) {
        emptyText.setText(message);
        emptyLayout.setVisibility(View.VISIBLE);
    }

    // ==================== 翻页 / 播放 ====================

    private void onPageSelected(int position) {
        if (position < 0 || position >= adapter.getItemCount() || position == currentPosition) {
            return;
        }
        // 离开上一页时结算观看比例（隐式反馈）
        recordWatchRatio(currentPosition);
        currentPosition = position;
        // 横屏锁只在手动退出时解除；翻到新视频不自动回竖屏。
        RecoCandidate candidate = adapter.getItem(position);
        if (candidate != null) {
            engine.markSeen(candidate.viewKey());
            applyAutoRotation(candidate);
        }
        startPlay(position);

        RecoParams params = engine.getParams();
        prefetcher.prefetch(adapter.getData(), position + 1, params.prefetchAhead);

        if (position >= adapter.getItemCount() - LOAD_MORE_THRESHOLD) {
            loadMore(false);
        }
        persistAsync(false);
    }

    /** M79：只有方向类别发生变化时才请求 Activity 旋转，横屏连续翻页保持横屏。
     *  M82：处于横屏即锁 —— 一旦 landscapeLock 为真，翻页、旋转重算、回前台都只重断言横屏，绝不回竖屏。 */
    private void applyAutoRotation(RecoCandidate candidate) {
        if (getActivity() == null || candidate == null) {
            return;
        }
        // 横屏锁：重断言横屏 + 沉浸式（覆盖导航栏状态），不打回竖屏。
        // 不论是全屏按钮进入，还是自动旋转进入的横屏，锁定时都保持横屏直到手动退出。
        if (landscapeLock) {
            enterLandscapeFullscreen();
            return;
        }
        if (!PlayUiPrefs.isAutoRotateLandscape(context)) {
            restorePortrait();
            return;
        }
        int targetOrientation;
        if (candidate.orientation == RecoCandidate.ORIENT_LANDSCAPE) {
            targetOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            landscapeLock = true; // 进入横屏即锁
        } else if (candidate.orientation == RecoCandidate.ORIENT_PORTRAIT) {
            targetOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        } else {
            return;
        }
        if (appliedPlaybackOrientation == targetOrientation) {
            return;
        }
        appliedPlaybackOrientation = targetOrientation;
        getActivity().setRequestedOrientation(targetOrientation);
        if (targetOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            enterLandscapeFullscreen();
        } else {
            restorePortrait();
        }
    }

    /**
     * 进入横屏全屏沉浸式。
     * <p>
     * 关键修复：
     * 1) 设置 {@code JZVideoPlayer.NORMAL_ORIENTATION = LANDSCAPE}，避免翻页时
     *    {@code JZVideoPlayer.releaseAllVideos()} 内部 onCompletion 把 Activity 强制切回 PORTRAIT（导致“竖屏闪一下”）。
     * 2) {@code setRequestedOrientation} 仅在方向真正变化时才调用，避免每次翻页重复请求旋转造成的抖动/闪烁。
     * 3) 隐藏 StatusBarUtil 注入的紫色假状态栏 View，并把状态栏/导航栏颜色设为透明，
     *    消除横屏顶部紫条与底部留白。
     */
    private void enterLandscapeFullscreen() {
        if (getActivity() == null) {
            return;
        }
        // 让 JZVD 完成/释放时保持横屏，不再回退到竖屏
        JZVideoPlayer.NORMAL_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;

        // 仅在方向变化时才请求旋转，避免重复 setRequestedOrientation 引发的闪烁
        if (appliedPlaybackOrientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            appliedPlaybackOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }

        Window window = getActivity().getWindow();
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
            // 不使用透明导航栏：Android 10+ 可能为透明导航栏强制绘制白色对比度背景。
            window.setNavigationBarColor(Color.BLACK);
        }
        applyImmersiveNavigationBar(window);
        // 撤销 StatusBarUtil 的 contentParent 紫色背景，并把 activity_main.xml 实际根 View 设为黑色。
        // 白条来自 activity_main.xml 最外层 FrameLayout 的白色背景，不是视频 item 背景。
        applyFeedContentFullBleed();

        if (landscapeBackView != null) {
            landscapeBackView.setVisibility(View.VISIBLE);
        }
        if (bottomNavigationBarView() != null) {
            bottomNavigationBarView().setVisibility(View.GONE);
        }
        View content = getActivity().findViewById(R.id.content);
        // content_main 被放在 CoordinatorLayout 中，实际参数是 CoordinatorLayout.LayoutParams，
        // 不能用 FrameLayout.LayoutParams 判断，否则横屏底部 margin 永远清不掉，白条就会露出。
        if (content != null && content.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) content.getLayoutParams();
            if (normalContentBottomMargin < 0) {
                normalContentBottomMargin = lp.bottomMargin;
            }
            lp.bottomMargin = 0;
            content.setLayoutParams(lp);
        }
        if (floatingActionButtonView() != null) {
            floatingActionButtonView().setVisibility(View.GONE);
        }
        // 让内容铺满导航栏区域，消除横屏沉浸时的底部留白
        if (getActivity() instanceof BaseAppCompatActivity) {
            ((BaseAppCompatActivity) getActivity()).setNavigationBarOverlap(true);
        }
        // MainActivity 使用 configChanges，首个已存在的 ViewHolder 不会自动换成 layout-land。
        // M90：立即对当前挂载的 ViewHolder 应用横屏布局；转屏动画期间 Holder 可能被 detach，
        // 旋转完成后（post）再补一次。新滑入的 ViewHolder 由 onBindViewHolder 自动带横屏布局。
        if (adapter != null && recyclerView != null) {
            adapter.setLandscapeMode(recyclerView, true);
            recyclerView.post(new Runnable() {
                @Override
                public void run() {
                    if (adapter != null && recyclerView != null && landscapeLock) {
                        adapter.applyOrientationUi(recyclerView, true);
                    }
                }
            });
        }
    }

    /** 仅重新应用沉浸式系统栏（不改动方向），供系统栏被临时唤出后自动收回。 */
    private void reapplyImmersive() {
        if (getActivity() == null) {
            return;
        }
        Window window = getActivity().getWindow();
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
        applyFeedContentFullBleed();
    }

    /**
     * 强制隐藏导航栏，并关闭 Android 10+ 透明导航栏的对比度遮罩。
     * 部分系统即使设置了 SYSTEM_UI_FLAG_HIDE_NAVIGATION，也会单独恢复导航栏；
     * 使用 WindowInsetsController 再补一次，避免底部露出白色系统栏。
     */
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

    /**
     * 推荐流满铺到顶部。
     * <p>
     * <b>真正的根因</b>（已被反编译 StatusBarUtil 1.5.1 的 {@code setColorForSwipeBack} 确认）：
     * 该方法通过 {@code findViewById(android.R.id.content)} 拿到系统 contentParent（不是 decorView），
     * 给它 {@code setBackgroundColor(紫)} 并 {@code setPadding(0, statusBarHeight, 0, 0)}，
     * 让出顶部一片紫色看起来像状态栏。<b>并没有</b> {@code addView} 任何假状态栏 View，
     * 所以 {@code hideFakeStatusBarView(findViewById(R.id.statusbarutil_fake_status_bar_view))}
     * 永远返回 null，等于 no-op——这是 v1.0.81 / v1.0.82 紫条修不掉的原因。
     * <p>
     * 正确做法是撤销 contentParent 的 background 和 paddingTop。
     */
    private void applyFeedContentFullBleed() {
        if (getActivity() == null) {
            return;
        }
        Window window = getActivity().getWindow();
        ViewGroup contentParent = (ViewGroup) getActivity().findViewById(android.R.id.content);
        if (contentParent != null) {
            contentParent.setBackgroundColor(Color.BLACK);
            contentParent.setPadding(0, 0, 0, 0);
            // contentParent 的第一个子 View 就是 setContentView(activity_main.xml) 的最外层 FrameLayout。
            // 必须改这个实际根 View，单改 DecorView 对横屏底部露白无效。
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

    private void restorePortrait() {
        landscapeLock = false;
        if (getActivity() == null) {
            return;
        }
        // 退出横屏后，JZVD 完成/释放应回退到竖屏（恢复默认行为）
        JZVideoPlayer.NORMAL_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;

        if (appliedPlaybackOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            appliedPlaybackOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        Window window = getActivity().getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setNavigationBarColor(Color.BLACK);
        }
        // 退出横屏但仍在推荐流内：保持状态栏透明（视频满铺），不再回退到紫色状态栏。
        applyFeedContentFullBleed();
        // 恢复 activity_main.xml 实际根 View 的白色背景。
        ViewGroup contentParent = (ViewGroup) getActivity().findViewById(android.R.id.content);
        if (contentParent != null && contentParent.getChildCount() > 0) {
            contentParent.getChildAt(0).setBackgroundColor(Color.WHITE);
        }
        if (landscapeBackView != null) {
            landscapeBackView.setVisibility(View.GONE);
        }
        if (bottomNavigationBarView() != null) {
            bottomNavigationBarView().setVisibility(View.VISIBLE);
        }
        View content = getActivity().findViewById(R.id.content);
        if (content != null && content.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                && normalContentBottomMargin >= 0) {
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) content.getLayoutParams();
            lp.bottomMargin = normalContentBottomMargin;
            content.setLayoutParams(lp);
        }
        // 恢复导航栏默认（不重叠），底部留白回到系统处理
        if (getActivity() instanceof BaseAppCompatActivity) {
            ((BaseAppCompatActivity) getActivity()).setNavigationBarOverlap(false);
        }
        if (adapter != null && recyclerView != null) {
            adapter.setLandscapeMode(recyclerView, false);
            recyclerView.post(new Runnable() {
                @Override
                public void run() {
                    if (adapter != null && recyclerView != null && !landscapeLock) {
                        adapter.applyOrientationUi(recyclerView, false);
                    }
                }
            });
        }
    }

    /**
     * 离开推荐流（切到其它 Tab / Fragment 销毁）时恢复 App 默认紫色状态栏，
     * 保证其它页面外观正确；同时解除横屏锁、清沉浸式、恢复底部导航。
     */
    private void restoreAppStatusBar() {
        landscapeLock = false;
        if (getActivity() == null) {
            return;
        }
        // 退出推荐流后，JZVD 完成/释放应回退到竖屏（恢复默认行为）
        JZVideoPlayer.NORMAL_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if (appliedPlaybackOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            appliedPlaybackOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        Window window = getActivity().getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setNavigationBarColor(Color.BLACK);
        }
        ViewGroup contentParent = (ViewGroup) getActivity().findViewById(android.R.id.content);
        if (contentParent != null && contentParent.getChildCount() > 0) {
            contentParent.getChildAt(0).setBackgroundColor(Color.WHITE);
        }
        // 恢复紫色状态栏（StatusBarUtil 会重新显示并为假状态栏 View 着色）
        if (getActivity() instanceof BaseAppCompatActivity) {
            ((BaseAppCompatActivity) getActivity())
                    .setStatusBarColor(ContextCompat.getColor(getActivity(), R.color.colorPrimary));
        }
        if (landscapeBackView != null) {
            landscapeBackView.setVisibility(View.GONE);
        }
        if (bottomNavigationBarView() != null) {
            bottomNavigationBarView().setVisibility(View.VISIBLE);
        }
        View content = getActivity().findViewById(R.id.content);
        if (content != null && content.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                && normalContentBottomMargin >= 0) {
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) content.getLayoutParams();
            lp.bottomMargin = normalContentBottomMargin;
            content.setLayoutParams(lp);
        }
        // 恢复导航栏默认（不重叠），底部留白回到系统处理
        if (getActivity() instanceof BaseAppCompatActivity) {
            ((BaseAppCompatActivity) getActivity()).setNavigationBarOverlap(false);
        }
    }

    private View bottomNavigationBarView() {
        return getActivity() == null ? null : getActivity().findViewById(R.id.bottom_navigation_bar);
    }

    private View floatingActionButtonView() {
        return getActivity() == null ? null : getActivity().findViewById(R.id.fab_search);
    }

    private void recordWatchRatio(int position) {
        if (position < 0 || adapter == null) {
            return;
        }
        RecoCandidate candidate = adapter.getItem(position);
        if (candidate == null) {
            return;
        }
        RecommendFeedAdapter.PageHolder holder = findHolder(position);
        float ratio = holder == null ? 0f : holder.player.watchedRatio();
        if (ratio > 0f) {
            engine.onWatchRatio(candidate, ratio);
        }
    }

    private void startPlay(final int position) {
        final RecoCandidate candidate = adapter.getItem(position);
        if (candidate == null) {
            return;
        }
        final RecommendFeedAdapter.PageHolder holder = findHolder(position);
        if (holder == null) {
            // ViewHolder 尚未 attach，下一帧再试
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isUsable() && position == currentPosition) {
                        startPlay(position);
                    }
                }
            }, 60L);
            return;
        }

        // 关掉其它页的循环续播，防止旧页在播完瞬间抢回解码器造成「画面 / 标题」错位
        disableLoopExcept(position);
        JZVideoPlayer.releaseAllVideos();
        stopCoverWatcher();
        stopProgressTicker();
        holder.loading.setVisibility(View.VISIBLE);
        holder.error.setVisibility(View.GONE);
        holder.cover.setVisibility(View.VISIBLE);
        holder.player.setVisibility(View.INVISIBLE);
        holder.progressContainer.setVisibility(View.GONE);
        holder.progress.setProgress(0);
        holder.curTime.setText("00:00");
        holder.durTime.setText("00:00");

        final String expectKey = candidate.viewKey();
        prefetcher.resolveNow(candidate, new RecommendPrefetcher.ResolveCallback() {
            @Override
            public void onResolved(String viewKey, String playUrl, V9MmanItem item) {
                if (!isUsable() || position != currentPosition) {
                    return;
                }
                // 解析回调是异步的，回来时必须确认还是同一条视频
                if (!TextUtils.isEmpty(viewKey) && !viewKey.equals(expectKey)) {
                    Logger.t(TAG).d("resolve key mismatch, drop: " + viewKey + " != " + expectKey);
                    return;
                }
                engine.attachAuthor(candidate, item);
                doStart(position, candidate, playUrl);
            }

            @Override
            public void onFailed(String viewKey, String message) {
                if (!isUsable() || position != currentPosition) {
                    return;
                }
                AppLog.e(TAG, "推荐播放解析失败 viewKey=" + candidate.viewKey() + " msg=" + message);
                showPlayError(position, message);
            }
        });
    }

    /** fragment 是否仍可用（替代原 Activity 的 isFinishing()）。隐藏或暂停后禁止异步回调起播。 */
    private boolean isUsable() {
        return isAdded() && getActivity() != null && !isHidden() && isResumed();
    }

    /** 离开推荐播放页面时彻底释放底层播放器，不能只暂停，否则 MediaPlayer 可能继续持有音频输出。 */
    private void stopPlaybackForLeavingPage() {
        stopCoverWatcher();
        stopProgressTicker();
        try {
            JZVideoPlayer.releaseAllVideos();
        } catch (Exception ignored) {
        }
    }

    /** 页面重新可见时，从当前候选重新建立播放；离页期间已经释放，不调用全局 resume。 */
    private void resumeCurrentPlaybackIfNeeded() {
        if (!isUsable() || currentPosition < 0 || adapter == null) {
            return;
        }
        RecommendFeedAdapter.PageHolder holder = findHolder(currentPosition);
        if (holder != null && holder.player.isCurrentPlayer()) {
            return;
        }
        startPlay(currentPosition);
    }

    /** 除 keepPosition 外，其余已 attach 的页一律禁止循环续播 */
    private void disableLoopExcept(int keepPosition) {
        if (recyclerView == null) {
            return;
        }
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            RecyclerView.ViewHolder vh = recyclerView.getChildViewHolder(child);
            if (!(vh instanceof RecommendFeedAdapter.PageHolder)) {
                continue;
            }
            if (vh.getAdapterPosition() == keepPosition) {
                continue;
            }
            RecommendFeedAdapter.PageHolder other = (RecommendFeedAdapter.PageHolder) vh;
            other.player.setLoopEnabled(false);
            other.progressContainer.setVisibility(View.GONE);
        }
    }

    private void doStart(int position, RecoCandidate candidate, String playUrl) {
        RecommendFeedAdapter.PageHolder holder = findHolder(position);
        if (holder == null) {
            return;
        }
        if (TextUtils.isEmpty(playUrl)) {
            showPlayError(position, null);
            return;
        }
        // 二次校验：ViewHolder 复用后可能已经绑到别的视频上，此时不能在这一页起播
        String boundKey = holder.boundKey;
        String candidateKey = candidate.viewKey();
        if (!TextUtils.isEmpty(boundKey) && !TextUtils.isEmpty(candidateKey)
                && !boundKey.equals(candidateKey)) {
            Logger.t(TAG).d("holder rebound, skip start: " + boundKey + " != " + candidateKey);
            return;
        }
        String title = candidate.title() == null ? "" : candidate.title();
        try {
            holder.player.setVisibility(View.VISIBLE);
            holder.player.setLoopEnabled(true);
            holder.player.setUp(playUrl, JZVideoPlayerStandard.SCREEN_WINDOW_LIST, title);
            holder.player.startVideo();
        } catch (Exception e) {
            Logger.t(TAG).d("start video failed: " + e.getMessage());
            AppLog.e(TAG, "推荐起播失败 viewKey=" + candidate.viewKey() + " " + AppLog.cause(e));
            showPlayError(position, null);
            return;
        }
        // 标题以「真正被起播的这条」为准，杜绝画面与文案对不上
        holder.title.setText(title);
        holder.progressContainer.setVisibility(View.VISIBLE);
        holder.speed.setText("1x");
        holder.player.setPlaybackSpeed(1.0f);
        bindSeekBar(position, holder);
        startProgressTicker(position);
        prefetcher.markWatched(candidate.item);
        startCoverWatcher(position);
    }

    // ==================== 进度条 ====================

    private void bindSeekBar(final int position, final RecommendFeedAdapter.PageHolder holder) {
        holder.progress.setMax(PROGRESS_MAX);
        holder.progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                long duration = holder.player.safeDuration();
                if (duration > 0) {
                    holder.curTime.setText(formatTime(duration * progress / PROGRESS_MAX));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
                // 拖动期间禁止父容器（RecyclerView）拦截触摸，否则容易被判定成翻页
                seekBar.getParent().requestDisallowInterceptTouchEvent(true);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;
                seekBar.getParent().requestDisallowInterceptTouchEvent(false);
                if (position != currentPosition) {
                    return;
                }
                holder.player.seekToProgress(seekBar.getProgress(), PROGRESS_MAX);
            }
        });
    }

    private void startProgressTicker(final int position) {
        stopProgressTicker();
        progressTicker = new Runnable() {
            @Override
            public void run() {
                if (!isUsable() || position != currentPosition) {
                    progressTicker = null;
                    return;
                }
                RecommendFeedAdapter.PageHolder holder = findHolder(position);
                if (holder == null) {
                    progressTicker = null;
                    return;
                }
                long duration = holder.player.safeDuration();
                if (duration > 0) {
                    holder.durTime.setText(formatTime(duration));
                    if (!userSeeking) {
                        long pos = holder.player.safePosition();
                        int p = (int) (pos * PROGRESS_MAX / duration);
                        holder.progress.setProgress(Math.max(0, Math.min(PROGRESS_MAX, p)));
                        holder.curTime.setText(formatTime(pos));
                    }
                }
                handler.postDelayed(this, PROGRESS_INTERVAL);
            }
        };
        handler.postDelayed(progressTicker, PROGRESS_INTERVAL);
    }

    private void stopProgressTicker() {
        if (progressTicker != null) {
            handler.removeCallbacks(progressTicker);
            progressTicker = null;
        }
        userSeeking = false;
    }

    private static String formatTime(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.getDefault(), "%02d:%02d", m, s);
    }

    private void showPlayError(int position, String message) {
        RecommendFeedAdapter.PageHolder holder = findHolder(position);
        if (holder == null) {
            return;
        }
        stopCoverWatcher();
        stopProgressTicker();
        holder.loading.setVisibility(View.GONE);
        holder.player.setVisibility(View.INVISIBLE);
        holder.progressContainer.setVisibility(View.GONE);
        holder.cover.setVisibility(View.VISIBLE);
        holder.error.setVisibility(View.VISIBLE);
        holder.error.setText(TextUtils.isEmpty(message)
                ? getString(R.string.reco_parse_failed)
                : message + "，点击重试");
    }

    /**
     * 起播是异步的，轮询到真正 PLAYING 后再撤掉封面与菊花，避免黑屏一闪。
     */
    private void startCoverWatcher(final int position) {
        stopCoverWatcher();
        final long deadline = SystemClock.uptimeMillis() + PLAY_POLL_TIMEOUT;
        coverWatcher = new Runnable() {
            @Override
            public void run() {
                if (!isUsable() || position != currentPosition) {
                    coverWatcher = null;
                    return;
                }
                RecommendFeedAdapter.PageHolder holder = findHolder(position);
                if (holder == null) {
                    coverWatcher = null;
                    return;
                }
                if (holder.player.isPlaying()) {
                    holder.cover.setVisibility(View.GONE);
                    holder.loading.setVisibility(View.GONE);
                    coverWatcher = null;
                    return;
                }
                if (SystemClock.uptimeMillis() > deadline) {
                    holder.loading.setVisibility(View.GONE);
                    coverWatcher = null;
                    return;
                }
                handler.postDelayed(this, PLAY_POLL_INTERVAL);
            }
        };
        handler.postDelayed(coverWatcher, PLAY_POLL_INTERVAL);
    }

    private void stopCoverWatcher() {
        if (coverWatcher != null) {
            handler.removeCallbacks(coverWatcher);
            coverWatcher = null;
        }
    }

    private RecommendFeedAdapter.PageHolder findHolder(int position) {
        if (position < 0 || recyclerView == null) {
            return null;
        }
        RecyclerView.ViewHolder vh = recyclerView.findViewHolderForAdapterPosition(position);
        return vh instanceof RecommendFeedAdapter.PageHolder
                ? (RecommendFeedAdapter.PageHolder) vh : null;
    }

    // ==================== 交互回调 ====================

    @Override
    public void onFullscreenClick(int position) {
        if (position != currentPosition || getActivity() == null) {
            return;
        }
        landscapeLock = true;
        enterLandscapeFullscreen();
    }

    @Override
    public void onSpeedClick(int position) {
        if (position != currentPosition) {
            return;
        }
        RecommendFeedAdapter.PageHolder holder = findHolder(position);
        if (holder == null) {
            return;
        }
        final boolean toDouble = "1x".contentEquals(holder.speed.getText());
        if (holder.player.setPlaybackSpeed(toDouble ? 2.0f : 1.0f)) {
            holder.speed.setText(toDouble ? "2x" : "1x");
        } else {
            showMessage(getString(R.string.reco_speed_unsupported), TastyToast.INFO);
        }
    }

    @Override
    public void onLikeClick(int position) {
        RecoCandidate candidate = adapter.getItem(position);
        if (candidate == null) {
            return;
        }
        boolean liked = engine.toggleLike(candidate);
        adapter.refreshActionState(recyclerView, position);
        showMessage(getString(liked ? R.string.reco_liked : R.string.reco_unliked),
                TastyToast.SUCCESS);
        persistAsync(true);
    }

    @Override
    public void onDoubleTap(int position, float normalizedX) {
        RecommendFeedAdapter.PageHolder holder = findHolder(position);
        if (holder == null) {
            return;
        }
        // 左 1/3 双击点赞；其余区域双击快进视频总时长的 1/8。
        if (normalizedX >= 0f && normalizedX < 1f / 3f) {
            RecoCandidate candidate = adapter.getItem(position);
            if (candidate == null) {
                return;
            }
            if (engine.actionOf(candidate.viewKey()) == RecoStore.ACTION_LIKE) {
                return;
            }
            engine.toggleLike(candidate);
            adapter.refreshActionState(recyclerView, position);
            showMessage(getString(R.string.reco_liked), TastyToast.SUCCESS);
            persistAsync(true);
            return;
        }

        long actual = holder.player.seekForwardOneEighth();
        if (actual > 0L) {
            long seconds = Math.max(1L, actual / 1000L);
            holder.seekFeedback.setText("+" + seconds + "秒");
            holder.seekFeedback.setVisibility(View.VISIBLE);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (holder.seekFeedback != null) {
                        holder.seekFeedback.setVisibility(View.GONE);
                    }
                }
            }, 900L);
        }
    }

    @Override
    public void onSingleTap(int position) {
        RecommendFeedAdapter.PageHolder holder = findHolder(position);
        if (holder != null) {
            holder.player.togglePlayPause();
        }
    }

    @Override
    public void onFavoriteClick(final int position) {
        final RecoCandidate candidate = adapter.getItem(position);
        if (candidate == null || candidate.item == null) {
            return;
        }
        final boolean wasFavorited = isFavorited(candidate);
        if (wasFavorited) {
            engine.onUnfavorite(candidate);
        } else {
            engine.onFavorite(candidate);
        }
        final V9MmanItem item = candidate.item;
        disposables.add(Observable.just(1)
                .map(i -> {
                    saveFavorite(item, !wasFavorited);
                    return true;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ok -> {
                    adapter.refreshActionState(recyclerView, position);
                    showMessage(getString(wasFavorited
                            ? R.string.reco_unfavorited : R.string.reco_favorited),
                            TastyToast.SUCCESS);
                }, throwable -> showMessage("收藏失败", TastyToast.ERROR)));
        persistAsync(true);
    }

    @Override
    public void onDislikeClick(int position) {
        RecoCandidate candidate = adapter.getItem(position);
        if (candidate == null) {
            return;
        }
        boolean disliked = engine.toggleDislike(candidate);
        adapter.refreshActionState(recyclerView, position);
        showMessage(getString(disliked ? R.string.reco_disliked : R.string.reco_undisliked),
                TastyToast.SUCCESS);
        persistAsync(true);
        // 标记不喜欢后直接跳到下一条
        if (disliked && position + 1 < adapter.getItemCount()) {
            recyclerView.smoothScrollToPosition(position + 1);
        }
    }

    @Override
    public void onDetailClick(int position) {
        RecoCandidate candidate = adapter.getItem(position);
        if (candidate == null || candidate.item == null) {
            return;
        }
        goToPlayVideo(candidate.item, dataManager.getPlaybackEngine(), 0, position);
    }

    @Override
    public void onRetryClick(int position) {
        if (position == currentPosition) {
            startPlay(position);
        }
    }

    // ==================== 下载 ====================

    @Override
    public void onDownloadClick(int position) {
        final RecoCandidate candidate = adapter.getItem(position);
        if (candidate == null || candidate.item == null
                || TextUtils.isEmpty(candidate.viewKey())) {
            return;
        }
        // 已经解析过（正在播的这条一定已解析）就直接入队
        if (!TextUtils.isEmpty(prefetcher.peekRawUrl(candidate.viewKey()))) {
            enqueueDownload(candidate);
            return;
        }
        // 还没解析：先解析出真实地址再入队
        showMessage(getString(R.string.reco_download_parsing), TastyToast.INFO);
        prefetcher.resolveNow(candidate, new RecommendPrefetcher.ResolveCallback() {
            @Override
            public void onResolved(String viewKey, String playUrl, V9MmanItem item) {
                if (!isUsable()) {
                    return;
                }
                enqueueDownload(candidate);
            }

            @Override
            public void onFailed(String viewKey, String message) {
                if (!isUsable()) {
                    return;
                }
                showMessage(TextUtils.isEmpty(message)
                        ? getString(R.string.reco_download_failed) : message, TastyToast.ERROR);
            }
        });
    }

    private void enqueueDownload(RecoCandidate candidate) {
        final String viewKey = candidate.viewKey();
        final V9MmanItem fallback = candidate.item;
        disposables.add(Observable.just(1)
                .subscribeOn(Schedulers.io())
                .map(i -> doEnqueueDownload(viewKey, fallback))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    showMessage(result.message, result.ok ? TastyToast.SUCCESS : TastyToast.INFO);
                    if (result.ok && getActivity() != null) {
                        // 拉起下载前台服务，保证退出页面后仍继续下载
                        try {
                            getActivity().startService(new Intent(getActivity(),
                                    DownloadVideoService.class));
                        } catch (Exception e) {
                            Logger.t(TAG).d("start download service failed: " + e.getMessage());
                        }
                    }
                }, throwable -> showMessage(getString(R.string.reco_download_failed),
                        TastyToast.ERROR)));
    }

    /**
     * 真正入队（IO 线程）。逻辑与播放页的下载保持一致：
     * 先取库里已解析的实体 → 查重 → 查是否在下载 → 交给 FileDownloader。
     */
    private DownloadResult doEnqueueDownload(String viewKey, V9MmanItem fallback) {
        DownloadDiag.reset(viewKey);
        V9MmanItem target = null;
        try {
            target = dataManager.findV9MmanItemByViewKey(viewKey);
        } catch (Exception ignored) {
        }
        if (target == null) {
            target = fallback;
        }
        if (target == null || target.getVideoResultId() == 0) {
            AppLog.e(TAG, "推荐下载缺少VideoResult viewKey=" + viewKey);
            DownloadDiag.append(viewKey, "enqueue=无已解析的 VideoResult → 失败");
            return new DownloadResult(false, "还未解析成功视频地址");
        }
        VideoResult videoResult;
        try {
            videoResult = target.getVideoResult();
        } catch (Exception e) {
            videoResult = null;
        }
        if (videoResult == null || TextUtils.isEmpty(videoResult.getVideoUrl())) {
            DownloadDiag.append(viewKey, "enqueue=videoResult.videoUrl 为空 → 失败");
            return new DownloadResult(false, "还未解析成功视频地址");
        }
        String path = target.getDownLoadPath(dataManager.getCustomDownloadVideoDirPath());
        // M61：兼容 ensureDownloadDir 回退目录，文件可能已写进应用专属目录
        File file = SDCardUtils.resolveExistingDownloadFile(context, path);
        if (file != null && file.exists() && file.length() > 0) {
            DownloadDiag.append(viewKey, "enqueue=目标文件已存在(" + file.length() + "B) → 跳过");
            return new DownloadResult(false, "已经下载过了，请查看下载目录");
        }
        if (target.getStatus() == FileDownloadStatus.progress && target.getDownloadId() != 0) {
            DownloadDiag.append(viewKey, "enqueue=已在下载(downloadId=" + target.getDownloadId() + ") → 跳过");
            return new DownloadResult(false, "已经在下载了");
        }
        // 91mman 视频分类源：直链带时效签名（st/f），预取/库里存的旧 URL 过期会被 CDN 拒绝
        // （表现为「正在下载」里报下载错误）。下载前重新解析播放页拿新鲜签名 URL。
        String url = videoResult.getVideoUrl();
        DownloadDiag.append(viewKey, "enqueue=旧URL host=" + DownloadDiag.hostOf(url));
        // M50：20 位 hex viewkey = 91porny 视频。这类视频直链多落在 cdn77，但 cdn77 对部分出口
        // （如用户 VPN）会返回 3.5KB 错误页——isAlive 探活只验 Content-Range 总长拦不住，下载即
        // 假完成/error。已知 key 直接走 91porny m3u8 HLS 下载，绕开 cdn77 直链，更稳。
        // M50：91porny 的 viewKey 实际为 24 位 hex（见 Parse91PornyVideo），旧正则锁死 20 位
        // 导致该绕过 cdn77 的分支永不触发。放宽到 [16,32] 位全 hex，覆盖 20/24 位。
        boolean isPornyHex = viewKey != null && viewKey.matches("[0-9a-fA-F]{16,32}");
        if (isPornyHex) {
            try {
                VideoResult porny = dataManager.loadPornyVideoUrl(viewKey).blockingFirst();
                if (porny != null && !TextUtils.isEmpty(porny.getVideoUrl())) {
                    DownloadDiag.append(viewKey, "91porny=直链host=" + DownloadDiag.hostOf(url)
                            + " → 命中m3u8=" + DownloadDiag.hostOf(porny.getVideoUrl()) + " → HLS下载");
                    PornyFallbackResolver.applyPornyResult(dataManager, target, porny);
                    String hlsPath = path;
                    try {
                        String ensured = SDCardUtils.ensureDownloadDir(path, context);
                        if (ensured != null) {
                            hlsPath = ensured;
                        }
                    } catch (Exception ignored) {
                    }
                    PornyFallbackResolver.enqueueHlsDownload(context,
                            target, porny.getVideoUrl(), hlsPath);
                    return new DownloadResult(true, "已改用分分钟源下载");
                }
                DownloadDiag.append(viewKey, "91porny=直链m3u8为空 → 退回原直链");
            } catch (Exception e) {
                DownloadDiag.append(viewKey, "91porny=直链异常(" + (e.getMessage() == null ? e.toString() : e.getMessage()) + ") → 退回原直链");
            }
        }
        // M73：与 Activity 同位置(785行)对齐，用权威判源 isPornySource（持久化 sourceName），
        // 此前依赖 transient 的 target.getSource()，DB 读出的 porny 条目该字段为空会被误走 9mman 解析必失败
        if (!com.m3man.ui.mman9video.play.PlayVideoPresenter.isPornySource(target)) {
            try {
                VideoResult fresh = dataManager.loadMman9VideoUrl(viewKey).blockingFirst();
                if (fresh != null && !TextUtils.isEmpty(fresh.getVideoUrl())) {
                    dataManager.saveVideoResult(fresh);
                    target.setVideoResult(fresh);
                    dataManager.updateV9MmanItem(target);
                    url = fresh.getVideoUrl();
                    DownloadDiag.append(viewKey, "reparse=成功 host=" + DownloadDiag.hostOf(url));
                } else {
                    DownloadDiag.append(viewKey, "reparse=空URL(观看超限/解析失败) → 尝试91porny备用源");
                }
            } catch (Exception e) {
                Logger.t(TAG).d("recommend re-parse failed, fallback old url: " + e.getMessage());
                DownloadDiag.append(viewKey, "reparse=异常(" + e.getMessage() + ") → 退回旧URL");
            }
            // 直链 CDN 可能封锁当前网络（下载报错/0% 无速度）：探活被拒则改用 91porny 备用源
            boolean alive = PornyFallbackResolver.isAlive(okHttpClient, url);
            DownloadDiag.append(viewKey, "isAlive=" + alive + " host=" + DownloadDiag.hostOf(url));
            if (!alive) {
                try {
                    VideoResult porny = PornyFallbackResolver.resolve(dataManager, target.getTitle());
                    if (porny != null && !TextUtils.isEmpty(porny.getVideoUrl())) {
                        DownloadDiag.append(viewKey, "91porny=命中 host=" + DownloadDiag.hostOf(porny.getVideoUrl()) + " → HLS下载");
                        PornyFallbackResolver.applyPornyResult(dataManager, target, porny);
                        // M50：兜底路径也要 ensureDownloadDir（之前漏了，HLS 写到不可写目录会 error）
                        String hlsPath2 = path;
                        try {
                            String ensured = SDCardUtils.ensureDownloadDir(path, context);
                            if (ensured != null) hlsPath2 = ensured;
                        } catch (Exception ignored) {}
                        PornyFallbackResolver.enqueueHlsDownload(context,
                                target, porny.getVideoUrl(), hlsPath2);
                        return new DownloadResult(true, "源站受限，已改用分分钟源下载");
                    }
                    DownloadDiag.append(viewKey, "91porny=未命中 → 退回原直链");
                } catch (Exception e) {
                    DownloadDiag.append(viewKey, "91porny=解析异常(" + (e.getMessage() == null ? e.toString() : e.getMessage()) + ") → 退回原直链");
                }
                // 备用源未命中：仍用原直链尝试（可能探活误报）
            }
        }
        String referer = null;
        try {
            String addr = dataManager.getMman9VideoAddress();
            if (!TextUtils.isEmpty(addr)) {
                // M62：viewKey 已带 "viewkey=" 前缀，直接拼会产生 viewkey=viewkey=XXX
                String bareKey = viewKey != null && viewKey.startsWith("viewkey=")
                        ? viewKey.substring(8) : viewKey;
                referer = addr + "view_video.php?viewkey=" + bareKey;
            }
        } catch (Exception ignored) {
        }
        // M47：Android 11+ 上 /sdcard/3mman/video/ 对 app 只读，FileDownloader 写入会抛
        // java.io.IOException: Operation not permitted。提前探测并 fallback 到 app 私有目录。
        String requestedPath = path;
        path = SDCardUtils.ensureDownloadDir(path, context);
        AppLog.i(TAG, "推荐下载目录 requested=" + requestedPath + " actual=" + path);
        if (path == null) {
            DownloadDiag.append(viewKey, "download=目录不可写且无 fallback（requested=" + requestedPath + "）");
            return new DownloadResult(false, "下载目录不可写，请检查存储权限或更换下载目录");
        }
        DownloadDiag.append(viewKey, "startDownload url.host=" + DownloadDiag.hostOf(url) + " referer=" + DownloadDiag.hostOf(referer) + " path.dir=" + path);
        // M61：m3u8 绝不能交给 FileDownloader（会把播放列表文本秒下成假 mp4），改走 HLS 服务
        if (DownloadManager.isHlsUrl(url)) {
            DownloadDiag.append(viewKey, "HLS=改走HlsDownloadService");
            PornyFallbackResolver.enqueueHlsDownload(context, target, url, path);
            return new DownloadResult(true, "已加入后台下载");
        }
        int id = DownloadManager.getImpl().startDownload(url, path,
                dataManager.isDownloadVideoNeedWifi(), false, referer);
        if (target.getAddDownloadDate() == null) {
            target.setAddDownloadDate(new Date());
        }
        target.setDownloadId(id);
        dataManager.updateV9MmanItem(target);
        return new DownloadResult(true, getString(R.string.reco_download_started));
    }

    /** 下载入队结果 */
    private static final class DownloadResult {
        final boolean ok;
        final String message;

        DownloadResult(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private boolean isFavorited(RecoCandidate candidate) {
        if (engine.actionOf(candidate.viewKey()) == RecoStore.ACTION_FAVORITE) {
            return true;
        }
        return candidate.item != null && Boolean.TRUE.equals(candidate.item.getIsLocalFavorite());
    }

    /** 与播放页的本地收藏保持一致（IO 线程调用） */
    private void saveFavorite(V9MmanItem item, boolean favorite) {
        if (item == null) {
            return;
        }
        if (item.getSourceName() == null) {
            item.setSourceName(item.getSource());
        }
        item.setIsLocalFavorite(favorite);
        try {
            VideoResult result = item.getVideoResult();
            if (result != null) {
                dataManager.saveVideoResult(result);
            }
        } catch (Exception ignored) {
            // 实体脱离 DaoSession 时 getVideoResult 会抛 DaoException
        }
        dataManager.saveV9MmanItem(item);
    }

    // ==================== 持久化 ====================

    private void persistAsync(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastPersistTime < PERSIST_INTERVAL) {
            return;
        }
        lastPersistTime = now;
        disposables.add(Observable.just(1)
                .subscribeOn(Schedulers.io())
                .subscribe(i -> engine.persist(),
                        throwable -> Logger.t(TAG).d("persist failed: " + throwable.getMessage())));
    }

    // ==================== 生命周期 ====================

    @Override
    public void onResume() {
        super.onResume();
        if (repository != null && context != null) {
            repository.setAutoRotateLandscape(PlayUiPrefs.isAutoRotateLandscape(context));
            repository.setOrientationFilter(PlayUiPrefs.getOrientationFilter(context));
            updateOrientationPill(PlayUiPrefs.getOrientationFilter(context));
        }
        if (currentPosition >= 0 && adapter != null) {
            applyAutoRotation(adapter.getItem(currentPosition));
        }
        // onPause/onHiddenChanged 已经释放底层播放器，回到页面时只恢复当前候选。
        resumeCurrentPlaybackIfNeeded();
    }

    @Override
    public void onPause() {
        // 不使用 goOnPlayOnPause：它只暂停并保留 MediaPlayer，切换页面后仍可能有音频输出。
        stopPlaybackForLeavingPage();
        recordWatchRatio(currentPosition);
        persistAsync(true);
        super.onPause();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        // hide/show 切换（切到其它 Tab）时必须释放底层播放器，不能只暂停。
        if (hidden) {
            stopPlaybackForLeavingPage();
            recordWatchRatio(currentPosition);
            persistAsync(true);
            // 切到其它 Tab：恢复 App 默认紫色状态栏（推荐流内才透明）
            restoreAppStatusBar();
        } else if (isResumed()) {
            if (currentPosition >= 0 && adapter != null) {
                applyAutoRotation(adapter.getItem(currentPosition));
            }
            resumeCurrentPlaybackIfNeeded();
        }
    }

    /** 供宿主 MainActivity 返回键转发：先让播放器消费（退出全屏等） */
    public boolean handleBackPressed() {
        if (JZVideoPlayer.backPress()) {
            return true;
        }
        return false;
    }

    @Override
    public void onDestroyView() {
        stopCoverWatcher();
        stopProgressTicker();
        handler.removeCallbacksAndMessages(null);
        recordWatchRatio(currentPosition);
        // View 已销毁，Rx 订阅马上会被清掉，这里用裸线程保证画像一定落盘
        final RecoEngine target = engine;
        if (target != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        target.persist();
                    } catch (Exception ignored) {
                    }
                }
            }, "reco-persist").start();
        }
        if (!disposables.isDisposed()) {
            disposables.clear();
        }
        if (prefetcher != null) {
            prefetcher.release();
        }
        JZVideoPlayer.releaseAllVideos();
        // Fragment 销毁（离开推荐流）：恢复 App 默认紫色状态栏
        restoreAppStatusBar();
        if (getActivity() != null) {
            try {
                getActivity().getWindow().getDecorView()
                        .setOnSystemUiVisibilityChangeListener(null);
            } catch (Exception ignored) {
            }
            try {
                getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } catch (Exception ignored) {
            }
        }
        super.onDestroyView();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // M82：横屏锁状态下，系统重算方向（旋转 / 切后台返回）后重新断言横屏 + 沉浸，避免被拉回竖屏。
        if (landscapeLock) {
            enterLandscapeFullscreen();
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("reco_landscape_lock", landscapeLock);
    }
}
