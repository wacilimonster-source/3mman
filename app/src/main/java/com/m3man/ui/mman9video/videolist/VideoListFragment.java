package com.m3man.ui.mman9video.videolist;


import android.app.Activity;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.helper.loadviewhelper.help.OnLoadViewListener;
import com.helper.loadviewhelper.load.LoadViewHelper;
import com.orhanobut.logger.Logger;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.R;
import com.m3man.adapter.SkipPageAdapter;
import com.m3man.adapter.V91MmanAdapter;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.MvpFragment;
import com.m3man.utils.AdapterDiffUtil;
import com.m3man.utils.AppUtils;
import com.m3man.utils.LoadHelperUtils;

import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;

/**
 * 通用视频列表加载
 * A simple {@link Fragment} subclass.
 *
 * @author flymegoc
 */
public class VideoListFragment extends MvpFragment<VideoListView, VideoListPresenter> implements VideoListView, SwipeRefreshLayout.OnRefreshListener {

    private static final String TAG = VideoListFragment.class.getSimpleName();
    @BindView(R.id.recyclerView_common)
    RecyclerView recyclerView;
    Unbinder unbinder;
    @BindView(R.id.contentView)
    SwipeRefreshLayout contentView;

    @BindView(R.id.recyclerView_skip_page)
    RecyclerView skipPageRecyclerView;

    @BindView(R.id.ll_skip_page_loading)
    LinearLayout skipLoadingLayout;

    @BindView(R.id.fl_skip_page)
    FrameLayout skipPageLayout;

    private V91MmanAdapter mV91MmanAdapter;

    private LoadViewHelper helper;

    @Inject
    protected VideoListPresenter videoListPresenter;

    private SkipPageAdapter skipPageAdapter;

    private int skipPage = 0;
    private int position;

    public VideoListFragment() {
        // Required empty public constructor
    }

    public void setSkipPage(int skipPage) {
        this.skipPage = skipPage;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public static VideoListFragment getInstance() {
        return new VideoListFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.t(TAG).d(category.getCategoryName()+"  VideoListFragment init..............");
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mV91MmanAdapter = new V91MmanAdapter(R.layout.item_v_9mman);
        skipPageAdapter = new SkipPageAdapter(R.layout.item_skip_page);
    }

    @NonNull
    @Override
    public VideoListPresenter createPresenter() {
        return videoListPresenter;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        super.onCreateView(inflater, container, savedInstanceState);
        return inflater.inflate(R.layout.fragment_video_list, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        unbinder = ButterKnife.bind(this, view);
        mV91MmanAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                V9MmanItem v9MmanItems = (V9MmanItem) adapter.getItem(position);
                View coverView = view.findViewById(R.id.iv_91mman_item_img);
                // 传入真实点击位置用于详情页内关联列表的滚动定位（之前用 position%20 会导致定位到错误位置）
                goToPlayVideo(v9MmanItems, presenter.getPlayBackEngine(), presenter.getPage(), position, coverView);
            }
        });
        mV91MmanAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                presenter.loadVideoListData(false, false, category.getCategoryValue(), 0);
            }
        });
        skipPageAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                int page = (int) adapter.getItem(position);
                loadData(false, false, page);
            }
        });
        // Setup contentView == SwipeRefreshView
        contentView.setOnRefreshListener(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setHasFixedSize(true);
        recyclerView.setAdapter(mV91MmanAdapter);

        helper = new LoadViewHelper(recyclerView);
        helper.setListener(new OnLoadViewListener() {
            @Override
            public void onRetryClick() {
                loadData(false, true, 0);
            }
        });
        //loadData(false);
        AppUtils.setColorSchemeColors(context, contentView);

        handlerSkipPage();
    }

    private void handlerSkipPage() {
        // 视频地址跳页功能固定展示，不再受设置项控制。
        skipPageLayout.setVisibility(View.VISIBLE);
        skipPageRecyclerView.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
        skipPageRecyclerView.setHasFixedSize(true);
        skipPageRecyclerView.setAdapter(skipPageAdapter);
    }

    @Override
    protected void onLazyLoadOnce() {
        Logger.t(TAG).d(category.getCategoryName()+"  初次加载数据......");
        loadData(false, false, skipPage);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unbinder.unbind();
    }

    @Override
    public void setData(List<V9MmanItem> data) {
        Logger.t(TAG).d(category.getCategoryName()+"  加载数据成功......");
        AdapterDiffUtil.apply(mV91MmanAdapter, data, AdapterDiffUtil.v9MmanItem());
        mV91MmanAdapter.disableLoadMoreIfNotFullPage(recyclerView);
        ((LinearLayoutManager) recyclerView.getLayoutManager()).scrollToPositionWithOffset(position, 0);
    }

    @Override
    public void setPageData(List<Integer> pageData) {
        skipPageAdapter.setNewData(pageData);
    }

    @Override
    public void updateCurrentPage(final int currentPage) {
        Logger.t(TAG).d(category.getCategoryName()+"第《" + currentPage + "》页");
        skipPageAdapter.setCurrentPage(currentPage);
        skipPageRecyclerView.postDelayed(new Runnable() {
            @Override
            public void run() {
                //异步，可能点击太快会导致视图已经销毁了
                if (skipPageRecyclerView == null) {
                    return;
                }
                int target = Math.max(0, Math.min(skipPageAdapter.getItemCount() - 1, currentPage - 1));
                skipPageRecyclerView.smoothScrollToPosition(target);
            }
        }, 200);
    }

    @Override
    public void showSkipPageLoading() {
        skipLoadingLayout.setVisibility(View.VISIBLE);

    }

    @Override
    public void hideSkipPageLoading() {
        skipLoadingLayout.setVisibility(View.GONE);
    }

    @Override
    public void showLoading(boolean pullToRefresh) {
        helper.showLoading();
        LoadHelperUtils.setLoadingText(helper.getLoadIng(), R.id.tv_loading_text, "拼命加载中...");
        contentView.setEnabled(false);
    }

    @Override
    public void loadData(boolean pullToRefresh, boolean cleanCache, int skipPage) {
        presenter.loadVideoListData(pullToRefresh, cleanCache, category.getCategoryValue(), skipPage);
    }

    @Override
    public void onRefresh() {
        loadData(true, true, 0);
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
        // M73：恢复刷新使能——showLoading 里禁用了 contentView，错误分支不恢复会导致刷新永久失效
        contentView.setEnabled(true);
        helper.showError();
        showMessage(message, TastyToast.ERROR);
    }

    @Override
    public void loadMoreDataComplete() {
        mV91MmanAdapter.loadMoreComplete();
    }

    @Override
    public void loadMoreFailed() {
        showMessage(getString(R.string.common_load_more_failed), TastyToast.ERROR);
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
    public String getTitle() {
        return category.getCategoryName();
    }
}
