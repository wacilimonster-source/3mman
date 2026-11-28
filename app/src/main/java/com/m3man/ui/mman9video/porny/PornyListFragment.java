package com.m3man.ui.mman9video.porny;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.MyApplication;
import com.m3man.R;
import com.m3man.adapter.V91MmanAdapter;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.model.BaseResult;
import com.m3man.parser.Parse91PornyVideo;
import androidx.fragment.app.Fragment;
import com.m3man.utils.AppLog;
import com.m3man.widget.VerticalSpaceItemDecoration;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;

/**
 * 91porny 来源的列表页（第三来源）。
 * <p>
 * 与主站列表解耦：数据来自 {@link DataManager#loadPornyList(int, int)}，
 * 结构对应站点 {@code colVideoList / video-elem}，播放走 91porny 直链（无需账号）。
 * 支持下拉刷新与上拉加载更多；末页用「没有更多了」结束。
 */
public class PornyListFragment extends Fragment {

    private static final String TAG = "PornyListFragment";
    private static final String ARG_KIND = "arg_kind";

    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private V91MmanAdapter adapter;
    private View emptyView;

    private int kind = Parse91PornyVideo.LIST_HOME;
    private int page = 1;
    private boolean loading;
    private boolean hasMore = true;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private DataManager dataManager;

    public static PornyListFragment newInstance(int kind) {
        PornyListFragment fragment = new PornyListFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_KIND, kind);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            kind = getArguments().getInt(ARG_KIND, Parse91PornyVideo.LIST_HOME);
        }
        dataManager = MyApplication.getInstance().getDataManager();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_porny_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        recyclerView = view.findViewById(R.id.recyclerView_porny_list);
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_porny_list);
        emptyView = view.findViewById(R.id.tv_porny_list_empty);

        adapter = new V91MmanAdapter(R.layout.item_v_9mman);
        adapter.setDataManager(dataManager);
        adapter.setOnItemClickListener((baseQuickAdapter, itemView, position) -> {
            V9MmanItem item = (V9MmanItem) baseQuickAdapter.getItem(position);
            startPlay(item);
        });
        adapter.setOnLoadMoreListener(() -> loadPage(false), recyclerView);
        adapter.setEnableLoadMore(false);

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.addItemDecoration(new VerticalSpaceItemDecoration(
                getResources().getDimensionPixelSize(R.dimen.video_card_spacing)));
        recyclerView.setAdapter(adapter);
        swipeRefreshLayout.setOnRefreshListener(this::refresh);

        loadPage(true);
    }

    private void refresh() {
        page = 1;
        hasMore = true;
        loadPage(true);
    }

    private void loadPage(boolean reset) {
        if (loading) {
            return;
        }
        if (!hasMore && !reset) {
            return;
        }
        loading = true;
        if (reset) {
            page = 1;
        }
        final int requestPage = page;
        disposables.add(dataManager.loadPornyList(kind, requestPage)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                        List<V9MmanItem> items = result == null ? null : result.getData();
                        if (items == null) {
                            items = new ArrayList<>();
                        }
                        if (requestPage == 1) {
                            adapter.setNewData(items);
                        } else if (!items.isEmpty()) {
                            adapter.addData(items);
                        }
                        hasMore = !items.isEmpty();
                        page = requestPage + 1;
                        adapter.setEnableLoadMore(hasMore);
                        adapter.loadMoreComplete();
                        if (!hasMore) {
                            adapter.loadMoreEnd(true);
                        }
                        boolean empty = adapter.getData().isEmpty();
                        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
                        swipeRefreshLayout.setRefreshing(false);
                        loading = false;
                }, e -> {
                        AppLog.w(TAG, "91porny 列表加载失败 kind=" + kind + " page=" + requestPage + " " + AppLog.cause(e));
                        loading = false;
                        swipeRefreshLayout.setRefreshing(false);
                        adapter.loadMoreFail();
                        if (adapter.getData().isEmpty()) {
                            TastyToast.makeText(getContext(), getString(R.string.porny_list_load_failed), TastyToast.LENGTH_LONG, TastyToast.ERROR).show();
                            emptyView.setVisibility(View.VISIBLE);
                        } else {
                            TastyToast.makeText(getContext(), getString(R.string.porny_list_load_more_failed), TastyToast.LENGTH_SHORT, TastyToast.INFO).show();
                        }
                }));
    }


    /** 打开播放页（普通 Fragment，直接用 PlaybackEngine 的 intent）。 */
    private void startPlay(V9MmanItem item) {
        if (item == null || getContext() == null) {
            return;
        }
        android.content.Intent intent = com.m3man.utils.PlaybackEngine.getPlaybackEngineIntent(getContext());
        intent.putExtra(com.m3man.constants.Keys.KEY_INTENT_V9MMAN_ITEM, item);
        intent.putExtra(com.m3man.constants.Keys.KEY_INTENT_SKIP_PAGE, 0);
        startActivity(intent);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        disposables.clear();
        recyclerView = null;
        swipeRefreshLayout = null;
        emptyView = null;
    }

    @Override
    public void onDestroy() {
        disposables.dispose();
        super.onDestroy();
    }
}
