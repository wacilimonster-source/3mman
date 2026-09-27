package com.m3man.ui.mman9video.porny;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.text.TextUtils;
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
import com.m3man.constants.Keys;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.AuthorFavorite;
import com.m3man.ui.MvpActivity;
import com.m3man.ui.MvpFragment;
import com.m3man.utils.AppUtils;
import com.m3man.utils.LoadHelperUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;

/**
 * 精品·大神频道（91porny，视频 Tab 频道行内）。
 *
 * 上半部"大神"横滑条：站点无大神索引页（/dashen 404），种子来自首页"大神"模块
 * （实测 9 位，含作品数），运行中从精品条目的 /dashen/ 链接增量积累并落本地库；
 * 下半部精品列表 /vod?page=N。两段数据独立加载，大神条失败不阻塞精品列表。
 * 大神卡/作者行点击 → AuthorActivity（source=91porny，uid="dashen/<名字>"，
 * 数据层按前缀路由到大神端点，作者收藏复用 AuthorFavorite 表）。
 */
public class PornyPremiumFragment extends MvpFragment<PornyListView, PornyListPresenter> implements PornyListView {

    private static final String TAG = PornyPremiumFragment.class.getSimpleName();

    @BindView(R.id.content_view)
    SwipeRefreshLayout contentView;
    @BindView(R.id.recyclerView_common)
    RecyclerView recyclerView;
    @BindView(R.id.recyclerView_dashen)
    RecyclerView dashenRecyclerView;

    private V91MmanAdapter mAdapter;
    private DashenAdapter mDashenAdapter;
    private LoadViewHelper helper;
    private Unbinder unbinder;

    @Inject
    protected PornyListPresenter pornyListPresenter;

    public static PornyPremiumFragment getInstance() {
        return new PornyPremiumFragment();
    }

    public PornyPremiumFragment() {
        // Required empty public constructor
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mAdapter = new V91MmanAdapter(R.layout.item_v_9mman);
        mDashenAdapter = new DashenAdapter();
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
        return inflater.inflate(R.layout.fragment_porny_premium, container, false);
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
                presenter.loadDashenStrip();
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

        mDashenAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View v, int position) {
                String name = (String) adapter.getItem(position);
                openDashenPage(name);
            }
        });
        dashenRecyclerView.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
        dashenRecyclerView.setHasFixedSize(true);
        dashenRecyclerView.setAdapter(mDashenAdapter);
    }

    @Override
    protected void onLazyLoadOnce() {
        super.onLazyLoadOnce();
        presenter.setMode(PornyListPresenter.MODE_PREMIUM);
        presenter.refresh();
        presenter.loadDashenStrip();
    }

    /** 大神卡 → 作者主页（复用 AuthorActivity，作者收藏/追更能力一并复用） */
    private void openDashenPage(String name) {
        if (TextUtils.isEmpty(name) || activity == null) {
            return;
        }
        Intent intent = new Intent(activity, com.m3man.ui.mman9video.author.AuthorActivity.class);
        intent.putExtra(Keys.KEY_INTENT_UID, "dashen/" + name);
        intent.putExtra(Keys.KEY_INTENT_SOURCE, AuthorFavorite.SOURCE_PORNY);
        intent.putExtra(Keys.KEY_INTENT_AUTHOR_NAME, name);
        startActivity(intent);
        activity.overridePendingTransition(R.anim.slide_in_right, R.anim.side_out_left);
    }

    /**
     * P1：从精品条目 info 行积累大神名（"作者: xxx"），本地库去重合并。
     * 蝌蚪条目无作者行，天然跳过。
     */
    private void collectDashenNames(List<V9MmanItem> items) {
        Set<String> names = new LinkedHashSet<>();
        for (V9MmanItem item : items) {
            String info = item == null ? null : item.getInfo();
            if (TextUtils.isEmpty(info) || !info.startsWith("作者: ")) {
                continue;
            }
            String name = info.substring("作者: ".length()).trim();
            if (!TextUtils.isEmpty(name)) {
                names.add(name);
            }
        }
        if (!names.isEmpty()) {
            presenter.accumulateDashenNames(new ArrayList<>(names));
        }
    }

    @Override
    public void setData(List<V9MmanItem> data) {
        mAdapter.setNewData(data);
        recyclerView.scrollToPosition(0);
        mAdapter.disableLoadMoreIfNotFullPage(recyclerView);
        if (data != null) {
            collectDashenNames(data);
        }
    }

    @Override
    public void setMoreData(List<V9MmanItem> data) {
        mAdapter.addData(data);
        if (data != null) {
            collectDashenNames(data);
        }
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
        mDashenAdapter.setNewData(names);
    }

    @Override
    public String getTitle() {
        return category != null ? category.getCategoryName() : "精品·大神";
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unbinder.unbind();
    }
}
