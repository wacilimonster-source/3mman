package com.m3man.ui.search;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.m3man.R;

/**
 * 搜索历史列表适配器（关键词为纯文本，复用项目已有 BaseQuickAdapter）。
 */
public class SearchHistoryAdapter extends BaseQuickAdapter<String, BaseViewHolder> {

    public SearchHistoryAdapter() {
        super(R.layout.item_search_history);
    }

    @Override
    protected void convert(BaseViewHolder helper, String item) {
        helper.setText(R.id.tv_search_history_keyword, item);
    }
}
