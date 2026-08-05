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

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.helper.loadviewhelper.help.OnLoadViewListener;
import com.helper.loadviewhelper.load.LoadViewHelper;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.R;
import com.m3man.adapter.V91MmanAdapter;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.MvpActivity;
import com.m3man.utils.LoadHelperUtils;
import com.m3man.utils.PlaybackEngine;

import org.angmarch.views.NiceSpinner;

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
    private String searchId;
    private V91MmanAdapter mV91MmanAdapter;
    private LoadViewHelper helper;

    /** 排序：默认 / 最新 / 最热 */
    private String currentSort = "";
    /** 发布时间 */
    private String currentTime = "";
    /** 播放量 */
    private String currentViews = "";

    @Inject
    protected SearchPornyPresenter searchPornyPresenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        ButterKnife.bind(this);
        init();
        setListener();
    }

    private void init() {
        initToolBar(toolbar);
        searchView.setQueryHint("搜索 分分钟 视频");
        searchView.onActionViewExpanded();

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
    }

    private void setListener() {
        searchView.setOnQueryTextListener(new android.support.v7.widget.SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                if (query.equals(searchId)) {
                    return false;
                }
                searchId = query;
                triggerSearch(true);
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                return false;
            }
        });
        searchView.setOnCloseListener(new android.support.v7.widget.SearchView.OnCloseListener() {
            @Override
            public boolean onClose() {
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
                    sortSpinner.setSelectedIndex(0);
                    timeSpinner.setSelectedIndex(0);
                    viewsSpinner.setSelectedIndex(0);
                })
                .setPositiveButton(getString(R.string.porny_filter_apply), (d, which) -> {
                    currentSort = textOf(sortOptions, sortSpinner);
                    currentTime = textOf(timeOptions, timeSpinner);
                    currentViews = textOf(viewsOptions, viewsSpinner);
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
    }

    @Override
    public void setData(List<V9MmanItem> data) {
        mV91MmanAdapter.setNewData(data);
        recyclerView.smoothScrollToPosition(0);
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