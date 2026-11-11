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

    /** T02：每个 adapter 独立一个异步 differ（adapter 回收后弱引用随之释放） */
    private static final java.util.Map<BaseQuickAdapter<?, ?>, AsyncAdapterDiffer<?>> DIFFERS =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<BaseQuickAdapter<?, ?>, AsyncAdapterDiffer<?>>());

    private AdapterDiffUtil() {
    }

    /**
     * T02：列表刷新统一入口。asyncDiff 开启时走异步计算（主线程零 diff 成本，
     * 过期提交按代际丢弃），关闭时保持 M115 同步路径（行为完全不变）。
     * 调用点无需感知差别；BRVAH header/empty/loadMore 行为两条路径一致。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> void apply(BaseQuickAdapter<T, ?> adapter,
                                 @Nullable List<T> newData,
                                 DiffUtil.ItemCallback<T> itemCallback) {
        List<T> target = newData == null ? new ArrayList<T>() : newData;
        List<T> current = adapter.getData();
        // 空↔非空必须同步 setNewData：调用方（首页/分类/搜索等）会在 apply 后立刻
        // 检查 adapter.getItemCount() 决定显示内容还是空态；异步 diff 期间仍是 0 条，
        // 会先闪出「暂无内容」，数据落地后空态又不会被收起。
        if (current == null || current.isEmpty() || target.isEmpty()) {
            adapter.setNewData(target);
            return;
        }
        if (com.m3man.perf.PerfFlags.asyncDiff) {
            // M165：同一 adapter 复用同一个 differ。旧实现每次 apply 都 new 一个，
            // 在途的旧 differ 不会被作废，可能在新数据之后派发旧快照；
            // 同时 AsyncAdapterDiffer 已改为弱引用 adapter，避免 WeakHashMap 值强引用 key 泄漏。
            AsyncAdapterDiffer differ = DIFFERS.get(adapter);
            if (differ == null) {
                differ = new AsyncAdapterDiffer(adapter);
                DIFFERS.put(adapter, differ);
            }
            ((AsyncAdapterDiffer<T>) differ).submit(target, itemCallback);
        } else {
            legacyApply(adapter, target, itemCallback);
        }
    }

    /** M115 原同步实现（asyncDiff 关闭时的回退路径，逐行保留）。 */
    private static <T> void legacyApply(BaseQuickAdapter<T, ?> adapter,
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

    /**
     * V9MmanItem（视频/下载记录）：身份优先业务键 viewKey（T02——网络项 DB id 为空也能
     * 稳定识别，不再误判为「删除+插入」）；缺 viewKey 的纯本地记录回退 DB id；
     * 两者皆空返回 false（未知对象不共享同一个 NO_ID）。
     */
    public static DiffUtil.ItemCallback<com.m3man.data.db.entity.V9MmanItem> v9MmanItem() {
        return new DiffUtil.ItemCallback<com.m3man.data.db.entity.V9MmanItem>() {
            @Override
            public boolean areItemsTheSame(@NonNull com.m3man.data.db.entity.V9MmanItem oldItem,
                                           @NonNull com.m3man.data.db.entity.V9MmanItem newItem) {
                String oldKey = identityOf(oldItem);
                String newKey = identityOf(newItem);
                if (oldKey != null || newKey != null) {
                    return oldKey != null && oldKey.equals(newKey);
                }
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

    /** T02：V9MmanItem 业务身份键（归一化 viewKey）；为空返回 null（交给 DB id 回退）。 */
    private static String identityOf(com.m3man.data.db.entity.V9MmanItem item) {
        String key = com.m3man.perf.VideoKey.canonicalize(item.getViewKey());
        return key == null || key.length() == 0 ? null : key;
    }

    /** T02：包内可见（AsyncAdapterDiffer 复用同一派发语义） */
    static final class WrapperCallback<T> extends DiffUtil.Callback {
        private final List<T> oldList;
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
