package com.m3man.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;

import com.chad.library.adapter.base.BaseQuickAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * M115：BRVAH 2.x 没有 setDiffNewData（3.x 才有），这里封装通用 DiffUtil 接入：
 * 直接原地替换 getData() 返回的内部列表，再把 DiffResult 派发给 adapter，
 * 列表刷新从全量 notifyDataSetChanged 变为细粒度的 item 动画。
 *
 * 注意：空 ↔ 非空切换仍走 setNewData，保留 BRVAH 空视图的显隐逻辑；
 * 本工具只用于「两边都非空」的刷新场景。
 */
public final class AdapterDiffUtil {

    private AdapterDiffUtil() {
    }

    public static <T> void apply(BaseQuickAdapter<T, ?> adapter,
                                 @Nullable List<T> newData,
                                 DiffUtil.ItemCallback<T> itemCallback) {
        List<T> target = newData == null ? new ArrayList<T>() : newData;
        List<T> current = adapter.getData();
        if (current == null || current.isEmpty() || target.isEmpty()) {
            adapter.setNewData(target);
            return;
        }
        DiffUtil.DiffResult result =
                DiffUtil.calculateDiff(new WrapperCallback<>(current, target, itemCallback), false);
        current.clear();
        current.addAll(target);
        result.dispatchUpdatesTo(adapter);
    }

    /** V9MmanItem（视频/下载记录）：以数据库 _id 为身份，比较列表渲染用到的字段 */
    public static DiffUtil.ItemCallback<com.m3man.data.db.entity.V9MmanItem> v9MmanItem() {
        return new DiffUtil.ItemCallback<com.m3man.data.db.entity.V9MmanItem>() {
            @Override
            public boolean areItemsTheSame(@NonNull com.m3man.data.db.entity.V9MmanItem oldItem,
                                           @NonNull com.m3man.data.db.entity.V9MmanItem newItem) {
                Long oldId = oldItem.getId();
                Long newId = newItem.getId();
                return oldId != null && newId != null && oldId.equals(newId);
            }

            @Override
            public boolean areContentsTheSame(@NonNull com.m3man.data.db.entity.V9MmanItem oldItem,
                                              @NonNull com.m3man.data.db.entity.V9MmanItem newItem) {
                return oldItem.getStatus() == newItem.getStatus()
                        && oldItem.getProgress() == newItem.getProgress()
                        && oldItem.getSoFarBytes() == newItem.getSoFarBytes()
                        && oldItem.getTotalFarBytes() == newItem.getTotalFarBytes()
                        && oldItem.getSpeed() == newItem.getSpeed()
                        && textEquals(oldItem.getTitle(), newItem.getTitle())
                        && textEquals(oldItem.getImgUrl(), newItem.getImgUrl())
                        && textEquals(oldItem.getDuration(), newItem.getDuration());
            }
        };
    }

    /** VideoComment（评论列表）：以 uid+replyId 为身份 */
    public static DiffUtil.ItemCallback<com.m3man.data.model.VideoComment> videoComment() {
        return new DiffUtil.ItemCallback<com.m3man.data.model.VideoComment>() {
            @Override
            public boolean areItemsTheSame(@NonNull com.m3man.data.model.VideoComment oldItem,
                                           @NonNull com.m3man.data.model.VideoComment newItem) {
                return textEquals(oldItem.getUid(), newItem.getUid())
                        && textEquals(oldItem.getReplyId(), newItem.getReplyId());
            }

            @Override
            public boolean areContentsTheSame(@NonNull com.m3man.data.model.VideoComment oldItem,
                                              @NonNull com.m3man.data.model.VideoComment newItem) {
                return textEquals(oldItem.getuName(), newItem.getuName())
                        && textEquals(oldItem.getReplyTime(), newItem.getReplyTime())
                        && textEquals(oldItem.getTitleInfo(), newItem.getTitleInfo());
            }
        };
    }

    /** AuthorFavorite（收藏作者）：以数据库 _id 为身份 */
    public static DiffUtil.ItemCallback<com.m3man.data.db.entity.AuthorFavorite> authorFavorite() {
        return new DiffUtil.ItemCallback<com.m3man.data.db.entity.AuthorFavorite>() {
            @Override
            public boolean areItemsTheSame(@NonNull com.m3man.data.db.entity.AuthorFavorite oldItem,
                                           @NonNull com.m3man.data.db.entity.AuthorFavorite newItem) {
                Long oldId = oldItem.getId();
                Long newId = newItem.getId();
                return oldId != null && newId != null && oldId.equals(newId);
            }

            @Override
            public boolean areContentsTheSame(@NonNull com.m3man.data.db.entity.AuthorFavorite oldItem,
                                              @NonNull com.m3man.data.db.entity.AuthorFavorite newItem) {
                return textEquals(oldItem.getAuthorName(), newItem.getAuthorName())
                        && textEquals(oldItem.getCoverUrl(), newItem.getCoverUrl())
                        && textEquals(oldItem.getTopViewKey(), newItem.getTopViewKey())
                        && (oldItem.getVideoCount() != null
                            ? oldItem.getVideoCount().equals(newItem.getVideoCount())
                            : newItem.getVideoCount() == null);
            }
        };
    }

    private static boolean textEquals(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static final class WrapperCallback<T> extends DiffUtil.Callback {        private final List<T> oldList;
        private final List<T> newList;
        private final DiffUtil.ItemCallback<T> itemCallback;

        WrapperCallback(List<T> oldList, List<T> newList, DiffUtil.ItemCallback<T> itemCallback) {
            this.oldList = oldList;
            this.newList = newList;
            this.itemCallback = itemCallback;
        }

        @Override
        public int getOldListSize() {
            return oldList.size();
        }

        @Override
        public int getNewListSize() {
            return newList.size();
        }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            return itemCallback.areItemsTheSame(oldList.get(oldItemPosition), newList.get(newItemPosition));
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            return itemCallback.areContentsTheSame(oldList.get(oldItemPosition), newList.get(newItemPosition));
        }

        @Nullable
        @Override
        public Object getChangePayload(int oldItemPosition, int newItemPosition) {
            return itemCallback.getChangePayload(oldList.get(oldItemPosition), newList.get(newItemPosition));
        }
    }
}
