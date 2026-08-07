package com.m3man.adapter;

import android.net.Uri;
import android.support.annotation.Nullable;
import android.widget.ImageView;

import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.m3man.R;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.utils.GlideApp;

import java.util.List;

/**
 * @author flymegoc
 * @date 2017/11/14
 */

public class V91MmanAdapter extends BaseQuickAdapter<V9MmanItem, BaseViewHolder> {

    public V91MmanAdapter(int layoutResId) {
        super(layoutResId);
    }

    @Override
    protected void convert(BaseViewHolder helper, V9MmanItem item) {
        // 注册左滑「删除」按钮的点击事件（否则 setOnItemChildClickListener 不生效）
        helper.addOnClickListener(R.id.right_menu_delete);
        helper.setText(R.id.tv_91mman_item_title, item.getTitleWithDuration());
        helper.setText(R.id.tv_91mman_item_info, item.getInfo());
        ImageView simpleDraweeView = helper.getView(R.id.iv_91mman_item_img);
        Uri uri = Uri.parse(item.getImgUrl());
        GlideApp.with(helper.itemView).load(uri).placeholder(R.drawable.placeholder).transition(new DrawableTransitionOptions().crossFade(300)).into(simpleDraweeView);
    }
}
