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
 * @date 2017/11/23
 * @describe
 */

public class FavoriteAdapter extends BaseQuickAdapter<V9MmanItem,BaseViewHolder>{

    public FavoriteAdapter(int layoutResId, @Nullable List<V9MmanItem> data) {
        super(layoutResId, data);
    }

    @Override
    protected void convert(BaseViewHolder helper, V9MmanItem item) {
        helper.setText(R.id.tv_91mman_item_title, item.getTitle() + "  (" + item.getDuration() + ")");
        helper.setText(R.id.tv_91mman_item_info, item.getInfo());
        ImageView simpleDraweeView = helper.getView(R.id.iv_91mman_item_img);
        // 统一使用等比例裁剪，避免不同来源缩略图被拉伸变形。
        simpleDraweeView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Uri uri = Uri.parse(item.getImgUrl());
        GlideApp.with(helper.itemView).load(uri).placeholder(R.drawable.placeholder).transition(new DrawableTransitionOptions().crossFade(300)).into(simpleDraweeView);

        helper.addOnClickListener(R.id.right_menu_delete);
    }
}
