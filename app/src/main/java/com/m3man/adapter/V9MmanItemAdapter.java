package com.m3man.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.m3man.R;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.utils.GlideApp;
import com.m3man.utils.GlideLoader;
import com.m3man.utils.VideoCardMeta;

import java.util.List;

/**
 * V9MmanItem 列表适配器（历史/收藏通用）。
 * <p>
 * 合并原 HistoryAdapter 与 FavoriteAdapter，消除 100% 重复代码。
 * 2026-11：与视频列表共用「标题 + 来源/作者 + 时长角标」结构，
 * 不再把时长拼进标题。
 */
public class V9MmanItemAdapter extends BaseQuickAdapter<V9MmanItem, BaseViewHolder> {

    public V9MmanItemAdapter(int layoutResId, @Nullable List<V9MmanItem> data) {
        super(layoutResId, data);
    }

    @Override
    protected void convert(BaseViewHolder helper, V9MmanItem item) {
        helper.setText(R.id.tv_91mman_item_title, item.getTitle());
        TextView metaView = helper.getView(R.id.tv_91mman_item_meta);
        String meta = VideoCardMeta.metaLine(item);
        if (metaView != null) {
            metaView.setText(meta);
            metaView.setVisibility(TextUtils.isEmpty(meta) ? View.GONE : View.VISIBLE);
        }
        TextView infoView = helper.getView(R.id.tv_91mman_item_info);
        if (infoView != null) {
            infoView.setVisibility(View.GONE);
        }
        TextView durationBadge = helper.getView(R.id.tv_duration_badge);
        String duration = VideoCardMeta.formatDuration(item.getDuration());
        if (durationBadge != null) {
            durationBadge.setText(duration);
            durationBadge.setVisibility(TextUtils.isEmpty(duration) ? View.GONE : View.VISIBLE);
        }
        ImageView simpleDraweeView = helper.getView(R.id.iv_91mman_item_img);
        simpleDraweeView.setContentDescription(item.getTitle());
        String coverUrl = item.getImgUrl();
        // M119：先清掉复用残留的方向角标，再按源图宽高比重设
        TextView badge = helper.getView(R.id.tv_orientation_badge);
        badge.setVisibility(View.GONE);
        // H-11: 使用共享工具类加载封面
        GlideLoader.loadCover(simpleDraweeView, coverUrl, R.drawable.placeholder_cover, orientation -> {
            if (orientation > 0) {
                badge.setText("竖屏");
                badge.setVisibility(View.VISIBLE);
            } else if (orientation < 0) {
                badge.setText("横屏");
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
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
            cover.setTag(R.id.tag_adapter_url, null);
        }
    }
}
