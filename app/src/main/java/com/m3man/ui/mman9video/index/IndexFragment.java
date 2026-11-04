package com.m3man.ui.mman9video.index;


import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.google.android.material.snackbar.Snackbar;
import com.helper.loadviewhelper.help.OnLoadViewListener;
import com.helper.loadviewhelper.load.LoadViewHelper;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.R;
import com.m3man.adapter.V91MmanAdapter;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.MvpFragment;
import com.m3man.ui.download.DownloadActivity;
import com.m3man.ui.main.MainActivity;
import com.m3man.ui.mman9video.videolist.VideoCardActions;
import com.m3man.utils.AdapterDiffUtil;
import com.m3man.utils.AppUtils;
import com.m3man.utils.LoadHelperUtils;
import com.m3man.widget.VerticalSpaceItemDecoration;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;


/**
 * A simple {@link Fragment} subclass.
 *
 * @author flymegoc
 */
public class IndexFragment extends MvpFragment<IndexView, IndexPresenter> implements IndexView, SwipeRefreshLayout.OnRefreshListener {


    private static final String TAG = IndexFragment.class.getSimpleName();
    @BindView(R.id.recyclerView_index)
    RecyclerView recyclerView;
    Unbinder unbinder;
    @BindView(R.id.contentView)
    SwipeRefreshLayout contentView;

    private V91MmanAdapter mV91MmanAdapter;

    private LoadViewHelper helper;

    @Inject
    protected IndexPresenter indexPresenter;
    @Inject
    protected DataManager dataManager;
    private int position;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable delayedLoadingRunnable;
    private Runnable slowHintRunnable;
    private Runnable hideLoadingRunnable;
    private boolean loadingViewVisible;
    private long loadingShownElapsed;

    public IndexFragment() {
        // Required empty public constructor
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public static IndexFragment getInstance() {
        return new IndexFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mV91MmanAdapter = new V91MmanAdapter(R.layout.item_v_9mman);
        mV91MmanAdapter.setDataManager(dataManager);
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
    public IndexPresenter createPresenter() {
        return indexPresenter;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        super.onCreateView(inflater, container, savedInstanceState);
        return inflater.inflate(R.layout.fragment_index, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        unbinder = ButterKnife.bind(this, view);
        // Setup contentView == SwipeRefreshView

        contentView.setOnRefreshListener(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setHasFixedSize(true);
        int horizontal = getResources().getDimensionPixelSize(R.dimen.video_list_horizontal_padding);
        recyclerView.setPadding(horizontal, horizontal, horizontal,
                getResources().getDimensionPixelSize(R.dimen.space_3));
        recyclerView.setClipToPadding(false);
        recyclerView.addItemDecoration(new VerticalSpaceItemDecoration(
                getResources().getDimensionPixelSize(R.dimen.video_card_spacing)));
        recyclerView.setAdapter(mV91MmanAdapter);

        mV91MmanAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                V9MmanItem v9MmanItems = (V9MmanItem) adapter.getData().get(position);
                View coverView = view.findViewById(R.id.iv_91mman_item_img);
                goToPlayVideo(v9MmanItems, 0, position, coverView);
            }
        });
        // EX-02：卡片“更多”与视频列表共用真实收藏/下载动作
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
                        Snackbar.make(recyclerView, message, Snackbar.LENGTH_LONG)
                                .setAction(getString(R.string.common_view), v -> {
                                    if (getActivity() != null) {
                                        startActivity(new android.content.Intent(getActivity(), DownloadActivity.class));
                                    }
                                })
                                .show();
                    } else {
                        showMessage(message, success ? TastyToast.SUCCESS : TastyToast.INFO);
                    }
                });
            }
        });

        helper = new LoadViewHelper(recyclerView);
        helper.setListener(new OnLoadViewListener() {
            @Override
            public void onRetryClick() {
                loadData(false, true);
            }
        });
        AppUtils.setColorSchemeColors(context, contentView);
    }

    @Override
    protected void onLazyLoadOnce() {
        loadData(false, false);
    }

    @Override
    public void onRefresh() {
        loadData(true, true);
    }

    @Override
    public void setData(List<V9MmanItem> data) {
        AdapterDiffUtil.apply(mV91MmanAdapter, data, AdapterDiffUtil.v9MmanItem());
        ((LinearLayoutManager)recyclerView.getLayoutManager()).scrollToPositionWithOffset(position,0);
    }

    @Override
    public void showLoading(boolean pullToRefresh) {
        contentView.setEnabled(false);
        cancelLoadingTimers();
        if (pullToRefresh) {
            return;
        }
        // 300ms 内完成不闪骨架（spec §3）
        delayedLoadingRunnable = () -> {
            if (!isAdded()) {
                return;
            }
            helper.showLoading();
            LoadHelperUtils.setLoadingText(helper.getLoadIng(), R.id.tv_loading_text, "拼命加载中...");
            loadingViewVisible = true;
            loadingShownElapsed = SystemClock.elapsedRealtime();
        };
        uiHandler.postDelayed(delayedLoadingRunnable, 300L);
        slowHintRunnable = () -> {
            if (isAdded()) {
                LoadHelperUtils.setLoadingText(helper.getLoadIng(), R.id.tv_loading_text,
                        getString(R.string.state_load_slow));
            }
        };
        uiHandler.postDelayed(slowHintRunnable, 8000L);
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
        if (hideLoadingRunnable != null) {
            uiHandler.removeCallbacks(hideLoadingRunnable);
            hideLoadingRunnable = null;
        }
    }

    @Override
    public void loadData(boolean pullToRefresh, boolean cleanCache) {
        presenter.loadIndexData(pullToRefresh, cleanCache);
    }

    @Override
    public void showContent() {
        cancelLoadingTimers();
        contentView.setEnabled(true);
        contentView.setRefreshing(false);
        if (loadingViewVisible) {
            long remain = Math.max(0L, 300L - (SystemClock.elapsedRealtime() - loadingShownElapsed));
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

    private void showEmptyStateIfNeeded() {
        if (!isAdded() || mV91MmanAdapter.getItemCount() > 0) {
            return;
        }
        helper.showEmpty();
        Button action = helper.getLoadEmpty() == null ? null
                : helper.getLoadEmpty().findViewById(R.id.load_empty_id_btn);
        if (action != null) {
            action.setVisibility(View.VISIBLE);
            action.setText(R.string.state_go_recommend);
            action.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).switchToTab(1);
                }
            });
        }
    }

    @Override
    public void showMessage(String msg, int type) {
        super.showMessage(msg, type);
    }


    @Override
    public void showError(String message) {
        cancelLoadingTimers();
        loadingViewVisible = false;
        contentView.setRefreshing(false);
        // M73：恢复刷新使能——showLoading 里禁用了 contentView，
        // 错误分支不恢复会导致首次失败后下拉刷新永久失效
        contentView.setEnabled(true);
        if (mV91MmanAdapter.getItemCount() > 0) {
            // 有内容时刷新失败：保留列表与滚动位置，只给轻量重试
            Snackbar.make(recyclerView, getString(R.string.state_refresh_failed_keep_content),
                    Snackbar.LENGTH_LONG)
                    .setAction(getString(R.string.state_retry), v -> loadData(true, true))
                    .show();
            return;
        }
        helper.showError();
        LoadHelperUtils.setErrorText(helper.getLoadError(), R.id.tv_error_text,
                getString(R.string.state_connect_failed_desc));
        wireErrorRetryButton();
    }

    private void wireErrorRetryButton() {
        if (helper.getLoadError() == null) {
            return;
        }
        Button retry = helper.getLoadError().findViewById(R.id.btn_error_retry);
        if (retry != null) {
            retry.setVisibility(View.VISIBLE);
            retry.setOnClickListener(v -> loadData(false, true));
        }
    }

    @Override
    public void onDestroyView() {
        cancelLoadingTimers();
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroyView();
        unbinder.unbind();
    }

    @Override
    public String getTitle() {
        return category.getCategoryName();
    }
}
