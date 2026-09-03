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
import com.m3man.utils.DialogUtils;
import com.m3man.utils.LoadHelperUtils;

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

    @Inject
    protected FavoritePresenter favoritePresenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_favorite);
        ButterKnife.bind(this);
        deleteAlertDialog = DialogUtils.initLoadingDialog(this, "删除中，请稍后...");
        localMode = presenter.isLocalFavoriteMode();
        initToolBar(toolbar);
        toolbar.setContentInsetStartWithNavigation(0);

        // Setup contentView == SwipeRefreshView
        contentView.setOnRefreshListener(this);

        List<V9MmanItem> mV9MmanItemList = new ArrayList<>();
        mUnLimit91Adapter = new V9MmanItemAdapter(R.layout.item_right_menu_delete, mV9MmanItemList);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        recyclerView.setAdapter(mUnLimit91Adapter);
        mUnLimit91Adapter.setEmptyView(R.layout.empty_view, recyclerView);

        mUnLimit91Adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                goToPlayVideo((V9MmanItem) adapter.getItem(position),presenter.getPlayBackEngine());
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
                    if (localMode) {
                        // M42：本地收藏模式直接删除本地标记
                        deleteLocalFavorite(v9MmanItem);
                        return;
                    }
                    if (v9MmanItem.getVideoResult() == null) {
                        showMessage("信息错误，无法删除", TastyToast.WARNING);
                        return;
                    }
                    presenter.deleteFavorite(v9MmanItem.getVideoResult().getVideoId());
                }
            }
        });

        // M42：本地收藏模式无远程分页，不启用“加载更多”
        if (!localMode) {
            mUnLimit91Adapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
                @Override
                public void onLoadMoreRequested() {
                    presenter.loadRemoteFavoriteData(false);
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
        mUnLimit91Adapter.setNewData(v9MmanItemList);
    }

    @Override
    public void loadMoreDataComplete() {
        mUnLimit91Adapter.loadMoreComplete();
    }

    @Override
    public void loadMoreFailed() {
        showMessage("加载更多失败", TastyToast.ERROR);
        mUnLimit91Adapter.loadMoreFail();
    }

    @Override
    public void noMoreData() {
        mUnLimit91Adapter.loadMoreEnd(true);
        showMessage("没有更多数据了", TastyToast.INFO);
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
        LoadHelperUtils.setLoadingText(helper.getLoadIng(), R.id.tv_loading_text, "加载中...");
        mDisposables.add(Observable.just(1)
                .map(integer -> presenter.loadLocalFavoriteItems())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(items -> {
                    if (items == null || items.isEmpty()) {
                        helper.showEmpty();
                        LoadHelperUtils.setEmptyText(helper.getLoadEmpty(), R.id.tv_empty_info, "还没有本地收藏，去收藏喜欢的视频吧");
                        mUnLimit91Adapter.setNewData(new ArrayList<>());
                    } else {
                        helper.showContent();
                        mUnLimit91Adapter.setNewData(items);
                    }
                }, throwable -> {
                    helper.showError();
                    LoadHelperUtils.setErrorText(helper.getLoadError(), R.id.tv_error_text, "加载失败，点击重试");
                }));
    }

    /** M42：本地收藏模式删除（仅取消本地收藏标记） */
    private void deleteLocalFavorite(final V9MmanItem item) {
        mDisposables.add(Observable.just(1)
                .map(integer -> presenter.deleteLocalFavorite(item))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(aBoolean -> {
                    if (aBoolean) {
                        showMessage("已取消收藏", TastyToast.SUCCESS);
                        loadLocalFavoriteData();
                    } else {
                        showMessage("取消收藏失败", TastyToast.ERROR);
                    }
                }, throwable -> showMessage("取消收藏失败", TastyToast.ERROR)));
    }

    @Override
    protected void onDestroy() {
        if (mDisposables != null && !mDisposables.isDisposed()) {
            mDisposables.clear();
        }
        super.onDestroy();
    }
}
