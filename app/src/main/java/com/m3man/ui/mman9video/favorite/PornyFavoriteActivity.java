package com.m3man.ui.mman9video.favorite;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.appcompat.widget.Toolbar;
import android.view.View;

import com.aitsuki.swipe.SwipeItemLayout;
import com.aitsuki.swipe.SwipeMenuRecyclerView;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.helper.loadviewhelper.help.OnLoadViewListener;
import com.helper.loadviewhelper.load.LoadViewHelper;
import com.m3man.R;
import com.m3man.adapter.V91MmanAdapter;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.MvpActivity;
import com.m3man.utils.AdapterDiffUtil;
import com.m3man.utils.LoadHelperUtils;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

/**
 * 分分钟（91porny）本地收藏页。
 *
 * 91porny 无账号体系，收藏直接落本地数据库（V9MmanItem.isLocalFavorite = true）。
 * 支持：点击播放（强制 ExoPlayer）、左滑/长按删除收藏。
 */
public class PornyFavoriteActivity extends MvpActivity<FavoriteView, FavoritePresenter> implements FavoriteView {

    @BindView(R.id.toolbar)
    Toolbar toolbar;
    @BindView(R.id.recyclerView)
    SwipeMenuRecyclerView recyclerView;

    private V91MmanAdapter mAdapter;
    private LoadViewHelper helper;

    /** R4：列表加载 / 删除的裸订阅统一回收（onResume 会反复订阅，必须释放） */
    private final io.reactivex.disposables.CompositeDisposable mDisposables = new io.reactivex.disposables.CompositeDisposable();

    @Inject
    protected FavoritePresenter favoritePresenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_porny_favorite);
        ButterKnife.bind(this);
        initToolBar(toolbar);
        toolbar.setTitle(R.string.porny_local_favorite);

        mAdapter = new V91MmanAdapter(R.layout.item_right_menu_delete);
        mAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                V9MmanItem item = (V9MmanItem) adapter.getData().get(position);
                View coverView = view.findViewById(R.id.iv_91mman_item_img);
                // 91porny 为 m3u8 HLS，强制 ExoPlayer
                goToPlayVideo(item, coverView);
            }
        });
        // 左滑菜单中的删除
        mAdapter.setOnItemChildClickListener(new BaseQuickAdapter.OnItemChildClickListener() {
            @Override
            public void onItemChildClick(BaseQuickAdapter adapter, View view, int position) {
                if (view.getId() == R.id.right_menu_delete) {
                    SwipeItemLayout swipeItemLayout = (SwipeItemLayout) view.getParent();
                    swipeItemLayout.close();
                    V9MmanItem item = (V9MmanItem) adapter.getData().get(position);
                    confirmDelete(item);
                }
            }
        });
        // 长按删除
        mAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                V9MmanItem item = (V9MmanItem) adapter.getData().get(position);
                confirmDelete(item);
                return true;
            }
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setHasFixedSize(true);
        recyclerView.setAdapter(mAdapter);
        // T08：pagedLocalList 开启时本地收藏游标分页（不全量装载）；flag 关闭回退全量旧行为
        if (com.m3man.perf.PerfFlags.pagedLocalList) {
            mAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
                @Override
                public void onLoadMoreRequested() {
                    loadMore();
                }
            }, recyclerView);
        }

        helper = new LoadViewHelper(recyclerView);
        helper.setListener(new OnLoadViewListener() {
            @Override
            public void onRetryClick() {
                loadData();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    // T08：本地收藏游标分页状态（pagedLocalList 开启时生效；主线程串行访问）
    private static final int PAGE_SIZE = 50;
    private Long lastId;
    private boolean loading;
    private boolean noMore;
    /** 刷新代际：刷新/删除后，在途的 loadMore 结果直接丢弃，避免旧游标数据追加到新列表。 */
    private int loadGeneration;

    private void loadData() {
        helper.showLoading();
        LoadHelperUtils.setLoadingText(helper.getLoadIng(), R.id.tv_loading_text, getString(R.string.porny_fav_loading));
        loadGeneration++;
        loading = false;
        lastId = null;
        noMore = false;
        final int generation = loadGeneration;
        mDisposables.add(Observable.just(1)
                .map(integer -> {
                    if (com.m3man.perf.PerfFlags.pagedLocalList) {
                        // T08：首页游标分页（IO 线程）
                        return (Object) presenter.loadLocalFavoritePage(null, PAGE_SIZE);
                    }
                    return (Object) presenter.loadLocalFavoriteItems();
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (generation != loadGeneration) {
                        return;
                    }
                    if (result instanceof com.m3man.data.db.Page) {
                        @SuppressWarnings("unchecked")
                        com.m3man.data.db.Page<V9MmanItem> page =
                                (com.m3man.data.db.Page<V9MmanItem>) result;
                        helper.showContent();
                        AdapterDiffUtil.apply(mAdapter, page.items, AdapterDiffUtil.v9MmanItem());
                        if (!page.items.isEmpty()) {
                            V9MmanItem last = page.items.get(page.items.size() - 1);
                            lastId = last != null ? last.getId() : null;
                        }
                        if (!page.hasMore) {
                            noMore = true;
                            mAdapter.loadMoreEnd(true);
                        } else {
                            mAdapter.loadMoreComplete();
                        }
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<V9MmanItem> items = (List<V9MmanItem>) result;
                    if (items == null || items.isEmpty()) {
                        helper.showEmpty();
                        LoadHelperUtils.setEmptyText(helper.getLoadEmpty(), R.id.tv_empty_info, getString(R.string.porny_fav_empty));
                        mAdapter.setNewData(new ArrayList<>());
                    } else {
                        helper.showContent();
                        AdapterDiffUtil.apply(mAdapter, items, AdapterDiffUtil.v9MmanItem());
                    }
                }, throwable -> {
                    if (generation != loadGeneration) {
                        return;
                    }
                    helper.showError();
                    LoadHelperUtils.setErrorText(helper.getLoadError(), R.id.tv_error_text, getString(R.string.porny_fav_load_failed_retry));
                }));
    }

    /** T08：游标续页（追加不复刷已有行） */
    private void loadMore() {
        if (loading || noMore || !com.m3man.perf.PerfFlags.pagedLocalList) {
            return;
        }
        loading = true;
        final Long cursor = lastId;
        final int generation = loadGeneration;
        mDisposables.add(Observable.just(1)
                .map(integer -> presenter.loadLocalFavoritePage(cursor, PAGE_SIZE))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(page -> {
                    if (generation != loadGeneration) {
                        return;
                    }
                    loading = false;
                    if (isFinishing()) {
                        return;
                    }
                    if (page.items != null && !page.items.isEmpty()) {
                        mAdapter.addData(page.items);
                        V9MmanItem last = page.items.get(page.items.size() - 1);
                        lastId = last != null ? last.getId() : null;
                    }
                    if (!page.hasMore) {
                        noMore = true;
                        mAdapter.loadMoreEnd(true);
                    } else {
                        mAdapter.loadMoreComplete();
                    }
                }, throwable -> {
                    if (generation != loadGeneration) {
                        return;
                    }
                    loading = false;
                    mAdapter.loadMoreFail();
                }));
    }

    private void confirmDelete(final V9MmanItem item) {
        if (item == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.porny_fav_cancel_title))
                .setMessage(getString(R.string.porny_fav_cancel_msg))
                .setPositiveButton(getString(R.string.sure), (dialog, which) -> doDelete(item))
                .setNegativeButton(getString(R.string.common_cancel), null)
                .show();
    }

    private void doDelete(final V9MmanItem item) {
        mDisposables.add(Observable.just(1)
                .map(integer -> presenter.deleteLocalFavorite(item))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(aBoolean -> {
                    if (aBoolean) {
                        showMessage(getString(R.string.porny_fav_canceled), com.sdsmdg.tastytoast.TastyToast.SUCCESS);
                        loadData();
                    } else {
                        showMessage(getString(R.string.porny_fav_cancel_failed), com.sdsmdg.tastytoast.TastyToast.ERROR);
                    }
                }, throwable -> showMessage(getString(R.string.porny_fav_cancel_failed), com.sdsmdg.tastytoast.TastyToast.ERROR)));
    }

    @NonNull
    @Override
    public FavoritePresenter createPresenter() {
        return favoritePresenter;
    }

    // ---- FavoriteView 接口 ----

    @Override
    public void setFavoriteData(List<V9MmanItem> v9MmanItemList) {
        AdapterDiffUtil.apply(mAdapter, v9MmanItemList, AdapterDiffUtil.v9MmanItem());
        helper.showContent();
    }

    @Override
    public void loadMoreDataComplete() {
    }

    @Override
    public void loadMoreFailed() {
    }

    @Override
    public void noMoreData() {
    }

    @Override
    public void setMoreData(List<V9MmanItem> v9MmanItemList) {
    }

    @Override
    public void deleteFavoriteSucc(String message) {
        showMessage(message, com.sdsmdg.tastytoast.TastyToast.SUCCESS);
        loadData();
    }

    @Override
    public void deleteFavoriteError(String message) {
        showMessage(message, com.sdsmdg.tastytoast.TastyToast.ERROR);
    }

    @Override
    public void showDeleteDialog() {
    }

    @Override
    public void showError(String message) {
        showMessage(message, com.sdsmdg.tastytoast.TastyToast.ERROR);
    }

    @Override
    public void showContent() {
        helper.showContent();
    }

    @Override
    public void showLoading(boolean pullToRefresh) {
        helper.showLoading();
        LoadHelperUtils.setLoadingText(helper.getLoadIng(), R.id.tv_loading_text, getString(R.string.porny_fav_loading));
    }

    @Override
    public void showMessage(String msg, int type) {
        super.showMessage(msg, type);
    }

    @Override
    protected void onDestroy() {
        // R4：释放裸订阅
        if (mDisposables != null && !mDisposables.isDisposed()) {
            mDisposables.clear();
        }
        super.onDestroy();
    }
}