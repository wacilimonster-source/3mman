package com.m3man.ui.mman9video.search;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.helper.loadviewhelper.help.OnLoadViewListener;
import com.helper.loadviewhelper.load.LoadViewHelper;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.R;
import com.m3man.adapter.SkipPageAdapter;
import com.m3man.adapter.V91MmanAdapter;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.DataManager;
import com.m3man.ui.MvpActivity;
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

/**
 * 分分钟（91porny）搜索界面
 *
 * 功能：
 *  1. 关键词搜索
 *  2. 工具栏右上角筛选按钮（弹窗选择 排序 / 发布时间 / 播放量）
 *  3. 搜索结果支持跳转到原生播放页（与「视频」tab 走同一播放流）
 */
public class SearchPornyActivity extends MvpActivity<SearchView, SearchPornyPresenter> implements SearchView {

    private static final String TAG = SearchPornyActivity.class.getSimpleName();
    @BindView(R.id.toolbar)
    Toolbar toolbar;
    @BindView(R.id.search_view)
    android.support.v7.widget.SearchView searchView;
    @BindView(R.id.nice_spinner_search)
    NiceSpinner niceSpinnerSearch;
    @BindView(R.id.nice_spinner_sort_by)
    NiceSpinner niceSpinnerSortBy;
    @BindView(R.id.recyclerView)
    RecyclerView recyclerView;
    // M43：底部页码跳转栏（参考视频分类交互）
    @BindView(R.id.fl_skip_page)
    FrameLayout skipPageLayout;
    @BindView(R.id.recyclerView_skip_page)
    RecyclerView skipPageRecyclerView;
    @BindView(R.id.ll_skip_page_loading)
    LinearLayout skipLoadingLayout;
    private String searchId;
    private V91MmanAdapter mV91MmanAdapter;
    private SearchHistoryPanel searchHistoryPanel;
    private LoadViewHelper helper;
    private SkipPageAdapter skipPageAdapter;

    /** 排序：默认 / 最新 / 最热 */
    private String currentSort = "";
    /** 发布时间 */
    private String currentTime = "";
    /** 播放量 */
    private String currentViews = "";

    @Inject
    protected SearchPornyPresenter searchPornyPresenter;
    @Inject
    protected DataManager dataManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        ButterKnife.bind(this);
        init();
        setListener();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // M42：从视频详情页返回时收起搜索框并清除焦点，避免输入法自动弹出
        searchView.onActionViewCollapsed();
        searchView.clearFocus();
    }

    private void init() {
        initToolBar(toolbar);
        // M100：恢复上次持久化的搜索筛选项，与 SearchPornyFragment 使用同一组 SP 键与语义
        restoreSearchFilters();
        searchView.setQueryHint("搜索视频");
        // M42：搜索框默认收起（点击放大镜才展开），避免进入页面/从详情页返回时自动弹出输入法
        searchView.setIconified(true);

        // 91porny 仅单一搜索模式，隐藏类型/排序下拉（改用工具栏筛选弹窗）
        niceSpinnerSearch.setVisibility(View.GONE);
        niceSpinnerSortBy.setVisibility(View.GONE);

        mV91MmanAdapter = new V91MmanAdapter(R.layout.item_v_9mman);
        mV91MmanAdapter.openLoadAnimation();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(mV91MmanAdapter);

        mV91MmanAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                V9MmanItem v9MmanItem = (V9MmanItem) adapter.getData().get(position);
                // 91porny 视频为 m3u8 HLS 流，只有 ExoPlayer 支持；JiaoZi/MediaPlayer 无法播放，强制使用 ExoPlayer
                goToPlayVideo(v9MmanItem, PlaybackEngine.DEFAULT_PLAYER_ENGINE);
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

        // M43：底部页码跳转栏（参考视频分类的翻页交互，直接展示在底部）
        initSkipPageBar();

        // 搜索历史面板（搜索框默认收起，进入即展示已有历史）
        ViewGroup historyContainer = findViewById(R.id.layout_search_history);
        searchHistoryPanel = new SearchHistoryPanel(historyContainer, dataManager,
                new SearchHistoryPanel.OnHistoryItemClickListener() {
                    @Override
                    public void onHistoryItemClick(String keyword) {
                        if (TextUtils.isEmpty(keyword)) {
                            return;
                        }
                        searchId = keyword;
                        searchView.setQuery(keyword, false);
                        triggerSearch(true);
                        searchHistoryPanel.hide();
                    }
                });
        searchHistoryPanel.show();
    }

    private void initSkipPageBar() {
        skipPageLayout.setVisibility(View.VISIBLE);
        skipPageRecyclerView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
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
            return;
        }
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
                // M100：滚动位置钳制到 [0, itemCount-1]，避免越界崩溃/无效滚动（对齐 SearchPornyFragment 同款写法）
                int targetPos = Math.max(0, Math.min(skipPageAdapter.getItemCount() - 1, currentPage + 2));
                skipPageRecyclerView.smoothScrollToPosition(targetPos);
            }
        }, 200);
    }

    private void setListener() {
        searchView.setOnQueryTextListener(new android.support.v7.widget.SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                if (!TextUtils.isEmpty(query)) {
                    searchHistoryPanel.onKeywordSubmitted(query);
                }
                if (query.equals(searchId)) {
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
        presenter.searchVideos(searchId, currentSort, currentTime, currentViews, pullToRefresh);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_porny_search, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_porny_filter) {
            showFilterDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
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
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.porny_filter_title)
                .setView(view)
                .setNeutralButton(getString(R.string.porny_filter_reset), (d, which) -> {
                    // M100：重置只还原弹窗内 spinner 选中项（局部变量），不立即持久化；
                    // 持久化与应用统一发生在「应用」点击，取消对话框不产生任何持久化副作用
                    sortSpinner.setSelectedIndex(0);
                    timeSpinner.setSelectedIndex(0);
                    viewsSpinner.setSelectedIndex(0);
                })
                .setPositiveButton(getString(R.string.porny_filter_apply), (d, which) -> {
                    currentSort = textOf(sortOptions, sortSpinner);
                    currentTime = textOf(timeOptions, timeSpinner);
                    currentViews = textOf(viewsOptions, viewsSpinner);
                    // M100：与 SearchPornyFragment 对齐——「应用」时本地保存，下次搜索自动套用
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

    /** M100：进入页面时恢复上次持久化的搜索筛选项（排序/发布时间/播放量），键与语义同 SearchPornyFragment */
    private void restoreSearchFilters() {
        currentSort = dataManager.getPornySearchSort();
        currentTime = dataManager.getPornySearchTime();
        currentViews = dataManager.getPornySearchViews();
    }

    /** M100：「应用」时将当前搜索筛选项持久化到本地（同一 SP 键），供下次搜索自动套用 */
    private void saveSearchFilters() {
        dataManager.setPornySearchSort(currentSort);
        dataManager.setPornySearchTime(currentTime);
        dataManager.setPornySearchViews(currentViews);
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
        recyclerView.smoothScrollToPosition(0);
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
            helper.showEmpty();
            LoadHelperUtils.setEmptyText(helper.getLoadEmpty(), R.id.tv_empty_info, "没有找到相关数据");
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
    }
}