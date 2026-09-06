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
    }

    /**
     * M112：ViewHolder 回收时取消挂起的 Glide 请求——
     * 快速滑动长列表时，离屏 item 的解码任务不再继续占用 CPU/内存，
     * 也避免迟到的解码结果写入已被复用的 ImageView 造成封面错位。
     */
    @Override
    public void onViewRecycled(BaseViewHolder holder) {
        super.onViewRecycled(holder);
        ImageView cover = holder.getView(R.id.iv_91mman_item_img);
        if (cover != null) {
            GlideApp.with(cover).clear(cover);
        }
    }
}
