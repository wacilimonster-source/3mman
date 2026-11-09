package com.m3man.adapter;

import android.content.Context;
import androidx.core.content.ContextCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.m3man.R;

/**
 * @author flymegoc
 * @date 2018/3/8
 */

public class SkipPageAdapter extends BaseQuickAdapter<Integer, BaseViewHolder> {

    private int currentPage = 1;

    public SkipPageAdapter(int layoutResId) {
        super(layoutResId);
    }

    @Override
    protected void convert(BaseViewHolder helper, Integer item) {
        helper.setText(R.id.tv_skip_page, String.valueOf(item));
        Context context = helper.itemView.getContext();
        if (item == currentPage) {
            helper.itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.colorPrimary));
        } else {
            helper.itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.common_background));
        }
    }

    public void setCurrentPage(int currentPage) {
        this.currentPage = currentPage;
        notifyDataSetChanged();
    }

    /** 一次性更新页码列表与当前页，避免两次 notify 造成闪烁。 */
    public void setPageData(java.util.List<Integer> data, int currentPage) {
        this.currentPage = currentPage;
        setNewData(data);
    }
}
