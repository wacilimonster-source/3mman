package com.m3man.ui.mman9video.author;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.Toolbar;
import android.view.View;

import com.aitsuki.swipe.SwipeItemLayout;
import com.aitsuki.swipe.SwipeMenuRecyclerView;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.helper.loadviewhelper.help.OnLoadViewListener;
import com.helper.loadviewhelper.load.LoadViewHelper;
import com.m3man.R;
import com.m3man.constants.Keys;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.AuthorFavorite;
import com.m3man.ui.BaseAppCompatActivity;
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
 * 作者收藏列表页。
 * 点击进入对应作者的视频列表页（AuthorActivity）；左滑 / 长按取消收藏。
 */
public class AuthorFavoriteActivity extends BaseAppCompatActivity {

    @BindView(R.id.toolbar)
    Toolbar toolbar;
    @BindView(R.id.recyclerView)
    SwipeMenuRecyclerView recyclerView;

    private AuthorFavoriteAdapter mAdapter;
    private LoadViewHelper helper;

    /** R3：列表加载 / 删除的裸订阅统一回收（onResume 会反复订阅，必须释放） */
    private final io.reactivex.disposables.CompositeDisposable mDisposables = new io.reactivex.disposables.CompositeDisposable();

    @Inject
    protected DataManager dataManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_author_favorite);
        ButterKnife.bind(this);
        initToolBar(toolbar);
        toolbar.setTitle(R.string.author_favorite);

        mAdapter = new AuthorFavoriteAdapter();
        mAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                AuthorFavorite item = (AuthorFavorite) adapter.getData().get(position);
                Intent intent = new Intent(AuthorFavoriteActivity.this, AuthorActivity.class);
                intent.putExtra(Keys.KEY_INTENT_UID, item.getAuthorKey());
                intent.putExtra(Keys.KEY_INTENT_SOURCE, item.getSource());
                intent.putExtra(Keys.KEY_INTENT_AUTHOR_NAME, item.getAuthorName());
                startActivityWithAnimation(intent);
            }
        });
        mAdapter.setOnItemChildClickListener(new BaseQuickAdapter.OnItemChildClickListener() {
            @Override
            public void onItemChildClick(BaseQuickAdapter adapter, View view, int position) {
                if (view.getId() == R.id.right_menu_delete) {
                    SwipeItemLayout swipeItemLayout = (SwipeItemLayout) view.getParent();
                    swipeItemLayout.close();
                    AuthorFavorite item = (AuthorFavorite) adapter.getData().get(position);
                    confirmDelete(item);
                }
            }
        });
        mAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                AuthorFavorite item = (AuthorFavorite) adapter.getData().get(position);
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
                .map(integer -> dataManager.loadAuthorFavorites())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(items -> {
                    if (items == null || items.isEmpty()) {
                        helper.showEmpty();
                        LoadHelperUtils.setEmptyText(helper.getLoadEmpty(), R.id.tv_empty_info, "还没有收藏作者，去作者页收藏吧");
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

    private void confirmDelete(final AuthorFavorite item) {
        if (item == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("取消收藏")
                .setMessage("确定取消收藏该作者吗？")
                .setPositiveButton("确定", (dialog, which) -> doDelete(item))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doDelete(final AuthorFavorite item) {
        mDisposables.add(Observable.just(1)
                .map(integer -> {
                    dataManager.deleteAuthorFavorite(item);
                    return true;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(aBoolean -> {
                    showMessage("已取消收藏", com.sdsmdg.tastytoast.TastyToast.SUCCESS);
                    loadData();
                }, throwable -> showMessage("取消收藏失败", com.sdsmdg.tastytoast.TastyToast.ERROR)));
    }

    @Override
    public void showMessage(String msg, int type) {
        super.showMessage(msg, type);
    }

    @Override
    protected void onDestroy() {
        // R3：释放裸订阅
        if (mDisposables != null && !mDisposables.isDisposed()) {
            mDisposables.clear();
        }
        super.onDestroy();
    }
}
