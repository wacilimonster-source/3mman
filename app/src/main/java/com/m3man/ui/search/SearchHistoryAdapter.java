package com.m3man.ui.search;

import android.view.View;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.m3man.R;

/**
 * 搜索历史列表适配器（关键词为纯文本）。
 *
 * 修复（v1.0.107）：历史条目点击此前依赖 BaseQuickAdapter 的 setOnItemClickListener
 * 内部绑定，在部分机型/场景下未生效，表现为"点了历史一点反应都没有"。改为在 convert()
 * 中手动给 itemView 设置点击/长按监听，保证历史一显示出来、点击就一定生效，不再依赖
 * 框架内部的绑定时机。
 */
public class SearchHistoryAdapter extends BaseQuickAdapter<String, BaseViewHolder> {

    public interface OnItemActionListener {
        void onItemClick(String keyword);
        void onItemLongClick(String keyword);
    }

    private OnItemActionListener actionListener;

    public SearchHistoryAdapter() {
        super(R.layout.item_search_history);
    }

    public void setOnItemActionListener(OnItemActionListener listener) {
        this.actionListener = listener;
    }

    @Override
    protected void convert(BaseViewHolder helper, String item) {
        helper.setText(R.id.tv_search_history_keyword, item);
        final String keyword = item;
        helper.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actionListener != null) {
                    actionListener.onItemClick(keyword);
                }
            }
        });
        helper.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (actionListener != null) {
                    actionListener.onItemLongClick(keyword);
                }
                return true;
            }
        });
    }
}
