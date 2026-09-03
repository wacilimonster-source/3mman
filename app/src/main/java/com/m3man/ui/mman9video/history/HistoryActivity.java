package com.m3man.ui.mman9video.history;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.appcompat.widget.Toolbar;
import android.view.View;

import com.aitsuki.swipe.SwipeMenuRecyclerView;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.m3man.R;
import com.m3man.adapter.V9MmanItemAdapter;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.MvpActivity;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;

/**
 * @author flymegoc
 */
public class HistoryActivity extends MvpActivity<HistoryView, HistoryPresenter> implements HistoryView {

    @BindView(R.id.toolbar)
    Toolbar toolbar;
    @BindView(R.id.recyclerView)
    SwipeMenuRecyclerView recyclerView;

    private V9MmanItemAdapter mUnLimit91Adapter;
    private List<V9MmanItem> mV9MmanItemList;

    @Inject
    protected HistoryPresenter historyPresenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        ButterKnife.bind(this);
        initToolBar(toolbar);
        mV9MmanItemList = new ArrayList<>();
        mUnLimit91Adapter = new V9MmanItemAdapter(R.layout.item_v_9mman, mV9MmanItemList);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        recyclerView.setAdapter(mUnLimit91Adapter);
        mUnLimit91Adapter.setEmptyView(R.layout.empty_view, recyclerView);

        mUnLimit91Adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                goToPlayVideo((V9MmanItem) adapter.getItem(position), presenter.getPlayBackEngine());
            }
        });
        mUnLimit91Adapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                presenter.loadHistoryData(false);
            }
        }, recyclerView);
        presenter.loadHistoryData(false);
    }

    @NonNull
    @Override
    public HistoryPresenter createPresenter() {
        return historyPresenter;
    }

    @Override
    public void showError(String message) {

    }

    @Override
    public void showLoading(boolean pullToRefresh) {

    }

    @Override
    public void showContent() {

    }

    @Override
    public void showMessage(String msg, int type) {
        super.showMessage(msg, type);
    }


    @Override
    public void loadMoreDataComplete() {
        mUnLimit91Adapter.loadMoreComplete();
    }

    @Override
    public void loadMoreFailed() {
        mUnLimit91Adapter.loadMoreFail();
    }

    @Override
    public void noMoreData() {
        mUnLimit91Adapter.loadMoreEnd(true);
    }

    @Override
    public void setData(List<V9MmanItem> v9MmanItemList) {
        mUnLimit91Adapter.setNewData(v9MmanItemList);
    }

    @Override
    public void setMoreData(List<V9MmanItem> v9MmanItemList) {
        mUnLimit91Adapter.addData(v9MmanItemList);
    }
}
