package com.m3man.ui.mman9video.source441447;

import android.graphics.Typeface;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.tabs.TabLayout;
import com.m3man.R;
import com.m3man.adapter.SkipPageAdapter;
import com.m3man.adapter.V91MmanAdapter;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.network.Source441447Client;
import com.m3man.ui.BaseFragment;
import com.m3man.ui.mman9video.videolist.VideoCardActions;
import com.m3man.widget.VerticalSpaceItemDecoration;
import com.sdsmdg.tastytoast.TastyToast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/** Video categories and paginated list for the 441447 source. */
public class Source441447Fragment extends BaseFragment {
    private static final String STATE_CATEGORY = "source_category";
    private static final String STATE_SORT = "source_sort";
    private static final String PREFERENCES = "video_source_selection";
    /** M165：服务端 total 异常时不无限生成页码，防止卡死/OOM。 */
    private static final int MAX_PAGE_BAR_PAGES = 500;
    @Inject Source441447Client sourceClient;
    @Inject DataManager dataManager;

    private final CompositeDisposable disposables = new CompositeDisposable();
    private Disposable listRequest;
    private Disposable categoryRequest;
    private V91MmanAdapter adapter;
    private RecyclerView recycler;
    private SwipeRefreshLayout refresh;
    private TabLayout categories;
    private TextView status;
    private TextView latest;
    private TextView hot;
    private RecyclerView skipPageRecyclerView;
    private LinearLayout skipLoadingLayout;
    private View skipPageLayout;
    private SkipPageAdapter skipPageAdapter;
    private String categoryId = "";
    private String sort = "latest";
    private int page;
    private int totalPage = 1;
    private int generation;
    private boolean loading;
    private boolean rebuildingCategories;
    private SharedPreferences preferences;

    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        preferences = requireContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        categoryId = preferences.getString(STATE_CATEGORY, "");
        sort = preferences.getString(STATE_SORT, "latest");
        if (state != null) {
            categoryId = state.getString(STATE_CATEGORY, categoryId);
            sort = state.getString(STATE_SORT, sort);
        }
        if (!"hot".equals(sort)) sort = "latest";
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_source_441447, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        categories = view.findViewById(R.id.source_categories);
        latest = view.findViewById(R.id.source_sort_latest);
        hot = view.findViewById(R.id.source_sort_hot);
        status = view.findViewById(R.id.source_status);
        refresh = view.findViewById(R.id.source_refresh);
        recycler = view.findViewById(R.id.source_list);
        adapter = new V91MmanAdapter(R.layout.item_v_9mman);
        adapter.setDataManager(dataManager);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        int horizontal = getResources().getDimensionPixelSize(R.dimen.video_list_horizontal_padding);
        recycler.setPadding(horizontal, horizontal, horizontal, horizontal);
        recycler.addItemDecoration(new VerticalSpaceItemDecoration(
                getResources().getDimensionPixelSize(R.dimen.video_card_spacing)));
        recycler.setAdapter(adapter);
        skipPageLayout = view.findViewById(R.id.fl_skip_page);
        skipPageRecyclerView = view.findViewById(R.id.recyclerView_skip_page);
        skipLoadingLayout = view.findViewById(R.id.ll_skip_page_loading);
        skipPageAdapter = new SkipPageAdapter(R.layout.item_skip_page);
        skipPageRecyclerView.setLayoutManager(new LinearLayoutManager(
                requireContext(), LinearLayoutManager.HORIZONTAL, false));
        skipPageRecyclerView.setHasFixedSize(true);
        skipPageRecyclerView.setAdapter(skipPageAdapter);
        skipPageAdapter.setOnItemClickListener((ignored, itemView, position) -> {
            Integer target = (Integer) skipPageAdapter.getItem(position);
            if (target != null && target != page) {
                loadPage(target, true);
            }
        });
        skipPageLayout.setVisibility(View.GONE);
        adapter.setOnItemClickListener((ignored, itemView, position) -> {
            V9MmanItem item = adapter.getItem(position);
            if (item != null) goToPlayVideo(item, page, position, itemView.findViewById(R.id.iv_91mman_item_img));
        });
        adapter.setOnLoadMoreListener(() -> loadPage(page + 1, false), recycler);
        adapter.setOnCardActionListener(new V91MmanAdapter.OnCardActionListener() {
            @Override
            public void onFavoriteAction(V9MmanItem item) {
                VideoCardActions.toggleFavorite(dataManager, item, (message, success, openDownloads) -> {
                    if (!isAdded() || adapter == null) return;
                    showMessage(message, success ? TastyToast.SUCCESS : TastyToast.ERROR);
                    int position = adapter.getData().indexOf(item);
                    if (position >= 0) adapter.notifyItemChanged(position);
                });
            }

            @Override
            public void onDownloadAction(V9MmanItem item) { }
        });
        categories.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                if (rebuildingCategories) return;
                String selected = tab.getTag() == null ? "" : tab.getTag().toString();
                if (!selected.equals(categoryId)) {
                    categoryId = selected;
                    preferences.edit().putString(STATE_CATEGORY, categoryId).apply();
                    startNewQuery();
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        latest.setOnClickListener(v -> changeSort("latest"));
        hot.setOnClickListener(v -> changeSort("hot"));
        refresh.setOnRefreshListener(() -> { loadCategories(); loadPage(1, true); });
        status.setOnClickListener(v -> { if (!loading) { loadCategories(); loadPage(1, true); } });
        rebuildCategories(new ArrayList<>(), false);
        updateSort();
        loadCategories();
        loadPage(1, true);
    }

    private void loadCategories() {
        if (categoryRequest != null && !categoryRequest.isDisposed()) categoryRequest.dispose();
        categoryRequest = Observable.fromCallable(() -> sourceClient.loadCategories())
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(values -> rebuildCategories(values, true), error -> {
                    if (!isAdded()) return;
                    if (categories != null && categories.getTabCount() == 1 && !categoryId.isEmpty()) {
                        categoryId = "";
                        startNewQuery();
                    }
                    showMessage("分类加载失败，下拉可重试", TastyToast.INFO);
                });
        disposables.add(categoryRequest);
    }

    private void rebuildCategories(List<Source441447Client.Category> values, boolean loaded) {
        if (categories == null) return;
        rebuildingCategories = true;
        categories.removeAllTabs();
        categories.addTab(categories.newTab().setText("全部").setTag(""), false);
        int selected = 0;
        for (Source441447Client.Category value : values) {
            categories.addTab(categories.newTab().setText(value.name).setTag(value.id), false);
            if (value.id.equals(categoryId)) selected = categories.getTabCount() - 1;
        }
        // Keep the saved category until a successful category response is available.
        if (loaded && selected == 0 && !categoryId.isEmpty()) {
            categoryId = "";
            preferences.edit().putString(STATE_CATEGORY, categoryId).apply();
            startNewQuery();
        }
        TabLayout.Tab tab = categories.getTabAt(selected);
        if (tab != null) tab.select();
        rebuildingCategories = false;
    }

    private void changeSort(String value) {
        if (value.equals(sort)) return;
        sort = value;
        preferences.edit().putString(STATE_SORT, sort).apply();
        updateSort();
        startNewQuery();
    }

    private void updateSort() {
        int active = ContextCompat.getColor(requireContext(), R.color.brand);
        int inactive = ContextCompat.getColor(requireContext(), R.color.text_secondary);
        latest.setTextColor("latest".equals(sort) ? active : inactive);
        hot.setTextColor("hot".equals(sort) ? active : inactive);
        latest.setTypeface(null, "latest".equals(sort) ? Typeface.BOLD : Typeface.NORMAL);
        hot.setTypeface(null, "hot".equals(sort) ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void startNewQuery() {
        adapter.setNewData(new ArrayList<>());
        recycler.scrollToPosition(0);
        page = 0;
        totalPage = 1;
        hidePageBar();
        loadPage(1, true);
    }

    /**
     * 加载指定页。
     *
     * @param requestedPage 目标页码，从 1 开始
     * @param replace       true=整页替换（首屏/刷新/点页码跳页）；false=下拉加载更多
     */
    private void loadPage(int requestedPage, boolean replace) {
        if (!replace && (loading || page >= totalPage)) {
            if (!loading) adapter.loadMoreEnd();
            return;
        }
        if (listRequest != null) listRequest.dispose();
        final int requestGeneration = ++generation;
        final int targetPage = Math.max(1, requestedPage);
        // 已有数据时整页替换视为“跳页”，底部页码栏显示加载遮罩，与主站分类列表一致。
        final boolean pageJump = replace && !adapter.getData().isEmpty();
        final String requestedCategory = categoryId;
        final String requestedSort = sort;
        loading = true;
        if (replace && adapter.getData().isEmpty()) {
            status.setText("正在加载视频…");
            status.setVisibility(View.VISIBLE);
        }
        if (pageJump && skipLoadingLayout != null) {
            skipLoadingLayout.setVisibility(View.VISIBLE);
        }
        adapter.setEnableLoadMore(false);
        listRequest = Observable.fromCallable(() -> sourceClient.loadVideos(requestedCategory, requestedSort, targetPage))
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (requestGeneration != generation) return;
                    loading = false;
                    refresh.setRefreshing(false);
                    page = targetPage;
                    totalPage = result.getTotalPage() == null ? 1 : result.getTotalPage();
                    List<V9MmanItem> videos = result.getData();
                    if (replace) {
                        adapter.setNewData(videos);
                        recycler.scrollToPosition(0);
                    } else {
                        Set<String> seen = new HashSet<>();
                        for (V9MmanItem existing : adapter.getData()) seen.add(existing.getViewKey());
                        List<V9MmanItem> unique = new ArrayList<>();
                        for (V9MmanItem item : videos) if (seen.add(item.getViewKey())) unique.add(item);
                        adapter.addData(unique);
                    }
                    adapter.setEnableLoadMore(true);
                    if (page >= totalPage || videos.isEmpty()) adapter.loadMoreEnd();
                    else adapter.loadMoreComplete();
                    if (skipLoadingLayout != null) skipLoadingLayout.setVisibility(View.GONE);
                    updatePageBar();
                    status.setText("暂无视频");
                    status.setVisibility(adapter.getData().isEmpty() ? View.VISIBLE : View.GONE);
                }, error -> {
                    if (requestGeneration != generation) return;
                    loading = false;
                    refresh.setRefreshing(false);
                    adapter.setEnableLoadMore(true);
                    if (skipLoadingLayout != null) skipLoadingLayout.setVisibility(View.GONE);
                    if (adapter.getData().isEmpty()) {
                        status.setText(Source441447Client.errorMessage(error) + "，点击重试");
                        status.setVisibility(View.VISIBLE);
                        hidePageBar();
                    } else if (pageJump) {
                        // 跳页失败保留原列表与当前页高亮
                        updatePageBar();
                        showMessage("第 " + targetPage + " 页加载失败，请重试", TastyToast.ERROR);
                    } else if (replace) {
                        showMessage("刷新失败，请重试", TastyToast.ERROR);
                    } else {
                        adapter.loadMoreFail();
                    }
                });
        disposables.add(listRequest);
    }

    /** 页码栏按 totalPage 生成 1..N，并高亮当前页。 */
    private void updatePageBar() {
        if (skipPageLayout == null || skipPageAdapter == null || recycler == null) {
            return;
        }
        if (page < 1 || totalPage < 1 || adapter.getData().isEmpty()) {
            hidePageBar();
            return;
        }
        int pageCount = Math.min(totalPage, MAX_PAGE_BAR_PAGES);
        List<Integer> pages = new ArrayList<>();
        for (int i = 1; i <= pageCount; i++) pages.add(i);
        skipPageAdapter.setPageData(pages, page);
        skipPageLayout.setVisibility(View.VISIBLE);
        skipPageRecyclerView.postDelayed(() -> {
            if (skipPageRecyclerView == null || skipPageAdapter == null) return;
            int target = Math.max(0, Math.min(skipPageAdapter.getItemCount() - 1, page - 1));
            skipPageRecyclerView.smoothScrollToPosition(target);
        }, 200);
    }

    private void hidePageBar() {
        if (skipPageLayout != null) {
            skipPageLayout.setVisibility(View.GONE);
        }
        if (skipLoadingLayout != null) {
            skipLoadingLayout.setVisibility(View.GONE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (recycler != null && adapter != null) recycler.postDelayed(() -> {
            if (getView() != null && adapter != null) adapter.reloadCovers(recycler);
        }, 600);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putString(STATE_CATEGORY, categoryId);
        state.putString(STATE_SORT, sort);
    }

    @Override
    public void onDestroyView() {
        generation++;
        disposables.clear();
        recycler = null;
        categories = null;
        adapter = null;
        refresh = null;
        status = null;
        latest = null;
        hot = null;
        skipPageRecyclerView = null;
        skipLoadingLayout = null;
        skipPageLayout = null;
        skipPageAdapter = null;
        super.onDestroyView();
    }
}
