package com.m3man.adapter;

import android.net.Uri;
import androidx.annotation.Nullable;
import android.widget.ImageView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.m3man.R;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.utils.GlideApp;
import com.m3man.utils.GlideLoader;

import java.util.List;

/**
 * V9MmanItem 列表适配器（历史/收藏通用）。
 * <p>
 * 合并原 HistoryAdapter 与 FavoriteAdapter，消除 100% 重复代码。
 */
public class V9MmanItemAdapter extends BaseQuickAdapter<V9MmanItem, BaseViewHolder> {

    public V9MmanItemAdapter(int layoutResId, @Nullable List<V9MmanItem> data) {
        super(layoutResId, data);
    }

    @Override
    protected void convert(BaseViewHolder helper, V9MmanItem item) {
        helper.setText(R.id.tv_91mman_item_title, item.getTitle() + "  (" + item.getDuration() + ")");
        helper.setText(R.id.tv_91mman_item_info, item.getInfo());
        ImageView simpleDraweeView = helper.getView(R.id.iv_91mman_item_img);
        String coverUrl = item.getImgUrl();
        // M119：先清掉复用残留的方向角标，再按源图宽高比重设
        android.widget.TextView badge = helper.getView(R.id.tv_orientation_badge);
        badge.setVisibility(android.view.View.GONE);
        // H-11: 使用共享工具类加载封面
        GlideLoader.loadCover(simpleDraweeView, coverUrl, orientation -> {
            if (orientation > 0) {
                badge.setText("竖屏");
                badge.setVisibility(android.view.View.VISIBLE);
            } else if (orientation < 0) {
                badge.setText("横屏");
                badge.setVisibility(android.view.View.VISIBLE);
            } else {
                badge.setVisibility(android.view.View.GONE);
            }
        });

        helper.addOnClickListener(R.id.right_menu_delete);
    }

    /** M112：回收时取消挂起的 Glide 请求（同 V91MmanAdapter） */
    @Override
    public void onViewRecycled(BaseViewHolder holder) {
        super.onViewRecycled(holder);
        ImageView cover = holder.getView(R.id.iv_91mman_item_img);
        if (cover != null) {
            GlideApp.with(cover).clear(cover);
        }
    }
}
