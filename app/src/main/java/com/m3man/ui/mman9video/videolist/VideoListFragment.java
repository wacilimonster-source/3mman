package com.m3man.ui.mman9video.videolist;


import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.google.android.material.snackbar.Snackbar;
import com.helper.loadviewhelper.help.OnLoadViewListener;
import com.helper.loadviewhelper.load.LoadViewHelper;
import com.orhanobut.logger.Logger;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.R;
import com.m3man.adapter.SkipPageAdapter;
import com.m3man.adapter.V91MmanAdapter;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.MvpFragment;
import com.m3man.ui.download.DownloadActivity;
import com.m3man.ui.main.MainActivity;
import com.m3man.utils.AdapterDiffUtil;
import com.m3man.utils.AppUtils;
import com.m3man.utils.LoadHelperUtils;
import com.m3man.widget.VerticalSpaceItemDecoration;

import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;

/**
 * 通用视频列表加载。
 * <p>
 * 2026-11 体验精修（EX-02）：
 * 单列大卡片 + 18dp 行距；首次加载 300ms 防闪烁、8s 慢提示、15s 超时进入可重试；
 * 有内容时刷新失败只做轻量提示，不再整页报错；成功但 0 条使用分类空态 + 去推荐。
 */
public class VideoListFragment extends MvpFragment<VideoListView, VideoListPresenter> implements VideoListView, SwipeRefreshLayout.OnRefreshListener {

    private static final String TAG = VideoListFragment.class.getSimpleName();

    /** 加载指示延迟出现 / 最短展示（spec §3）。 */
    private static final long LOADING_DELAY_MS = 300L;
    private static final long LOADING_MIN_SHOW_MS = 300L;
    private static final long LOADING_SLOW_MS = 8000L;
    private static final long LOADING_TIMEOUT_MS = 15000L;

    @BindView(R.id.recyclerView_common)
    RecyclerView recyclerView;
    Unbinder unbinder;
    @BindView(R.id.contentView)
    SwipeRefreshLayout contentView;

    @BindView(R.id.recyclerView_skip_page)
    RecyclerView skipPageRecyclerView;

    @BindView(R.id.ll_skip_page_loading)
    LinearLayout skipLoadingLayout;

    @BindView(R.id.fl_skip_page)
    FrameLayout skipPageLayout;

    private V91MmanAdapter mV91MmanAdapter;

    private LoadViewHelper helper;

    @Inject
    protected VideoListPresenter videoListPresenter;

    @Inject
    protected DataManager dataManager;

    private SkipPageAdapter skipPageAdapter;

    private int skipPage = 0;
    private int position;
    /** M165：是否已完成首次数据定位（之后刷新/跳页回顶部）。 */
    private boolean firstDataSet = true;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable delayedLoadingRunnable;
    private Runnable slowHintRunnable;
    private Runnable timeoutRunnable;
    private Runnable hideLoadingRunnable;
    private long loadingShownElapsed = 0L;
    private boolean loadingViewVisible = false;
    private boolean loadInProgress = false;

    public VideoListFragment() {
        // Required empty public constructor
    }

    public void setSkipPage(int skipPage) {
        this.skipPage = skipPage;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public static VideoListFragment getInstance() {
        return new VideoListFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.t(TAG).d(category.getCategoryName() + "  VideoListFragment init..............");
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mV91MmanAdapter = new V91MmanAdapter(R.layout.item_v_9mman);
        mV91MmanAdapter.setDataManager(dataManager);
        skipPageAdapter = new SkipPageAdapter(R.layout.item_skip_page);
    }

    @Override
    public void onResume() {
        super.onResume();
        // 播放页返回后共享元素会清空封面 drawable，延迟到返回动画结束后重新加载。
        if (mV91MmanAdapter != null && getView() != null) {
            getView().postDelayed(() -> {
                if (isAdded() && mV91MmanAdapter != null) {
                    mV91MmanAdapter.reloadCovers(recyclerView);
                }
            }, 600);
        }
    }

    @NonNull
    @Override
    public VideoListPresenter createPresenter() {
        return videoListPresenter;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        super.onCreateView(inflater, container, savedInstanceState);
        return inflater.inflate(R.layout.fragment_video_list, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        unbinder = ButterKnife.bind(this, view);
        mV91MmanAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                V9MmanItem v9MmanItems = (V9MmanItem) adapter.getItem(position);
                View coverView = view.findViewById(R.id.iv_91mman_item_img);
                // 传入真实点击位置用于详情页内关联列表的滚动定位（之前用 position%20 会导致定位到错误位置）
                goToPlayVideo(v9MmanItems, presenter.getPage(), position, coverView);
            }
        });
        mV91MmanAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                presenter.loadVideoListData(false, false, category.getCategoryValue(), 0);
            }
        });
        mV91MmanAdapter.setOnCardActionListener(new V91MmanAdapter.OnCardActionListener() {
            @Override
            public void onFavoriteAction(V9MmanItem item) {
                VideoCardActions.toggleFavorite(dataManager, item, (message, success, openDownloads) -> {
                    if (!isAdded()) {
                        return;
                    }
                    showMessage(message, success ? TastyToast.SUCCESS : TastyToast.ERROR);
                    int index = mV91MmanAdapter.getData().indexOf(item);
                    if (success && index >= 0) {
                        mV91MmanAdapter.notifyItemChanged(index);
                    }
                });
            }

            @Override
            public void onDownloadAction(V9MmanItem item) {
                VideoCardActions.download(getContext(), dataManager, item, (message, success, openDownloads) -> {
                    if (!isAdded()) {
                        return;
                    }
                    if (openDownloads) {
                        showDownloadSnackbar(message);
                    } else {
                        showMessage(message, success ? TastyToast.SUCCESS : TastyToast.INFO);
                    }
                });
            }
        });
        skipPageAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                int page = (int) adapter.getItem(position);
                loadData(false, false, page);
            }
        });
        // Setup contentView == SwipeRefreshView
        contentView.setOnRefreshListener(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setHasFixedSize(true);
        // 单列大卡片：水平 12dp（spec §3），行距 18dp 由 ItemDecoration 提供。
        int horizontal = getResources().getDimensionPixelSize(R.dimen.video_list_horizontal_padding);
        int vertical = getResources().getDimensionPixelSize(R.dimen.video_card_spacing);
        recyclerView.setPadding(horizontal, horizontal, horizontal,
                getResources().getDimensionPixelSize(R.dimen.space_3));
        recyclerView.setClipToPadding(false);
        recyclerView.addItemDecoration(new VerticalSpaceItemDecoration(vertical));
        recyclerView.setAdapter(mV91MmanAdapter);

        helper = new LoadViewHelper(recyclerView);
        helper.setListener(new OnLoadViewListener() {
            @Override
            public void onRetryClick() {
                loadData(false, true, 0);
            }
        });
        //loadData(false);
        AppUtils.setColorSchemeColors(context, contentView);

        handlerSkipPage();
    }

    private void handlerSkipPage() {
        // 视频地址跳页功能固定展示，不再受设置项控制。
        skipPageLayout.setVisibility(View.VISIBLE);
        skipPageRecyclerView.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
        skipPageRecyclerView.setHasFixedSize(true);
        skipPageRecyclerView.setAdapter(skipPageAdapter);
    }

    @Override
    protected void onLazyLoadOnce() {
        Logger.t(TAG).d(category.getCategoryName() + "  初次加载数据......");
        loadData(false, false, skipPage);
    }

    @Override
    public void onDestroyView() {
        cancelLoadingTimers();
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroyView();
        unbinder.unbind();
    }

    @Override
    public void setData(List<V9MmanItem> data) {
        Logger.t(TAG).d(category.getCategoryName() + "  加载数据成功......");
        AdapterDiffUtil.apply(mV91MmanAdapter, data, AdapterDiffUtil.v9MmanItem());
        mV91MmanAdapter.disableLoadMoreIfNotFullPage(recyclerView);
        // M165：仅首次进入按来源列表位置定位；刷新/跳页回顶部，避免跳到旧条目位置。
        if (firstDataSet) {
            firstDataSet = false;
            ((LinearLayoutManager) recyclerView.getLayoutManager()).scrollToPositionWithOffset(position, 0);
        } else {
            recyclerView.scrollToPosition(0);
        }
        // M165：空态时页码条被隐藏，重新拿到数据后必须恢复，否则永久消失。
        if (data != null && !data.isEmpty()) {
            skipPageLayout.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void setPageData(List<Integer> pageData) {
        skipPageAdapter.setNewData(pageData);
    }

    @Override
    public void updateCurrentPage(final int currentPage) {
        Logger.t(TAG).d(category.getCategoryName() + "第《" + currentPage + "》页");
        skipPageAdapter.setCurrentPage(currentPage);
        skipPageRecyclerView.postDelayed(new Runnable() {
            @Override
            public void run() {
                //异步，可能点击太快会导致视图已经销毁了
                if (skipPageRecyclerView == null) {
                    return;
                }
                int target = Math.max(0, Math.min(skipPageAdapter.getItemCount() - 1, currentPage - 1));
                skipPageRecyclerView.smoothScrollToPosition(target);
            }
        }, 200);
    }

    @Override
    public void showSkipPageLoading() {
        skipLoadingLayout.setVisibility(View.VISIBLE);

    }

    @Override
    public void hideSkipPageLoading() {
        skipLoadingLayout.setVisibility(View.GONE);
    }

    @Override
    public void showLoading(boolean pullToRefresh) {
        contentView.setEnabled(false);
        loadInProgress = true;
        cancelLoadingTimers();
        // 下拉刷新由 SwipeRefreshLayout 自己表示进行中，不再叠骨架。
        if (pullToRefresh) {
            return;
        }
        delayedLoadingRunnable = () -> {
            if (!isAdded() || !loadInProgress) {
                return;
            }
            helper.showLoading();
            LoadHelperUtils.setLoadingText(helper.getLoadIng(), R.id.tv_loading_text, "拼命加载中...");
            loadingViewVisible = true;
            loadingShownElapsed = SystemClock.elapsedRealtime();
        };
        uiHandler.postDelayed(delayedLoadingRunnable, LOADING_DELAY_MS);

        slowHintRunnable = () -> {
            if (isAdded() && loadInProgress) {
                LoadHelperUtils.setLoadingText(helper.getLoadIng(), R.id.tv_loading_text,
                        getString(R.string.state_load_slow));
            }
        };
        uiHandler.postDelayed(slowHintRunnable, LOADING_SLOW_MS);

        timeoutRunnable = () -> {
            if (!isAdded() || !loadInProgress) {
                return;
            }
            // 15s：取消本次请求，进入可重试状态；迟到的响应由 generation 拦截。
            presenter.cancelCurrentRequest();
            loadInProgress = false;
            showError(getString(R.string.state_connect_failed));
        };
        uiHandler.postDelayed(timeoutRunnable, LOADING_TIMEOUT_MS);
    }

    @Override
    public void loadData(boolean pullToRefresh, boolean cleanCache, int skipPage) {
        presenter.loadVideoListData(pullToRefresh, cleanCache, category.getCategoryValue(), skipPage);
    }

    @Override
    public void onRefresh() {
        loadData(true, true, 0);
    }

    @Override
    public void showContent() {
        loadInProgress = false;
        cancelLoadingTimers();
        contentView.setEnabled(true);
        contentView.setRefreshing(false);
        if (loadingViewVisible) {
            long elapsed = SystemClock.elapsedRealtime() - loadingShownElapsed;
            long remain = Math.max(0L, LOADING_MIN_SHOW_MS - elapsed);
            hideLoadingRunnable = () -> {
                loadingViewVisible = false;
                if (isAdded()) {
                    helper.showContent();
                    showEmptyStateIfNeeded();
                }
            };
            uiHandler.postDelayed(hideLoadingRunnable, remain);
        } else {
            helper.showContent();
            showEmptyStateIfNeeded();
        }
    }

    /** 成功但 0 条：分类专用空文案 + “去推荐看看”，不显示分页条。 */
    private void showEmptyStateIfNeeded() {
        if (!isAdded() || mV91MmanAdapter.getItemCount() > 0) {
            return;
        }
        helper.showEmpty();
        TextView emptyText = helper.getLoadEmpty() == null ? null
                : helper.getLoadEmpty().findViewById(R.id.tv_empty_info);
        if (emptyText != null) {
            emptyText.setText(getString(R.string.state_empty_category));
        }
        Button action = helper.getLoadEmpty() == null ? null
                : helper.getLoadEmpty().findViewById(R.id.load_empty_id_btn);
        if (action != null) {
            action.setVisibility(View.VISIBLE);
            action.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).switchToTab(1);
                }
            });
        }
        skipPageLayout.setVisibility(View.GONE);
    }

    @Override
    public void showMessage(String msg, int type) {
        super.showMessage(msg, type);
    }

    @Override
    public void showError(String message) {
        loadInProgress = false;
        cancelLoadingTimers();
        contentView.setRefreshing(false);
        // M73：恢复刷新使能——showLoading 里禁用了 contentView，错误分支不恢复会导致刷新永久失效
        contentView.setEnabled(true);
        if (mV91MmanAdapter.getItemCount() > 0) {
            // 有内容再刷新失败：保留原内容与滚动位置，只给轻量恢复入口（AC-02.3）
            Snackbar.make(recyclerView, getString(R.string.state_refresh_failed_keep_content),
                    Snackbar.LENGTH_LONG)
                    .setAction(getString(R.string.state_retry), v -> loadData(true, true, 0))
                    .show();
            return;
        }
        helper.showError();
        if (!TextUtils.isEmpty(message)) {
            Logger.t(TAG).d("load failed: " + message);
        }
        LoadHelperUtils.setErrorText(helper.getLoadError(), R.id.tv_error_text,
                getString(R.string.state_connect_failed_desc));
        wireErrorRetryButton();
    }

    private void wireErrorRetryButton() {
        if (helper.getLoadError() == null) {
            return;
        }
        Button retry = helper.getLoadError().findViewById(R.id.btn_error_retry);
        if (retry == null) {
            return;
        }
        retry.setVisibility(View.VISIBLE);
        retry.setOnClickListener(v -> loadData(false, true, 0));
    }

    private void showDownloadSnackbar(String message) {
        Snackbar.make(recyclerView, message, Snackbar.LENGTH_LONG)
                .setAction(getString(R.string.common_view), v -> {
                    if (getActivity() != null) {
                        startActivity(new android.content.Intent(getActivity(), DownloadActivity.class));
                    }
                })
                .show();
    }

    private void cancelLoadingTimers() {
        if (delayedLoadingRunnable != null) {
            uiHandler.removeCallbacks(delayedLoadingRunnable);
            delayedLoadingRunnable = null;
        }
        if (slowHintRunnable != null) {
            uiHandler.removeCallbacks(slowHintRunnable);
            slowHintRunnable = null;
        }
        if (timeoutRunnable != null) {
            uiHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
        if (hideLoadingRunnable != null) {
            uiHandler.removeCallbacks(hideLoadingRunnable);
            hideLoadingRunnable = null;
        }
    }

    @Override
    public void loadMoreDataComplete() {
        mV91MmanAdapter.loadMoreComplete();
    }

    @Override
    public void loadMoreFailed() {
        // 原内容保持，底部显示加载失败并允许重试当前失败页（不跳页、不重复追加）
        showMessage(getString(R.string.common_load_more_failed), TastyToast.ERROR);
        mV91MmanAdapter.loadMoreFail();
    }

    @Override
    public void noMoreData() {
        mV91MmanAdapter.loadMoreEnd(true);
    }

    @Override
    public void setMoreData(List<V9MmanItem> v9MmanItemList) {
        mV91MmanAdapter.addData(v9MmanItemList);
    }

    @Override
    public String getTitle() {
        return category.getCategoryName();
    }
}
