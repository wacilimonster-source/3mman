package com.m3man.ui.mman9video.search;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.helper.loadviewhelper.help.OnLoadViewListener;
import com.helper.loadviewhelper.load.LoadViewHelper;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.R;
import com.m3man.adapter.SkipPageAdapter;
import com.m3man.adapter.V91MmanAdapter;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.DataManager;
import com.m3man.data.prefs.PreferencesHelper;
import com.m3man.ui.MvpFragment;
import com.m3man.ui.search.SearchHistoryPanel;
import com.m3man.utils.LoadHelperUtils;
import com.m3man.utils.PlaybackEngine;

import org.angmarch.views.NiceSpinner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;

/**
 * 分分钟（91porny）搜索页 —— 以 Fragment 形式内嵌在主界面「分分钟」Tab 下，
 * 点击 Tab 固定选中并直接展示本页（不再跳独立 Activity）。
 *
 * 功能：
 *  1. 关键词搜索
 *  2. 工具栏右上角筛选按钮（弹窗选择 排序 / 发布时间 / 播放量）
 *  3. 搜索结果支持跳转到原生播放页（与「视频」tab 走同一播放流）
 */
public class SearchPornyFragment extends MvpFragment<SearchView, SearchPornyPresenter> implements SearchView {

    private static final String TAG = SearchPornyFragment.class.getSimpleName();
    @BindView(R.id.toolbar)
    Toolbar toolbar;
    @BindView(R.id.search_view)
    android.support.v7.widget.SearchView searchView;
    @BindView(R.id.recyclerView)
    RecyclerView recyclerView;
    @BindView(R.id.layout_search_history)
    ViewGroup searchHistoryContainer;
    // 默认搜索提示（未搜索 / 无结果时居中展示）
    @BindView(R.id.tv_search_hint)
    TextView tvSearchHint;
    // M43：底部页码跳转栏（参考视频分类交互）
    @BindView(R.id.fl_skip_page)
    FrameLayout skipPageLayout;
    @BindView(R.id.recyclerView_skip_page)
    RecyclerView skipPageRecyclerView;
    @BindView(R.id.ll_skip_page_loading)
    LinearLayout skipLoadingLayout;
    Unbinder unbinder;
    private String searchId;
    private V91MmanAdapter mV91MmanAdapter;
    private LoadViewHelper helper;
    private SkipPageAdapter skipPageAdapter;
    private SearchHistoryPanel searchHistoryPanel;

    /** 排序：默认 / 最新 / 最热 */
    private String currentSort = "";
    /** 发布时间 */
    private String currentTime = "";
    /** 播放量 */
    private String currentViews = "";

    @Inject
    protected SearchPornyPresenter searchPornyPresenter;
    @Inject
    protected PreferencesHelper preferencesHelper;
    @Inject
    protected DataManager dataManager;

    public static SearchPornyFragment getInstance() {
        return new SearchPornyFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_search_porny, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        unbinder = ButterKnife.bind(this, view);
        init();
        setListener();
    }

    @Override
    public void onResume() {
        super.onResume();
        // M42：从视频详情页返回时收起搜索框并清除焦点，避免输入法自动弹出
        if (searchView != null) {
            searchView.onActionViewCollapsed();
            searchView.clearFocus();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unbinder.unbind();
    }

    private void init() {
        initToolBar(toolbar);
        // 恢复上次持久化的搜索筛选项，使下次搜索自动套用（无需重新打开筛选弹窗）
        restoreSearchFilters();
        searchView.setQueryHint("搜索视频");
        // M42：搜索框默认收起（点击放大镜才展开），避免进入页面/从详情页返回时自动弹出输入法
        searchView.setIconified(true);

        mV91MmanAdapter = new V91MmanAdapter(R.layout.item_v_9mman);
        mV91MmanAdapter.openLoadAnimation();
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        // RV 自身尺寸由父布局固定（match_parent），子项增删不会改其大小，
        // 标记为 fixed 可跳过无谓的整体布局请求，滚动更顺滑
        recyclerView.setAdapter(mV91MmanAdapter);

        mV91MmanAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                V9MmanItem v9MmanItem = (V9MmanItem) adapter.getData().get(position);
                // 91porny 视频为 m3u8 HLS 流，只有 ExoPlayer 支持；JiaoZi/MediaPlayer 无法播放，强制使用 ExoPlayer
                goToPlayVideo(v9MmanItem, PlaybackEngine.DEFAULT_PLAYER_ENGINE, 0, position);
            }
        });
        mV91MmanAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                triggerSearch(false);
            }
        }, recyclerView);

        helper = new LoadViewHelper(recyclerView);
        helper.setListener(new OnLoadViewListener() {
            @Override
            public void onRetryClick() {
                triggerSearch(false);
            }
        });

        // 进入页面默认展示搜索提示，避免一进来太空
        showSearchHint(R.string.search_hint_default);
        // 点击提示直接聚焦搜索框
        tvSearchHint.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchView.setIconified(false);
                searchView.requestFocus();
            }
        });
        // 放大搜索按钮（放大镜 / 提交箭头）的触发区域
        enlargeSearchTrigger();

        // M43：底部页码跳转栏（参考视频分类的翻页交互，直接展示在底部，有结果才显示）
        initSkipPageBar();

        // 搜索历史面板
        searchHistoryPanel = new SearchHistoryPanel(searchHistoryContainer, dataManager,
                new SearchHistoryPanel.OnHistoryItemClickListener() {
                    @Override
                    public void onHistoryItemClick(String keyword) {
                        if (TextUtils.isEmpty(keyword)) {
                            return;
                        }
                        searchId = keyword;
                        searchView.setQuery(keyword, false);
                        hideSearchHint();
                        triggerSearch(true);
                        searchHistoryPanel.hide();
                    }
                }, searchView);
        searchHistoryPanel.show();
    }

    private void initToolBar(Toolbar toolbar) {
        if (toolbar == null) {
            return;
        }
        toolbar.setTitle(R.string.title_porny);
        toolbar.setBackgroundColor(getResources().getColor(R.color.colorPrimary));
        toolbar.inflateMenu(R.menu.menu_porny_search);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_porny_filter) {
                showFilterDialog();
                return true;
            }
            return false;
        });
    }

    private void initSkipPageBar() {
        // 默认隐藏，等有搜索结果（updateSkipBar 拿到总页数）再显示，避免一进来底部空一条
        skipPageLayout.setVisibility(View.GONE);
        skipPageRecyclerView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        skipPageAdapter = new SkipPageAdapter(R.layout.item_skip_page);
        skipPageRecyclerView.setAdapter(skipPageAdapter);
        skipPageAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                Integer target = (Integer) adapter.getItem(position);
                if (target == null || TextUtils.isEmpty(searchId)) {
                    return;
                }
                if (target == presenter.getCurrentPage()) {
                    return;
                }
                skipLoadingLayout.setVisibility(View.VISIBLE);
                presenter.jumpToPage(target, searchId, currentSort, currentTime, currentViews);
            }
        });
    }

    /** M43：刷新底部页码栏（数据/总页数/当前页变化时调用） */
    private void updateSkipBar() {
        if (skipPageAdapter == null) {
            return;
        }
        int total = presenter.getTotalPage();
        if (total <= 0) {
            skipPageLayout.setVisibility(View.GONE);
            return;
        }
        skipPageLayout.setVisibility(View.VISIBLE);
        List<Integer> pages = new ArrayList<>(total);
        for (int i = 1; i <= total; i++) {
            pages.add(i);
        }
        skipPageAdapter.setNewData(pages);
        skipPageAdapter.setCurrentPage(presenter.getCurrentPage());
        skipLoadingLayout.setVisibility(View.GONE);
        // 与「视频分类」翻页交互保持一致：当前页保持在靠前可见位置（当前页 + 2）
        final int currentPage = presenter.getCurrentPage();
        skipPageRecyclerView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (skipPageRecyclerView == null) {
                    return;
                }
                int target = Math.max(0, Math.min(skipPageAdapter.getItemCount() - 1, currentPage - 1));
                skipPageRecyclerView.smoothScrollToPosition(target);
            }
        }, 200);
    }

    // ============ 搜索页 UX 辅助方法 ============

    /** 居中展示搜索 / 空结果提示 */
    private void showSearchHint(int msgRes) {
        if (tvSearchHint == null) {
            return;
        }
        tvSearchHint.setText(msgRes);
        tvSearchHint.setVisibility(View.VISIBLE);
    }

    private void hideSearchHint() {
        if (tvSearchHint != null) {
            tvSearchHint.setVisibility(View.GONE);
        }
    }

    /** 列表立即回到顶部（新搜索 / 换页结果回来后调用） */
    private void scrollListToTop() {
        if (recyclerView != null) {
            recyclerView.scrollToPosition(0);
        }
    }

    /** 放大 SearchView 内部「放大镜」与「提交箭头」的触摸区域，避免触发区域太小 */
    private void enlargeSearchTrigger() {
        // 启用提交按钮：输入文字后出现明确的「搜索」箭头，给一个更大的可点目标
        searchView.setSubmitButtonEnabled(true);
        searchView.post(new Runnable() {
            @Override
            public void run() {
                int extra = (int) (getResources().getDisplayMetrics().density * 12);
                View mag = findSearchViewInnerView("search_mag_icon");
                if (mag != null) {
                    mag.setPadding(mag.getPaddingLeft() + extra, mag.getPaddingTop() + extra,
                            mag.getPaddingRight() + extra, mag.getPaddingBottom() + extra);
                }
                View go = findSearchViewInnerView("search_go_btn");
                if (go != null) {
                    go.setPadding(go.getPaddingLeft() + extra, go.getPaddingTop() + extra,
                            go.getPaddingRight() + extra, go.getPaddingBottom() + extra);
                }
            }
        });
    }

    /** 按名称取 SearchView 内部子 View（appcompat 资源在打包时已并入本包，可直接 getIdentifier 命中） */
    private View findSearchViewInnerView(String name) {
        if (searchView == null || context == null) {
            return null;
        }
        int id = getResources().getIdentifier(name, "id", context.getPackageName());
        if (id == 0) {
            return null;
        }
        return searchView.findViewById(id);
    }

    private void setListener() {
        searchView.setOnQueryTextListener(new android.support.v7.widget.SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                if (!TextUtils.isEmpty(query)) {
                    searchHistoryPanel.onKeywordSubmitted(query);
                }
                if (TextUtils.equals(query, searchId)) {
                    searchHistoryPanel.hide();
                    return false;
                }
                searchId = query;
                triggerSearch(true);
                searchHistoryPanel.hide();
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (TextUtils.isEmpty(newText)) {
                    searchHistoryPanel.show();
                } else {
                    searchHistoryPanel.hide();
                }
                return false;
            }
        });
        searchView.setOnCloseListener(new android.support.v7.widget.SearchView.OnCloseListener() {
            @Override
            public boolean onClose() {
                searchHistoryPanel.show();
                return true;
            }
        });
    }

    private void triggerSearch(boolean pullToRefresh) {
        if (TextUtils.isEmpty(searchId)) {
            // 没有关键词时不发起请求，避免无意义的请求
            return;
        }
        if (pullToRefresh) {
            // 新关键词 / 重新筛选：先回到顶部并收起默认提示，避免滚动后搜新词还停在底部
            scrollListToTop();
            hideSearchHint();
            skipPageLayout.setVisibility(View.GONE);
        }
        presenter.searchVideos(searchId, currentSort, currentTime, currentViews, pullToRefresh);
    }

    private void showFilterDialog() {
        // 把字符串数组转成 List，供 NiceSpinner 使用
        final List<String> sortOptions = new LinkedList<>(Arrays.asList(
                getString(R.string.porny_sort_default),
                getString(R.string.porny_sort_latest),
                getString(R.string.porny_sort_hot)
        ));
        final List<String> timeOptions = new LinkedList<>(Arrays.asList(
                getResources().getStringArray(R.array.porny_time_filter)
        ));
        final List<String> viewsOptions = new LinkedList<>(Arrays.asList(
                getResources().getStringArray(R.array.porny_views_filter)
        ));

        final View view = getLayoutInflater().inflate(R.layout.layout_porny_filter_dialog, toolbar, false);
        final NiceSpinner sortSpinner = view.findViewById(R.id.spinner_porny_filter_sort);
        final NiceSpinner timeSpinner = view.findViewById(R.id.spinner_porny_filter_time);
        final NiceSpinner viewsSpinner = view.findViewById(R.id.spinner_porny_filter_views);
        sortSpinner.attachDataSource(sortOptions);
        timeSpinner.attachDataSource(timeOptions);
        viewsSpinner.attachDataSource(viewsOptions);

        // 还原当前选择
        sortSpinner.setSelectedIndex(indexOf(sortOptions, currentSort));
        timeSpinner.setSelectedIndex(indexOf(timeOptions, currentTime));
        viewsSpinner.setSelectedIndex(indexOf(viewsOptions, currentViews));

        final boolean[] applied = {false};
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.porny_filter_title)
                .setView(view)
                .setNeutralButton(getString(R.string.porny_filter_reset), (d, which) -> {
                    // M100：重置只还原弹窗内 spinner 选中项（局部变量），不再立即写 SP；
                    // 持久化与应用统一发生在「应用」点击，取消对话框不产生任何持久化副作用
                    sortSpinner.setSelectedIndex(0);
                    timeSpinner.setSelectedIndex(0);
                    viewsSpinner.setSelectedIndex(0);
                })
                .setPositiveButton(getString(R.string.porny_filter_apply), (d, which) -> {
                    currentSort = textOf(sortOptions, sortSpinner);
                    currentTime = textOf(timeOptions, timeSpinner);
                    currentViews = textOf(viewsOptions, viewsSpinner);
                    // 调整后本地保存，下次搜索自动套用
                    saveSearchFilters();
                    applied[0] = true;
                    d.dismiss();
                })
                .create();
        dialog.setOnDismissListener(d -> {
            if (applied[0]) {
                triggerSearch(true);
            }
        });
        dialog.show();
    }

    private static int indexOf(List<String> list, String value) {
        if (TextUtils.isEmpty(value)) {
            return 0;
        }
        int idx = list.indexOf(value);
        return idx >= 0 ? idx : 0;
    }

    private static String textOf(List<String> list, NiceSpinner spinner) {
        int index = spinner.getSelectedIndex();
        if (index >= 0 && index < list.size()) {
            return list.get(index);
        }
        return "";
    }

    /** 进入页面时恢复上次持久化的搜索筛选项（排序/发布时间/播放量），使下次搜索自动套用 */
    private void restoreSearchFilters() {
        if (preferencesHelper == null) {
            return;
        }
        currentSort = preferencesHelper.getPornySearchSort();
        currentTime = preferencesHelper.getPornySearchTime();
        currentViews = preferencesHelper.getPornySearchViews();
    }

    /** 将当前搜索筛选项持久化到本地，供下次搜索自动套用 */
    private void saveSearchFilters() {
        if (preferencesHelper == null) {
            return;
        }
        preferencesHelper.setPornySearchSort(currentSort);
        preferencesHelper.setPornySearchTime(currentTime);
        preferencesHelper.setPornySearchViews(currentViews);
    }

    @NonNull
    @Override
    public SearchPornyPresenter createPresenter() {
        return searchPornyPresenter;
    }

    @Override
    public void loadMoreDataComplete() {
        mV91MmanAdapter.loadMoreComplete();
    }

    @Override
    public void loadMoreFailed() {
        mV91MmanAdapter.loadMoreFail();
    }

    @Override
    public void noMoreData() {
        mV91MmanAdapter.loadMoreEnd(true);
    }

    @Override
    public void setMoreData(List<V9MmanItem> v9MmanItemList) {
        mV91MmanAdapter.addData(v9MmanItemList);
        updateSkipBar();
    }

    @Override
    public void setData(List<V9MmanItem> data) {
        mV91MmanAdapter.setNewData(data);
        // 新结果立即回到顶部（用 instant scroll，避免 smooth 在列表重建后被打断导致没回到顶）
        recyclerView.scrollToPosition(0);
        updateSkipBar();
    }

    @Override
    public void showLoading(boolean pullToRefresh) {
        helper.showLoading();
        LoadHelperUtils.setLoadingText(helper.getLoadIng(), R.id.tv_loading_text, "搜索中，请稍候...");
    }

    @Override
    public void showContent() {
        helper.showContent();
        if (mV91MmanAdapter.getData().size() == 0) {
            // 无结果：用居中提示代替空列表，避免一进来/换词后页面太空
            showSearchHint(R.string.search_empty_result);
            skipPageLayout.setVisibility(View.GONE);
        } else {
            hideSearchHint();
        }
    }

    @Override
    public void showMessage(String msg, int type) {
        super.showMessage(msg, type);
    }

    @Override
    public void showError(String message) {
        showMessage(message, TastyToast.ERROR);
        helper.showError();
        LoadHelperUtils.setErrorText(helper.getLoadError(), R.id.tv_error_text, "搜索失败了，点击重试");
        // L-fix：历史面板/hint 层叠在结果区之上，失败时仅 toast+loadError 用户无感
        // （表现为点了历史后毫无反应）。直接把顶层提示换成失败信息并收起面板，确保可见。
        if (!TextUtils.isEmpty(message)) {
            showSearchHintText("搜索失败了：" + message);
            if (searchHistoryPanel != null) {
                searchHistoryPanel.hide();
            }
        }
    }

    /** L-fix：以任意文本显示顶层提示层 */
    private void showSearchHintText(String text) {
        if (tvSearchHint == null) {
            return;
        }
        tvSearchHint.setText(text);
        tvSearchHint.setVisibility(View.VISIBLE);
    }
}
