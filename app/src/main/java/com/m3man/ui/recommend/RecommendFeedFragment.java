package com.m3man.ui.recommend;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
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

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liulishuo.filedownloader.BaseDownloadTask;
import com.liulishuo.filedownloader.model.FileDownloadStatus;
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
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.Action;
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
    /** 首批请求是否已经真正提交；避免首次生命周期回调被跳过后永久停在 loading。 */
    private boolean firstLoadStarted = false;
    private boolean engineInitFailed = false;
    private boolean engineInitStarted = false;
    private boolean noMore = false;
    private int emptyRetryCount = 0;
    /** M98：最近一次加载是否发生过错误（error 不计入空批计数，也不置 noMore，但保留重试入口） */
    private boolean lastLoadHadError = false;
    private long lastPersistTime = 0L;
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

    /** M78：方向筛选（0=全部 1=仅竖屏 2=仅横屏） */
    private TextView orientationFilterView;
    private ImageView landscapeBackView;

    /** M118：右上角时长筛选胶囊（≤N分钟，0=不限）与自动连播开关胶囊 */
    private TextView durationFilterView;
    private TextView autoNextView;

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

    /** M119：新手引导浮层与筛选空池恢复按钮 */
    private View guideView;
    private Button resetFilterButton;
    /** M119：第一页下拉换一批 */
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh;

    /** 横屏/沉浸模式 Helper（从 Fragment 提取，降低 God Class 复杂度） */
    private LandscapeOrientationHelper orientationHelper;
    /** 下载入队 Helper（从 Fragment 提取，降低 God Class 复杂度） */
    private DownloadEnqueuer downloadEnqueuer;

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
                        loadMore(true);
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
        // M118：右上角整体容器（方向 / 时长 / 连播三枚胶囊）统一做状态栏避让
        View topFilters = getView() == null ? null : getView().findViewById(R.id.ll_reco_top_filters);
        if (topFilters != null) {
            ViewGroup.LayoutParams raw = topFilters.getLayoutParams();
            if (raw instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
                lp.topMargin = statusBarHeight + dp(14);
                lp.topMargin = Math.max(lp.topMargin, dp(14));
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
                // M98：手动重试同时清除错误标记并收起提示层
                lastLoadHadError = false;
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

        initOrientationFilterPill();
        initDurationFilterPill();
        initAutoNextPill();
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
        new androidx.appcompat.app.AlertDialog.Builder(getActivity(), R.style.RecoOrientationDialogTheme)
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
        if (repository == null) {
            return;
        }
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

    // ==================== 时长筛选（M118） ====================

    /** 与设置页 SettingActivity 的选项保持一致：0=不限，其余为分钟上限 */
    private static final int[] RECO_DURATION_VALUES = {0, 1, 2, 3, 5, 10};

    private void initDurationFilterPill() {
        durationFilterView = getView().findViewById(R.id.tv_reco_duration_filter);
        updateDurationPill(dataManager.getRecoMaxDurationMinutes());
        durationFilterView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDurationFilterMenu();
            }
        });
    }

    private void updateDurationPill(int minutes) {
        if (durationFilterView == null) {
            return;
        }
        durationFilterView.setText(minutes <= 0
                ? getString(R.string.reco_duration_all)
                : "≤" + minutes + "分 ▾");
    }

    /** 右上角胶囊单选菜单：不限 / 1 / 2 / 3 / 5 / 10 分钟（与设置页选项一致） */
    private void showDurationFilterMenu() {
        final int current = dataManager.getRecoMaxDurationMinutes();
        final String[] items = {"不限", "1 分钟", "2 分钟", "3 分钟", "5 分钟", "10 分钟"};
        int checkedIndex = 0;
        for (int i = 0; i < RECO_DURATION_VALUES.length; i++) {
            if (RECO_DURATION_VALUES[i] == current) {
                checkedIndex = i;
                break;
            }
        }
        new androidx.appcompat.app.AlertDialog.Builder(getActivity(), R.style.RecoOrientationDialogTheme)
                .setTitle(getString(R.string.reco_duration_filter))
                .setSingleChoiceItems(items, checkedIndex, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        dialog.dismiss();
                        int picked = RECO_DURATION_VALUES[which];
                        if (picked != current) {
                            applyDurationFilter(picked);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 切换时长上限：写与设置页同一个持久化值（两处入口永远一致），严格过滤——
     * 清空当前流重拉，已出队的超时长候选不可逆丢弃。
     * lastApplied 同步更新，避免 onResume 把本页刚改的值误判成设置页改动造成二次重置。
     */
    private void applyDurationFilter(int minutes) {
        dataManager.setRecoMaxDurationMinutes(minutes);
        lastAppliedRecoDurationMinutes = minutes;
        updateDurationPill(minutes);
        if (repository == null) {
            return;
        }
        repository.setMaxDurationMinutes(minutes);
        // 严格过滤：清空当前流与已服务标记，从头按新时长上限拉取
        adapter.setData(new ArrayList<RecoCandidate>());
        currentPosition = -1;
        noMore = false;
        emptyRetryCount = 0;
        JZVideoPlayer.releaseAllVideos();
        stopCoverWatcher();
        stopProgressTicker();
        repository.resetSession();
        loadMore(true);
        showMessage(minutes <= 0 ? "已切换：不限" : "已切换：≤" + minutes + " 分钟以内",
                TastyToast.INFO);
    }

    // ==================== 自动连播（M118） ====================

    private void initAutoNextPill() {
        autoNextView = getView().findViewById(R.id.tv_reco_auto_next);
        updateAutoNextPill(PlayUiPrefs.isRecoAutoNextEnabled(context));
        autoNextView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean enabled = !PlayUiPrefs.isRecoAutoNextEnabled(context);
                PlayUiPrefs.setRecoAutoNextEnabled(context, enabled);
                updateAutoNextPill(enabled);
                showMessage(enabled ? "已开启自动连播，播完自动播放下一条"
                                : "已关闭自动连播，播完循环当前视频",
                        TastyToast.INFO);
            }
        });
    }

    private void updateAutoNextPill(boolean enabled) {
        if (autoNextView == null) {
            return;
        }
        autoNextView.setText(enabled ? R.string.reco_auto_next_on : R.string.reco_auto_next_off);
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
                // 完播是最强正反馈：AUTO_COMPLETE 态 watchedRatio() 恒 0，翻页前显式补记
                RecoCandidate finished = adapter.getItem(currentPosition);
                if (finished != null && engine != null) {
                    engine.onWatchRatio(finished, 1f);
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
     * L-fix：读取上次会话的本地缓存批次并立即上屏。条目点击后走既有播放解析流程。
     * 与后续网络批次的衔接：网络批次 append 在尾部，用户滑过缓存条目即进入新内容。
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

    private void loadMore(final boolean first) {
        if (loading || (noMore && !first) || repository == null) {
            return;
        }
        // L-fix：进入推荐先渲染上次会话的本地缓存批次（秒开，不转圈），随后照常拉取新批次追加在后
        if (first && PlayUiPrefs.isRecoPrefetchEnabled(
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
        disposables.add(repository.nextBatch(BATCH_SIZE)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(list -> onBatchLoaded(list, first),
                        throwable -> onBatchFailed(first, throwable)));
    }

    private void onBatchLoaded(List<RecoCandidate> list, boolean first) {
        loading = false;
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
                    showEmpty(getString(R.string.reco_empty_filtered));
                    resetFilterButton.setVisibility(View.VISIBLE);
                } else {
                    showEmpty(getString(R.string.reco_empty));
                }
            } else {
                noMore = true;
                showMessage(getString(R.string.reco_no_more), TastyToast.INFO);
                // M98：放宽重试按钮显示条件——noMore 且本次会话最近出现过加载错误时也显示，
                // 给用户手动恢复入口，避免把瞬时网络失败永久限流成「没有更多」
                if (lastLoadHadError) {
                    emptyText.setText(getString(R.string.reco_no_more));
                    emptyLayout.setVisibility(View.VISIBLE);
                }
            }
            return;
        }

        emptyRetryCount = 0;
        // M98：成功拿到真实内容才清除错误标记（空批不清，供 noMore 分支判断）
        lastLoadHadError = false;
        boolean wasEmpty = adapter.getItemCount() == 0;
        adapter.appendData(list);
        // L-fix：成功出批后保存本批到本地缓存（保留最近一屏），供下次冷启动秒显
        saveBatchToCache(list);
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
        if (swipeRefresh != null) {
            swipeRefresh.setRefreshing(false);
        }
        // M98：召回 error 走这里——不计入连续空批计数、不置 noMore（避免瞬时失败被永久限流），
        // 只记录错误标记供「noMore + 出错」时放宽重试按钮显示。
        lastLoadHadError = true;
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
    }

    /** M119：方向/时长筛选是否处于非默认状态 */
    private boolean isFilterActive() {
        return (context != null && PlayUiPrefs.getOrientationFilter(context) != PlayUiPrefs.FILTER_ALL)
                || dataManager.getRecoMaxDurationMinutes() > 0;
    }

    /** M119：一键恢复全部筛选（方向=全部、时长=不限），单次重置重拉 */
    private void resetAllFilters() {
        if (context != null) {
            PlayUiPrefs.setOrientationFilter(context, PlayUiPrefs.FILTER_ALL);
        }
        dataManager.setRecoMaxDurationMinutes(0);
        lastAppliedRecoDurationMinutes = 0;
        updateOrientationPill(PlayUiPrefs.FILTER_ALL);
        updateDurationPill(0);
        if (repository == null) {
            return;
        }
        repository.setOrientationFilter(PlayUiPrefs.FILTER_ALL);
        repository.setMaxDurationMinutes(0);
        repository.resetSession();
        adapter.setData(new ArrayList<RecoCandidate>());
        currentPosition = -1;
        noMore = false;
        emptyRetryCount = 0;
        lastLoadHadError = false;
        emptyLayout.setVisibility(View.GONE);
        globalLoading.setVisibility(View.VISIBLE);
        loadMore(true);
        showMessage("已恢复全部筛选", TastyToast.INFO);
    }

    // ==================== 翻页 / 播放 ====================

    private void onPageSelected(int position) {
        if (position < 0 || position >= adapter.getItemCount() || position == currentPosition) {
            return;
        }
        // 离开上一页时结算观看比例（隐式反馈）
        recordWatchRatio(currentPosition);
        // M119：离开上一页时记录断点续播位置（仅 >3 分钟视频）
        captureResumePosition(currentPosition);
        currentPosition = position;
        // 横屏锁只在手动退出时解除；翻到新视频不自动回竖屏。
        RecoCandidate candidate = adapter.getItem(position);
        if (candidate != null && engine != null) {
            engine.markSeen(candidate.viewKey());
            orientationHelper.applyAutoRotation(candidate);
        }
        startPlay(position);

        if (engine != null && prefetcher != null) {
            RecoParams params = engine.getParams();
            prefetcher.prefetch(adapter.getData(), position + 1, params.prefetchAhead);
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

    private void recordWatchRatio(int position) {
        if (position < 0 || adapter == null || engine == null) {
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
                }, throwable -> showMessage(getString(R.string.play_favorite_failed), TastyToast.ERROR)));
        persistAsync(true);
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
        // M119：详情改为半屏面板（复制链接/进播放页），不离开滑动上下文
        showDetailSheet(position);
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
        if (loading || repository == null) {
            if (swipeRefresh != null) {
                swipeRefresh.setRefreshing(false);
            }
            return;
        }
        JZVideoPlayer.releaseAllVideos();
        stopCoverWatcher();
        stopProgressTicker();
        adapter.setData(new ArrayList<RecoCandidate>());
        currentPosition = -1;
        noMore = false;
        emptyRetryCount = 0;
        lastLoadHadError = false;
        emptyLayout.setVisibility(View.GONE);
        repository.resetSession();
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

    // ==================== M119：详情半屏面板 ====================

    private void showDetailSheet(final int position) {
        final RecoCandidate candidate = adapter.getItem(position);
        if (candidate == null || candidate.item == null || getActivity() == null) {
            return;
        }
        final V9MmanItem item = candidate.item;
        View view = LayoutInflater.from(getActivity())
                .inflate(R.layout.dialog_reco_detail_bottom, null);
        TextView titleView = view.findViewById(R.id.tv_detail_sheet_title);
        TextView metaView = view.findViewById(R.id.tv_detail_sheet_meta);
        TextView linkView = view.findViewById(R.id.tv_detail_sheet_link);
        View copyLink = view.findViewById(R.id.bt_detail_copy_link);
        View openPlay = view.findViewById(R.id.bt_detail_open_play);

        titleView.setText(item.getTitle() == null ? "" : item.getTitle());
        metaView.setText(adapter.metaText(position));
        final String url = buildVideoPageUrl(item);
        if (TextUtils.isEmpty(url)) {
            copyLink.setVisibility(View.GONE);
            linkView.setVisibility(View.GONE);
        } else {
            linkView.setText(url);
        }
        final BottomSheetDialog dialog = new BottomSheetDialog(getActivity());
        dialog.setContentView(view);
        copyLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard(url);
                showMessage("链接已复制", TastyToast.SUCCESS);
                dialog.dismiss();
            }
        });
        openPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                goToPlayVideo(item, dataManager.getPlaybackEngine(), 0, position);
            }
        });
        dialog.show();
    }

    /** 由 viewKey + 用户配置的站点地址还原视频页链接（复制分享用）。
     * 91porn 的 viewKey 存储形如 "viewkey=xxx"（参数片段），勿重复加前缀。 */
    private String buildVideoPageUrl(V9MmanItem item) {
        String key = item.getViewKey();
        if (TextUtils.isEmpty(key)) {
            return null;
        }
        String source = item.getSource();
        if ("91porny".equals(source)) {
            String base = dataManager.getPornyAddress();
            if (TextUtils.isEmpty(base)) {
                base = "https://91porny.com/";
            }
            String id = key.startsWith("/video/view/") ? key.substring("/video/view/".length()) : key;
            return base.endsWith("/") ? base + "video/view/" + id
                    : base + "/video/view/" + id;
        }
        String base = dataManager.getMman9VideoAddress();
        if (TextUtils.isEmpty(base)) {
            base = "https://www.91porn.com/";
        }
        String param = key.startsWith("viewkey=") ? key : "viewkey=" + key;
        return base.endsWith("/") ? base + "view_video.php?" + param
                : base + "/view_video.php?" + param;
    }

    private void copyToClipboard(String text) {
        if (getActivity() == null || TextUtils.isEmpty(text)) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) getActivity()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("video", text));
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

    /** M98：force 请求的合并窗口——强制落盘也走合并调度，500ms 内的多次请求合并为一次 */
    private static final long PERSIST_FORCE_INTERVAL = 500L;

    private void persistAsync(boolean force) {
        long now = SystemClock.uptimeMillis();
        // M98：force 不再绕过节流直写。统一走合并调度：普通间隔 PERSIST_INTERVAL(2s)，
        // 强制间隔降到 PERSIST_FORCE_INTERVAL(500ms)。崩溃时最多丢最近 500ms 的
        // actions/seen 增量——这些属可再学习数据（重新观看即可重建反馈），可容忍。
        long window = force ? PERSIST_FORCE_INTERVAL : PERSIST_INTERVAL;
        if (now - lastPersistTime < window) {
            return;
        }
        lastPersistTime = now;
        disposables.add(Observable.just(1)
                .subscribeOn(Schedulers.io())
                .subscribe(i -> { if (engine != null) engine.persist(); },
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
            // 推荐时长筛选变化检测：用户在设置页改完，回到这里若发现值不同则重置会话重拉，
            // 否则出队过的候选会保留，UI 与新阈值不一致。
            int persistedMax = dataManager.getRecoMaxDurationMinutes();
            if (persistedMax != lastAppliedRecoDurationMinutes) {
                repository.setMaxDurationMinutes(persistedMax);
                lastAppliedRecoDurationMinutes = persistedMax;
                // M118：设置页改的值，右上角时长胶囊文案同步刷新
                updateDurationPill(persistedMax);
                if (engineInitFailed) {
                    // 引擎初始化失败导致 repository 没建出来——忽略，下一次 resume 再补偿
                } else if (repository != null && adapter != null) {
                    repository.resetSession();
                    // 避免重置把正在播放的 page 状态搞乱，先彻底清空再重新拉首批
                    adapter.setData(new java.util.ArrayList<RecoCandidate>());
                    noMore = false;
                    emptyRetryCount = 0;
                    lastLoadHadError = false;
                    emptyLayout.setVisibility(View.GONE);
                    globalLoading.setVisibility(View.VISIBLE);
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
        // M98：先结算观看比例再释放播放器——release 后 watchedRatio 恒为 0，隐式反馈会丢失。
        recordWatchRatio(currentPosition);
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
            // M98：先结算观看比例再释放播放器（同 onPause，release 后比例读不到）
            recordWatchRatio(currentPosition);
            // M119：切走前记录断点续播位置（>3 分钟视频）
            captureResumePosition(currentPosition);
            stopPlaybackForLeavingPage();
            persistAsync(true);
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
        recordWatchRatio(currentPosition);
        // View 已销毁，用 RxJava Completable 在 IO 线程持久化画像，避免裸线程
        final RecoEngine target = engine;
        if (target != null) {
            disposables.add(Completable.fromAction(new Action() {
                @Override
                public void run() throws Exception {
                    target.persist();
                }
            })
                    .subscribeOn(Schedulers.io())
                    .subscribe());
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
