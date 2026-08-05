package com.m3man.ui.mman9video.index;


import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

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
    private int position;

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
        recyclerView.setAdapter(mV91MmanAdapter);

        mV91MmanAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                V9MmanItem v9MmanItems = (V9MmanItem) adapter.getData().get(position);
                goToPlayVideo(v9MmanItems, presenter.getPlayBackEngine(), 0, position);
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
        mV91MmanAdapter.setNewData(data);
        mV91MmanAdapter.notifyDataSetChanged();
        ((LinearLayoutManager)recyclerView.getLayoutManager()).scrollToPositionWithOffset(position,0);
    }

    @Override
    public void showLoading(boolean pullToRefresh) {
        helper.showLoading();
        LoadHelperUtils.setLoadingText(helper.getLoadIng(), R.id.tv_loading_text, "拼命加载中...");
        contentView.setEnabled(false);
    }

    @Override
    public void loadData(boolean pullToRefresh, boolean cleanCache) {
        presenter.loadIndexData(pullToRefresh, cleanCache);
    }

    @Override
    public void showContent() {
        helper.showContent();
        contentView.setEnabled(true);
        contentView.setRefreshing(false);
    }

    @Override
    public void showMessage(String msg, int type) {
        super.showMessage(msg, type);
    }


    @Override
    public void showError(String message) {
        contentView.setRefreshing(false);
        helper.showError();
        showMessage(message, TastyToast.ERROR);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unbinder.unbind();
    }

    @Override
    public String getTitle() {
        return category.getCategoryName();
    }
}
