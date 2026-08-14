package com.m3man.ui.search;

import android.content.DialogInterface;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
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

    public SearchHistoryPanel(View container, DataManager dm, OnHistoryItemClickListener listener) {
        this.container = container;
        this.dm = dm;
        this.clickListener = listener;

        this.rv = container.findViewById(R.id.rv_search_history);
        this.tvClear = container.findViewById(R.id.tv_clear_search_history);

        this.adapter = new SearchHistoryAdapter();
        this.rv.setLayoutManager(new LinearLayoutManager(container.getContext()));
        this.rv.setNestedScrollingEnabled(false);
        this.rv.setAdapter(adapter);

        this.adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter a, View v, int position) {
                String kw = (String) a.getItem(position);
                if (clickListener != null && !TextUtils.isEmpty(kw)) {
                    clickListener.onHistoryItemClick(kw);
                }
            }
        });
        this.adapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter a, View v, int position) {
                String kw = (String) a.getItem(position);
                if (!TextUtils.isEmpty(kw)) {
                    confirmRemove(kw);
                }
                return true;
            }
        });
        this.tvClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearAll();
            }
        });
    }

    /** 加载并展示历史；无历史时自动隐藏 */
    public void show() {
        List<String> list = dm.getSearchHistory(TYPE, LIMIT);
        if (list == null || list.isEmpty()) {
            container.setVisibility(View.GONE);
            return;
        }
        adapter.setNewData(list);
        container.setVisibility(View.VISIBLE);
    }

    public void hide() {
        container.setVisibility(View.GONE);
    }

    /** 搜索提交后调用：记录该关键词（自动去重 + 更新最近使用时间） */
    public void onKeywordSubmitted(String keyword) {
        if (TextUtils.isEmpty(keyword)) {
            return;
        }
        dm.saveAutoComplete(keyword.trim(), TYPE);
    }

    private void clearAll() {
        dm.clearSearchHistory(TYPE);
        adapter.setNewData(new ArrayList<String>());
        container.setVisibility(View.GONE);
    }

    private void confirmRemove(final String keyword) {
        new AlertDialog.Builder(container.getContext())
                .setTitle(R.string.search_history_title)
                .setMessage(container.getContext().getString(R.string.search_history_delete_confirm, keyword))
                .setPositiveButton(R.string.clean, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dm.deleteSearchHistory(keyword, TYPE);
                        show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
