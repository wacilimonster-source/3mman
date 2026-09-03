package com.m3man.adapter;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.m3man.R;
import com.m3man.data.model.ProxyModel;

import java.util.List;

/**
 * @author flymegoc
 * @date 2018/1/20
 */

public class ProxyAdapter extends BaseQuickAdapter<ProxyModel, BaseViewHolder> {
    private int clickPosition = -1;

    public ProxyAdapter(int layoutResId, @Nullable List<ProxyModel> data) {
        super(layoutResId, data);
    }

    @Override
    protected void convert(BaseViewHolder helper, ProxyModel item) {
        helper.setText(R.id.tv_item_proxy_ip_address, item.getProxyIp());
        helper.setText(R.id.tv_item_proxy_port, item.getProxyPort());
        helper.setText(R.id.tv_item_proxy_anonymous, item.getAnonymous());
        helper.setText(R.id.tv_item_proxy_delay_time, item.getResponseTime());
        helper.setText(R.id.tv_item_proxy_type, item.getTypeShowStr());
        if (helper.getLayoutPosition() == clickPosition) {
            helper.itemView.setBackgroundColor(ContextCompat.getColor(helper.itemView.getContext(), R.color.colorPrimary));
        } else {
            helper.itemView.setBackgroundColor(ContextCompat.getColor(helper.itemView.getContext(), R.color.common_background));
        }
    }

    public void setClickPosition(int clickPosition) {
        this.clickPosition = clickPosition + 1;
        notifyDataSetChanged();
    }
}
