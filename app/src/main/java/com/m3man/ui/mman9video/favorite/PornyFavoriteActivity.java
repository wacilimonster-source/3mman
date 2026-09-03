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
import com.m3man.utils.LoadHelperUtils;
import com.m3man.utils.PlaybackEngine;

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
                // 91porny 为 m3u8 HLS，强制 ExoPlayer
                goToPlayVideo(item, PlaybackEngine.DEFAULT_PLAYER_ENGINE);
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
        recyclerView.setAdapter(mAdapter);

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

    private void loadData() {
        helper.showLoading();
        LoadHelperUtils.setLoadingText(helper.getLoadIng(), R.id.tv_loading_text, "加载中...");
        mDisposables.add(Observable.just(1)
                .map(integer -> presenter.loadLocalFavoriteItems())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(items -> {
                    if (items == null || items.isEmpty()) {
                        helper.showEmpty();
                        LoadHelperUtils.setEmptyText(helper.getLoadEmpty(), R.id.tv_empty_info, "还没有本地收藏，去搜索页收藏吧");
                        mAdapter.setNewData(new ArrayList<>());
                    } else {
                        helper.showContent();
                        mAdapter.setNewData(items);
                    }
                }, throwable -> {
                    helper.showError();
                    LoadHelperUtils.setErrorText(helper.getLoadError(), R.id.tv_error_text, "加载失败，点击重试");
                }));
    }

    private void confirmDelete(final V9MmanItem item) {
        if (item == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("取消收藏")
                .setMessage("确定取消收藏该视频吗？")
                .setPositiveButton("确定", (dialog, which) -> doDelete(item))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doDelete(final V9MmanItem item) {
        mDisposables.add(Observable.just(1)
                .map(integer -> presenter.deleteLocalFavorite(item))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(aBoolean -> {
                    if (aBoolean) {
                        showMessage("已取消收藏", com.sdsmdg.tastytoast.TastyToast.SUCCESS);
                        loadData();
                    } else {
                        showMessage("取消收藏失败", com.sdsmdg.tastytoast.TastyToast.ERROR);
                    }
                }, throwable -> showMessage("取消收藏失败", com.sdsmdg.tastytoast.TastyToast.ERROR)));
    }

    @NonNull
    @Override
    public FavoritePresenter createPresenter() {
        return favoritePresenter;
    }

    // ---- FavoriteView 接口 ----

    @Override
    public void setFavoriteData(List<V9MmanItem> v9MmanItemList) {
        mAdapter.setNewData(v9MmanItemList);
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
        LoadHelperUtils.setLoadingText(helper.getLoadIng(), R.id.tv_loading_text, "加载中...");
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