package com.m3man.ui.search;

import android.content.DialogInterface;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import com.m3man.R;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.AutoCompleteEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 可复用的搜索历史面板：绑定一个容器布局（layout_search_history），负责
 * 加载/展示历史、点击回填、长按删除、清空全部。三个搜索入口共用本类，
 * 避免在三套搜索 Activity/Fragment 里重复历史逻辑。
 *
 * 数据落在 GreenDAO 的 AutoCompleteEntity 表（type = TYPE_SEARCH_HISTORY），
 * 复用已有的 saveAutoComplete 写入（自动去重 + 更新时间），读取按最近使用倒序。
 */
public class SearchHistoryPanel {

    public interface OnHistoryItemClickListener {
        /** 点击某条历史：宿主应把关键词填回搜索框并发起搜索 */
        void onHistoryItemClick(String keyword);
    }

    private static final int TYPE = AutoCompleteEntity.TYPE_SEARCH_HISTORY;
    private static final int LIMIT = 30;

    private final View container;
    private final RecyclerView rv;
    private final TextView tvClear;
    private final SearchHistoryAdapter adapter;
    private final DataManager dm;
    private final OnHistoryItemClickListener clickListener;
    private final SearchView searchView;
    /**
     * M154：show / showSuggestions 的异步展示订阅。宿主在点击历史条目时会先
     * setQuery（触发 onQueryTextChange → showSuggestions 发起异步 DB 查询）
     * 再同步调 hide()；异步回调晚于 hide() 落地时会把面板重新置顶盖住搜索结果
     * （表现为「底部分页栏出现了，但列表没有内容」）。hide() 时取消这些在途
     * 回调，保证面板一旦被主动隐藏就不会自己复活。
     */
    private final io.reactivex.disposables.CompositeDisposable panelSubscriptions =
            new io.reactivex.disposables.CompositeDisposable();

    public SearchHistoryPanel(View container, DataManager dm, OnHistoryItemClickListener listener) {
        this(container, dm, listener, null);
    }

    public SearchHistoryPanel(View container, DataManager dm, OnHistoryItemClickListener listener, SearchView searchView) {
        this.container = container;
        this.dm = dm;
        this.clickListener = listener;
        this.searchView = searchView;

        this.rv = container.findViewById(R.id.rv_search_history);
        this.tvClear = container.findViewById(R.id.tv_clear_search_history);

        this.adapter = new SearchHistoryAdapter();
        this.rv.setLayoutManager(new LinearLayoutManager(container.getContext()));
        rv.setHasFixedSize(true);
        this.rv.setNestedScrollingEnabled(false);
        this.rv.setAdapter(adapter);

        // v1.0.107：改用适配器自带的手动点击绑定，避免 BaseQuickAdapter 内部
        // setOnItemClickListener 在部分场景不生效导致"点击历史无反应"
        this.adapter.setOnItemActionListener(new SearchHistoryAdapter.OnItemActionListener() {
            @Override
            public void onItemClick(String keyword) {
                if (clickListener != null && !TextUtils.isEmpty(keyword)) {
                    clickListener.onHistoryItemClick(keyword);
                }
            }

            @Override
            public void onItemLongClick(String keyword) {
                if (!TextUtils.isEmpty(keyword)) {
                    confirmRemove(keyword);
                }
            }
        });
        this.tvClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearAll();
            }
        });
        // 关键：M-fix 把 layout_search_history 重新置顶。
        // 根因：SearchPornyFragment 的 FrameLayout 中，LoadViewHelper 注入的空状态
        // 容器（loading/error/empty，VISIBLE+CLICKABLE+match_parent）会在运行时 add 到
        // history include 之后，绘制与触摸分发都在 history 之上。用户点「清空」/历史条目
        // 时 touch 全部被空状态容器吃掉，表现为「清空无效 + 点击历史不搜索」。
        // bringToFront() 把 history 容器移到父 FrameLayout 子 View 列表末尾，重新成为
        // 最上层，触摸优先到 history（itemView / tvClear 的 click 触发回调）。
        if (container != null) {
            container.bringToFront();
        }
    }

    /** 加载并展示历史；无历史时自动隐藏。M73：查询切 IO 线程后回主线程更新 UI */
    public void show() {
        // M109-fix：绝不能再动 SearchView！v1.0.105 在这里加的 setIconified(true) 与
        // 宿主 onClose()→show() 形成无限递归：点 X / 清空输入 / onResume 收起搜索框时，
        // setIconified(true) 触发 onCloseClicked→onClose()→show()→setIconified(true)→……
        // 直到 StackOverflowError 闪退（清空搜索 / 反复按删除键 / 搜索结果返回均触发）。
        // 历史面板本身 match_parent 全屏覆盖在结果层之上，不需要收起搜索框来防拦截。
        // 另外收起动作会清掉用户刚输入/回填的关键词，也是点历史不搜索的直接原因。
        showPanelOnly();
    }

    /** M109-fix：仅展示历史面板，绝不触碰 SearchView（供 onClose 回调安全调用） */
    public void showPanelOnly() {
        // 见构造注释：LoadViewHelper 的空状态容器会在 history 之上，
        // 每次展示都要重新置顶，否则 onQueryTextChange("")→show() 出来的面板
        // 仍被空状态容器挡住、touch 不可达。
        if (container != null) {
            container.bringToFront();
        }
        panelSubscriptions.clear();
        io.reactivex.disposables.Disposable d = io.reactivex.Observable.just(1)
                .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(o -> {
                    List<String> list = dm.getSearchHistory(TYPE, LIMIT);
                    if (list == null || list.isEmpty()) {
                        container.setVisibility(View.GONE);
                        return;
                    }
                    adapter.setNewData(list);
                    container.setVisibility(View.VISIBLE);
                });
        panelSubscriptions.add(d);
    }

    /**
     * M154：隐藏面板。除置 GONE 外必须取消在途的展示回调（show / showSuggestions
     * 都是异步 IO→主线程），否则点击历史条目触发的联想查询晚于本方法落地时，
     * 面板会在搜索结果返回前后自己重新显示出来，盖住结果列表。
     */
    public void hide() {
        panelSubscriptions.clear();
        container.setVisibility(View.GONE);
    }

    /**
     * M119：输入联想——按关键词对本地搜索历史做包含匹配，命中即以面板形式展示；
     * 空输入回退到完整历史，无命中自动隐藏。无网络请求，纯本地。
     */
    public void showSuggestions(String query) {
        if (TextUtils.isEmpty(query) || query.trim().isEmpty()) {
            showPanelOnly();
            return;
        }
        final String q = query.trim().toLowerCase(java.util.Locale.getDefault());
        panelSubscriptions.clear();
        io.reactivex.disposables.Disposable d = io.reactivex.Observable.just(1)
                .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(o -> {
                    List<String> list = dm.getSearchHistory(TYPE, LIMIT);
                    List<String> matched = new ArrayList<>();
                    if (list != null) {
                        for (String item : list) {
                            if (!TextUtils.isEmpty(item)
                                    && item.toLowerCase(java.util.Locale.getDefault()).contains(q)) {
                                matched.add(item);
                            }
                        }
                    }
                    if (container == null) {
                        return;
                    }
                    if (matched.isEmpty()) {
                        container.setVisibility(View.GONE);
                        return;
                    }
                    container.bringToFront();
                    adapter.setNewData(matched);
                    container.setVisibility(View.VISIBLE);
                });
        panelSubscriptions.add(d);
    }

    /** 搜索提交后调用：记录该关键词（自动去重 + 更新最近使用时间）。M73：写库切 IO 线程 */
    public void onKeywordSubmitted(String keyword) {
        if (TextUtils.isEmpty(keyword)) {
            return;
        }
        final String kw = keyword.trim();
        io.reactivex.Observable.just(1)
                .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                .subscribe(o -> dm.saveAutoComplete(kw, TYPE));
    }

    /** M73：清空历史切 IO 线程，避免主线程直写 DB */
    private void clearAll() {
        io.reactivex.Observable.just(1)
                .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                .subscribe(o -> dm.clearSearchHistory(TYPE));
        adapter.setNewData(new ArrayList<String>());
        // M154：复用 hide()，取消在途展示回调，防止清空后面板又被异步回调拉起
        hide();
    }

    private void confirmRemove(final String keyword) {
        new AlertDialog.Builder(container.getContext())
                .setTitle(R.string.search_history_title)
                .setMessage(container.getContext().getString(R.string.search_history_delete_confirm, keyword))
                .setPositiveButton(R.string.clean, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // M73：删除切 IO 线程，完成后回主线程刷新面板
                        io.reactivex.Observable.just(1)
                                .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                                .subscribe(o -> {
                                    dm.deleteSearchHistory(keyword, TYPE);
                                    show();
                                });
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
