package com.m3man.ui.mman9video.porny;

import android.app.Activity;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.helper.loadviewhelper.help.OnLoadViewListener;
import com.helper.loadviewhelper.load.LoadViewHelper;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.R;
import com.m3man.adapter.V91MmanAdapter;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.MvpFragment;
import com.m3man.utils.AppUtils;
import com.m3man.utils.LoadHelperUtils;

import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;

/**
 * 蝌蚪长视频频道（91porny /videos，视频 Tab 频道行内）。
 *
 * 条目全部为 ≥10 分钟长视频（实测中位 54 分钟）；三排序（最近更新/最高评分/最受欢迎）
 * 为路径式分页 /videos/{sort}/{page}；翻页预取上限 10 页（防风控，见 PornyListPresenter）。
 * 点击条目进统一 Exo 播放页（MP4 直链，PlayVideoPresenter 按 sourceName 路由）。
 */
public class PornyTadpoleFragment extends MvpFragment<PornyListView, PornyListPresenter> implements PornyListView {

    private static final String TAG = PornyTadpoleFragment.class.getSimpleName();

    @BindView(R.id.content_view)
    SwipeRefreshLayout contentView;
    @BindView(R.id.recyclerView_common)
    RecyclerView recyclerView;
    @BindView(R.id.pill_latest)
    TextView pillLatest;
    @BindView(R.id.pill_top_rated)
    TextView pillTopRated;
    @BindView(R.id.pill_most_popular)
    TextView pillMostPopular;

    private V91MmanAdapter mAdapter;
    private LoadViewHelper helper;
    private Unbinder unbinder;

    @Inject
    protected PornyListPresenter pornyListPresenter;

    public static PornyTadpoleFragment getInstance() {
        return new PornyTadpoleFragment();
    }

    public PornyTadpoleFragment() {
        // Required empty public constructor
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mAdapter = new V91MmanAdapter(R.layout.item_v_9mman);
    }

    @NonNull
    @Override
    public PornyListPresenter createPresenter() {
        return pornyListPresenter;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        return inflater.inflate(R.layout.fragment_porny_tadpole, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        unbinder = ButterKnife.bind(this, view);
        mAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View v, int position) {
                V9MmanItem item = (V9MmanItem) adapter.getItem(position);
                View coverView = v.findViewById(R.id.iv_91mman_item_img);
                goToPlayVideo(item, 0, position, coverView);
            }
        });
        mAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                presenter.loadMore();
            }
        }, recyclerView);
        contentView.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                presenter.refresh();
            }
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setHasFixedSize(true);
        recyclerView.setAdapter(mAdapter);
        helper = new LoadViewHelper(recyclerView);
        helper.setListener(new OnLoadViewListener() {
            @Override
            public void onRetryClick() {
                presenter.refresh();
            }
        });
        AppUtils.setColorSchemeColors(context, contentView);
        View.OnClickListener pillListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String sort;
                if (v == pillLatest) {
                    sort = PornyListPresenter.SORT_LATEST;
                } else if (v == pillTopRated) {
                    sort = PornyListPresenter.SORT_TOP_RATED;
                } else {
                    sort = PornyListPresenter.SORT_MOST_POPULAR;
                }
                if (sort.equals(presenter.getSort())) {
                    return;
                }
                presenter.setSort(sort);
                updatePillStates();
                presenter.refresh();
            }
        };
        pillLatest.setOnClickListener(pillListener);
        pillTopRated.setOnClickListener(pillListener);
        pillMostPopular.setOnClickListener(pillListener);
        updatePillStates();
    }

    @Override
    protected void onLazyLoadOnce() {
        super.onLazyLoadOnce();
        presenter.setMode(PornyListPresenter.MODE_TADPOLE);
        presenter.refresh();
    }

    private void updatePillStates() {
        stylePill(pillLatest, PornyListPresenter.SORT_LATEST);
        stylePill(pillTopRated, PornyListPresenter.SORT_TOP_RATED);
        stylePill(pillMostPopular, PornyListPresenter.SORT_MOST_POPULAR);
    }

    private void stylePill(TextView pill, String sort) {
        boolean selected = sort.equals(presenter.getSort());
        pill.setBackgroundResource(selected ? R.drawable.bg_porny_pill_selected : R.drawable.bg_porny_pill_normal);
        pill.setTextColor(getResources().getColor(selected ? R.color.colorPrimary : R.color.text_secondary));
    }

    @Override
    public void setData(List<V9MmanItem> data) {
        mAdapter.setNewData(data);
        recyclerView.scrollToPosition(0);
        mAdapter.disableLoadMoreIfNotFullPage(recyclerView);
    }

    @Override
    public void setMoreData(List<V9MmanItem> data) {
        mAdapter.addData(data);
    }

    @Override
    public void loadMoreDataComplete() {
        mAdapter.loadMoreComplete();
    }

    @Override
    public void loadMoreFailed() {
        showMessage(getString(R.string.common_load_more_failed), TastyToast.ERROR);
        mAdapter.loadMoreFail();
    }

    @Override
    public void noMoreData() {
        mAdapter.loadMoreEnd(true);
    }

    @Override
    public void showLoading(boolean pullToRefresh) {
        helper.showLoading();
        LoadHelperUtils.setLoadingText(helper.getLoadIng(), R.id.tv_loading_text, "拼命加载中...");
        contentView.setEnabled(false);
    }

    @Override
    public void showContent() {
        helper.showContent();
        contentView.setEnabled(true);
        contentView.setRefreshing(false);
    }

    @Override
    public void showError(String message) {
        contentView.setRefreshing(false);
        contentView.setEnabled(true);
        helper.showError();
        LoadHelperUtils.setErrorText(helper.getLoadError(), R.id.tv_error_text,
                "91porny 源加载失败，点击重试");
        showMessage(message, TastyToast.ERROR);
    }

    @Override
    public void setDashen(List<String> names) {
        // 蝌蚪频道无大神横滑条，空实现
    }

    @Override
    public String getTitle() {
        return category != null ? category.getCategoryName() : "长视频";
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unbinder.unbind();
    }
}
