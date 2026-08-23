package com.m3man.ui.recommend;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.PagerSnapHelper;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
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
import com.m3man.ui.BaseAppCompatActivity;
import com.m3man.utils.DownloadManager;
import com.orhanobut.logger.Logger;
import com.sdsmdg.tastytoast.TastyToast;

import java.io.File;
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
 * 推荐流（上下滑切换的沉浸式视频流）。
 * <p>
 * 结构：RecyclerView + PagerSnapHelper 做整页吸附，一屏一条视频。
 * 同一时刻只有「当前吸附页」持有解码器，切页时先释放再起播。
 * <ul>
 *   <li>召回 / 排序：{@link RecoRepository}</li>
 *   <li>解析 / 预热：{@link RecommendPrefetcher}</li>
 *   <li>画像 / 反馈：{@link RecoEngine}</li>
 * </ul>
 *
 * @author 3mman
 */
public class RecommendFeedActivity extends BaseAppCompatActivity
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recommend_feed);
        setStatusBarColor(Color.BLACK, 0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        engine = RecoEngine.get(this);
        repository = new RecoRepository(dataManager, engine);
        prefetcher = new RecommendPrefetcher(this, dataManager);

        initViews();
        loadMore(true);
    }

    /** 竖向流与播放器的横向手势冲突，这里关掉侧滑返回 */
    @Override
    public boolean isSupportSwipeBack() {
        return false;
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerView_recommend);
        globalLoading = findViewById(R.id.pb_recommend_loading);
        emptyLayout = findViewById(R.id.ll_recommend_empty);
        emptyText = findViewById(R.id.tv_recommend_empty);
        retryButton = findViewById(R.id.btn_recommend_retry);
        ImageView backView = findViewById(R.id.iv_recommend_back);
        ImageView tuneView = findViewById(R.id.iv_recommend_tune);

        layoutManager = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
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

        backView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });
        tuneView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTuneDialog();
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
        Logger.t(TAG).d("load batch failed: "
                + (throwable == null ? "unknown" : throwable.getMessage()));
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

        RecoCandidate candidate = adapter.getItem(position);
        if (candidate != null) {
            engine.markSeen(candidate.viewKey());
        }
        startPlay(position);

        RecoParams params = engine.getParams();
        prefetcher.prefetch(adapter.getData(), position + 1, params.prefetchAhead);

        if (position >= adapter.getItemCount() - LOAD_MORE_THRESHOLD) {
            loadMore(false);
        }
        persistAsync(false);
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
                    if (!isFinishing() && position == currentPosition) {
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
                if (isFinishing() || position != currentPosition) {
                    return;
                }
                // 解析回调是异步的，回来时必须确认还是同一条视频
                if (!TextUtils.isEmpty(viewKey) && !viewKey.equals(expectKey)) {
                    Logger.t(TAG).d("resolve key mismatch, drop: " + viewKey + " != " + expectKey);
                    return;
                }
                engine.attachAuthor(candidate, item);
                // M71：作者名/权威标题此刻已回填，立即刷新该页文案，
                // 消除"先显示 时长·添加时间、解析完滑回来才出现 @作者"的跳变
                RecommendFeedAdapter.PageHolder curHolder = findHolder(position);
                if (curHolder != null && !TextUtils.isEmpty(curHolder.boundKey)
                        && curHolder.boundKey.equals(expectKey)) {
                    curHolder.title.setText(candidate.title() == null ? "" : candidate.title());
                    adapter.refreshMeta(recyclerView, position);
                }
                doStart(position, candidate, playUrl);
            }

            @Override
            public void onFailed(String viewKey, String message) {
                if (isFinishing() || position != currentPosition) {
                    return;
                }
                showPlayError(position, message);
            }
        });
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
            showPlayError(position, null);
            return;
        }
        // 标题以「真正被起播的这条」为准，杜绝画面与文案对不上
        holder.title.setText(title);
        holder.progressContainer.setVisibility(View.VISIBLE);
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
                if (isFinishing() || position != currentPosition) {
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
                if (isFinishing() || position != currentPosition) {
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
    public void onDoubleTap(int position) {
        RecoCandidate candidate = adapter.getItem(position);
        if (candidate == null) {
            return;
        }
        // 双击只点赞，不取消赞（与短视频流的习惯一致）
        if (engine.actionOf(candidate.viewKey()) == RecoStore.ACTION_LIKE) {
            return;
        }
        engine.toggleLike(candidate);
        adapter.refreshActionState(recyclerView, position);
        showMessage(getString(R.string.reco_liked), TastyToast.SUCCESS);
        persistAsync(true);
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
        goToPlayVideo(candidate.item, dataManager.getPlaybackEngine());
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
                if (isFinishing()) {
                    return;
                }
                enqueueDownload(candidate);
            }

            @Override
            public void onFailed(String viewKey, String message) {
                if (isFinishing()) {
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
                    if (result.ok) {
                        // 拉起下载前台服务，保证退出页面后仍继续下载
                        try {
                            startService(new Intent(RecommendFeedActivity.this,
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
        V9MmanItem target = null;
        try {
            target = dataManager.findV9MmanItemByViewKey(viewKey);
        } catch (Exception ignored) {
        }
        if (target == null) {
            target = fallback;
        }
        if (target == null || target.getVideoResultId() == 0) {
            return new DownloadResult(false, "还未解析成功视频地址");
        }
        VideoResult videoResult;
        try {
            videoResult = target.getVideoResult();
        } catch (Exception e) {
            videoResult = null;
        }
        if (videoResult == null || TextUtils.isEmpty(videoResult.getVideoUrl())) {
            return new DownloadResult(false, "还未解析成功视频地址");
        }
        String path = target.getDownLoadPath(dataManager.getCustomDownloadVideoDirPath());
        File file = new File(path);
        if (file.exists() && file.length() > 0) {
            return new DownloadResult(false, "已经下载过了，请查看下载目录");
        }
        if (target.getStatus() == FileDownloadStatus.progress && target.getDownloadId() != 0) {
            return new DownloadResult(false, "已经在下载了");
        }
        // 91mman 视频分类源：直链带时效签名（st/f），预取/库里存的旧 URL 过期会被 CDN 拒绝
        // （表现为「正在下载」里报下载错误）。下载前重新解析播放页拿新鲜签名 URL。
        String url = videoResult.getVideoUrl();
        if (!com.m3man.ui.mman9video.play.PlayVideoPresenter.isPornySource(target)) {
            try {
                VideoResult fresh = dataManager.loadMman9VideoUrl(viewKey).blockingFirst(); // M62：直传完整 viewKey（契约要求带前缀）
                if (fresh != null && !TextUtils.isEmpty(fresh.getVideoUrl())) {
                    dataManager.saveVideoResult(fresh);
                    target.setVideoResult(fresh);
                    dataManager.updateV9MmanItem(target);
                    url = fresh.getVideoUrl();
                }
            } catch (Exception e) {
                Logger.t(TAG).d("recommend re-parse failed, fallback old url: " + e.getMessage());
            }
            // 直链 CDN 可能封锁当前网络（下载报错/0% 无速度）：探活被拒则改用 91porny 备用源
            if (!PornyFallbackResolver.isAlive(okHttpClient, url)) {
                try {
                    VideoResult porny = PornyFallbackResolver.resolve(dataManager, target.getTitle());
                    if (porny != null && !TextUtils.isEmpty(porny.getVideoUrl())) {
                        PornyFallbackResolver.applyPornyResult(dataManager, target, porny);
                        PornyFallbackResolver.enqueueHlsDownload(RecommendFeedActivity.this,
                                target, porny.getVideoUrl(), path);
                        return new DownloadResult(true, "源站受限，已改用分分钟源下载");
                    }
                } catch (Exception ignored) {
                }
                // 备用源未命中：仍用原直链尝试（可能探活误报）
            }
        }
        String referer = null;
        try {
            String addr = dataManager.getMman9VideoAddress();
            if (!TextUtils.isEmpty(addr)) {
                String cleanKey = viewKey != null && viewKey.startsWith("viewkey=")
                        ? viewKey.substring(8) : viewKey;
                referer = addr + "view_video.php?viewkey=" + cleanKey;
            }
        } catch (Exception ignored) {
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

    // ==================== 调参 ====================

    private void showTuneDialog() {
        new RecoSettingsDialog(this, engine, dataManager, new RecoSettingsDialog.OnParamsChangedListener() {
            @Override
            public void onParamsChanged(RecoParams params) {
                showMessage(getString(R.string.reco_param_saved), TastyToast.SUCCESS);
                // 参数变了，池子里的旧分数已失效，重排会话
                repository.resetSession();
                noMore = false;
            }

            @Override
            public void onMemoryCleared() {
                engine.resetMemory();
                repository.resetSession();
                noMore = false;
                showMessage(getString(R.string.reco_param_memory_cleared), TastyToast.SUCCESS);
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }
        }).show();
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
    protected void onResume() {
        super.onResume();
        JZVideoPlayer.goOnPlayOnResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        JZVideoPlayer.goOnPlayOnPause();
        recordWatchRatio(currentPosition);
        persistAsync(true);
    }

    @Override
    public void onBackPressed() {
        if (JZVideoPlayer.backPress()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        stopCoverWatcher();
        stopProgressTicker();
        handler.removeCallbacksAndMessages(null);
        recordWatchRatio(currentPosition);
        // Activity 已在销毁，Rx 订阅马上会被清掉，这里用裸线程保证画像一定落盘
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
        super.onDestroy();
    }
}
