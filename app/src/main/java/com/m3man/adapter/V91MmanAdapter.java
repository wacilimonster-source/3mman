package com.m3man.adapter;

import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.m3man.R;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.utils.GlideApp;
import com.m3man.utils.GlideLoader;
import com.m3man.utils.VideoCardMeta;

/**
 * 视频列表/搜索结果适配器。
 * <p>
 * 2026-11 体验精修（EX-02）：标题、来源/作者、时长角标分层展示；
 * 封面上方保留方向/来源角标；右侧「更多」是独立 48dp 热区，
 * 收藏/下载动作始终作用于本次点击的那条 viewKey。
 */
public class V91MmanAdapter extends BaseQuickAdapter<V9MmanItem, BaseViewHolder> {

    /** 卡片「更多」菜单动作回调 */
    public interface OnCardActionListener {
        void onFavoriteAction(V9MmanItem item);

        void onDownloadAction(V9MmanItem item);
    }

    private OnCardActionListener cardActionListener;
    private DataManager dataManager;

    public V91MmanAdapter(int layoutResId) {
        super(layoutResId);
    }

    /** 用于打开「更多」菜单时读取 DB 中真实的本地收藏态，避免菜单文案与动作相反。 */
    public void setDataManager(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    public void setOnCardActionListener(OnCardActionListener listener) {
        this.cardActionListener = listener;
    }

    /**
     * 播放页返回后，共享元素会清空原封面的 drawable；列表不会自动 rebind，
     * 这里主动清掉可见项的封面 tag 并刷新，触发 Glide 重新加载。
     */
    public void reloadCovers(@Nullable androidx.recyclerview.widget.RecyclerView recyclerView) {
        if (recyclerView == null) {
            return;
        }
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            ImageView cover = recyclerView.getChildAt(i).findViewById(R.id.iv_91mman_item_img);
            if (cover != null) {
                cover.setTag(R.id.tag_adapter_url, null);
                cover.setAlpha(1f);
            }
        }
        notifyDataSetChanged();
    }

    @Override
    protected void convert(BaseViewHolder helper, V9MmanItem item) {
        // 注册左滑「删除」按钮的点击事件（否则 setOnItemChildClickListener 不生效）
        helper.addOnClickListener(R.id.right_menu_delete);

        helper.setText(R.id.tv_91mman_item_title, item == null ? "" : item.getTitle());
        TextView titleView = helper.getView(R.id.tv_91mman_item_title);
        if (titleView != null) {
            titleView.setContentDescription(item == null ? "" : item.getTitle());
        }

        // 结构化元信息：来源 · 作者（缺省省略），不再把时长塞进标题。
        TextView metaView = helper.getView(R.id.tv_91mman_item_meta);
        String meta = VideoCardMeta.metaLine(item);
        if (metaView != null) {
            metaView.setText(meta);
            metaView.setVisibility(TextUtils.isEmpty(meta) ? View.GONE : View.VISIBLE);
        }
        // 旧 info 字段在列表/搜索中不再重复占行，保留 View 供历史/下载等共用布局。
        TextView infoView = helper.getView(R.id.tv_91mman_item_info);
        if (infoView != null) {
            infoView.setVisibility(View.GONE);
        }

        TextView durationBadge = helper.getView(R.id.tv_duration_badge);
        String duration = VideoCardMeta.formatDuration(item == null ? null : item.getDuration());
        if (durationBadge != null) {
            durationBadge.setText(duration);
            durationBadge.setVisibility(TextUtils.isEmpty(duration) ? View.GONE : View.VISIBLE);
        }

        ImageView simpleDraweeView = helper.getView(R.id.iv_91mman_item_img);
        String coverUrl = item == null ? null : item.getImgUrl();
        if (simpleDraweeView != null) {
            simpleDraweeView.setContentDescription(item == null ? "" : item.getTitle());
            // 共享元素返回后系统可能把原 View 置为透明；rebind 时恢复可见。
            simpleDraweeView.setAlpha(1f);
        }
        // M119：先清掉复用残留的方向角标，再按源图宽高比重设
        TextView badge = helper.getView(R.id.tv_orientation_badge);
        if (badge != null) {
            badge.setVisibility(View.GONE);
        }
        // 来源角标（蝌蚪/精品）：按 viewKey 前缀判定，先清复用残留
        TextView sourceBadge = helper.getView(R.id.tv_source_badge);
        applySourceBadge(sourceBadge, item == null ? null : item.getViewKey());

        // H-11: 使用共享工具类加载封面；缺图/失败时用同尺寸占位，不改变行高
        if (simpleDraweeView != null) {
            String tag = (String) simpleDraweeView.getTag(R.id.tag_adapter_url);
            if (TextUtils.isEmpty(coverUrl)) {
                GlideApp.with(simpleDraweeView).clear(simpleDraweeView);
                simpleDraweeView.setTag(R.id.tag_adapter_url, null);
                simpleDraweeView.setImageResource(R.drawable.placeholder_cover);
                if (badge != null) {
                    badge.setVisibility(View.GONE);
                }
            } else if (!coverUrl.equals(tag) || simpleDraweeView.getDrawable() == null) {
                // 共享元素返回时系统会清空封面 drawable，但 tag 仍等于旧 URL；
                // 若只比 tag 会永久跳过重载，列表留下空白封面。
                if (coverUrl.equals(tag)) {
                    simpleDraweeView.setTag(R.id.tag_adapter_url, null);
                }
                GlideLoader.loadCover(simpleDraweeView, coverUrl, R.drawable.placeholder_cover, orientation -> {
                    if (badge == null) {
                        return;
                    }
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
            }
        }

        ImageView moreView = helper.getView(R.id.iv_91mman_item_more);
        if (moreView != null) {
            // 只有接入动作回调的页面才显示“更多”，避免出现点了没反应的按钮。
            moreView.setVisibility(cardActionListener == null ? View.GONE : View.VISIBLE);
            moreView.setContentDescription(mContext.getString(R.string.card_more_actions) + "："
                    + (item == null ? "" : item.getTitle()));
            final String actionKey = item == null ? null : item.getViewKey();
            moreView.setOnClickListener(v -> showMoreMenu(moreView, actionKey));
        }
    }

    /** 弹出「更多」菜单：收藏/取消收藏 + 下载，均作用于点击时绑定的 viewKey。 */
    private void showMoreMenu(View anchor, @Nullable String viewKey) {
        V9MmanItem item = findByViewKey(viewKey);
        if (item == null) {
            return;
        }
        boolean favorited = Boolean.TRUE.equals(item.getIsLocalFavorite());
        // 列表对象只在本进程内更新；跨会话/从播放页收藏后，内存值可能过期。
        // 以 DB 为准刷新菜单文案，保证「加入收藏/取消收藏」与点击后的实际动作一致。
        if (dataManager != null) {
            V9MmanItem stored = dataManager.findV9MmanItemByViewKey(viewKey);
            if (stored != null) {
                favorited = Boolean.TRUE.equals(stored.getIsLocalFavorite());
                item.setIsLocalFavorite(favorited);
            }
        }
        PopupMenu menu = new PopupMenu(
                new ContextThemeWrapper(mContext, R.style.AppTheme_PopupMenu), anchor);
        menu.getMenu().add(0, R.id.action_card_favorite, 0,
                mContext.getString(favorited
                        ? R.string.card_action_unfavorite : R.string.card_action_favorite));
        menu.getMenu().add(0, R.id.action_card_download, 1,
                mContext.getString(R.string.card_action_download));
        menu.setOnMenuItemClickListener(menuItem -> {
            V9MmanItem current = findByViewKey(viewKey);
            if (current == null || cardActionListener == null) {
                return false;
            }
            if (menuItem.getItemId() == R.id.action_card_favorite) {
                cardActionListener.onFavoriteAction(current);
                return true;
            }
            if (menuItem.getItemId() == R.id.action_card_download) {
                cardActionListener.onDownloadAction(current);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private V9MmanItem findByViewKey(@Nullable String viewKey) {
        if (TextUtils.isEmpty(viewKey)) {
            return null;
        }
        for (V9MmanItem item : getData()) {
            if (item != null && viewKey.equals(item.getViewKey())) {
                return item;
            }
        }
        return null;
    }

    /**
     * 91porny 新分区条目来源角标：蝌蚪长视频（videos/）绿、精品（vod/）金。
     * 主源 9mman 与 91porny 主源条目不显示。搜索结果混排时用于区分来源（产品设计 P5）。
     */
    private void applySourceBadge(TextView badge, String viewKey) {
        if (badge == null) {
            return;
        }
        if (com.m3man.parser.Parse91PornyVideo.isTadpoleViewKey(viewKey)) {
            badge.setText(R.string.porny_source_badge_tad);
            badge.setBackgroundColor(0xEE0E7C66);
            badge.setVisibility(View.VISIBLE);
        } else if (com.m3man.parser.Parse91PornyVideo.isPremiumViewKey(viewKey)) {
            badge.setText(R.string.porny_source_badge_prem);
            badge.setBackgroundColor(0xEEB8730A);
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }
    }

    /** M112：ViewHolder 回收时取消挂起的 Glide 请求——快速滑动长列表时，
     * 离屏 item 的解码任务不再继续占用 CPU/内存，也避免迟到的解码结果写入被复用的
     * ImageView 造成封面错位。 */
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
