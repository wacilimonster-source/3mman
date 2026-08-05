package com.m3man.ui.mman9video.author;


import android.app.Activity;
import android.app.Fragment;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.widget.TextView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.orhanobut.logger.Logger;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.R;
import com.m3man.adapter.V91MmanAdapter;
import com.m3man.constants.Keys;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.parser.Parse91PornyVideo;
import com.m3man.ui.MvpFragment;
import com.m3man.ui.mman9video.play.PlayVideoPresenter;
import com.m3man.utils.AppUtils;
import com.m3man.utils.PlaybackEngine;

import android.content.Intent;

import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;

/**
 * A simple {@link Fragment} subclass.
 * 作者视频
 *
 * @author megoc
 */
public class AuthorFragment extends MvpFragment<AuthorView, AuthorPresenter> implements AuthorView {

    private static final String TAG = AuthorFragment.class.getSimpleName();
    @BindView(R.id.recyclerView)
    RecyclerView recyclerView;
    @BindView(R.id.swipe_layout)
    SwipeRefreshLayout swipeLayout;
    Unbinder unbinder;

    private V9MmanItem v9MmanItem;

    private V91MmanAdapter mV91MmanAdapter;

    @Inject
    protected AuthorPresenter authorPresenter;

    @Inject
    public AuthorFragment() {
        // Required empty public constructor
        Logger.t(TAG).d("AuthorFragment初始化了.....");
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mV91MmanAdapter = new V91MmanAdapter(R.layout.item_v_9mman);
        mV91MmanAdapter.setOnItemClickListener((adapter, view, position) -> {
            V9MmanItem v9MmanItems = (V9MmanItem) adapter.getData().get(position);
            if (v9MmanItems == null) {
                return;
            }
            // 进入该视频的播放页（新 Activity），而非在当前播放页内联播放
            int engine = PlayVideoPresenter.isPornySource(v9MmanItems)
                    ? PlaybackEngine.DEFAULT_PLAYER_ENGINE
                    : authorPresenter.getPlayBackEngine();
            Intent intent = PlaybackEngine.getPlaybackEngineIntent(getActivity(), engine);
            intent.putExtra(Keys.KEY_INTENT_V9MMAN_ITEM, v9MmanItems);
            startActivityWithAnimation(intent);
        });
        mV91MmanAdapter.setOnLoadMoreListener(() -> {
            if (canLoadAuthorVideos()) {
                loadAuthorVideos(false);
            } else {
                showError("数据错误，无法加载");
            }

        }, recyclerView);

    }

    public void setV9MmanItem(V9MmanItem v9MmanItem) {
        this.v9MmanItem = v9MmanItem;
        // 解析完成后 setV9MmanItem 再次被调用，若 view 已创建且数据已就绪，则补一次加载。
        if (v9MmanItem != null && mIsLoadedData && getView() != null) {
            if (canLoadAuthorVideos()) {
                loadAuthorVideos(false);
            }
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_author, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        unbinder = ButterKnife.bind(this, view);
        init();
    }

    private void init() {
        AppUtils.setColorSchemeColors(getContext(), swipeLayout);
        swipeLayout.setOnRefreshListener(() -> {
            if (canLoadAuthorVideos()) {
                loadAuthorVideos(true);
            } else {
                showError("数据错误，无法加载");
            }
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(mV91MmanAdapter);
    }

    @Override
    protected void onLazyLoadOnce() {
        super.onLazyLoadOnce();
        // 数据未就绪时不报错，等待 setV9MmanItem 补加载（视频解析完成后再触发）
        if (canLoadAuthorVideos()) {
            loadAuthorVideos(false);
        }
    }

    public void loadAuthorVideos() {
        loadAuthorVideos(false);
    }

    private void loadAuthorVideos(boolean pullToRefresh) {
        String ownerId = v9MmanItem.getVideoResult().getOwnerId();
        if (isPornySource()) {
            // 91porny 作者：ownerId 即作者名，走 /author/{name}
            presenter.pornyAuthorVideos(ownerId, pullToRefresh);
        } else {
            presenter.authorVideos(ownerId, pullToRefresh);
        }
    }

    private boolean isPornySource() {
        return PlayVideoPresenter.isPornySource(v9MmanItem);
    }

    private boolean canLoadAuthorVideos() {
        return v9MmanItem != null && v9MmanItem.getVideoResult() != null
                && v9MmanItem.getVideoResultId() != 0
                && !TextUtils.isEmpty(v9MmanItem.getVideoResult().getOwnerId());
    }

    @Override
    public String getTitle() {
        return "作者";
    }

    @NonNull
    @Override
    public AuthorPresenter createPresenter() {
        return authorPresenter;
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
        swipeLayout.setRefreshing(false);
    }

    @Override
    public void showLoading(boolean pullToRefresh) {
        swipeLayout.setRefreshing(true);
    }

    @Override
    public void showContent() {
        swipeLayout.setRefreshing(false);
    }

    @Override
    public void showMessage(String msg, int type) {
        super.showMessage(msg, type);
    }

    @Override
    public void showError(String message) {
        showMessage(message, TastyToast.ERROR);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unbinder.unbind();
    }
}
