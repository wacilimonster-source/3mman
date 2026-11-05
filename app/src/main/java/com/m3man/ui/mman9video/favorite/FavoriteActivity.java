package com.m3man.ui.mman9video.favorite;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.appcompat.widget.Toolbar;
import android.view.View;

import com.aitsuki.swipe.SwipeItemLayout;
import com.aitsuki.swipe.SwipeMenuRecyclerView;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.helper.loadviewhelper.help.OnLoadViewListener;
import com.helper.loadviewhelper.load.LoadViewHelper;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.R;
import com.m3man.adapter.V9MmanItemAdapter;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.MvpActivity;
import com.m3man.utils.AdapterDiffUtil;
import com.m3man.widget.VerticalSpaceItemDecoration;
import com.m3man.utils.DialogUtils;
import com.m3man.utils.LoadHelperUtils;
import com.m3man.utils.UndoSnackbar;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;

/**
 * @author flymegoc
 */
public class FavoriteActivity extends MvpActivity<FavoriteView, FavoritePresenter> implements FavoriteView, SwipeRefreshLayout.OnRefreshListener {

    @BindView(R.id.recyclerView)
    SwipeMenuRecyclerView recyclerView;
    @BindView(R.id.toolbar)
    Toolbar toolbar;
    @BindView(R.id.contentView)
    SwipeRefreshLayout contentView;

    private V9MmanItemAdapter mUnLimit91Adapter;

    private LoadViewHelper helper;
    private AlertDialog deleteAlertDialog;

    /** M42：true=本地收藏模式（与分分钟合并展示，无需登录）；false=服务器收藏 */
    private boolean localMode = false;
    private final CompositeDisposable mDisposables = new CompositeDisposable();

    // T08：本地收藏游标分页状态（pagedLocalList 开启时生效；主线程串行访问）
    /** 本地收藏分页大小（方案 §5-T08 默认 50） */
    private static final int LOCAL_PAGE_SIZE = 50;
    /** 游标：最后一行 _id；null=首页 */
    private Long localLastId;
    /** 刷新代际：下拉刷新/重试递增，过期分页回调丢弃 */
    private int localGeneration;
    private boolean localLoading;
    private boolean localNoMore;

    @Inject
    protected FavoritePresenter favoritePresenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_favorite);
        ButterKnife.bind(this);
        deleteAlertDialog = DialogUtils.initLoadingDialog(this, getString(R.string.favorite_deleting_loading));
        localMode = presenter.isLocalFavoriteMode();
        initToolBar(toolbar);
        toolbar.setContentInsetStartWithNavigation(0);

        // Setup contentView == SwipeRefreshView
        contentView.setOnRefreshListener(this);

        List<V9MmanItem> mV9MmanItemList = new ArrayList<>();
        mUnLimit91Adapter = new V9MmanItemAdapter(R.layout.item_right_menu_delete, mV9MmanItemList);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setHasFixedSize(true);
        recyclerView.addItemDecoration(new VerticalSpaceItemDecoration(
                getResources().getDimensionPixelSize(R.dimen.video_card_spacing)));

        recyclerView.setAdapter(mUnLimit91Adapter);
        mUnLimit91Adapter.setEmptyView(R.layout.empty_view, recyclerView);

        mUnLimit91Adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                View coverView = view.findViewById(R.id.iv_91mman_item_img);
                goToPlayVideo((V9MmanItem) adapter.getItem(position), coverView);
            }
        });

        mUnLimit91Adapter.setOnItemChildClickListener(new BaseQuickAdapter.OnItemChildClickListener() {
            @Override
            public void onItemChildClick(BaseQuickAdapter adapter, View view, int position) {
                SwipeItemLayout swipeItemLayout = (SwipeItemLayout) view.getParent();
                swipeItemLayout.close();
                if (view.getId() == R.id.right_menu_delete) {
                    V9MmanItem v9MmanItem = (V9MmanItem) adapter.getItem(position);
                    if (v9MmanItem == null) {
                        return;
                    }
                    // M112：取消收藏给一次撤销窗口，确认条超时未撤销才真正落库
                    final boolean isLocal = localMode;
                    UndoSnackbar.confirmWithUndo(recyclerView, getString(R.string.favorite_canceled), getString(R.string.common_undo),
                            new UndoSnackbar.Action() {
                                @Override
                                public void run() {
                                    if (isLocal) {
                                        deleteLocalFavorite(v9MmanItem);
                                    } else if (v9MmanItem.getVideoResult() != null) {
                                        presenter.deleteFavorite(v9MmanItem.getVideoResult().getVideoId());
                                    } else {
                                        showMessage(getString(R.string.favorite_info_error), TastyToast.WARNING);
                                    }
                                }
                            });
                }
            }
        });

        // M42：本地收藏模式无远程分页，不启用“加载更多”。
        // T08：pagedLocalList 开启时本地收藏改为游标分页（方案 §5-T08，不全量装载），
        // 重新启用加载更多；flag 关闭回退全量装载旧行为。
        if (!localMode) {
            mUnLimit91Adapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
                @Override
                public void onLoadMoreRequested() {
                    presenter.loadRemoteFavoriteData(false);
                }
            }, recyclerView);
        } else if (com.m3man.perf.PerfFlags.pagedLocalList) {
            mUnLimit91Adapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
                @Override
                public void onLoadMoreRequested() {
                    loadLocalFavoriteMore();
                }
            }, recyclerView);
        }

        helper = new LoadViewHelper(recyclerView);
        helper.setListener(new OnLoadViewListener() {
            @Override
            public void onRetryClick() {
                if (localMode) {
                    loadLocalFavoriteData();
                } else {
                    presenter.loadRemoteFavoriteData(false);
                }
            }
        });
        if (localMode) {
            loadLocalFavoriteData();
        } else {
            boolean needRefresh = presenter.isFavoriteNeedRefresh();
            presenter.loadRemoteFavoriteData(needRefresh);
        }
    }

    @NonNull
    @Override
    public FavoritePresenter createPresenter() {
        return favoritePresenter;
    }


    @Override
    public void setFavoriteData(List<V9MmanItem> v9MmanItemList) {
        presenter.setFavoriteNeedRefresh(false);
        AdapterDiffUtil.apply(mUnLimit91Adapter, v9MmanItemList, AdapterDiffUtil.v9MmanItem());
    }

    @Override
    public void loadMoreDataComplete() {
        mUnLimit91Adapter.loadMoreComplete();
    }

    @Override
    public void loadMoreFailed() {
        showMessage(getString(R.string.favorite_load_more_failed), TastyToast.ERROR);
        mUnLimit91Adapter.loadMoreFail();
    }

    @Override
    public void noMoreData() {
        mUnLimit91Adapter.loadMoreEnd(true);
        showMessage(getString(R.string.favorite_no_more), TastyToast.INFO);
    }

    @Override
    public void setMoreData(List<V9MmanItem> v9MmanItemList) {
        mUnLimit91Adapter.addData(v9MmanItemList);
    }

    @Override
    public void deleteFavoriteSucc(String message) {
        //标志删除失败，下次加载服务器数据，清空缓存
        presenter.setFavoriteNeedRefresh(true);
        dismissDialog();
        showMessage(message, TastyToast.SUCCESS);
    }

    @Override
    public void deleteFavoriteError(String message) {
        dismissDialog();
        showMessage(message, TastyToast.ERROR);
    }

    @Override
    public void showDeleteDialog() {
        deleteAlertDialog.show();
    }

    private void dismissDialog() {
        if (deleteAlertDialog != null && deleteAlertDialog.isShowing() && !isFinishing()) {
            deleteAlertDialog.dismiss();
        }
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
    public void showLoading(boolean pullToRefresh) {
        helper.showLoading();
        LoadHelperUtils.setLoadingText(helper.getLoadIng(), R.id.tv_loading_text, getString(R.string.favorite_loading_hard));
        contentView.setEnabled(false);
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
    public void onRefresh() {
        if (localMode) {
            loadLocalFavoriteData();
        } else {
            presenter.loadRemoteFavoriteData(true);
        }
    }

    /** M42：本地收藏模式加载（全部来源本地收藏，含分分钟，合并展示） */
    private void loadLocalFavoriteData() {
        helper.showLoading();
        LoadHelperUtils.setLoadingText(helper.getLoadIng(), R.id.tv_loading_text, getString(R.string.favorite_loading));
        // T08：游标分页状态复位（新刷新会话）
        localLastId = null;
        localGeneration++;
        localNoMore = false;
        final int gen = localGeneration;
        mDisposables.add(Observable.just(1)
                .map(integer -> {
                    if (com.m3man.perf.PerfFlags.pagedLocalList) {
                        // T08：首页 cursor=null，limit 50（IO 线程）
                        return (Object) presenter.loadLocalFavoritePage(null, LOCAL_PAGE_SIZE);
                    }
                    // flag 关闭：全量装载旧行为（仍在 IO 线程）
                    return (Object) presenter.loadLocalFavoriteItems();
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (gen != localGeneration || isFinishing()) {
                        return; // 过期分页回调不得污染刷新结果
                    }
                    if (result instanceof com.m3man.data.db.Page) {
                        com.m3man.data.db.Page<V9MmanItem> page =
                                (com.m3man.data.db.Page<V9MmanItem>) result;
                        helper.showContent();
                        AdapterDiffUtil.apply(mUnLimit91Adapter, page.items, AdapterDiffUtil.v9MmanItem());
                        if (!page.items.isEmpty()) {
                            V9MmanItem last = page.items.get(page.items.size() - 1);
                            localLastId = last != null ? last.getId() : null;
                        }
                        if (!page.hasMore) {
                            localNoMore = true;
                            mUnLimit91Adapter.loadMoreEnd(true);
                        } else {
                            mUnLimit91Adapter.loadMoreComplete();
                        }
                    } else {
                        @SuppressWarnings("unchecked")
                        List<V9MmanItem> items = (List<V9MmanItem>) result;
                        applyLocalFavoriteItems(items);
                    }
                }, throwable -> {
                    helper.showError();
                    LoadHelperUtils.setErrorText(helper.getLoadError(), R.id.tv_error_text, getString(R.string.favorite_load_failed_retry));
                }));
    }

    /** T08：本地收藏加载更多（游标续页；追加不复刷已有行，不触发封面重载） */
    private void loadLocalFavoriteMore() {
        if (localLoading || localNoMore || !com.m3man.perf.PerfFlags.pagedLocalList) {
            return;
        }
        localLoading = true;
        final int gen = localGeneration;
        final Long cursor = localLastId;
        mDisposables.add(Observable.just(1)
                .map(integer -> presenter.loadLocalFavoritePage(cursor, LOCAL_PAGE_SIZE))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(page -> {
                    localLoading = false;
                    if (gen != localGeneration || isFinishing()) {
                        return; // 刷新期间到达的旧分页丢弃
                    }
                    if (page.items != null && !page.items.isEmpty()) {
                        mUnLimit91Adapter.addData(page.items);
                        V9MmanItem last = page.items.get(page.items.size() - 1);
                        localLastId = last != null ? last.getId() : null;
                    }
                    if (!page.hasMore) {
                        localNoMore = true;
                        mUnLimit91Adapter.loadMoreEnd(true);
                    } else {
                        mUnLimit91Adapter.loadMoreComplete();
                    }
                }, throwable -> {
                    localLoading = false;
                    showMessage(getString(R.string.favorite_load_more_failed), TastyToast.ERROR);
                    mUnLimit91Adapter.loadMoreFail();
                }));
    }

    /** 旧全量路径（flag 关闭时保留）：空态/内容态与原实现一致 */
    private void applyLocalFavoriteItems(List<V9MmanItem> items) {
        if (items == null || items.isEmpty()) {
            helper.showEmpty();
            LoadHelperUtils.setEmptyText(helper.getLoadEmpty(), R.id.tv_empty_info, getString(R.string.favorite_empty_local));
            mUnLimit91Adapter.setNewData(new ArrayList<>());
        } else {
            helper.showContent();
            AdapterDiffUtil.apply(mUnLimit91Adapter, items, AdapterDiffUtil.v9MmanItem());
        }
    }

    /** M42：本地收藏模式删除（仅取消本地收藏标记） */
    private void deleteLocalFavorite(final V9MmanItem item) {
        mDisposables.add(Observable.just(1)
                .map(integer -> presenter.deleteLocalFavorite(item))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(aBoolean -> {
                    if (aBoolean) {
                        showMessage(getString(R.string.favorite_canceled), TastyToast.SUCCESS);
                        loadLocalFavoriteData();
                    } else {
                        showMessage(getString(R.string.favorite_cancel_failed), TastyToast.ERROR);
                    }
                }, throwable -> showMessage(getString(R.string.favorite_cancel_failed), TastyToast.ERROR)));
    }

    @Override
    protected void onDestroy() {
        if (mDisposables != null && !mDisposables.isDisposed()) {
            mDisposables.clear();
        }
        super.onDestroy();
    }
}
