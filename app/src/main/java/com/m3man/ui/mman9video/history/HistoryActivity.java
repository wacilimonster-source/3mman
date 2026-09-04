package com.m3man.ui.mman9video.history;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.appcompat.widget.Toolbar;
import android.view.View;

import com.aitsuki.swipe.SwipeMenuRecyclerView;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.helper.loadviewhelper.help.OnLoadViewListener;
import com.helper.loadviewhelper.load.LoadViewHelper;
import com.m3man.utils.AdapterDiffUtil;
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
    /** M112：统一加载/错误/空三态 */
    private LoadViewHelper helper;

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
        recyclerView.setHasFixedSize(true);

        recyclerView.setAdapter(mUnLimit91Adapter);
        mUnLimit91Adapter.setEmptyView(R.layout.empty_view, recyclerView);

        // M112：接入统一三态（加载中/错误/重试），此前 showError/showLoading 均为空实现，
        // 加载失败时用户只看到白屏
        helper = new LoadViewHelper(recyclerView);
        helper.setListener(new OnLoadViewListener() {
            @Override
            public void onRetryClick() {
                presenter.loadHistoryData(false);
            }
        });

        mUnLimit91Adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                View coverView = view.findViewById(R.id.iv_91mman_item_img);
                goToPlayVideo((V9MmanItem) adapter.getItem(position), presenter.getPlayBackEngine(), coverView);
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
        helper.showError();
    }

    @Override
    public void showLoading(boolean pullToRefresh) {
        helper.showLoading();
    }

    @Override
    public void showContent() {
        helper.showContent();
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
        AdapterDiffUtil.apply(mUnLimit91Adapter, v9MmanItemList, AdapterDiffUtil.v9MmanItem());
    }

    @Override
    public void setMoreData(List<V9MmanItem> v9MmanItemList) {
        mUnLimit91Adapter.addData(v9MmanItemList);
    }
}
