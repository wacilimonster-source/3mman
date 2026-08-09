package com.m3man.ui.recommend;

import android.graphics.Color;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import com.m3man.R;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.reco.RecoCandidate;
import com.m3man.data.reco.RecoEngine;
import com.m3man.data.reco.RecoStore;
import com.m3man.utils.GlideApp;

import java.util.ArrayList;
import java.util.List;

/**
 * 推荐流适配器：一屏一条视频。
 * <p>
 * 适配器只负责「静态内容」（封面 / 标题 / 操作栏状态），
 * 真正的起播与释放由 {@link RecommendFeedActivity} 根据当前吸附位置驱动，
 * 这样同一时刻只有一个 {@link RecoVideoPlayer} 持有解码器。
 *
 * @author 3mman
 */
public class RecommendFeedAdapter extends RecyclerView.Adapter<RecommendFeedAdapter.PageHolder> {

    /** 选中态：赞 */
    private static final int COLOR_LIKE = 0xFFFF4D67;
    /** 选中态：收藏 */
    private static final int COLOR_FAVORITE = 0xFFFF4D67;
    /** 选中态：不喜欢 */
    private static final int COLOR_DISLIKE = 0xFF4FC3F7;
    private static final int COLOR_NORMAL = Color.WHITE;

    public interface Callback {
        void onLikeClick(int position);

        void onFavoriteClick(int position);

        void onDislikeClick(int position);

        void onDetailClick(int position);

        void onRetryClick(int position);

        void onDownloadClick(int position);

        void onDoubleTap(int position);

        void onSingleTap(int position);
    }

    private final List<RecoCandidate> data = new ArrayList<>();
    private final RecoEngine engine;
    private final Callback callback;

    public RecommendFeedAdapter(RecoEngine engine, Callback callback) {
        this.engine = engine;
        this.callback = callback;
        setHasStableIds(true);
    }

    public List<RecoCandidate> getData() {
        return data;
    }

    public RecoCandidate getItem(int position) {
        if (position < 0 || position >= data.size()) {
            return null;
        }
        return data.get(position);
    }

    public void appendData(List<RecoCandidate> more) {
        if (more == null || more.isEmpty()) {
            return;
        }
        int start = data.size();
        data.addAll(more);
        notifyItemRangeInserted(start, more.size());
    }

    public void setData(List<RecoCandidate> list) {
        data.clear();
        if (list != null) {
            data.addAll(list);
        }
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        RecoCandidate c = getItem(position);
        String key = c == null ? null : c.viewKey();
        return key == null ? position : key.hashCode();
    }

    @Override
    public PageHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recommend_page, parent, false);
        // 每一页都必须撑满 RecyclerView，PagerSnapHelper 才能整页吸附
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp == null) {
            lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        view.setLayoutParams(lp);
        return new PageHolder(view);
    }

    @Override
    public void onBindViewHolder(final PageHolder holder, int position) {
        RecoCandidate candidate = getItem(position);
        if (candidate == null) {
            return;
        }
        V9MmanItem item = candidate.item;

        // 记录本页绑定的 key，Activity 起播前会拿它做一致性校验，
        // 避免 ViewHolder 复用时出现「画面 / 标题」错位
        holder.boundKey = candidate.viewKey();
        holder.title.setText(item == null ? "" : item.getTitle());
        holder.meta.setText(buildMeta(candidate));

        // 封面：起播前顶在最上层，起播后由 Activity 隐藏
        holder.cover.setVisibility(View.VISIBLE);
        holder.error.setVisibility(View.GONE);
        holder.loading.setVisibility(View.VISIBLE);
        holder.player.setVisibility(View.INVISIBLE);
        holder.progressContainer.setVisibility(View.GONE);
        holder.progress.setOnSeekBarChangeListener(null);
        holder.progress.setProgress(0);
        holder.curTime.setText("00:00");
        holder.durTime.setText("00:00");
        // 未成为当前页之前不允许循环续播，防止旧页抢解码器
        holder.player.setLoopEnabled(false);
        String imgUrl = item == null ? null : item.getImgUrl();
        if (TextUtils.isEmpty(imgUrl)) {
            holder.cover.setImageDrawable(null);
        } else {
            GlideApp.with(holder.cover.getContext())
                    .load(imgUrl)
                    .into(holder.cover);
        }

        bindActionState(holder, candidate);

        holder.like.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dispatch(holder, 0);
            }
        });
        holder.favorite.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dispatch(holder, 1);
            }
        });
        holder.dislike.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dispatch(holder, 2);
            }
        });
        holder.detail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dispatch(holder, 3);
            }
        });
        holder.error.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dispatch(holder, 4);
            }
        });
        holder.download.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dispatch(holder, 7);
            }
        });
        holder.player.setOnTapListener(new RecoVideoPlayer.OnTapListener() {
            @Override
            public void onDoubleTap(RecoVideoPlayer player) {
                dispatch(holder, 5);
            }

            @Override
            public void onSingleTap(RecoVideoPlayer player) {
                dispatch(holder, 6);
            }
        });
    }

    private void dispatch(PageHolder holder, int what) {
        if (callback == null) {
            return;
        }
        int pos = holder.getAdapterPosition();
        if (pos == RecyclerView.NO_POSITION) {
            return;
        }
        switch (what) {
            case 0:
                callback.onLikeClick(pos);
                break;
            case 1:
                callback.onFavoriteClick(pos);
                break;
            case 2:
                callback.onDislikeClick(pos);
                break;
            case 3:
                callback.onDetailClick(pos);
                break;
            case 4:
                callback.onRetryClick(pos);
                break;
            case 7:
                callback.onDownloadClick(pos);
                break;
            case 5:
                callback.onDoubleTap(pos);
                break;
            case 6:
                callback.onSingleTap(pos);
                break;
            default:
        }
    }

    /** 刷新某一条的操作栏状态（点赞后局部刷新，避免整页重绑导致播放中断） */
    public void refreshActionState(RecyclerView recyclerView, int position) {
        if (recyclerView == null) {
            return;
        }
        RecyclerView.ViewHolder vh = recyclerView.findViewHolderForAdapterPosition(position);
        if (vh instanceof PageHolder) {
            bindActionState((PageHolder) vh, getItem(position));
        }
    }

    private void bindActionState(PageHolder holder, RecoCandidate candidate) {
        if (candidate == null) {
            return;
        }
        int action = engine == null ? 0 : engine.actionOf(candidate.viewKey());
        boolean liked = action == RecoStore.ACTION_LIKE;
        boolean disliked = action == RecoStore.ACTION_DISLIKE;
        boolean favorited = isFavorited(candidate, action);

        holder.like.setColorFilter(liked ? COLOR_LIKE : COLOR_NORMAL);
        holder.like.setAlpha(liked ? 1.0f : 0.9f);
        holder.dislike.setColorFilter(disliked ? COLOR_DISLIKE : COLOR_NORMAL);
        holder.dislike.setAlpha(disliked ? 1.0f : 0.9f);
        holder.favorite.setColorFilter(favorited ? COLOR_FAVORITE : COLOR_NORMAL);
        holder.favorite.setAlpha(favorited ? 1.0f : 0.9f);
        holder.likeText.setText(liked ? R.string.reco_liked : R.string.reco_like);
    }

    private boolean isFavorited(RecoCandidate candidate, int action) {
        if (action == RecoStore.ACTION_FAVORITE) {
            return true;
        }
        V9MmanItem item = candidate.item;
        return item != null && Boolean.TRUE.equals(item.getIsLocalFavorite());
    }

    private String buildMeta(RecoCandidate candidate) {
        V9MmanItem item = candidate.item;
        if (item == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(candidate.authorName)) {
            sb.append('@').append(candidate.authorName);
        }
        if (!TextUtils.isEmpty(item.getDuration())) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append(item.getDuration());
        }
        if (!TextUtils.isEmpty(item.getInfo())) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append(item.getInfo());
        }
        return sb.toString();
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    @Override
    public void onViewRecycled(PageHolder holder) {
        super.onViewRecycled(holder);
        holder.player.setOnTapListener(null);
        // 回收前彻底停掉这一页：关循环 + 摘监听 + 收进度条，
        // 否则复用到新页时残留的状态会造成画面/标题错位
        holder.player.setLoopEnabled(false);
        holder.progress.setOnSeekBarChangeListener(null);
        holder.progressContainer.setVisibility(View.GONE);
        holder.boundKey = null;
        if (holder.player.isCurrentPlayer()) {
            try {
                holder.player.release();
            } catch (Exception ignored) {
            }
        }
    }

    static class PageHolder extends RecyclerView.ViewHolder {

        final RecoVideoPlayer player;
        final ImageView cover;
        final ProgressBar loading;
        final TextView error;
        final ImageView like;
        final TextView likeText;
        final ImageView favorite;
        final ImageView dislike;
        final ImageView detail;
        final ImageView download;
        final TextView title;
        final TextView meta;
        final SeekBar progress;
        final TextView curTime;
        final TextView durTime;
        final View progressContainer;
        /** 当前绑定的视频 key，供起播时做一致性校验 */
        String boundKey;

        PageHolder(View itemView) {
            super(itemView);
            player = itemView.findViewById(R.id.reco_player);
            cover = itemView.findViewById(R.id.iv_reco_cover);
            loading = itemView.findViewById(R.id.pb_reco_loading);
            error = itemView.findViewById(R.id.tv_reco_error);
            like = itemView.findViewById(R.id.iv_reco_like);
            likeText = itemView.findViewById(R.id.tv_reco_like);
            favorite = itemView.findViewById(R.id.iv_reco_favorite);
            dislike = itemView.findViewById(R.id.iv_reco_dislike);
            detail = itemView.findViewById(R.id.iv_reco_detail);
            download = itemView.findViewById(R.id.iv_reco_download);
            title = itemView.findViewById(R.id.tv_reco_title);
            meta = itemView.findViewById(R.id.tv_reco_meta);
            progress = itemView.findViewById(R.id.sb_reco_progress);
            curTime = itemView.findViewById(R.id.tv_reco_cur);
            durTime = itemView.findViewById(R.id.tv_reco_dur);
            progressContainer = itemView.findViewById(R.id.ll_reco_progress);
        }
    }
}
