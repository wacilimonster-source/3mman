package com.m3man.ui.mman9video.author;


import android.app.Activity;
import android.app.Fragment;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
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
import com.helper.loadviewhelper.load.LoadViewHelper;
import com.helper.loadviewhelper.help.OnLoadViewListener;
import com.m3man.utils.AdapterDiffUtil;
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
    private LoadViewHelper helper;

    /** M92：UID 过期自愈状态——首页失败后重拉详情页换新 ownerId，成功前只自愈一次 */
    private boolean healingUid;
    /** 记录最近一次加载的下拉刷新标记（自愈重试时沿用） */
    private boolean lastPullToRefresh;

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
        Logger.t(TAG).d("setV9MmanItem: item=" + (v9MmanItem != null)
                + " mIsLoaded=" + mIsLoadedData
                + " view=" + (getView() != null)
                + " canLoad=" + canLoadAuthorVideos());
        // 解析完成后 setV9MmanItem 再次被调用，若 view 已创建且数据已就绪，则补一次加载。
        if (v9MmanItem != null && mIsLoadedData && getView() != null) {
            if (canLoadAuthorVideos()) {
                loadAuthorVideos(false);
            } else {
                // L-fix：ownerId 解析失败/HTML 抓不到作者链接时主动触发自愈，避免滑到作者 Tab 永远空白。
                // 仅 9mman 源走 reloadOwnerThenAuthorVideos；porny 源不重试（自愈实现 return）。
                maybeSelfHealOwner();
            }
        }
        // M-fix：兜底——无论 mIsLoadedData 状态如何，只要 view 已创建，post 一次检查。
        // 修复推荐流/视频播放页进入后作者 Tab 滑到底部仍空白的问题：
        // ViewPager 的 setUserVisibleHint 与 parseVideoUrlSuccess 回调存在竞态，
        // 极端情况下 mIsLoadedData 在 setV9MmanItem 调用时仍为 false，
        // 而 onLazyLoadOnce 也因 v9MmanItem 未设置而跳过，之后两者不再重试。
        // post 到下一帧确保在主线程消息队列的最后一个检查点。
        if (v9MmanItem != null && getView() != null) {
            getView().post(() -> {
                if (!isAdded()) {
                    return;
                }
                if (canLoadAuthorVideos() && mV91MmanAdapter.getItemCount() == 0) {
                    loadAuthorVideos(false);
                    return;
                }
                // 仍然拿不到 ownerId：先尝试一次自愈（不算下拉刷新，不清 healingUid 状态）
                if (mV91MmanAdapter.getItemCount() == 0) {
                    maybeSelfHealOwner();
                }
            });
        }
    }

    /**
     * L-fix：仅在 ownerId 缺失时主动触发 M92 自愈一次——比等用户在作者 Tab 上看到错误再自愈
     * 更早；同一个数据会话内通过 healingUid 标记只走一次，避免反复拉详情。
     */
    private void maybeSelfHealOwner() {
        if (healingUid || v9MmanItem == null || isPornySource()) {
            return;
        }
        // 非 9mman 源 / ownerId 解析失败时，VideoResult.ownerId 空或 ownerId 形如加密 token 过期态，
        // 重拉一次详情页换新 ownerId 然后重试作者列表。
        if (v9MmanItem.getVideoResult() == null
                || TextUtils.isEmpty(v9MmanItem.getVideoResult().getOwnerId())) {
            healingUid = true;
            swipeLayout.setRefreshing(true);
            presenter.reloadOwnerThenAuthorVideos(v9MmanItem, lastPullToRefresh);
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
        recyclerView.setHasFixedSize(true);
        recyclerView.setAdapter(mV91MmanAdapter);

        helper = new LoadViewHelper(recyclerView);
        helper.setListener(new OnLoadViewListener() {
            @Override
            public void onRetryClick() {
                if (canLoadAuthorVideos()) {
                    loadAuthorVideos(true);
                }
            }
        });
    }

    @Override
    protected void onLazyLoadOnce() {
        super.onLazyLoadOnce();
        // 数据未就绪时不报错，等待 setV9MmanItem 补加载（视频解析完成后再触发）
        if (canLoadAuthorVideos()) {
            loadAuthorVideos(false);
            return;
        }
        // M-fix：兜底——用户首次滑到作者 Tab 时，若数据尚未就绪（视频仍在解析中），
        // 延迟 300ms 再检查一次。setV9MmanItem 的 post 兜底也会覆盖此场景，
        // 但双重保险确保极端时序下不遗漏。
        final View root = getView();
        if (root != null) {
            root.postDelayed(() -> {
                if (!isAdded()) {
                    return;
                }
                if (canLoadAuthorVideos() && mV91MmanAdapter.getItemCount() == 0) {
                    loadAuthorVideos(false);
                    return;
                }
                // 仍未就绪：再延 1.2s 给 parseVideoUrlSuccess / DB 命中时间
                if (mV91MmanAdapter.getItemCount() == 0) {
                    final View root2 = getView();
                    if (root2 == null) {
                        // 视图已销毁，直接走自愈（不依赖 view）
                        maybeSelfHealOwner();
                        return;
                    }
                    root2.postDelayed(() -> {
                        if (!isAdded()) {
                            return;
                        }
                        if (canLoadAuthorVideos() && mV91MmanAdapter.getItemCount() == 0) {
                            loadAuthorVideos(false);
                            return;
                        }
                        // 仍然拿不到：主动自愈一次
                        maybeSelfHealOwner();
                    }, 1200);
                }
            }, 300);
        }
    }

    public void loadAuthorVideos() {
        loadAuthorVideos(false);
    }

    private void loadAuthorVideos(boolean pullToRefresh) {
        // 防御：视频未解析完成时 getVideoResult() 可能为 null，先判空再访问
        if (!canLoadAuthorVideos()) {
            showError("数据错误，无法加载作者视频");
            return;
        }
        lastPullToRefresh = pullToRefresh;
        // 手动下拉刷新视为用户主动重试，允许再次触发自愈
        if (pullToRefresh) {
            healingUid = false;
        }
        String ownerId = v9MmanItem.getVideoResult().getOwnerId();
        // M66b：ownerId 形态防御——9mman 的加密 UID（含 _/- 或超长）绝不能走 porny /author/，
        // 否则站点 404（作者其他作品加载失败）
        boolean uidLooksLikeMman = ownerId != null
                && (ownerId.contains("_") || ownerId.contains("-") || ownerId.length() > 32);
        if (isPornySource() && !uidLooksLikeMman) {
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
        boolean ok = v9MmanItem != null && v9MmanItem.getVideoResult() != null
                && v9MmanItem.getVideoResultId() != 0
                && !TextUtils.isEmpty(v9MmanItem.getVideoResult().getOwnerId());
        if (!ok) {
            Logger.t(TAG).w("canLoadAuthorVideos=false"
                    + " item=" + (v9MmanItem != null)
                    + " vr=" + (v9MmanItem != null && v9MmanItem.getVideoResult() != null)
                    + " vrId=" + (v9MmanItem != null ? v9MmanItem.getVideoResultId() : "N/A")
                    + " owner=" + (v9MmanItem != null && v9MmanItem.getVideoResult() != null
                        ? v9MmanItem.getVideoResult().getOwnerId() : "N/A"));
        }
        return ok;
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
        AdapterDiffUtil.apply(mV91MmanAdapter, data, AdapterDiffUtil.v9MmanItem());
        recyclerView.smoothScrollToPosition(0);
        swipeLayout.setRefreshing(false);
        healingUid = false;
    }

    @Override
    public void onAuthorUidHealed(String newUid) {
        if (v9MmanItem == null || v9MmanItem.getVideoResult() == null
                || TextUtils.isEmpty(newUid)) {
            return;
        }
        v9MmanItem.getVideoResult().setOwnerId(newUid);
        healingUid = false;
    }

    @Override
    public void showLoading(boolean pullToRefresh) {
        swipeLayout.setRefreshing(true);
        if (mV91MmanAdapter.getItemCount() == 0) {
            helper.showLoading();
        }
    }

    @Override
    public void showContent() {
        swipeLayout.setRefreshing(false);
        helper.showContent();
        if (mV91MmanAdapter.getItemCount() == 0) {
            mV91MmanAdapter.setEmptyView(R.layout.empty_view, recyclerView);
        }
    }

    @Override
    public void showMessage(String msg, int type) {
        super.showMessage(msg, type);
    }

    @Override
    public void showError(String message) {
        showMessage(message, TastyToast.ERROR);
        if (mV91MmanAdapter.getItemCount() == 0) {
            helper.showError();
        }
        tryHealStaleOwner();
    }

    /**
     * M92：9mman 作者 UID 是加密临时 token，DB 缓存的旧 token 请求 uvideos.php 会 404。
     * 首页加载失败时重拉视频详情页换取新 ownerId 并重试（每次数据会话仅一次）。
     */
    private void tryHealStaleOwner() {
        if (healingUid || !canLoadAuthorVideos() || isPornySource()) {
            return;
        }
        healingUid = true;
        swipeLayout.setRefreshing(true);
        presenter.reloadOwnerThenAuthorVideos(v9MmanItem, lastPullToRefresh);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unbinder.unbind();
    }
}
