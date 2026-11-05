package com.m3man.ui.recommend;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Build;
import android.content.res.Configuration;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
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

import com.liulishuo.filedownloader.BaseDownloadTask;
import com.liulishuo.filedownloader.model.FileDownloadStatus;
import com.google.android.material.snackbar.Snackbar;
import com.m3man.R;
import com.m3man.constants.Keys;
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
import com.m3man.ui.download.DownloadActivity;
import com.m3man.ui.mman9video.author.AuthorActivity;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
// RxJava2 的 fromCallable 收 java.util.concurrent.Callable，io.reactivex.functions 包下无此类型
import java.util.concurrent.Callable;

import javax.inject.Inject;

import cn.jzvd.JZVideoPlayer;
import cn.jzvd.JZVideoPlayerStandard;
import com.google.gson.Gson;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;

/**
 * 推荐流（抖音式上下滑全屏播放）—— 以 Fragment 形式内嵌在主界面「推荐」Tab 下，
 * 点击 Tab 固定选中并直接展示本页（不再跳独立 Activity）。
 * 逻辑与原 RecommendFeedActivity 一致（引擎召回 / 预取 / 进度条 / 封面 watcher / 下载兜底）。
 */
public class RecommendFeedFragment extends BaseFragment
        implements RecommendFeedAdapter.Callback, LandscapeOrientationHelper.Host {

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
    /** 首批请求是否已经真正提交；避免首次生命周期回调被跳过后永久停在 loading。 */
    private boolean firstLoadStarted = false;
    private boolean engineInitFailed = false;
    private boolean engineInitStarted = false;
    private boolean noMore = false;
    private int emptyRetryCount = 0;
    /**
     * L-fix：跟踪上一次推到 RecoRepository 的「时长上限（分钟）」，
     * onResume 时与持久化值对比，变化则重置会话重拉（已出队不可逆）。
     */
    private int lastAppliedRecoDurationMinutes = 0;
    private Runnable coverWatcher;
    private Runnable progressTicker;
    /** 用户正在拖动进度条时暂停自动刷新，避免手指被「拽回去」 */
    private boolean userSeeking = false;
    /** M-06: Fragment 销毁后防止 progressTicker 重复触发 */
    private boolean viewDestroyed = false;
    /** v3 P0-1：当前页进入时刻（elapsedRealtime），结算隐式反馈时算停留时长用 */
    private long currentEnterElapsed = 0L;

    /** M78：方向筛选（0=全部 1=仅竖屏 2=仅横屏） */
    private ImageView landscapeBackView;

    /** EX-03：右上角唯一筛选入口（显示“方向 · 时长”），不再拆成三枚胶囊。 */
    private TextView filterEntryView;
    /** EX-03：在途批次请求，结构变更时必须先取消，杜绝旧响应回灌新流。 */
    private io.reactivex.disposables.Disposable batchDisposable;

    /** M119：倍速可选项（与右栏长按 2x 手势共存，菜单选择会持久化） */
    private static final float[] RECO_SPEED_VALUES = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
    /** M119：断点续播仅对超过 3 分钟的视频生效 */
    private static final long RESUME_MIN_DURATION_MS = 180_000L;
    /** M119：viewKey → 离开时的播放位置（ms），会话内有效 */
    private final HashMap<String, Long> resumePositions = new HashMap<>();
    private String pendingResumeKey;
    private long pendingResumeMillis = -1L;

    /** M119：下载角标状态（viewKey → 标签文案）与 downloadId 反查表 */
    private final HashMap<String, String> downloadLabelStates = new HashMap<>();
    private final HashMap<Integer, String> downloadIdToViewKey = new HashMap<>();
    private final Set<String> hydratedDownloadKeys = new HashSet<>();
    private final Set<Integer> downloadIdLookupInFlight = new HashSet<>();
    /** EX-05：收藏请求在途去重（同一 itemKey 只允许一次写库/学习）。 */
    private final Set<String> favoriteInFlight = new HashSet<>();

    /** M119：新手引导浮层与筛选空池恢复按钮 */
    private View guideView;
    private Button resetFilterButton;
    private Button adjustFilterButton;
    /** M119：第一页下拉换一批 */
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh;

    /** 横屏/沉浸模式 Helper（从 Fragment 提取，降低 God Class 复杂度） */
    private LandscapeOrientationHelper orientationHelper;
    /** 下载入队 Helper（从 Fragment 提取，降低 God Class 复杂度） */
    private DownloadEnqueuer downloadEnqueuer;

    /** T01：推荐缓存批次读写器（cacheFirst 开时启用；JSON/IO 全在缓存执行器） */
    private RecoBatchCache batchCache;
    /** T01：视图代际——onViewCreated/筛选结构变更递增，旧缓存读结果按代际丢弃 */
    private int viewGeneration = 0;
    /** T01：缓存批次已上屏（后续不再重复盖 globalLoading、不重复自动起播/曝光） */
    private boolean cacheRendered = false;
    /** T01：网络内容已上屏（后到的缓存不清空前到内容） */
    private boolean networkVisible = false;
    /** T01：缓存已渲染但引擎未就绪——引擎 ready 且当前页有效时补一次自动起播 */
    private boolean pendingAutoPlay = false;

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
        // 初始化 Helper 类
        orientationHelper = new LandscapeOrientationHelper(this);
        downloadEnqueuer = new DownloadEnqueuer(dataManager, okHttpClient, context, disposables);
        // M82：进程重建后恢复横屏锁，避免回到竖屏
        if (savedInstanceState != null) {
            orientationHelper.setLandscapeLock(savedInstanceState.getBoolean("reco_landscape_lock", false));
        }
        if (getActivity() != null) {
            getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getActivity().getWindow().getDecorView()
                    .setOnSystemUiVisibilityChangeListener(visibility -> {
                        if (orientationHelper.isLandscapeLock() && getActivity() != null
                                && ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0
                                || (visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0)) {
                            orientationHelper.reapplyImmersive();
                        }
                    });
        }
        initViews();

        // M91：RecoEngine 初始化含磁盘 I/O（assets JSON 词典 + SharedPreferences 画像），
        // 移到后台线程执行，避免阻塞主线程导致进入推荐页时黑屏转圈过久。
        // globalLoading 已默认 VISIBLE，引擎就绪后再发起首次拉取。
        // H-06：使用 RxJava + CompositeDisposable 替代原始 Thread，
        // 确保 Fragment 销毁时自动取消，避免访问已销毁的 View。
        final Context appContext = (context != null) ? context.getApplicationContext() : null;
        if (appContext == null) {
            return;
        }
        // T01：视图重建 generation 递增；旧视图的在途缓存读结果按代际丢弃
        viewGeneration++;
        // T01（方案 §5-T01 顺序 1）：View 就绪后缓存 IO 与引擎 IO 并行启动。
        // 缓存解析/过滤/DTO 构造在缓存执行器，主线程只接收不可变批次渲染。
        // 保留既有缓存启用规则：仅预取开关开启时读缓存展示（不改变设置语义）。
        if (com.m3man.perf.PerfFlags.cacheFirst
                && PlayUiPrefs.isRecoPrefetchEnabled(appContext)) {
            batchCache = new RecoBatchCache(appContext, dataManager);
            startCacheBatchRead();
        }
        disposables.add(Observable.fromCallable(new Callable<RecoEngine>() {
            @Override
            public RecoEngine call() throws Exception {
                return RecoEngine.get(appContext);
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe(disposable -> {
                    // 订阅时检查 Fragment 是否仍附着
                    if (!isAdded() || isDetached()) {
                        disposable.dispose();
                    }
                })
                .subscribe(new Consumer<RecoEngine>() {
                    @Override
                    public void accept(RecoEngine e) throws Exception {
                        if (getView() == null || !isAdded() || isDetached()) {
                            return;
                        }
                        engine = e;
                        repository = new RecoRepository(dataManager, engine);
                        prefetcher = new RecommendPrefetcher(appContext, dataManager);
                        repository.setOrientationFilter(PlayUiPrefs.getOrientationFilter(appContext));
                        repository.setAutoRotateLandscape(PlayUiPrefs.isAutoRotateLandscape(appContext));
                        // 推荐流时长上限：仅在数据源就绪后才能安全地 push 给 repository
                        repository.setMaxDurationMinutes(dataManager.getRecoMaxDurationMinutes());
                        lastAppliedRecoDurationMinutes = repository.getMaxDurationMinutes();
                        // M92c：Adapter 构造时 engine 还是 null（后台初始化），
                        // 必须回填并刷新已挂载页，否则点赞/踩/收藏选中态永远不显示
                        if (adapter != null) {
                            adapter.setEngine(engine);
                            adapter.refreshAttachedActionStates(recyclerView);
                        }
                        // T01（方案 §5-T01 顺序 2）：缓存已上屏时不再显示遮挡内容的 globalLoading，
                        // 静默续拉网络召回；需要画像的自动起播/曝光结算在引擎 ready
                        // 且当前页仍有效时执行一次（pendingAutoPlay 只消费一次）。
                        if (com.m3man.perf.PerfFlags.cacheFirst && cacheRendered
                                && adapter != null && adapter.getItemCount() > 0) {
                            if (pendingAutoPlay && currentPosition == -1) {
                                pendingAutoPlay = false;
                                onPageSelected(0);
                            }
                            loadMore(false);
                        } else {
                            loadMore(true);
                        }
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable t) throws Exception {
                        AppLog.e(TAG, "推荐引擎初始化失败: " + AppLog.cause(t));
                        if (getView() != null && isAdded() && !isDetached()) {
                            engineInitFailed = true;
                            globalLoading.setVisibility(View.GONE);
                            showEmpty("推荐初始化失败，点击重试");
                        }
                    }
                }));
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
                    orientationHelper.applyFeedContentFullBleed();
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
        // EX-03：筛选入口整体做状态栏避让；横屏左侧给返回箭头让位，避免热区重叠。
        View topFilters = getView() == null ? null : getView().findViewById(R.id.ll_reco_top_filters);
        if (topFilters != null) {
            ViewGroup.LayoutParams raw = topFilters.getLayoutParams();
            if (raw instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
                lp.topMargin = statusBarHeight + dp(14);
                lp.topMargin = Math.max(lp.topMargin, dp(14));
                boolean landscape = getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_LANDSCAPE;
                int start = dp(landscape ? 62 : 14);
                lp.setMarginStart(start);
                lp.leftMargin = start;
                topFilters.setLayoutParams(lp);
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
                orientationHelper.restorePortrait();
            }
        });

        layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setHasFixedSize(true);
        // 关掉默认动画，避免插入新页时当前页被重绘导致画面闪烁
        recyclerView.setItemAnimator(null);
        // T03：注册推荐页封面容器实测尺寸（全屏页），预取与展示共享同一 spec 缓存键
        recyclerView.post(new Runnable() {
            @Override
            public void run() {
                if (getView() != null && recyclerView.getWidth() > 0
                        && recyclerView.getHeight() > 0) {
                    com.m3man.utils.CoverRequests.registerCardSize(
                            recyclerView.getWidth(), recyclerView.getHeight());
                }
            }
        });

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
                engineInitFailed = false;
                emptyLayout.setVisibility(View.GONE);
                if (repository == null) {
                    // 初始化线程仍未完成时不提交空请求；onResume/初始化回调会自动补发首批。
                    globalLoading.setVisibility(View.VISIBLE);
                    return;
                }
                repository.resetSession();
                loadMore(true);
            }
        });

        // M119：新手引导浮层（首次显示，点击任意处关闭）
        guideView = getView().findViewById(R.id.ll_reco_guide);
        guideView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideGuide();
            }
        });
        // M119：筛选空池的一键恢复入口
        resetFilterButton = getView().findViewById(R.id.btn_recommend_reset_filter);
        resetFilterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetAllFilters();
            }
        });
        adjustFilterButton = getView().findViewById(R.id.btn_recommend_adjust_filter);
        if (adjustFilterButton != null) {
            adjustFilterButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showFilterSheet();
                }
            });
        }
        // M119：第一页下拉换一批（SwipeRefreshLayout 只在列表到顶时拦截下拉）
        swipeRefresh = getView().findViewById(R.id.swipe_refresh_recommend);
        if (swipeRefresh != null) {
            swipeRefresh.setColorSchemeResources(R.color.colorPrimary);
            swipeRefresh.setOnRefreshListener(new androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener() {
                @Override
                public void onRefresh() {
                    swapBatch();
                }
            });
        }
        // M119：注册下载状态回调（右栏"下载"标签实时显示进度/完成）
        DownloadManager.getImpl().addUpdater(downloadUpdater);

        initFilterEntry();
        setupFeedAccessibilityActions();
    }

    /**
     * EX-03：TalkBack 自定义「下一视频 / 上一视频」动作；
     * 画面上仍不放下一条/上一条按钮，翻页只用手势与吸附。
     */
    private void setupFeedAccessibilityActions() {
        androidx.core.view.ViewCompat.addAccessibilityAction(recyclerView,
                getString(R.string.reco_filter_a11y_next),
                new androidx.core.view.accessibility.AccessibilityViewCommand() {
                    @Override
                    public boolean perform(View view,
                                           androidx.core.view.accessibility.AccessibilityViewCommand.CommandArguments arguments) {
                        moveFeedBy(1);
                        return true;
                    }
                });
        androidx.core.view.ViewCompat.addAccessibilityAction(recyclerView,
                getString(R.string.reco_filter_a11y_previous),
                new androidx.core.view.accessibility.AccessibilityViewCommand() {
                    @Override
                    public boolean perform(View view,
                                           androidx.core.view.accessibility.AccessibilityViewCommand.CommandArguments arguments) {
                        moveFeedBy(-1);
                        return true;
                    }
                });
    }

    /** 无障碍切片：首条下滑/末条上滑不越界，翻页仍走 onPageSelected 的既有结算与起播链路。 */
    private void moveFeedBy(int delta) {
        if (adapter == null || recyclerView == null) {
            return;
        }
        int base = Math.max(0, currentPosition);
        int target = Math.max(0, Math.min(adapter.getItemCount() - 1, base + delta));
        if (target != currentPosition) {
            recyclerView.smoothScrollToPosition(target);
        }
    }

    // ==================== 筛选与播放（EX-03） ====================

    private void initFilterEntry() {
        filterEntryView = getView().findViewById(R.id.tv_reco_filter_entry);
        updateFilterEntry();
        filterEntryView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFilterSheet();
            }
        });
    }

    /** 入口只显示当前已应用值：方向 · 时长。 */
    private void updateFilterEntry() {
        if (filterEntryView == null || context == null) {
            return;
        }
        int orientation = PlayUiPrefs.getOrientationFilter(context);
        int minutes = dataManager.getRecoMaxDurationMinutes();
        filterEntryView.setText(getString(R.string.reco_filter_entry,
                orientationLabel(orientation), durationLabel(minutes)));
    }

    private String orientationLabel(int filter) {
        if (filter == PlayUiPrefs.FILTER_PORTRAIT) {
            return getString(R.string.reco_filter_orientation_portrait);
        }
        if (filter == PlayUiPrefs.FILTER_LANDSCAPE) {
            return getString(R.string.reco_filter_orientation_landscape);
        }
        return getString(R.string.reco_filter_orientation_all);
    }

    private String durationLabel(int minutes) {
        return minutes <= 0
                ? getString(R.string.reco_filter_duration_unlimited)
                : getString(R.string.reco_filter_duration_value, minutes);
    }

    /** 打开筛选面板：复制已应用值到草稿，取消/遮罩/Back 不产生任何副作用。 */
    private void showFilterSheet() {
        if (context == null || dataManager == null) {
            return;
        }
        RecoFilterSheet.show(context,
                PlayUiPrefs.getOrientationFilter(context),
                dataManager.getRecoMaxDurationMinutes(),
                PlayUiPrefs.isRecoAutoNextEnabled(context),
                new RecoFilterSheet.Listener() {
                    @Override
                    public void onApply(int orientation, int maxDurationMinutes, boolean autoplay) {
                        applyFilters(orientation, maxDurationMinutes, autoplay);
                    }
                });
    }

    /**
     * 一次提交：
     * 1. 结构变更（方向/时长）→ 按已有规则结算一次当前条目 → 原子写偏好 →
     *    取消旧批次 → 一次 reset/refill；
     * 2. 仅连播变化 → 只写偏好并刷新提示，不 reset、不重排、不中断播放；
     * 3. 无任何变化 → 只关闭面板。
     */
    private void applyFilters(int newOrientation, int newMinutes, boolean newAutoplay) {
        if (context == null) {
            return;
        }
        int oldOrientation = PlayUiPrefs.getOrientationFilter(context);
        int oldMinutes = dataManager.getRecoMaxDurationMinutes();
        boolean oldAutoplay = PlayUiPrefs.isRecoAutoNextEnabled(context);
        boolean structureChanged = newOrientation != oldOrientation || newMinutes != oldMinutes;
        boolean autoplayChanged = newAutoplay != oldAutoplay;
        if (!structureChanged && !autoplayChanged) {
            return;
        }

        if (structureChanged) {
            // 结构变更前按已有有效观看规则结算离开条目一次（不叠加额外隐式反馈）
            settleImplicit(currentPosition);
            captureResumePosition(currentPosition);
        }

        // 原子写入：方向/时长/连播各写原有键，不创建重复键
        PlayUiPrefs.setOrientationFilter(context, newOrientation);
        dataManager.setRecoMaxDurationMinutes(newMinutes);
        lastAppliedRecoDurationMinutes = newMinutes;
        PlayUiPrefs.setRecoAutoNextEnabled(context, newAutoplay);
        updateFilterEntry();
        if (adapter != null) {
            adapter.refreshAutoplayHints(recyclerView);
        }

        if (!structureChanged) {
            showMessage(getString(R.string.reco_filter_applied), TastyToast.INFO);
            return;
        }

        // 新 generation：先取消在途旧批次，保证只有本轮流能落到 adapter
        cancelBatchRequest();
        if (repository == null) {
            return;
        }
        repository.setOrientationFilter(newOrientation);
        repository.setMaxDurationMinutes(newMinutes);
        adapter.setData(new ArrayList<RecoCandidate>());
        currentPosition = -1;
        noMore = false;
        emptyRetryCount = 0;
        emptyLayout.setVisibility(View.GONE);
        resetFilterButton.setVisibility(View.GONE);
        if (adjustFilterButton != null) {
            adjustFilterButton.setVisibility(View.GONE);
        }
        JZVideoPlayer.releaseAllVideos();
        stopCoverWatcher();
        stopProgressTicker();
        repository.resetSession();
        // T01：筛选结构变更 = 新会话；作废在途缓存读，复位缓存/网络可见状态。
        // 旧缓存批次因 scope（方向/时长）已变化，本来也不会再命中。
        viewGeneration++;
        cacheRendered = false;
        networkVisible = false;
        pendingAutoPlay = false;
        globalLoading.setVisibility(View.VISIBLE);
        loadMore(true);
        showMessage(getString(R.string.reco_filter_applied), TastyToast.INFO);
    }

    /** 取消在途批次请求（结构变更/换一批时调用，防止旧响应回灌新流）。 */
    private void cancelBatchRequest() {
        if (batchDisposable != null && !batchDisposable.isDisposed()) {
            batchDisposable.dispose();
        }
        batchDisposable = null;
        loading = false;
    }

    /**
     * 当前视频自然播完（RecoVideoPlayer.OnAutoCompletionListener）：
     * 连播开且下一条存在 → 平滑翻页，完全复用既有 onPageSelected → startPlay 起播链路；
     * 连播开但已是最后一条 → 触发补批，本条原地循环兜底，新批次到后下次播完接上；
     * 连播关 → 原地循环（原行为）。只处理「该 player 就是当前页 holder」的回调。
     */
    private void onFeedVideoCompleted(RecoVideoPlayer player) {
        if (!isUsable() || viewDestroyed) {
            return;
        }
        RecommendFeedAdapter.PageHolder holder = findHolder(currentPosition);
        boolean isCurrentPage = holder != null && holder.player == player;
        if (isCurrentPage && PlayUiPrefs.isRecoAutoNextEnabled(context)) {
            if (currentPosition + 1 < adapter.getItemCount()) {
                // 完播是最强正反馈：走 v3 P0-1 的完播结算（含每视频一次封顶，
                // 循环播放下反复触发只首次全额）；翻页时的 settle 不会重复全额
                RecoCandidate finished = adapter.getItem(currentPosition);
                if (finished != null && engine != null) {
                    engine.onImplicitFinish(finished);
                }
                recyclerView.smoothScrollToPosition(currentPosition + 1);
                return;
            }
            if (!noMore && !loading && repository != null) {
                loadMore(false);
            }
        }
        if (!isCurrentPage) {
            return;
        }
        // 原地循环兜底（=原 loopEnabled 行为）：下一帧再起播，避免和 release 流程抢资源
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!isUsable() || viewDestroyed) {
                    return;
                }
                RecommendFeedAdapter.PageHolder h = findHolder(currentPosition);
                if (h != null && h.player == player) {
                    try {
                        h.player.startVideo();
                    } catch (Exception ignored) {
                        // 页面已回收
                    }
                }
            }
        });
    }

    // ==================== 数据 ====================

    /**
     * T01（方案 §5-T01 顺序 1）：并行启动缓存批次读取。
     * SP 读/JSON 解析/候选重建都在缓存执行器；主线程只接收不可变批次渲染。
     */
    private void startCacheBatchRead() {
        final int gen = viewGeneration;
        disposables.add(batchCache.read()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(batch -> {
                    // 缓存命中计数（供 T00 基线区分 cacheHit）
                    com.m3man.perf.Perf.recorder().counter(
                            batch == null || batch.expired ? "reco.cache.miss" : "reco.cache.hit", 1);
                    renderCachedBatch(batch, gen);
                }, throwable -> {
                    com.m3man.perf.Perf.recorder().counter("reco.cache.read_fail", 1);
                    // 读缓存失败按未命中处理，不阻断引擎路径
                    Logger.t(TAG).d("read reco cache failed: %s", throwable.getMessage());
                }));
    }

    /**
     * T01（方案 §5-T01 顺序 3）：渲染缓存批次。
     * 状态机：EMPTY_LOADING → CACHE_VISIBLE → NETWORK_VISIBLE（或 EMPTY_ERROR）。
     * 已有条目（网络先到/重复进入）时不再渲染，后到的缓存不清空前到内容；
     * 自动起播只在引擎 ready 后执行一次，不重复记录曝光。
     */
    private void renderCachedBatch(RecoBatchCache.CacheBatch batch, int gen) {
        if (gen != viewGeneration || getView() == null || !isAdded() || isDetached() || viewDestroyed) {
            return; // 过期代际 / 视图已销毁
        }
        if (batch == null || batch.expired || batch.candidates.isEmpty()) {
            return; // 未命中：等待引擎路径，不改状态
        }
        if (adapter == null || adapter.getItemCount() != 0 || networkVisible) {
            return; // 已有内容：后到缓存不清空前到内容
        }
        long renderReq = com.m3man.perf.Perf.nextRequestId();
        long renderBegin = com.m3man.perf.Perf.recorder()
                .begin(com.m3man.perf.Perf.ST_RECO_CACHE_RENDER, renderReq);
        try {
            adapter.appendData(batch.candidates);
            cacheRendered = true;
            globalLoading.setVisibility(View.GONE);
            emptyLayout.setVisibility(View.GONE);
            if (engine != null) {
                // 缓存后到但引擎已就绪：与 M119 一致补自动起播首页（只此一次）
                if (currentPosition == -1) {
                    recyclerView.post(new Runnable() {
                        @Override
                        public void run() {
                            if (currentPosition == -1) {
                                onPageSelected(0);
                            }
                        }
                    });
                }
            } else {
                // 引擎未就绪：挂起自动起播，引擎 ready 后消费（见引擎回调）
                pendingAutoPlay = true;
            }
        } finally {
            com.m3man.perf.Perf.recorder().end(com.m3man.perf.Perf.ST_RECO_CACHE_RENDER,
                    renderReq, renderBegin, com.m3man.perf.Perf.OUTCOME_OK);
        }
    }

    /**
     * L-fix：读取上次会话的本地缓存批次并立即上屏。条目点击后走既有播放解析流程。
     * 与后续网络批次的衔接：网络批次 append 在尾部，用户滑过缓存条目即进入新内容。
     * T01：cacheFirst 开启时该 v1 路径不再进入（缓存展示移至 onViewCreated 并行读取）。
     */
    private void showCachedBatchFirst() {
        try {
            android.content.Context ctx = getContext() != null
                    ? getContext().getApplicationContext() : null;
            String json = ctx == null ? null : PlayUiPrefs.getRecoCacheBatch(ctx);
            if (TextUtils.isEmpty(json)) {
                return;
            }
            V9MmanItem[] arr = new Gson().fromJson(json, V9MmanItem[].class);
            java.util.List<RecoCandidate> cached = new ArrayList<>();
            for (V9MmanItem it : (arr == null ? new V9MmanItem[0] : arr)) {
                if (it != null && !TextUtils.isEmpty(it.getViewKey())) {
                    cached.add(new RecoCandidate(it, null, RecoCandidate.FROM_EXPLORE));
                }
            }
            if (!cached.isEmpty() && adapter.getItemCount() == 0) {
                adapter.appendData(cached);
                globalLoading.setVisibility(View.GONE);
                // M119：缓存秒显后同样自动起播首页（此前只有网络首批才触发 onPageSelected(0)，
                // 有缓存时首条视频永远不自动播，新手引导也不会出现）
                recyclerView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (currentPosition == -1) {
                            onPageSelected(0);
                        }
                    }
                });
            }
        } catch (Exception e) {
            Logger.t(TAG).d("展示推荐本地缓存被跳过: %s", e.getMessage());
        }
    }

    /** L-fix：把本批候选序列化进本地缓存（保留最近一屏），供冷启动秒显 */
    private void saveBatchToCache(java.util.List<RecoCandidate> list) {
        try {
            android.content.Context ctx = getContext() != null
                    ? getContext().getApplicationContext() : null;
            if (ctx == null || list == null || list.isEmpty()) {
                return;
            }
            ArrayList<V9MmanItem> keep = new ArrayList<>();
            for (RecoCandidate c : list) {
                if (c != null && c.item != null && !TextUtils.isEmpty(c.item.getViewKey())) {
                    keep.add(c.item);
                }
            }
            if (keep.size() > BATCH_SIZE) {
                keep.subList(0, keep.size() - BATCH_SIZE).clear();
            }
            PlayUiPrefs.setRecoCacheBatch(ctx, new Gson().toJson(keep));
        } catch (Exception ignored) {
        }
    }

    /**
     * T01（方案 §5-T01 顺序 4）：主线程标量拷贝构造 DTO → 缓存执行器编码落盘。
     * 保留最近一屏（与 v1 saveBatchToCache 语义一致），合并旧写、只写最新批。
     */
    private void saveBatchToCacheV2(java.util.List<RecoCandidate> list) {
        if (batchCache == null || list == null || list.isEmpty()) {
            return;
        }
        ArrayList<CachedVideo> keep = new ArrayList<>(list.size());
        for (RecoCandidate c : list) {
            CachedVideo d = CachedVideo.fromCandidate(c);
            if (d != null) {
                keep.add(d);
            }
        }
        if (keep.size() > BATCH_SIZE) {
            keep.subList(0, keep.size() - BATCH_SIZE).clear();
        }
        if (!keep.isEmpty()) {
            batchCache.writeLatest(keep);
        }
    }

    /**
     * T01：与已展示内容按 VideoKey 去重；批内同 key 也去重。
     * 同 key 的网络候选携带权威元数据——就地回填到已展示条目（不 notify、不重绑定）。
     */
    private List<RecoCandidate> dedupAgainstDisplayed(List<RecoCandidate> list) {
        Set<String> seen = new HashSet<>();
        List<RecoCandidate> existing = adapter == null ? null : adapter.getData();
        if (existing != null) {
            for (RecoCandidate c : existing) {
                String k = displayKey(c);
                if (k != null) {
                    seen.add(k);
                }
            }
        }
        List<RecoCandidate> out = new ArrayList<>(list.size());
        for (RecoCandidate c : list) {
            if (c == null || c.item == null) {
                continue;
            }
            String k = displayKey(c);
            if (k != null && seen.contains(k)) {
                mergeAuthoritativeMetadata(k, c);
                continue;
            }
            if (k != null) {
                seen.add(k);
            }
            out.add(c);
        }
        return out;
    }

    /** T01：展示身份键（源+归一化 viewKey）；无法构造稳定身份返回 null（调用方不当作同一 NO_ID）。 */
    private static String displayKey(RecoCandidate c) {
        if (c == null || c.item == null) {
            return null;
        }
        com.m3man.perf.VideoKey k = com.m3man.perf.VideoKey.of(
                c.item.getSourceName(), c.item.getViewKey());
        return k != null ? k.toString() : null;
    }

    /**
     * T01：网络同 key 更新权威元数据。已展示条目为 detached 对象（缓存壳/解析新建），
     * 原地改标量字段但不 notifyItemChanged——保留正在播放的 ViewHolder 与位置，不重启播放器。
     */
    private void mergeAuthoritativeMetadata(String key, RecoCandidate network) {
        if (adapter == null || network == null || network.item == null) {
            return;
        }
        List<RecoCandidate> data = adapter.getData();
        for (int i = 0; i < data.size(); i++) {
            RecoCandidate c = data.get(i);
            if (!key.equals(displayKey(c))) {
                continue;
            }
            V9MmanItem src = network.item;
            V9MmanItem dst = c.item;
            if (!TextUtils.equals(dst.getTitle(), src.getTitle())) {
                dst.setTitle(src.getTitle());
            }
            if (!TextUtils.equals(dst.getImgUrl(), src.getImgUrl())) {
                dst.setImgUrl(src.getImgUrl());
            }
            if (!TextUtils.equals(dst.getDuration(), src.getDuration())) {
                dst.setDuration(src.getDuration());
            }
            if (!TextUtils.equals(dst.getInfo(), src.getInfo())) {
                dst.setInfo(src.getInfo());
            }
            if (c.publishYear != network.publishYear) {
                c.publishYear = network.publishYear;
            }
            break;
        }
    }

    private void loadMore(final boolean first) {
        if (loading || (noMore && !first) || repository == null) {
            return;
        }
        // L-fix：进入推荐先渲染上次会话的本地缓存批次（秒开，不转圈），随后照常拉取新批次追加在后
        // T01：cacheFirst 开启时缓存展示已移至 onViewCreated 并行读取，此处仅保留旧路径
        if (first && !com.m3man.perf.PerfFlags.cacheFirst && PlayUiPrefs.isRecoPrefetchEnabled(
                getContext() != null ? getContext().getApplicationContext() : null)) {
            showCachedBatchFirst();
        }
        if (first) {
            firstLoadStarted = true;
        }
        loading = true;
        if (first) {
            globalLoading.setVisibility(View.VISIBLE);
            emptyLayout.setVisibility(View.GONE);
        }
        disposables.add(batchDisposable = repository.nextBatch(BATCH_SIZE)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(list -> onBatchLoaded(list, first),
                        throwable -> onBatchFailed(first, throwable)));
    }

    private void onBatchLoaded(List<RecoCandidate> list, boolean first) {
        loading = false;
        batchDisposable = null;
        globalLoading.setVisibility(View.GONE);
        if (swipeRefresh != null) {
            swipeRefresh.setRefreshing(false);
        }

        if (list == null || list.isEmpty()) {
            // 某个召回源恰好没数据是常态，换一个源再试几次。
            // M98：只有「成功且空」才计入连续空批计数；召回错误已改走 onBatchFailed（仓库层不再吞错）。
            if (++emptyRetryCount < MAX_EMPTY_RETRY) {
                loadMore(first);
                return;
            }
            emptyRetryCount = 0;
            if (adapter.getItemCount() == 0) {
                // M119：方向/时长严格筛选可能把池子筛空，此时给出一键恢复入口
                if (isFilterActive()) {
                    showEmpty(getString(R.string.reco_empty_filtered_action));
                    resetFilterButton.setVisibility(View.VISIBLE);
                    if (adjustFilterButton != null) {
                        adjustFilterButton.setVisibility(View.VISIBLE);
                    }
                } else {
                    showEmpty(getString(R.string.reco_empty));
                }
            } else {
                noMore = true;
                showMessage(getString(R.string.reco_no_more), TastyToast.INFO);
                // M158：有内容时不再弹出居中提示块——提示块会压在正在播放的视频画面中央，
                // 与 onBatchFailed（M100）确立的「有内容只用 Toast、不遮画面」原则对齐。
                // 恢复入口保持可用：第一页下拉「换一批」/筛选变更/重进推荐页都会重置 noMore。
            }
            return;
        }

        emptyRetryCount = 0;
        // T01（方案 §5-T01 顺序 4）：缓存与网络按 VideoKey 去重——
        // 网络同 key 只更新权威元数据（不重绑定、不重启播放器），新 key 才追加；
        // 全部重复视为成功空操作，不进空批重试、不清空已到内容。
        if (com.m3man.perf.PerfFlags.cacheFirst) {
            int before = list.size();
            list = dedupAgainstDisplayed(list);
            if (list.isEmpty() && before > 0) {
                return;
            }
        }
        boolean wasEmpty = adapter.getItemCount() == 0;
        adapter.appendData(list);
        networkVisible = true;
        // L-fix：成功出批后保存本批到本地缓存（保留最近一屏），供下次冷启动秒显
        if (com.m3man.perf.PerfFlags.cacheFirst) {
            // T01：主线程只做标量拷贝构造 DTO，JSON 编码落盘在缓存执行器（合并旧写）
            saveBatchToCacheV2(list);
        } else {
            saveBatchToCache(list);
        }
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
        batchDisposable = null;
        globalLoading.setVisibility(View.GONE);
        if (swipeRefresh != null) {
            swipeRefresh.setRefreshing(false);
        }
        // M98/M158：召回 error 走这里——不计入连续空批计数、不置 noMore（避免瞬时失败被永久限流）；
        // 下次滑动会自动重试加载更多（M158：原错误标记已随遮罩入口一并移除）。
        String reason = throwable == null ? "unknown" : AppLog.cause(throwable);
        Logger.t(TAG).d("load batch failed: " + reason);
        AppLog.e(TAG, "推荐列表加载失败: " + reason);
        if (adapter.getItemCount() == 0) {
            showEmpty("加载失败，点击重试");
        } else {
            // M100：已有内容时不再弹出全屏重试遮罩——视频正在播放，遮罩会挡住画面，
            // 改用 Toast 提示用户，下次滑动会自动重试加载更多
            showMessage(getString(R.string.reco_load_failed_swipe_retry), TastyToast.WARNING);
        }
    }

    private void showEmpty(String message) {
        emptyText.setText(message);
        emptyLayout.setVisibility(View.VISIBLE);
        if (resetFilterButton != null) {
            resetFilterButton.setVisibility(View.GONE);
        }
        if (adjustFilterButton != null) {
            adjustFilterButton.setVisibility(View.GONE);
        }
    }

    /** M119：方向/时长筛选是否处于非默认状态 */
    private boolean isFilterActive() {
        return (context != null && PlayUiPrefs.getOrientationFilter(context) != PlayUiPrefs.FILTER_ALL)
                || dataManager.getRecoMaxDurationMinutes() > 0;
    }

    /** M119/EX-03：一键恢复全部筛选（方向=全部、时长=不限），保留连播选择，只 reset 一次。 */
    private void resetAllFilters() {
        applyFilters(PlayUiPrefs.FILTER_ALL, 0, PlayUiPrefs.isRecoAutoNextEnabled(context));
    }

    // ==================== 翻页 / 播放 ====================

    private void onPageSelected(int position) {
        if (position < 0 || position >= adapter.getItemCount() || position == currentPosition) {
            return;
        }
        // 离开上一页时结算隐式反馈（v3 P0-1：秒划负反馈 + 分级中段 + 防重复加权）
        settleImplicit(currentPosition);
        // M119：离开上一页时记录断点续播位置（仅 >3 分钟视频）
        captureResumePosition(currentPosition);
        currentPosition = position;
        // v3 P0-1：刷新当前页进入时刻（dwell 结算基准）
        currentEnterElapsed = SystemClock.elapsedRealtime();
        // 横屏锁只在手动退出时解除；翻到新视频不自动回竖屏。
        RecoCandidate candidate = adapter.getItem(position);
        if (candidate != null && engine != null) {
            engine.markSeen(candidate.viewKey());
            orientationHelper.applyAutoRotation(candidate);
        }
        startPlay(position);

        if (engine != null && prefetcher != null) {
            RecoParams params = engine.getParams();
            if (com.m3man.perf.PerfFlags.prefetchScheduler) {
                // T04：窗口调度——当前页+后续 ahead 条；出窗后台解析取消，队列不累计
                prefetcher.updateWindow(adapter.getData(), position, params.prefetchAhead);
            } else {
                prefetcher.prefetch(adapter.getData(), position + 1, params.prefetchAhead);
            }
        }

        if (position >= adapter.getItemCount() - LOAD_MORE_THRESHOLD) {
            loadMore(false);
        }
        persistAsync(false);
        // M119：首次进入（第一页）弹一次新手操作引导
        if (position == 0) {
            showGuideIfNeeded();
        }
    }

    /**
     * v3 P0-1：结算一页的隐式反馈。采集维度从「当前播放位置」升级为：
     * 是否起播 / 最高到达进度 / 累计有效播放时长 / 停留时长——
     * 起播前划走（旧版完全不学习的最大负信号）现在会记秒划负反馈，
     * seek 到片尾骗完播、循环播放反复加分都由引擎侧封顶与规则挡住。
     */
    private void settleImplicit(int position) {
        if (position < 0 || adapter == null || engine == null) {
            return;
        }
        RecoCandidate candidate = adapter.getItem(position);
        if (candidate == null) {
            return;
        }
        RecommendFeedAdapter.PageHolder holder = findHolder(position);
        if (holder == null || holder.errorNoted) {
            // 解析/起播失败离开不学习（与内容无关）
            return;
        }
        long dwellMs = currentEnterElapsed > 0L
                ? SystemClock.elapsedRealtime() - currentEnterElapsed : 0L;
        engine.onImplicitSettle(candidate, holder.playbackStarted,
                holder.maxRatio, holder.playedMs, dwellMs);
    }

    private void startPlay(final int position) {
        final RecoCandidate candidate = adapter.getItem(position);
        if (candidate == null || prefetcher == null) {
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
        // v3 P0-1：每次（重）起播重置隐式反馈采集；首帧兜底刷新进入时刻
        holder.resetImplicitStats();
        if (currentEnterElapsed <= 0L) {
            currentEnterElapsed = SystemClock.elapsedRealtime();
        }

        // 关掉其它页的循环续播，防止旧页在播完瞬间抢回解码器造成「画面 / 标题」错位
        disableLoopExcept(position);
        JZVideoPlayer.releaseAllVideos();
        stopCoverWatcher();
        stopProgressTicker();
        holder.loading.setVisibility(View.VISIBLE);
        holder.errorContainer.setVisibility(View.GONE);
        holder.seekBubble.setVisibility(View.GONE);
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
                if (engine != null) {
                    engine.attachAuthor(candidate, item);
                }
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
            // M118：播完续播交给 Fragment 决定（自动连播开 → 翻下一条；否则原地循环）
            holder.player.setOnAutoCompletionListener(new RecoVideoPlayer.OnAutoCompletionListener() {
                @Override
                public void onAutoCompletion(RecoVideoPlayer player) {
                    onFeedVideoCompleted(player);
                }
            });
            holder.player.setUp(playUrl, JZVideoPlayerStandard.SCREEN_WINDOW_LIST, title);
            holder.player.startVideo();
            // v3 P0-1：真正发起过起播（区别于「起播前就划走」的秒划负反馈）
            holder.playbackStarted = true;
        } catch (Exception e) {
            Logger.t(TAG).d("start video failed: " + e.getMessage());
            AppLog.e(TAG, "推荐起播失败 viewKey=" + candidate.viewKey() + " " + AppLog.cause(e));
            showPlayError(position, null);
            return;
        }
        // 标题以「真正被起播的这条」为准，杜绝画面与文案对不上
        holder.title.setText(title);
        holder.progressContainer.setVisibility(View.VISIBLE);
        // M119：显示记忆倍速（真正应用在起播成功后的 applyPlaybackPrefs 里做）
        holder.speed.setText(speedLabel(PlayUiPrefs.getRecoPlaybackSpeed(context)));
        holder.player.setPlaybackSpeed(1.0f);
        // M119：断点续播挂起——起播成功（cover watcher）后 seek 到离开位置
        pendingResumeKey = candidateKey;
        Long savedResume = resumePositions.get(pendingResumeKey);
        pendingResumeMillis = savedResume == null ? -1L : savedResume;
        // M119：右栏"下载"标签按 DB 状态注水（已完成/下载中/百分比）
        hydrateDownloadState(candidate);
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
                    long target = duration * progress / PROGRESS_MAX;
                    String text = formatTime(target);
                    holder.curTime.setText(text);
                    // M112：拖动时在手指上方显示时间气泡，取代原来只有左侧小字变化、
                    // 手指位置毫无反馈的做法
                    if (holder.seekBubble != null) {
                        holder.seekBubble.setText(text);
                        holder.seekBubble.setVisibility(View.VISIBLE);
                    }
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
                if (holder.seekBubble != null) {
                    holder.seekBubble.setVisibility(View.GONE);
                }
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
                // M-06: Fragment 销毁后不再重新调度
                if (viewDestroyed || !isUsable() || position != currentPosition) {
                    progressTicker = null;
                    return;
                }
                RecommendFeedAdapter.PageHolder holder = findHolder(position);
                if (holder == null) {
                    progressTicker = null;
                    return;
                }
                // v3 P0-1：隐式反馈采集——仅真正在播时累计有效播放时长，
                // 并记录到达过的最高进度（结算用，防循环播放/seek 干扰）
                long nowElapsed = SystemClock.elapsedRealtime();
                if (holder.lastTickElapsed > 0L) {
                    if (holder.player.isPlaying()) {
                        holder.playedMs += Math.max(0L, nowElapsed - holder.lastTickElapsed);
                    }
                }
                holder.lastTickElapsed = nowElapsed;
                float ratio = holder.player.watchedRatio();
                if (ratio > holder.maxRatio) {
                    holder.maxRatio = ratio;
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
        // v3 P0-1：失败离开不学习（与内容质量无关）
        holder.errorNoted = true;
        stopCoverWatcher();
        stopProgressTicker();
        holder.loading.setVisibility(View.GONE);
        holder.player.setVisibility(View.INVISIBLE);
        holder.progressContainer.setVisibility(View.GONE);
        holder.cover.setVisibility(View.VISIBLE);
        // M112：显示「文案 + 重试按钮」容器。
        // 以前是在文案末尾拼「，点击重试」让用户去点整块文字——没有按钮外观，
        // 绝大多数用户不知道这里可以点，只能滑走。
        holder.errorContainer.setVisibility(View.VISIBLE);
        holder.error.setText(TextUtils.isEmpty(message)
                ? getString(R.string.reco_parse_failed)
                : message);
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
                    // M119：起播成功——应用记忆倍速/静音与断点续播位置
                    applyPlaybackPrefs(holder);
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
        orientationHelper.setLandscapeLock(true);
        orientationHelper.enterLandscapeFullscreen();
    }

    @Override
    public void onSpeedClick(int position) {
        if (position != currentPosition) {
            return;
        }
        showSpeedMenu(position);
    }

    /** M119：倍速单选菜单（0.75~2x），选择持久化，后续视频沿用 */
    private void showSpeedMenu(final int position) {
        final float current = PlayUiPrefs.getRecoPlaybackSpeed(context);
        final String[] items = {"0.75x", "1x", "1.25x", "1.5x", "2x"};
        int checkedIndex = 1;
        for (int i = 0; i < RECO_SPEED_VALUES.length; i++) {
            if (RECO_SPEED_VALUES[i] == current) {
                checkedIndex = i;
                break;
            }
        }
        new androidx.appcompat.app.AlertDialog.Builder(getActivity(), R.style.RecoOrientationDialogTheme)
                .setTitle("播放倍速")
                .setSingleChoiceItems(items, checkedIndex, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        dialog.dismiss();
                        float picked = RECO_SPEED_VALUES[which];
                        if (picked == current) {
                            return;
                        }
                        PlayUiPrefs.setRecoPlaybackSpeed(context, picked);
                        RecommendFeedAdapter.PageHolder holder = findHolder(currentPosition);
                        if (holder != null && holder.player.setPlaybackSpeed(picked)) {
                            holder.speed.setText(speedLabel(picked));
                            showMessage("已切换 " + speedLabel(picked) + "，将记住选择", TastyToast.INFO);
                        } else {
                            // 暂停态/引擎不支持时改不了当前通道，但记忆已保存，起播时生效
                            showMessage("已记住 " + speedLabel(picked) + "，下次起播生效", TastyToast.INFO);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String speedLabel(float value) {
        return value == (int) value ? (int) value + "x" : value + "x";
    }

    @Override
    public void onLikeClick(int position) {
        RecoCandidate candidate = adapter.getItem(position);
        if (candidate == null || engine == null) {
            return;
        }
        boolean liked = engine.toggleLike(candidate);
        adapter.refreshActionState(recyclerView, position);
        showMessage(getString(liked ? R.string.reco_liked : R.string.reco_unliked),
                TastyToast.SUCCESS);
        // M119：点赞时心形爆流动效
        if (liked) {
            playLikeBurst(position);
        }
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
            if (engine == null) {
                return;
            }
            if (engine.actionOf(candidate.viewKey()) == RecoStore.ACTION_LIKE) {
                return;
            }
            engine.toggleLike(candidate);
            adapter.refreshActionState(recyclerView, position);
            showMessage(getString(R.string.reco_liked), TastyToast.SUCCESS);
            // M119：双击点赞同款心形爆流动效
            playLikeBurst(position);
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
        if (candidate == null || candidate.item == null || engine == null) {
            return;
        }
        final String key = candidate.viewKey();
        if (!favoriteInFlight.add(key)) {
            // 同项请求期间禁用重复操作（EX-05）
            showMessage(getString(R.string.reco_favorite_in_flight), TastyToast.INFO);
            return;
        }
        final boolean wasFavorited = isFavorited(candidate);
        final boolean targetFavorite = !wasFavorited;
        final V9MmanItem item = candidate.item;
        // 先写库，写入成功后才更新引擎学习与选中态；失败保持原状态并提示（可重试）。
        disposables.add(Observable.just(1)
                .map(i -> {
                    saveFavorite(item, targetFavorite);
                    return true;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ok -> {
                    favoriteInFlight.remove(key);
                    if (targetFavorite) {
                        engine.onFavorite(candidate);
                    } else {
                        engine.onUnfavorite(candidate);
                    }
                    adapter.refreshActionState(recyclerView, position);
                    showMessage(getString(targetFavorite
                            ? R.string.reco_favorited : R.string.reco_unfavorited),
                            TastyToast.SUCCESS);
                    persistAsync(true);
                }, throwable -> {
                    favoriteInFlight.remove(key);
                    // 失败不改变选中态，用户可重试；不提交任何学习信号
                    adapter.refreshActionState(recyclerView, position);
                    showMessage(getString(R.string.play_favorite_failed), TastyToast.ERROR);
                }));
    }

    @Override
    public void onDislikeClick(int position) {
        RecoCandidate candidate = adapter.getItem(position);
        if (candidate == null || engine == null) {
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
        if (candidate == null || candidate.item == null || getActivity() == null) {
            return;
        }
        goToPlayVideo(candidate.item, 0, position);
    }

    @Override
    public void onRetryClick(int position) {
        if (position == currentPosition) {
            startPlay(position);
        }
    }

    // ==================== M119：操作增强（动效/作者/静音/换一批/引导） ====================

    /** 点赞心形爆流动效 + 图标弹跳（右栏点赞与双击点赞共用） */
    private void playLikeBurst(final int position) {
        final RecommendFeedAdapter.PageHolder holder = findHolder(position);
        if (holder == null || holder.burstHeart == null) {
            return;
        }
        // 图标弹跳
        holder.like.animate().cancel();
        holder.like.setScaleX(1f);
        holder.like.setScaleY(1f);
        holder.like.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        holder.like.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    }
                }).start();
        // 心形爆流：小→大→放大消散
        holder.burstHeart.animate().cancel();
        holder.burstHeart.setVisibility(View.VISIBLE);
        holder.burstHeart.setAlpha(1f);
        holder.burstHeart.setScaleX(0.3f);
        holder.burstHeart.setScaleY(0.3f);
        holder.burstHeart.setRotation(-12f);
        holder.burstHeart.animate().scaleX(1.15f).scaleY(1.15f).setDuration(180)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        holder.burstHeart.animate().alpha(0f).scaleX(1.4f).scaleY(1.4f)
                                .setDuration(340)
                                .withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        holder.burstHeart.setVisibility(View.GONE);
                                        holder.burstHeart.setAlpha(1f);
                                        holder.burstHeart.setScaleX(0.3f);
                                        holder.burstHeart.setScaleY(0.3f);
                                    }
                                }).start();
                    }
                }).start();
    }

    @Override
    public void onAuthorClick(int position) {
        RecoCandidate candidate = adapter.getItem(position);
        if (candidate == null || candidate.item == null || getActivity() == null) {
            return;
        }
        // authorKey 由解析回填（attachAuthor），当前播放页一定已解析
        if (TextUtils.isEmpty(candidate.authorKey)) {
            showMessage("作者信息解析中，请稍后再试", TastyToast.INFO);
            return;
        }
        Intent intent = new Intent(getActivity(), AuthorActivity.class);
        intent.putExtra(Keys.KEY_INTENT_UID, candidate.authorKey);
        intent.putExtra(Keys.KEY_INTENT_SOURCE, candidate.item.getSource());
        String name = candidate.item.getAuthorText();
        if (TextUtils.isEmpty(name)) {
            name = candidate.authorName;
        }
        intent.putExtra(Keys.KEY_INTENT_AUTHOR_NAME, name);
        // 作者页 UID 过期 404 时可用本条作品自愈
        intent.putExtra(Keys.KEY_INTENT_AUTHOR_LAST_VIEW_KEY, candidate.viewKey());
        startActivity(intent);
    }

    @Override
    public void onMuteClick(int position) {
        boolean muted = !PlayUiPrefs.isRecoMuted(context);
        PlayUiPrefs.setRecoMuted(context, muted);
        adapter.refreshMuteIcons(recyclerView);
        RecommendFeedAdapter.PageHolder holder = findHolder(currentPosition);
        if (holder != null) {
            holder.player.setMuted(muted);
        }
        showMessage(muted ? "已静音" : "已取消静音", TastyToast.INFO);
    }

    @Override
    public void onPullToRefresh() {
        // M119：player 内累计位移方案已废弃（RecyclerView 会拦截 MOVE），
        // 换一批由 SwipeRefreshLayout.onRefresh → swapBatch() 驱动；此回调保留空实现以兼容接口。
    }

    /** M119：换一批——清空当前流、重置会话重新召回（第一页下拉触发）。
     * 用 loadMore(false) 避免全屏 loading 遮罩（SwipeRefreshLayout 自带转圈）。 */
    private void swapBatch() {
        if (repository == null) {
            if (swipeRefresh != null) {
                swipeRefresh.setRefreshing(false);
            }
            return;
        }
        cancelBatchRequest();
        JZVideoPlayer.releaseAllVideos();
        stopCoverWatcher();
        stopProgressTicker();
        adapter.setData(new ArrayList<RecoCandidate>());
        currentPosition = -1;
        noMore = false;
        emptyRetryCount = 0;
        emptyLayout.setVisibility(View.GONE);
        repository.resetSession();
        // T01：换一批 = 新会话；作废在途缓存读，复位缓存/网络可见状态
        viewGeneration++;
        cacheRendered = false;
        networkVisible = false;
        pendingAutoPlay = false;
        loadMore(false);
        showMessage("换一批", TastyToast.INFO);
    }

    private void showGuideIfNeeded() {
        if (guideView == null || context == null || getView() == null) {
            return;
        }
        if (PlayUiPrefs.isRecoGuideShown(context)) {
            return;
        }
        guideView.setVisibility(View.VISIBLE);
    }

    private void hideGuide() {
        if (guideView != null) {
            guideView.setVisibility(View.GONE);
        }
        if (context != null) {
            PlayUiPrefs.setRecoGuideShown(context, true);
        }
    }

    // ==================== M119：断点续播 / 倍速静音应用 ====================

    /** 离开页面/翻页时记录断点（仅 >3 分钟且看了一部分、未近尾声的视频） */
    private void captureResumePosition(int position) {
        if (position < 0 || adapter == null) {
            return;
        }
        RecoCandidate candidate = adapter.getItem(position);
        RecommendFeedAdapter.PageHolder holder = findHolder(position);
        if (candidate == null || TextUtils.isEmpty(candidate.viewKey()) || holder == null) {
            return;
        }
        long duration = holder.player.safeDuration();
        if (duration <= RESUME_MIN_DURATION_MS) {
            return;
        }
        long pos = holder.player.safePosition();
        if (pos > 30_000L && pos < duration - 5_000L) {
            resumePositions.put(candidate.viewKey(), pos);
        } else {
            resumePositions.remove(candidate.viewKey());
        }
    }

    /** 起播成功后应用记忆倍速/静音与断点续播位置（cover watcher 确认 PLAYING 后调用） */
    private void applyPlaybackPrefs(final RecommendFeedAdapter.PageHolder holder) {
        // 断点续播：从离开处继续（>30s 才值得续）
        if (pendingResumeKey != null && pendingResumeMillis > 30_000L
                && holder.player.seekToMillis(pendingResumeMillis)) {
            showMessage("已从 " + formatTime(pendingResumeMillis) + " 继续播放", TastyToast.INFO);
        }
        pendingResumeKey = null;
        pendingResumeMillis = -1L;
        // 倍速记忆
        float speed = PlayUiPrefs.getRecoPlaybackSpeed(context);
        if (speed != 1.0f) {
            if (holder.player.setPlaybackSpeed(speed)) {
                holder.speed.setText(speedLabel(speed));
            }
        } else {
            holder.speed.setText("1x");
        }
        // 静音记忆
        if (PlayUiPrefs.isRecoMuted(context)) {
            holder.player.setMuted(true);
        }
    }

    // ==================== M119：下载状态角标 ====================

    private final DownloadManager.DownloadStatusUpdater downloadUpdater =
            new DownloadManager.DownloadStatusUpdater() {
                @Override
                public void complete(BaseDownloadTask task) {
                    handleDownloadEvent(task, FileDownloadStatus.completed);
                }

                @Override
                public void update(BaseDownloadTask task) {
                    handleDownloadEvent(task, task == null ? FileDownloadStatus.progress
                            : task.getStatus());
                }
            };

    /** FileDownloader 回调线程不确定，统一抛回主线程处理 */
    private void handleDownloadEvent(final BaseDownloadTask task, final int status) {
        if (task == null || viewDestroyed) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!isUsable() || viewDestroyed) {
                    return;
                }
                String viewKey = downloadIdToViewKey.get(task.getId());
                if (viewKey == null) {
                    lookupDownloadIdAsync(task.getId());
                    return;
                }
                // 该版本 BaseDownloadTask 无 getProgress()，按字节换算百分比
                long soFar = task.getSmallFileSoFarBytes();
                long total = task.getSmallFileTotalBytes();
                int percent = total > 0 ? (int) (soFar * 100 / total) : 0;
                String label = resolveDownloadLabel(status, percent);
                downloadLabelStates.put(viewKey, label);
                applyDownloadLabel(viewKey, label);
            }
        });
    }

    /** 未知 downloadId → 异步查 DB 反查 viewKey（IO 线程） */
    private void lookupDownloadIdAsync(final int downloadId) {
        if (downloadId <= 0 || !downloadIdLookupInFlight.add(downloadId)) {
            return;
        }
        disposables.add(Observable.just(downloadId)
                .map(i -> dataManager.findV9MmanItemByDownloadId(i))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(item -> {
                    downloadIdLookupInFlight.remove(downloadId);
                    if (item != null && !TextUtils.isEmpty(item.getViewKey())) {
                        downloadIdToViewKey.put(downloadId, item.getViewKey());
                        applyDownloadState(item);
                    }
                }, throwable -> downloadIdLookupInFlight.remove(downloadId)));
    }

    /** 按 viewKey 注水下载状态（doStart 绑定当前页时调用；每 key 只查一次 DB） */
    private void hydrateDownloadState(final RecoCandidate candidate) {
        if (candidate == null || TextUtils.isEmpty(candidate.viewKey())) {
            return;
        }
        final String key = candidate.viewKey();
        String cached = downloadLabelStates.get(key);
        if (cached != null) {
            applyDownloadLabel(key, cached);
            return;
        }
        if (!hydratedDownloadKeys.add(key)) {
            return;
        }
        disposables.add(Observable.just(key)
                .map(k -> dataManager.findV9MmanItemByViewKey(k))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(item -> {
                    if (item != null && item.getDownloadId() > 0) {
                        downloadIdToViewKey.put(item.getDownloadId(), key);
                        applyDownloadState(item);
                    }
                }, throwable -> {
                }));
    }

    private void applyDownloadState(V9MmanItem item) {
        if (item == null || TextUtils.isEmpty(item.getViewKey())) {
            return;
        }
        String key = item.getViewKey();
        int status = item.getStatus();
        if (status == FileDownloadStatus.error) {
            downloadLabelStates.remove(key);
            applyDownloadLabel(key, "下载");
            return;
        }
        String label = resolveDownloadLabel(status, item.getProgress());
        downloadLabelStates.put(key, label);
        applyDownloadLabel(key, label);
    }

    private void applyDownloadLabel(String viewKey, String label) {
        if (adapter != null) {
            adapter.updateDownloadLabel(recyclerView, viewKey, label);
        }
    }

    private static String resolveDownloadLabel(int status, int progress) {
        if (status == FileDownloadStatus.completed) {
            return "已完成";
        }
        if (status == FileDownloadStatus.error) {
            return "失败";
        }
        if (progress > 0 && progress < 100) {
            return progress + "%";
        }
        if (status == FileDownloadStatus.started || status == FileDownloadStatus.connected
                || status == FileDownloadStatus.progress) {
            return "下载中";
        }
        return "排队中";
    }

    // ==================== 下载 ====================

    /** EX-05：成功入队后提供“查看”，导航到下载进行中列表。 */
    private void showDownloadQueuedSnackbar(String message) {
        if (!isUsable() || recyclerView == null || getActivity() == null) {
            return;
        }
        Snackbar.make(recyclerView, message, Snackbar.LENGTH_LONG)
                .setAction(getString(R.string.common_view), v ->
                        startActivity(new Intent(getActivity(), DownloadActivity.class)))
                .show();
    }

    @Override
    public void onDownloadClick(int position) {
        final RecoCandidate candidate = adapter.getItem(position);
        if (candidate == null || candidate.item == null
                || TextUtils.isEmpty(candidate.viewKey())) {
            return;
        }
        // M119：已下载完成的视频，点下载图标直接跳「我的下载」
        if ("已完成".equals(downloadLabelStates.get(candidate.viewKey()))
                && getActivity() != null) {
            startActivity(new Intent(getActivity(), DownloadActivity.class));
            return;
        }
        // 已经解析过（正在播的这条一定已解析）就直接入队
        if (!TextUtils.isEmpty(prefetcher.peekRawUrl(candidate.viewKey()))) {
            downloadEnqueuer.enqueueDownload(candidate.viewKey(), candidate.item,
                    new DownloadEnqueuer.Callback() {
                        @Override
                        public boolean isUsable() {
                            return RecommendFeedFragment.this.isUsable();
                        }

                        @Override
                        public void showMessage(String msg, int type) {
                            RecommendFeedFragment.this.showMessage(msg, type);
                        }

                        @Override
                        public void onEnqueued(String message) {
                            showDownloadQueuedSnackbar(message);
                        }

                        @Override
                        public void startDownloadService() {
                            if (getActivity() != null) {
                                try {
                                    getActivity().startService(new Intent(getActivity(),
                                            DownloadVideoService.class));
                                } catch (Exception e) {
                                    Logger.t(TAG).d("start download service failed: " + e.getMessage());
                                }
                            }
                        }
                    });
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
                downloadEnqueuer.enqueueDownload(candidate.viewKey(), candidate.item,
                        new DownloadEnqueuer.Callback() {
                            @Override
                            public boolean isUsable() {
                                return RecommendFeedFragment.this.isUsable();
                            }

                            @Override
                            public void showMessage(String msg, int type) {
                                RecommendFeedFragment.this.showMessage(msg, type);
                            }

                            @Override
                            public void onEnqueued(String message) {
                                showDownloadQueuedSnackbar(message);
                            }

                            @Override
                            public void startDownloadService() {
                                if (getActivity() != null) {
                                    try {
                                        getActivity().startService(new Intent(getActivity(),
                                                DownloadVideoService.class));
                                    } catch (Exception e) {
                                        Logger.t(TAG).d("start download service failed: " + e.getMessage());
                                    }
                                }
                            }
                        });
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

    private boolean isFavorited(RecoCandidate candidate) {
        if (engine != null && engine.actionOf(candidate.viewKey()) == RecoStore.ACTION_FAVORITE) {
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
        // T06（方案 §5-T06）：保存统一交进程级单写者协调器——
        // 普通请求 2s 合并、urgent 最多 500ms 后执行，窗口内请求安排尾沿补写（不再直接 return 丢请求）；
        // 保存订阅不再挂进 View 的 CompositeDisposable，onDestroyView clear 不再取消保存。
        // 退出页面只发请求，不阻塞主线程等待；交互发生时已在 store 内标脏。
        if (engine == null || context == null) {
            return;
        }
        com.m3man.data.reco.RecoPersistenceCoordinator
                .get(context.getApplicationContext())
                .requestSave(engine.getStore(), "fragment:" + (force ? "urgent" : "normal"), force);
    }

    // ==================== 生命周期 ====================

    @Override
    public void onResume() {
        super.onResume();
        if (repository != null && context != null) {
            repository.setAutoRotateLandscape(PlayUiPrefs.isAutoRotateLandscape(context));
            repository.setOrientationFilter(PlayUiPrefs.getOrientationFilter(context));
            updateFilterEntry();
            // 推荐时长筛选变化检测：用户在设置页改完，回到这里若发现值不同则重置会话重拉，
            // 否则出队过的候选会保留，UI 与新阈值不一致。
            int persistedMax = dataManager.getRecoMaxDurationMinutes();
            if (persistedMax != lastAppliedRecoDurationMinutes) {
                repository.setMaxDurationMinutes(persistedMax);
                lastAppliedRecoDurationMinutes = persistedMax;
                // M118：设置页改的值，入口文案与当前流同步刷新
                updateFilterEntry();
                if (engineInitFailed) {
                    // 引擎初始化失败导致 repository 没建出来——忽略，下一次 resume 再补偿
                } else if (repository != null && adapter != null) {
                    cancelBatchRequest();
                    repository.resetSession();
                    // 避免重置把正在播放的 page 状态搞乱，先彻底清空再重新拉首批
                    adapter.setData(new java.util.ArrayList<RecoCandidate>());
                    noMore = false;
                    emptyRetryCount = 0;
                    emptyLayout.setVisibility(View.GONE);
                    globalLoading.setVisibility(View.VISIBLE);
                    // T01：时长筛选变化 = 新会话；作废在途缓存读，复位缓存/网络可见状态
                    viewGeneration++;
                    cacheRendered = false;
                    networkVisible = false;
                    pendingAutoPlay = false;
                    loadMore(true);
                }
            }
        }
        if (currentPosition >= 0 && adapter != null) {
            orientationHelper.applyAutoRotation(adapter.getItem(currentPosition));
        }
        // 首次初始化可能早于 onResume 完成；此处补偿一次被生命周期时序跳过的首批请求。
        if (!firstLoadStarted && repository != null && adapter != null
                && adapter.getItemCount() == 0) {
            loadMore(true);
        }
        // onPause/onHiddenChanged 已经释放底层播放器，回到页面时只恢复当前候选。
        resumeCurrentPlaybackIfNeeded();
    }

    @Override
    public void onPause() {
        // 不使用 goOnPlayOnPause：它只暂停并保留 MediaPlayer，切换页面后仍可能有音频输出。
        // M98：先结算隐式反馈再释放播放器——release 后进度读不到，反馈会丢失。
        settleImplicit(currentPosition);
        // M119：切走前记录断点续播位置（>3 分钟视频）
        captureResumePosition(currentPosition);
        stopPlaybackForLeavingPage();
        persistAsync(true);
        super.onPause();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        // hide/show 切换（切到其它 Tab）时必须释放底层播放器，不能只暂停。
        if (hidden) {
            // M98：先结算隐式反馈再释放播放器（同 onPause，release 后进度读不到）
            settleImplicit(currentPosition);
            // M119：切走前记录断点续播位置（>3 分钟视频）
            captureResumePosition(currentPosition);
            stopPlaybackForLeavingPage();
            persistAsync(true);
            // T04：隐藏页面停止后台预取（解析/封面/字节预热），回前台由翻页重新开窗
            if (prefetcher != null) {
                prefetcher.cancelBackground();
            }
            // 切到其它 Tab：恢复 App 默认紫色状态栏（推荐流内才透明）
            orientationHelper.restoreAppStatusBar();
        } else if (isResumed()) {
            if (currentPosition >= 0 && adapter != null) {
                orientationHelper.applyAutoRotation(adapter.getItem(currentPosition));
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
        viewDestroyed = true;
        handler.removeCallbacksAndMessages(null);
        // v3 P0-1：隐式反馈已在 onPause/onHiddenChanged 结算过，这里不再重复
        // （每视频封顶也会把重复结算压到 0.15 倍，但能省则省）
        // T06：退出页面只发请求，交进程级单写者协调器保存——
        // 不再新建保存订阅后立刻 disposables.clear()（旧写法可把保存取消掉，尾沿丢失）。
        // 也不依赖 onDestroy 必达；进程级 writer 在 appContext 上执行。
        final RecoEngine target = engine;
        if (target != null && context != null) {
            com.m3man.data.reco.RecoPersistenceCoordinator
                    .get(context.getApplicationContext())
                    .requestSave(target.getStore(), "onDestroyView", true);
        }
        if (!disposables.isDisposed()) {
            disposables.clear();
        }
        if (prefetcher != null) {
            prefetcher.release();
        }
        // M119：注销下载状态回调
        DownloadManager.getImpl().removeUpdater(downloadUpdater);
        JZVideoPlayer.releaseAllVideos();
        // Fragment 销毁（离开推荐流）：恢复 App 默认紫色状态栏
        orientationHelper.restoreAppStatusBar();
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
        if (orientationHelper != null && orientationHelper.isLandscapeLock()) {
            orientationHelper.enterLandscapeFullscreen();
        }
        // 横竖屏切换后重新计算筛选入口位置（横屏避开左上角返回箭头）
        applyTopBarInsets();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("reco_landscape_lock",
                orientationHelper != null && orientationHelper.isLandscapeLock());
    }

    // ==================== LandscapeOrientationHelper.Host ====================

    @Override
    public RecommendFeedAdapter getAdapter() {
        return adapter;
    }

    @Override
    public RecyclerView getRecyclerView() {
        return recyclerView;
    }

    @Override
    public View getLandscapeBackView() {
        return landscapeBackView;
    }

    private int normalContentBottomMargin = -1;

    @Override
    public int getNormalContentBottomMargin() {
        return normalContentBottomMargin;
    }

    @Override
    public void setNormalContentBottomMargin(int value) {
        normalContentBottomMargin = value;
    }
}
