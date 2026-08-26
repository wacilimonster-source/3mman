package com.m3man.ui.mman9video.author;

import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.helper.loadviewhelper.help.OnLoadViewListener;
import com.helper.loadviewhelper.load.LoadViewHelper;
import com.orhanobut.logger.Logger;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.R;
import com.m3man.adapter.V91MmanAdapter;
import com.m3man.constants.Keys;
import com.m3man.data.db.entity.AuthorFavorite;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.parser.Parse91PornyVideo;
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
 * 作者视频列表页（视频源与分分钟源通用）。
 * <p>
 * 入口：
 * 1. 视频源作者（uid + 默认 source=mman9）
 * 2. 分分钟作者（authorKey + source=91porny），由作者收藏列表页跳转而来
 * <p>
 * 顶部菜单提供 ⭐ 收藏作者按钮，可切换该作者的收藏状态。
 *
 * @author flymegoc
 */
public class AuthorActivity extends MvpActivity<AuthorView, AuthorPresenter> implements AuthorView {

    @BindView(R.id.toolbar)
    Toolbar toolbar;
    @BindView(R.id.recyclerView)
    RecyclerView recyclerView;
    @BindView(R.id.swipe_layout)
    SwipeRefreshLayout swipeLayout;
    private V91MmanAdapter mV91MmanAdapter;
    private LoadViewHelper helper;
    private String uid;
    private String source;
    private String authorName;
    /** M92g：收藏行携带的关联作品 viewKey（UID 过期自愈用，可空） */
    private String lastViewKey;
    /** M92g：自愈状态——每次数据会话只自动尝试一次 */
    private boolean healingUid;
    private boolean isFavorited;

    /** R2：收藏相关裸订阅统一回收 */
    private final io.reactivex.disposables.CompositeDisposable mDisposables = new io.reactivex.disposables.CompositeDisposable();

    @Inject
    protected AuthorPresenter authorPresenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_author);
        ButterKnife.bind(this);
        initToolBar(toolbar);
        uid = getIntent().getStringExtra(Keys.KEY_INTENT_UID);
        source = getIntent().getStringExtra(Keys.KEY_INTENT_SOURCE);
        if (TextUtils.isEmpty(source)) {
            source = AuthorFavorite.SOURCE_MMAN9;
        }
        if (TextUtils.isEmpty(uid)) {
            showMessage("用户信息错误，无法获取数据", TastyToast.ERROR);
            //C12：参数缺失时必须结束页面，否则界面处于未初始化状态，后续回调会空指针崩溃
            finish();
            return;
        }
        authorName = getIntent().getStringExtra(Keys.KEY_INTENT_AUTHOR_NAME);
        if (TextUtils.isEmpty(authorName)) {
            authorName = uid;
        }
        lastViewKey = getIntent().getStringExtra(Keys.KEY_INTENT_AUTHOR_LAST_VIEW_KEY);
        toolbar.setTitle(authorName);
        initFavoriteMenu();
        init();
    }

    private void initFavoriteMenu() {
        toolbar.inflateMenu(R.menu.menu_author);
        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.menu_favorite_author) {
                    toggleFavorite();
                    return true;
                }
                return false;
            }
        });
        // 初始收藏态需要从数据库读取（IO 线程）
        mDisposables.add(Observable.just(1)
                .map(integer -> presenter.isAuthorFavorited(uid, source))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(fav -> {
                    isFavorited = fav;
                    updateFavoriteMenu();
                }, throwable -> Logger.t("AuthorActivity").e(throwable, "读取作者收藏态失败")));
    }

    private void updateFavoriteMenu() {
        MenuItem item = toolbar.getMenu().findItem(R.id.menu_favorite_author);
        if (item != null) {
            item.setTitle(isFavorited ? "★" : "☆");
        }
    }

    private void toggleFavorite() {
        mDisposables.add(Observable.just(1)
                .map(integer -> {
                    // 实时查库，避免收藏态未刷新完成时点击造成的重复 / 漏删
                    boolean currentlyFav = presenter.isAuthorFavorited(uid, source);
                    if (currentlyFav) {
                        presenter.removeAuthorFavorite(uid, source);
                    } else {
                        presenter.addAuthorFavorite(uid, authorName, source);
                    }
                    return !currentlyFav;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(newState -> {
                    isFavorited = newState;
                    updateFavoriteMenu();
                    showMessage(isFavorited ? "已收藏作者" : "已取消收藏作者",
                            isFavorited ? TastyToast.SUCCESS : TastyToast.DEFAULT);
                }, throwable -> showMessage("操作失败，请重试", TastyToast.ERROR)));
    }

    /**
     * M66b：来源判定加 UID 形态防御。9mman 作者 UID 是长 Base64ish 串（常含 _ 或 -，
     * 长度远超 porny 的短 slug 作者名），历史数据可能把 source 标错；
     * 形态与标记冲突时以形态为准，避免把 9mman UID 拿去请求 porny /author/ 而 404。
     */
    private boolean isPorny() {
        boolean bySource = AuthorFavorite.SOURCE_PORNY.equals(source);
        if (TextUtils.isEmpty(uid)) {
            return bySource;
        }
        boolean looksLikeMmanUid = uid.contains("_") || uid.contains("-") || uid.length() > 32;
        if (looksLikeMmanUid) {
            return false;
        }
        return bySource;
    }

    private int pickEngine() {
        // 分分钟为 m3u8 HLS，强制 ExoPlayer；视频源用用户偏好引擎
        return isPorny() ? PlaybackEngine.DEFAULT_PLAYER_ENGINE : presenter.getPlayBackEngine();
    }

    private void init() {
        swipeLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                if (isPorny()) {
                    presenter.pornyAuthorVideos(uid, true);
                } else {
                    presenter.authorVideos(uid, true);
                }
            }
        });
        swipeLayout.setEnabled(false);
        mV91MmanAdapter = new V91MmanAdapter(R.layout.item_v_9mman);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(mV91MmanAdapter);

        mV91MmanAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                V9MmanItem v9MmanItems = (V9MmanItem) adapter.getData().get(position);
                Intent intent = PlaybackEngine.getPlaybackEngineIntent(AuthorActivity.this, pickEngine());
                intent.putExtra(Keys.KEY_INTENT_V9MMAN_ITEM, v9MmanItems);
                startActivityWithAnimation(intent);
            }
        });
        mV91MmanAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                if (isPorny()) {
                    presenter.pornyAuthorVideos(uid, false);
                } else {
                    presenter.authorVideos(uid, false);
                }
            }
        }, recyclerView);

        helper = new LoadViewHelper(recyclerView);
        helper.setListener(new OnLoadViewListener() {
            @Override
            public void onRetryClick() {
                swipeLayout.setEnabled(false);
                if (isPorny()) {
                    presenter.pornyAuthorVideos(uid, true);
                } else {
                    presenter.authorVideos(uid, true);
                }
            }
        });
        if (isPorny()) {
            presenter.pornyAuthorVideos(uid, false);
        } else {
            presenter.authorVideos(uid, false);
        }
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
        swipeLayout.setEnabled(true);
        swipeLayout.setRefreshing(false);
        healingUid = false;
    }

    @Override
    public void showLoading(boolean pullToRefresh) {
        if (helper == null) {
            return;
        }
        helper.showLoading();
        LoadHelperUtils.setLoadingText(helper.getLoadIng(), R.id.tv_loading_text, "加载中，请稍候...");
    }

    @Override
    public void showContent() {
        if (helper == null || mV91MmanAdapter == null) {
            return;
        }
        helper.showContent();
        if (mV91MmanAdapter.getData().size() == 0) {
            helper.showEmpty();
            LoadHelperUtils.setEmptyText(helper.getLoadEmpty(), R.id.tv_empty_info, "暂无数据");
        }
    }

    @Override
    public void showMessage(String msg, int type) {
        super.showMessage(msg, type);
    }

    @Override
    public void showError(String message) {
        showMessage(message, TastyToast.ERROR);
        tryHealStaleUid();
        if (helper == null) {
            return;
        }
        helper.showError();
        LoadHelperUtils.setErrorText(helper.getLoadError(), R.id.tv_error_text, "加载数据失败了，点击重试");
    }

    /**
     * M92g：mman9 作者 UID 是加密临时 token，收藏行的旧 token 请求 uvideos.php 会 404。
     * 若收藏行携带了关联作品的 viewKey，则重拉该作品详情换取新 ownerId 并重试（仅一次）。
     */
    @Override
    public void onAuthorUidHealed(String newUid) {
        if (TextUtils.isEmpty(newUid)) {
            return;
        }
        String oldUid = uid;
        uid = newUid;
        if (TextUtils.isEmpty(authorName) || authorName.equals(oldUid)) {
            authorName = newUid;
            toolbar.setTitle(authorName);
        }
        // 后续刷新/加载更多/收藏均使用新 UID；收藏态由 toggleFavorite 实时查库确认。
        updateFavoriteMenu();
    }

    private void tryHealStaleUid() {
        if (healingUid || isPorny() || TextUtils.isEmpty(lastViewKey)) {
            return;
        }
        healingUid = true;
        swipeLayout.setRefreshing(true);
        presenter.reloadOwnerFromViewKey(lastViewKey, uid, source, false);
    }

    @Override
    protected void onDestroy() {
        // R2：释放收藏相关裸订阅
        if (mDisposables != null && !mDisposables.isDisposed()) {
            mDisposables.clear();
        }
        super.onDestroy();
    }
}
