package com.m3man.ui.mman9video.author;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
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
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.model.BaseResult;
import com.m3man.ui.BaseAppCompatActivity;
import com.m3man.utils.LoadHelperUtils;

import java.util.ArrayList;
import java.util.Date;
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
 * <p>
 * 进入页面后静默刷新每个作者的摘要（作品数 / 最新作品标识 / 封面）：
 * 6 小时节流、并发上限 2、单作者失败静默跳过，不阻塞列表展示。
 */
public class AuthorFavoriteActivity extends BaseAppCompatActivity {

    /** 摘要刷新节流间隔 */
    private static final long REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000;
    /** 摘要刷新并发上限（避免一次打 N 个请求触发风控） */
    private static final int REFRESH_CONCURRENCY = 2;

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
                clearNewBadge(item, position);
                Intent intent = new Intent(AuthorFavoriteActivity.this, AuthorActivity.class);
                intent.putExtra(Keys.KEY_INTENT_UID, item.getAuthorKey());
                intent.putExtra(Keys.KEY_INTENT_SOURCE, item.getSource());
                intent.putExtra(Keys.KEY_INTENT_AUTHOR_NAME, item.getAuthorName());
                // M92g：带上关联作品的 viewKey，作者页 UID 过期 404 时可自愈
                if (!TextUtils.isEmpty(item.getLastViewKey())) {
                    intent.putExtra(Keys.KEY_INTENT_AUTHOR_LAST_VIEW_KEY, item.getLastViewKey());
                }
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

    /** 是否已完成过一次加载（决定 onResume 重查是否显示加载圈） */
    private boolean firstLoadDone;

    private void loadData() {
        // M92h：返回本页的静默重查不再闪全屏加载圈（作者页内可切换收藏，数据仍需刷新）
        if (firstLoadDone) {
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
                            refreshAuthorSummaries(items);
                        }
                    }, throwable -> { /* 静默失败：保留旧列表 */ }));
            return;
        }
        helper.showLoading();
        LoadHelperUtils.setLoadingText(helper.getLoadIng(), R.id.tv_loading_text, "加载中...");
        mDisposables.add(Observable.just(1)
                .map(integer -> dataManager.loadAuthorFavorites())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(items -> {
                    firstLoadDone = true;
                    if (items == null || items.isEmpty()) {
                        helper.showEmpty();
                        LoadHelperUtils.setEmptyText(helper.getLoadEmpty(), R.id.tv_empty_info, "还没有收藏作者，去作者页收藏吧");
                        mAdapter.setNewData(new ArrayList<>());
                    } else {
                        helper.showContent();
                        mAdapter.setNewData(items);
                        // 列表展示后静默刷新摘要（节流 + 限并发，不阻塞 UI）
                        refreshAuthorSummaries(items);
                    }
                }, throwable -> {
                    helper.showError();
                    LoadHelperUtils.setErrorText(helper.getLoadError(), R.id.tv_error_text, "加载失败，点击重试");
                }));
    }

    // ==================== 摘要静默刷新 ====================

    /** 对过期条目（超 6 小时未刷新）并发上限 2 地刷新摘要，失败静默跳过 */
    private void refreshAuthorSummaries(List<AuthorFavorite> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        mDisposables.add(Observable.fromIterable(items)
                .flatMap(this::refreshOneAuthor, REFRESH_CONCURRENCY)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onSummaryUpdated,
                        throwable -> { /* 单条失败已在内部消化，整体失败同样不影响列表 */ }));
    }

    /** 刷新单个作者；节流期内返回 empty，请求失败返回 empty（不更新任何字段） */
    private Observable<AuthorFavorite> refreshOneAuthor(AuthorFavorite fav) {
        Date last = fav.getLastRefreshTime();
        if (last != null && System.currentTimeMillis() - last.getTime() < REFRESH_INTERVAL_MS) {
            return Observable.empty();
        }
        boolean porny = AuthorFavorite.SOURCE_PORNY.equals(fav.getSource());
        Observable<BaseResult<List<V9MmanItem>>> firstPage = porny
                ? dataManager.loadPornyAuthorVideos(fav.getAuthorKey(), 1)
                : dataManager.loadMman9authorVideos(fav.getAuthorKey(), "public", 1, false);
        return firstPage
                .subscribeOn(Schedulers.io())
                .map(base -> applyFirstPage(fav, base))
                .onErrorReturn(throwable -> null)
                .filter(updated -> updated != null);
    }

    /**
     * 用首页结果回填摘要：作品数（末页精确 / 失败估算）、最新作品变更检测、封面。
     * 必须运行在 IO 线程（内含 DB 写与可能的末页网络请求）。
     */
    private AuthorFavorite applyFirstPage(AuthorFavorite fav, BaseResult<List<V9MmanItem>> base) {
        List<V9MmanItem> list = base.getData();
        Integer totalPageObj = base.getTotalPage();
        int totalPage = totalPageObj == null ? 1 : Math.max(1, totalPageObj);

        if (list == null || list.isEmpty()) {
            // M92：空列表可能是 UID token 过期导致的 404 拦截页（解析出空），
            // 不能落库成「0部作品」；抛错走 onErrorReturn 跳过，保持旧摘要，下个节流周期再试
            throw new IllegalStateException("作者作品列表为空（UID 可能已过期）");
        }

        V9MmanItem top = list.get(0);
        int pageSize = list.size();
        int count;
        if (totalPage <= 1) {
            count = pageSize;
        } else {
            boolean porny = AuthorFavorite.SOURCE_PORNY.equals(fav.getSource());
            Observable<BaseResult<List<V9MmanItem>>> lastPage = porny
                    ? dataManager.loadPornyAuthorVideos(fav.getAuthorKey(), totalPage)
                    : dataManager.loadMman9authorVideos(fav.getAuthorKey(), "public", totalPage, false);
            int estimate;
            try {
                BaseResult<List<V9MmanItem>> lastBase = lastPage.blockingSingle();
                List<V9MmanItem> lastList = lastBase != null ? lastBase.getData() : null;
                int lastSize = (lastList == null || lastList.isEmpty()) ? pageSize : lastList.size();
                count = (totalPage - 1) * pageSize + lastSize;
                estimate = 0;
            } catch (Exception e) {
                count = 0;
                estimate = totalPage * pageSize;
            }
            if (count <= 0) {
                count = estimate;
            }
        }

        // 变更检测：最新一部作品的标识变了 → 记为「有更新」
        String newTopKey = top.getViewKey();
        String oldTopKey = fav.getTopViewKey();
        if (!TextUtils.isEmpty(newTopKey) && oldTopKey != null && !oldTopKey.equals(newTopKey)) {
            fav.setLastNewTime(new Date());
            fav.setHasNew(true);
        }

        fav.setVideoCount(count);
        if (!TextUtils.isEmpty(newTopKey)) {
            fav.setTopViewKey(newTopKey);
            // M92g：顺手回填自愈用 viewKey（首页第一条即该作者作品，老收藏首次刷新即获得自愈能力）
            fav.setLastViewKey(newTopKey);
        }
        if (!TextUtils.isEmpty(top.getImgUrl())) {
            fav.setCoverUrl(top.getImgUrl());
        }
        fav.setLastRefreshTime(new Date());
        dataManager.updateAuthorFavorite(fav);
        return fav;
    }

    /** 主线程：把刷新完的实体替换进适配器并局部刷新对应行 */
    private void onSummaryUpdated(AuthorFavorite updated) {
        List<AuthorFavorite> data = mAdapter.getData();
        for (int i = 0; i < data.size(); i++) {
            AuthorFavorite cur = data.get(i);
            if (cur.getId() != null && cur.getId().equals(updated.getId())) {
                data.set(i, updated);
                mAdapter.notifyItemChanged(i);
                break;
            }
        }
    }

    /** 进入作者页时清除 NEW 角标（立即更新 UI，DB 异步落库） */
    private void clearNewBadge(AuthorFavorite item, int position) {
        if (item == null || !Boolean.TRUE.equals(item.getHasNew())) {
            return;
        }
        item.setHasNew(false);
        mAdapter.notifyItemChanged(position);
        mDisposables.add(Observable.just(1)
                .map(integer -> {
                    dataManager.updateAuthorFavorite(item);
                    return true;
                })
                .subscribeOn(Schedulers.io())
                .subscribe(aBoolean -> { },
                        throwable -> { /* 角标清除失败不影响跳转 */ }));
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
