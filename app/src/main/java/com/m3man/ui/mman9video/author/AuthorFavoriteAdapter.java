package com.m3man.ui.mman9video.author;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.m3man.R;
import com.m3man.data.db.entity.AuthorFavorite;
import com.m3man.utils.GlideApp;

import java.util.Date;

/**
 * 作者收藏列表适配器。
 * 条目展示：封面缩略图 / 作者名 / NEW 角标 / 作品数与更新时间 / 来源与收藏时间。
 * 摘要数据由列表页静默刷新回填（未刷新过时相关行隐藏）。
 */
public class AuthorFavoriteAdapter extends BaseQuickAdapter<AuthorFavorite, BaseViewHolder> {

    public AuthorFavoriteAdapter() {
        super(R.layout.item_author_favorite);
    }

    @Override
    protected void convert(BaseViewHolder helper, AuthorFavorite item) {
        // 注册左滑「删除」按钮的点击事件（否则 setOnItemChildClickListener 不生效）
        helper.addOnClickListener(R.id.right_menu_delete);
        TextView name = helper.getView(R.id.tv_author_name);
        TextView badge = helper.getView(R.id.tv_author_new_badge);
        TextView meta = helper.getView(R.id.tv_author_meta);
        TextView sourceView = helper.getView(R.id.tv_author_source);
        ImageView cover = helper.getView(R.id.iv_author_cover);

        name.setText(item.getAuthorName());

        // NEW 角标：有未读新作品时亮起
        boolean hasNew = Boolean.TRUE.equals(item.getHasNew());
        badge.setVisibility(hasNew ? View.VISIBLE : View.GONE);

        // 摘要行：N部作品 · 更新于X前（未刷新过则整行隐藏）
        StringBuilder metaText = new StringBuilder();
        if (item.getVideoCount() != null && item.getVideoCount() > 0) {
            metaText.append(item.getVideoCount()).append("部作品");
        }
        if (item.getLastNewTime() != null) {
            if (metaText.length() > 0) {
                metaText.append(" · ");
            }
            metaText.append("更新于").append(relativeTime(item.getLastNewTime()));
        }
        if (metaText.length() > 0) {
            meta.setText(metaText);
            meta.setVisibility(View.VISIBLE);
        } else {
            meta.setVisibility(View.GONE);
        }

        // 来源行：来源标签 · 收藏于X前
        StringBuilder sourceText = new StringBuilder();
        String src = item.getSource();
        if (TextUtils.equals(src, AuthorFavorite.SOURCE_PORNY)) {
            sourceText.append("搜索源 (91porny)");
        } else {
            sourceText.append("视频源 (mman9)");
        }
        if (item.getFavoriteDate() != null) {
            sourceText.append(" · 收藏于").append(relativeTime(item.getFavoriteDate()));
        }
        sourceView.setText(sourceText);

        // 封面：最新作品缩略图，无则保留占位底色
        String coverUrl = item.getCoverUrl();
        if (!TextUtils.isEmpty(coverUrl)) {
            GlideApp.with(cover.getContext())
                    .load(coverUrl)
                    .transition(new DrawableTransitionOptions().crossFade(200))
                    .centerCrop()
                    .into(cover);
        } else {
            GlideApp.with(cover.getContext()).clear(cover);
        }
    }

    /** 相对时间：刚刚/N分钟前/N小时前/N天前/N个月前/N年前 */
    private static String relativeTime(Date date) {
        if (date == null) {
            return "";
        }
        long diff = System.currentTimeMillis() - date.getTime();
        if (diff < 0) {
            diff = 0;
        }
        long minutes = diff / 60000L;
        if (minutes < 1L) {
            return "刚刚";
        }
        if (minutes < 60L) {
            return minutes + "分钟前";
        }
        long hours = minutes / 60L;
        if (hours < 24L) {
            return hours + "小时前";
        }
        long days = hours / 24L;
        if (days < 30L) {
            return days + "天前";
        }
        long months = days / 30L;
        if (months < 12L) {
            return months + "个月前";
        }
        return (days / 365L) + "年前";
    }
}
