package com.m3man.ui.mman9video.porny;

import androidx.core.content.ContextCompat;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.m3man.R;

/**
 * 大神横滑条适配器（精品·大神频道顶部）。
 * 数据为大神名列表（无索引页，首页模块种子 + 运行积累）；头像为首字圆形占位。
 */
public class DashenAdapter extends BaseQuickAdapter<String, BaseViewHolder> {

    public DashenAdapter() {
        super(R.layout.item_porny_dashen);
    }

    @Override
    protected void convert(BaseViewHolder helper, String name) {
        TextView avatar = helper.getView(R.id.tv_dashen_avatar);
        String first = (name == null || name.isEmpty()) ? "?" : name.substring(0, 1);
        avatar.setText(first);
        helper.setText(R.id.tv_dashen_name, name);
        helper.setText(R.id.tv_dashen_role, "精品作者");
        helper.setTextColor(R.id.tv_dashen_name,
                ContextCompat.getColor(mContext, R.color.text_primary));
    }
}
