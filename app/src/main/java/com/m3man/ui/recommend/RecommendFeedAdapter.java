package com.m3man.ui.recommend;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import androidx.recyclerview.widget.RecyclerView;
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
import com.m3man.utils.PlayUiPrefs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 推荐流适配器：一屏一条视频。
 * <p>
 * 适配器只负责「静态内容」（封面 / 标题 / 操作栏状态），
 * 真正的起播与释放由 {@link RecommendFeedFragment} 根据当前吸附位置驱动，
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

        void onDoubleTap(int position, float x);

        void onSingleTap(int position);

        void onFullscreenClick(int position);

        void onSpeedClick(int position);
    }

    private final List<RecoCandidate> data = new ArrayList<>();
    /**
     * M92c：引擎在后台线程异步初始化（M91），构造 Adapter 时可能尚为 null。
     * 初始化完成后由 Fragment 调 {@link #setEngine(RecoEngine)} 回填——
     * 否则 bindActionState 的 actionOf 永远返回 0，点赞/踩/收藏的选中态全部失效。
     */
    private RecoEngine engine;
    private final Callback callback;
    /**
     * 当前方向模式（由 Fragment 在横竖屏切换时更新）。
     * <p>
     * M90：作为「方向事实来源」由 Adapter 持有，新 ViewHolder 在 onBindViewHolder
     * 时立刻按此值布局——即使旋转动画期间旧 ViewHolder 被 detach 导致遍历漏掉，
     * 新滑入/重新绑定的页面也会带正确的按钮位置，杜绝"部分界面横屏缺按钮、
     * 回竖屏按钮偏移到屏幕中央"的时序竞态。
     */
    private boolean landscapeMode = false;

    public RecommendFeedAdapter(RecoEngine engine, Callback callback) {
        this.engine = engine;
        this.callback = callback;
        setHasStableIds(true);
    }

    /** 引擎后台初始化完成后回填引用（见字段注释 M92c） */
    public void setEngine(RecoEngine engine) {
        this.engine = engine;
    }

    /** 刷新当前所有已挂载页的操作态（引擎回填后调用，避免整页 notifyDataSetChanged 打断播放） */
    public void refreshAttachedActionStates(RecyclerView recyclerView) {
        if (recyclerView == null) {
            return;
        }
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            RecyclerView.ViewHolder vh = recyclerView.getChildViewHolder(recyclerView.getChildAt(i));
            if (!(vh instanceof PageHolder)) {
                continue;
            }
            int pos = vh.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION || pos >= getItemCount()) {
                continue;
            }
            RecoCandidate candidate = getItem(pos);
            if (candidate != null) {
                bindActionState((PageHolder) vh, candidate);
            }
        }
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

    /**
     * M71：作者名异步回填后立即刷新该页信息栏，不再等 ViewHolder 复用时才更新。
     * 消除"刚滑到显示 时长·添加时间，滑回来变成 @作者·时长·添加时间"的顺序跳变。
     */
    public void refreshMeta(RecyclerView recyclerView, int position) {
        if (position < 0 || position >= data.size() || recyclerView == null) {
            return;
        }
        RecyclerView.ViewHolder vh = recyclerView.findViewHolderForAdapterPosition(position);
        if (vh instanceof PageHolder) {
            PageHolder holder = (PageHolder) vh;
            RecoCandidate candidate = data.get(position);
            // 一致性校验：复用错位时不刷新
            if (candidate != null && candidate.viewKey() != null
                    && candidate.viewKey().equals(holder.boundKey)) {
                holder.meta.setText(buildMeta(candidate));
            }
        }
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

    /**
     * M98：viewKey -> 稳定自增 id 的懒分配映射（替代原 key.hashCode() 方案）。
     * hashCode 存在碰撞可能，两个不同视频拿到同一 id 会导致 RecyclerView 的
     * 稳定 id 复用/动画错乱；自增 Long 保证会话内唯一。
     */
    private final HashMap<String, Long> idMap = new HashMap<>();
    /** M98：id 分配序号（仅在 getIdOf 同步方法内递增） */
    private long idSeq = 0L;

    /** M98：懒分配稳定 id——缺失则 put 自增序号，主线程调用故用 synchronized 兜底 */
    private synchronized long idOf(String viewKey) {
        if (viewKey == null) {
            return RecyclerView.NO_ID;
        }
        Long id = idMap.get(viewKey);
        if (id == null) {
            id = ++idSeq;
            idMap.put(viewKey, id);
        }
        return id;
    }

    @Override
    public long getItemId(int position) {
        RecoCandidate c = getItem(position);
        String key = c == null ? null : c.viewKey();
        if (key == null) {
            // viewKey 为空仍回退 position（保留原兜底行为）
            return position;
        }
        return idOf(key);
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
        holder.seekFeedback.setVisibility(View.GONE);
        holder.progress.setOnSeekBarChangeListener(null);
        holder.progress.setProgress(0);
        holder.curTime.setText("00:00");
        holder.durTime.setText("00:00");
        holder.speed.setText("1x");
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

        // M78：按「隐藏播放页操作栏」偏好决定初始显隐
        holder.applyHidePolicy(PlayUiPrefs.isHideActionBar(holder.itemView.getContext()));

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
        holder.fullscreen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dispatch(holder, 8);
            }
        });
        holder.speed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dispatch(holder, 9);
            }
        });
        holder.player.setOnTapListener(new RecoVideoPlayer.OnTapListener() {
            @Override
            public void onDoubleTap(RecoVideoPlayer player, float x) {
                int pos = holder.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || callback == null) {
                    return;
                }
                callback.onDoubleTap(pos, x / Math.max(1f, player.getWidth()));
            }

            @Override
            public void onSingleTap(RecoVideoPlayer player) {
                dispatch(holder, 6);
            }
        });

        // M90：新 ViewHolder（横屏时滑入、或 ViewHolder 复用重新绑定）在绑定瞬间
        // 就按当前方向布局，即使旋转动画期间旧 Holder 被 detach 导致遍历漏掉，
        // 新页面也带正确的按钮位置。
        holder.applyOrientationUi(landscapeMode);
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
            case 8:
                callback.onFullscreenClick(pos);
                break;
            case 9:
                callback.onSpeedClick(pos);
                break;
            case 5:
                callback.onDoubleTap(pos, 0.5f);
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
        // M76：展示以列表页解析的 authorText（info 原文提取）为准——首屏即显，不等详情回填，
        // 消除"滑回来才出现 @作者"的跳变；authorText 提取失败时仍用详情页权威 ownerName 兜底。
        // 注意 candidate.authorName/authorKey 仍是作者召回与作者收藏的数据来源，此处只调整展示优先级。
        String author = item.getAuthorText();
        if (TextUtils.isEmpty(author)) {
            author = candidate.authorName;
        }
        if (!TextUtils.isEmpty(author)) {
            sb.append('@').append(author.trim());
        }
        // M75：meta 行只保留「添加时间」。时长由播放器底部进度条呈现，热度/收藏在详情页查看，
        // 不再塞进推荐流信息栏（此前第二行展示不完整）。
        // M76：截断表补入 作者/From/时长/Duration——否则字段顺序为「添加时间…From:xx」时，
        // added 段会把作者一并吞进来，与前缀 @作者 重复显示两次。
        String added = extractAddedTime(item.getInfo());
        if (!TextUtils.isEmpty(added)) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append(added);
        }
        return sb.toString();
    }

    /** M75：从 info 文本里只截取「添加时间」段；时长/热度/收藏/留言等不再展示 */
    private static String extractAddedTime(String info) {
        if (info == null || info.isEmpty()) {
            return "";
        }
        String[] starts = {"添加时间:", "添加時間:", "Added:", "Added"};
        int start = -1;
        for (String s : starts) {
            int i = info.indexOf(s);
            if (i >= 0) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return "";
        }
        // M76：截到下一个已知字段标签为止（补入 作者/From/时长/Duration，防止吞进作者段造成重复）
        String[] ends = {"热度:", "Views:", "查看:", "收藏:", "Favorites:", "留言:",
                "Comments:", "评论:", "积分:", "Point:", "来自:", "來自:",
                "作者:", "作者：", "From:", "时长:", "時長:", "Duration:"};
        int end = info.length();
        for (String e : ends) {
            int i = info.indexOf(e, start + 1);
            if (i >= 0 && i < end) {
                end = i;
            }
        }
        return info.substring(start, end).trim();
    }

    /**
     * M90：切换方向模式。
     * <p>
     * 更新 {@link #landscapeMode}（方向事实来源，新 ViewHolder 绑定即生效），
     * 并立即对当前已挂载的 ViewHolder 应用方向布局。Fragment 应在进入横屏 / 恢复竖屏时
     * 调用，并在旋转完成后（{@code recyclerView.post{...}}）再调一次，覆盖转屏动画期间
     * 被 detach、旋转完成后重新 attach 的 ViewHolder —— 这是"部分界面横屏缺按钮、
     * 回竖屏按钮偏移"时序竞态的根治。
     */
    public void setLandscapeMode(RecyclerView recyclerView, boolean landscape) {
        landscapeMode = landscape;
        applyOrientationUi(recyclerView, landscape);
    }

    public boolean isLandscapeMode() {
        return landscapeMode;
    }

    /**
     * 旋转时 Activity 使用 configChanges，不会重新 inflate 已存在的 ViewHolder。
     * 对当前已挂载的页面立即套用对应方向的操作栏/进度条参数，避免第一个视频仍沿用竖屏布局。
     */
    public void applyOrientationUi(RecyclerView recyclerView, boolean landscape) {
        if (recyclerView == null) {
            return;
        }
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            RecyclerView.ViewHolder viewHolder = recyclerView.getChildViewHolder(child);
            if (viewHolder instanceof PageHolder) {
                ((PageHolder) viewHolder).applyOrientationUi(landscape);
            }
        }
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    /**
     * M90：ViewHolder 重新 attach（滑入 / 转屏后 detach 再 attach / 复用）时，
     * 立即按当前方向模式重设按钮位置，覆盖旋转动画期间遍历不到挂载 Holder 的竞态。
     */
    @Override
    public void onViewAttachedToWindow(PageHolder holder) {
        super.onViewAttachedToWindow(holder);
        if (holder != null) {
            holder.applyOrientationUi(landscapeMode);
        }
    }

    @Override
    public void onViewRecycled(PageHolder holder) {
        super.onViewRecycled(holder);
        // M98：对**所有** holder 无条件取消挂起手势（单击延迟 / 长按计时 / 双击标志），
        // 不 release 播放器——防止非当前页的 Holder 在回收后延迟手势到期误触发到复用的新视频上
        holder.player.cancelPendingGestures();
        holder.player.setOnTapListener(null);
        // 回收前彻底停掉这一页：关循环 + 摘监听 + 收进度条，
        // 否则复用到新页时残留的状态会造成画面/标题错位
        holder.player.setLoopEnabled(false);
        holder.progress.setOnSeekBarChangeListener(null);
        holder.progressContainer.setVisibility(View.GONE);
        holder.seekFeedback.setVisibility(View.GONE);
        holder.boundKey = null;
        // M78：清掉可能存在的自动收起计时，避免回收后误触发到别的视频
        holder.hideHandler.removeCallbacks(holder.hideRunnable);
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
        final TextView seekFeedback;
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
        final ImageView fullscreen;
        final TextView speed;
        final View progressContainer;
        final View actionsContainer;
        final View handle;
        final Handler hideHandler;
        final Runnable hideRunnable;
        /** 当前绑定的视频 key，供起播时做一致性校验 */
        String boundKey;
        private boolean orientationLandscape;

        PageHolder(View itemView) {
            super(itemView);
            player = itemView.findViewById(R.id.reco_player);
            cover = itemView.findViewById(R.id.iv_reco_cover);
            loading = itemView.findViewById(R.id.pb_reco_loading);
            error = itemView.findViewById(R.id.tv_reco_error);
            seekFeedback = itemView.findViewById(R.id.tv_reco_seek_feedback);
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
            fullscreen = itemView.findViewById(R.id.iv_reco_fullscreen);
            speed = itemView.findViewById(R.id.tv_reco_speed);
            progressContainer = itemView.findViewById(R.id.ll_reco_progress);
            actionsContainer = itemView.findViewById(R.id.ll_reco_actions);
            handle = itemView.findViewById(R.id.v_reco_handle);
            hideHandler = new Handler(Looper.getMainLooper());
            hideRunnable = new Runnable() {
                @Override
                public void run() {
                    collapseActions();
                }
            };
            handle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleActions();
                }
            });
        }

        /** M78：根据「隐藏播放页操作栏」偏好设置初始状态。 */
        /**
         * 在 configChanges 旋转场景下，已存在的 ViewHolder 不会重新 inflate 横屏 XML。
         * 这里只重设**位置参数**（margin / gravity / padding / speed width）。
         * <p>
         * 图标尺寸与文字显隐已由 item_recommend_page.xml / layout-land/item_recommend_page.xml
         * 静态统一为「小图标 + 无文字」，本方法不再触碰子 View，杜绝翻页/回竖屏时的残留 bug。
         * <p>
         * 数值与两份 XML 100% 对齐：
         * <ul>
         *   <li>竖屏 actionsContainer: rightMargin=18dp, bottomMargin=40dp, padding(6,8,6,8)</li>
         *   <li>横屏 actionsContainer: rightMargin=112dp, bottomMargin=0,   padding(4,4,4,4)</li>
         *   <li>竖屏 progressContainer: rightMargin=12dp, bottomMargin=12dp</li>
         *   <li>横屏 progressContainer: rightMargin=96dp, bottomMargin=16dp</li>
         *   <li>横屏 speed: width=40dp；竖屏 speed: WRAP_CONTENT</li>
         * </ul>
         */
        void applyOrientationUi(boolean landscape) {
            orientationLandscape = landscape;
            // M92d：参数唯一来源是 dimens_reco（values / values-land 各一份），
            // 这里按当前配置读取，杜绝 Java 常量与 XML 双份维护漂移
            android.content.res.Resources res = itemView.getResources();
            // M108-fix：横竖屏资源存在时序差（旋转未落地时仍是竖屏配置），
            // 缺资源时降级为 0 而不是崩溃（推荐页曾因 reco_speed_width 缺失闪退）
            java.util.HashMap<Integer, Integer> dims = new java.util.HashMap<>();
            int[] dimIds = {R.dimen.reco_actions_margin_end, R.dimen.reco_actions_margin_bottom,
                    R.dimen.reco_actions_padding_h, R.dimen.reco_actions_padding_v,
                    R.dimen.reco_progress_margin_left, R.dimen.reco_progress_margin_right,
                    R.dimen.reco_progress_margin_bottom, R.dimen.reco_speed_width};
            for (int id : dimIds) {
                int value = 0;
                try {
                    value = res.getDimensionPixelSize(id);
                } catch (Exception ignored) {
                    // 当前配置缺该资源：降级为 0，不让推荐页闪退
                }
                dims.put(id, value);
            }
            if (actionsContainer != null) {
                ViewGroup.LayoutParams rawParams = actionsContainer.getLayoutParams();
                if (rawParams instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) rawParams;
                    margins.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                    margins.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    int right = dims.get(R.dimen.reco_actions_margin_end);
                    int bottom = dims.get(R.dimen.reco_actions_margin_bottom);
                    margins.setMargins(margins.leftMargin, 0, right, bottom);
                    margins.setMarginEnd(right);
                    margins.setMarginStart(0);
                    if (rawParams instanceof android.widget.FrameLayout.LayoutParams) {
                        ((android.widget.FrameLayout.LayoutParams) rawParams).gravity =
                                android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL;
                    }
                    actionsContainer.setLayoutParams(rawParams);
                }
                int padH = dims.get(R.dimen.reco_actions_padding_h);
                int padV = dims.get(R.dimen.reco_actions_padding_v);
                actionsContainer.setPadding(padH, padV, padH, padV);
                actionsContainer.requestLayout();
            }
            if (progressContainer != null) {
                ViewGroup.LayoutParams rawParams = progressContainer.getLayoutParams();
                if (rawParams instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) rawParams;
                    int right = dims.get(R.dimen.reco_progress_margin_right);
                    int bottom = dims.get(R.dimen.reco_progress_margin_bottom);
                    margins.rightMargin = right;
                    margins.setMarginEnd(right);
                    margins.leftMargin = dims.get(R.dimen.reco_progress_margin_left);
                    margins.setMarginStart(margins.leftMargin);
                    margins.bottomMargin = bottom;
                    progressContainer.setLayoutParams(rawParams);
                }
            }
            if (speed != null) {
                ViewGroup.LayoutParams speedParams = speed.getLayoutParams();
                if (speedParams != null) {
                    speedParams.width = landscape
                            ? dims.get(R.dimen.reco_speed_width)
                            : ViewGroup.LayoutParams.WRAP_CONTENT;
                    speed.setLayoutParams(speedParams);
                }
            }
            if (fullscreen != null) {
                fullscreen.setVisibility(landscape ? View.GONE : View.VISIBLE);
            }
            itemView.requestLayout();
        }

        // M92d：原 STD_* 双份常量组已删除，参数唯一来源见 values(-land)/dimens_reco.xml

        void applyHidePolicy(boolean hide) {
            hideHandler.removeCallbacks(hideRunnable);
            if (hide) {
                collapseActions();
            } else {
                actionsContainer.setVisibility(View.VISIBLE);
                handle.setVisibility(View.GONE);
            }
        }

        /** 收起操作栏、显示右缘把手 */
        void collapseActions() {
            actionsContainer.setVisibility(View.GONE);
            handle.setVisibility(View.VISIBLE);
            hideHandler.removeCallbacks(hideRunnable);
        }

        /** 呼出操作栏、隐藏把手，并启动 5 秒无操作自动收起 */
        void expandActions() {
            actionsContainer.setVisibility(View.VISIBLE);
            handle.setVisibility(View.GONE);
            hideHandler.removeCallbacks(hideRunnable);
            hideHandler.postDelayed(hideRunnable, 5000);
        }

        void toggleActions() {
            if (actionsContainer.getVisibility() == View.VISIBLE) {
                collapseActions();
            } else {
                expandActions();
            }
        }
    }
}
