package com.m3man.data.reco;

import android.content.Context;
import android.text.TextUtils;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.VideoResult;

import java.util.List;

/**
 * 推荐引擎门面：把词典、画像、打分器、持久化串起来，对 UI 只暴露少量方法。
 * 进程内单例（Application Context 持有）。
 *
 * @author 3mman
 */
public class RecoEngine {

    private static volatile RecoEngine sInstance;

    private final Context appContext;
    private final RecoTagDictionary dictionary;
    private final RecoStore store;
    /**
     * v3 P1-1：会话画像（短期兴趣）。仅内存态、进程重启即清空——
     * 会话半衰期默认 90 分钟，不值得落盘；由仓库在出批前按毫秒级衰减。
     */
    private final RecoProfile sessionProfile = new RecoProfile();
    /** M98：volatile——打分线程读、设置页写，保证参数与打分器替换后的可见性 */
    private volatile RecoParams params;
    private volatile RecoScorer scorer;

    private RecoEngine(Context context) {
        this.appContext = context.getApplicationContext();
        this.dictionary = RecoTagDictionary.get(appContext);
        this.store = new RecoStore(appContext);
        this.params = RecoParams.load(appContext);
        this.scorer = new RecoScorer(dictionary, params);
        // v3 P1-3：打分器接线 UCB 统计源（参数重建时同样要重接）
        this.scorer.attachStats(store);
        // M98：启动时先做一次时间衰减；仅在有实际变更时 markDirty，
        // 避免每次启动都无条件触发一次全量落盘
        if (store.getProfile().applyDecay(System.currentTimeMillis(), params.decayDays)) {
            store.markDirty();
        }
        // 词典版本升级（标签体系变更）时自动重置旧画像，避免上一代噪声标签残留污染新推荐
        if (store.getDictVersion() != dictionary.getVersion()) {
            store.reset();
            store.setDictVersion(dictionary.getVersion());
        }
    }

    public static RecoEngine get(Context context) {
        if (sInstance == null) {
            synchronized (RecoEngine.class) {
                if (sInstance == null) {
                    sInstance = new RecoEngine(context);
                }
            }
        }
        return sInstance;
    }

    public RecoParams getParams() {
        return params;
    }

    public RecoScorer getScorer() {
        return scorer;
    }

    public RecoStore getStore() {
        return store;
    }

    public RecoTagDictionary getDictionary() {
        return dictionary;
    }

    public RecoProfile getProfile() {
        return store.getProfile();
    }

    /** v3 P1-1：会话画像（仅内存，重启清空） */
    public RecoProfile getSessionProfile() {
        return sessionProfile;
    }

    /**
     * v3 P1-1：会话画像衰减。由仓库在每次出批/预热前调用，
     * 半衰期 sessionHalfLifeMinutes（默认 90 分钟），毫秒级实算。
     */
    public void decaySession(long nowMillis) {
        sessionProfile.applyDecayMs(nowMillis, params.sessionHalfLifeMinutes);
    }

    /** 参数被用户调整后调用，重建打分器 */
    public void updateParams(RecoParams newParams) {
        if (newParams == null) {
            return;
        }
        newParams.clamp();
        newParams.save(appContext);
        // M98：先在局部完成打分器构建，再一次性发布两个字段，
        // 避免读到「新 params + 旧 scorer」的中间组合
        RecoScorer newScorer = new RecoScorer(dictionary, newParams);
        // v3 P1-3：重建后重接 UCB 统计源
        newScorer.attachStats(store);
        this.params = newParams;
        this.scorer = newScorer;
    }

    public List<String> tagsOf(String title) {
        return dictionary.tokenize(title);
    }

    /** 该视频的当前互动状态（点赞 / 不喜欢 / 收藏 / 无） */
    public int actionOf(String viewKey) {
        return store.getAction(viewKey);
    }

    /**
     * 点赞 / 取消点赞。
     *
     * @return 操作后是否为已点赞
     */
    public boolean toggleLike(RecoCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        String key = candidate.viewKey();
        int cur = store.getAction(key);
        List<String> tags = ensureTags(candidate);
        if (cur == RecoStore.ACTION_LIKE) {
            likeBoth(candidate, tags, -1);
            store.setAction(key, 0);
            return false;
        }
        if (cur == RecoStore.ACTION_DISLIKE) {
            // 先撤销不喜欢，再点赞
            dislikeBoth(candidate, tags, -1);
        }
        likeBoth(candidate, tags, 1);
        store.setAction(key, RecoStore.ACTION_LIKE);
        return true;
    }

    /**
     * 不喜欢 / 取消不喜欢。注意：不喜欢只降权，不做永久屏蔽。
     *
     * @return 操作后是否为已标记不喜欢
     */
    public boolean toggleDislike(RecoCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        String key = candidate.viewKey();
        int cur = store.getAction(key);
        List<String> tags = ensureTags(candidate);
        if (cur == RecoStore.ACTION_DISLIKE) {
            dislikeBoth(candidate, tags, -1);
            store.setAction(key, 0);
            return false;
        }
        if (cur == RecoStore.ACTION_LIKE) {
            likeBoth(candidate, tags, -1);
        }
        dislikeBoth(candidate, tags, 1);
        store.setAction(key, RecoStore.ACTION_DISLIKE);
        return true;
    }

    /** 收藏（收藏本身的落库由调用方处理，这里只更新画像） */
    public void onFavorite(RecoCandidate candidate) {
        if (candidate == null) {
            return;
        }
        List<String> tags = ensureTags(candidate);
        favoriteBoth(candidate, tags, 1);
        // 收藏不覆盖已有的赞 / 不喜欢状态（三者共用一个 action 槽位）
        if (store.getAction(candidate.viewKey()) == 0) {
            store.setAction(candidate.viewKey(), RecoStore.ACTION_FAVORITE);
        } else {
            store.markDirty();
        }
    }

    /** 取消收藏（落库同样由调用方处理） */
    public void onUnfavorite(RecoCandidate candidate) {
        if (candidate == null) {
            return;
        }
        List<String> tags = ensureTags(candidate);
        favoriteBoth(candidate, tags, -1);
        if (store.getAction(candidate.viewKey()) == RecoStore.ACTION_FAVORITE) {
            store.setAction(candidate.viewKey(), 0);
        } else {
            store.markDirty();
        }
    }

    // ==================== v3：双时间尺度反馈写回 ====================

    /** 点赞方向反馈（dir=1 点赞 / -1 撤销），长期画像走 scorer 计数包装，会话画像只写权重 */
    private void likeBoth(RecoCandidate candidate, List<String> tags, int dir) {
        RecoProfile profile = store.getProfile();
        if (dir > 0) {
            scorer.onLike(profile, tags, candidate.categoryValue, candidate.authorKey);
            bumpPositiveStats(candidate, tags);
        } else {
            scorer.onUnlike(profile, tags, candidate.categoryValue, candidate.authorKey);
        }
        scorer.applyFeedback(sessionProfile, tags, candidate.categoryValue,
                candidate.authorKey, dir * params.likeBoost);
        bumpSoftPrefs(candidate, dir * params.likeBoost);
    }

    /** 不喜欢方向反馈（dir=1 标记 / -1 撤销） */
    private void dislikeBoth(RecoCandidate candidate, List<String> tags, int dir) {
        RecoProfile profile = store.getProfile();
        if (dir > 0) {
            scorer.onDislike(profile, tags, candidate.categoryValue, candidate.authorKey);
        } else {
            scorer.onUndislike(profile, tags, candidate.categoryValue, candidate.authorKey);
        }
        // 不喜欢的原始 delta 是负的 penalty：标记时 -penalty，撤销时 +penalty
        scorer.applyFeedback(sessionProfile, tags, candidate.categoryValue,
                candidate.authorKey, -dir * params.dislikePenalty);
        bumpSoftPrefs(candidate, -dir * params.dislikePenalty);
    }

    /** 收藏方向反馈（dir=1 收藏 / -1 取消） */
    private void favoriteBoth(RecoCandidate candidate, List<String> tags, int dir) {
        RecoProfile profile = store.getProfile();
        if (dir > 0) {
            scorer.onFavorite(profile, tags, candidate.categoryValue, candidate.authorKey);
            bumpPositiveStats(candidate, tags);
        } else {
            scorer.onUnfavorite(profile, tags, candidate.categoryValue, candidate.authorKey);
        }
        scorer.applyFeedback(sessionProfile, tags, candidate.categoryValue,
                candidate.authorKey, dir * params.favoriteBoost);
        bumpSoftPrefs(candidate, dir * params.favoriteBoost);
    }

    /** v3 P1-2：时长桶 / 方向桶软偏好随任何反馈同步学习（0.3 倍缩放） */
    private void bumpSoftPrefs(RecoCandidate candidate, double delta) {
        if (candidate == null || candidate.item == null || delta == 0.0d) {
            return;
        }
        double scaled = delta * 0.3d;
        String db = RecoScorer.durationBucket(candidate.item.getDuration());
        if (db != null) {
            store.getProfile().bumpDuration(db, scaled);
            sessionProfile.bumpDuration(db, scaled);
        }
        String ob = RecoScorer.orientationBucket(candidate.orientation);
        if (ob != null) {
            store.getProfile().bumpOrientation(ob, scaled);
            sessionProfile.bumpOrientation(ob, scaled);
        }
    }

    /** v3 P1-3：正反馈计入 UCB 特征统计（分类键 + 最高 idf 标签键） */
    private void bumpPositiveStats(RecoCandidate candidate, List<String> tags) {
        if (!TextUtils.isEmpty(candidate.categoryValue)) {
            store.bumpStatPositive("c:" + candidate.categoryValue);
        }
        String top = RecoScorer.topIdfTag(tags, dictionary);
        if (top != null) {
            store.bumpStatPositive("t:" + top);
        }
    }

    // ==================== v3 P0-1：隐式反馈结算 ====================

    /** 极速划过（未起播且停留不足 1.5s）大概率是误触/快速找内容，不学习 */
    private static final long QUICK_FLICK_MS = 1500L;
    /** 有效播放不足 4s 视为秒划 */
    private static final long MIN_PLAYED_MS = 4000L;
    /** 按最高进度判完播时，还需至少真实播放 8s（防 seek 到片尾骗完播加成） */
    private static final long FINISH_MIN_PLAYED_MS = 8000L;

    /**
     * 离开一页时结算隐式反馈（v3 P0-1 重写：秒划负反馈 + 分级中段 + 防重复加权）。
     *
     * @param playbackStarted 该页是否真正起播过
     * @param maxRatio        本次停留内到达过的最高播放进度（0~1，防 seek 骗分用历史最高）
     * @param playedMs        累计有效播放毫秒数（暂停/seek 不计）
     * @param dwellMs         本页停留毫秒数
     */
    public void onImplicitSettle(RecoCandidate candidate, boolean playbackStarted,
                                 float maxRatio, long playedMs, long dwellMs) {
        if (candidate == null) {
            return;
        }
        Double delta = null;
        boolean positive = false;
        if (!playbackStarted && dwellMs >= 0L && dwellMs < QUICK_FLICK_MS) {
            // 信号太弱，不学习
        } else if (!playbackStarted || playedMs < MIN_PLAYED_MS) {
            // v3 核心修复：起播前划走/看了几秒就走，此前完全不学习，现在是最强负信号
            delta = (double) -params.quickSkipPenalty;
            positive = false;
        } else if (maxRatio >= 0.92f && playedMs >= FINISH_MIN_PLAYED_MS) {
            delta = (double) params.finishBoost;
            positive = true;
        } else if (maxRatio >= 0.7f) {
            delta = 0.5d * params.finishBoost;
            positive = true;
        } else if (maxRatio >= 0.4f) {
            // 中性区间，不学习
        } else if (maxRatio > 0.15f) {
            delta = -0.4d * params.skipPenalty;
            positive = false;
        } else {
            delta = (double) -params.skipPenalty;
            positive = false;
        }
        if (delta == null || delta == 0.0d) {
            return;
        }
        applyImplicit(candidate, delta, positive);
    }

    /** 自然完播事件（连播触发，最强正反馈），同样受每视频每极性一次封顶保护 */
    public void onImplicitFinish(RecoCandidate candidate) {
        if (candidate == null) {
            return;
        }
        applyImplicit(candidate, params.finishBoost, true);
    }

    /**
     * 隐式反馈落地：长期 + 会话双写、软偏好同步、每视频每极性首次全额、
     * 之后重复结算按 0.15 倍折减（防 A→B→A 来回刷反复加权）。
     */
    private void applyImplicit(RecoCandidate candidate, double delta, boolean positive) {
        List<String> tags = ensureTags(candidate);
        String key = candidate.viewKey();
        int flags = store.implicitFlags(key);
        boolean done = (flags & (positive
                ? RecoStore.IMPLICIT_POS_DONE : RecoStore.IMPLICIT_NEG_DONE)) != 0;
        boolean firstEver = flags == 0;
        double applied = done ? delta * 0.15d : delta;
        RecoProfile longP = store.getProfile();
        scorer.applyFeedback(longP, tags, candidate.categoryValue, candidate.authorKey, applied);
        scorer.applyFeedback(sessionProfile, tags, candidate.categoryValue,
                candidate.authorKey, applied);
        bumpSoftPrefs(candidate, applied);
        if (firstEver) {
            longP.watchCount++;
        }
        if (!done) {
            store.markImplicit(key, positive);
            if (positive) {
                bumpPositiveStats(candidate, tags);
            }
        }
        store.markDirty();
    }

    /** 标记已曝光，后续召回会自动去重 */
    public void markSeen(String viewKey) {
        store.markSeen(viewKey);
    }

    /** 解析出作者后回填到候选与画像键上 */
    public void attachAuthor(RecoCandidate candidate, V9MmanItem item) {
        if (candidate == null || item == null) {
            return;
        }
        try {
            VideoResult r = item.getVideoResult();
            if (r == null) {
                return;
            }
            if (!TextUtils.isEmpty(r.getOwnerId())) {
                candidate.authorKey = r.getOwnerId();
            }
            if (!TextUtils.isEmpty(r.getOwnerName())) {
                candidate.authorName = r.getOwnerName();
            }
            // 画像里只存作者 id，这里顺手把昵称缓存下来，供「学习记录」展示
            store.putAuthorName(candidate.authorKey, candidate.authorName);
        } catch (Exception ignored) {
            // getVideoResult 在实体脱离 DaoSession 时会抛 DaoException
        }
    }

    private List<String> ensureTags(RecoCandidate candidate) {
        if (candidate.tags == null) {
            candidate.tags = dictionary.tokenize(candidate.title());
        }
        return candidate.tags;
    }

    /** 落盘（IO 线程调用） */
    public void persist() {
        store.save();
    }

    /** 清空推荐记忆 */
    public void resetMemory() {
        store.reset();
    }
}
