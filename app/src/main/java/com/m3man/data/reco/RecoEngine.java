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
    private RecoParams params;
    private RecoScorer scorer;

    private RecoEngine(Context context) {
        this.appContext = context.getApplicationContext();
        this.dictionary = RecoTagDictionary.get(appContext);
        this.store = new RecoStore(appContext);
        this.params = RecoParams.load(appContext);
        this.scorer = new RecoScorer(dictionary, params);
        // 启动时先做一次时间衰减
        store.getProfile().applyDecay(System.currentTimeMillis(), params.decayDays);
        store.markDirty();
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

    /** 参数被用户调整后调用，重建打分器 */
    public void updateParams(RecoParams newParams) {
        if (newParams == null) {
            return;
        }
        newParams.clamp();
        newParams.save(appContext);
        this.params = newParams;
        this.scorer = new RecoScorer(dictionary, newParams);
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
        RecoProfile profile = store.getProfile();
        List<String> tags = ensureTags(candidate);
        if (cur == RecoStore.ACTION_LIKE) {
            scorer.onUnlike(profile, tags, candidate.categoryValue, candidate.authorKey);
            store.setAction(key, 0);
            return false;
        }
        if (cur == RecoStore.ACTION_DISLIKE) {
            // 先撤销不喜欢，再点赞
            scorer.onUndislike(profile, tags, candidate.categoryValue, candidate.authorKey);
        }
        scorer.onLike(profile, tags, candidate.categoryValue, candidate.authorKey);
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
        RecoProfile profile = store.getProfile();
        List<String> tags = ensureTags(candidate);
        if (cur == RecoStore.ACTION_DISLIKE) {
            scorer.onUndislike(profile, tags, candidate.categoryValue, candidate.authorKey);
            store.setAction(key, 0);
            return false;
        }
        if (cur == RecoStore.ACTION_LIKE) {
            scorer.onUnlike(profile, tags, candidate.categoryValue, candidate.authorKey);
        }
        scorer.onDislike(profile, tags, candidate.categoryValue, candidate.authorKey);
        store.setAction(key, RecoStore.ACTION_DISLIKE);
        return true;
    }

    /** 收藏（收藏本身的落库由调用方处理，这里只更新画像） */
    public void onFavorite(RecoCandidate candidate) {
        if (candidate == null) {
            return;
        }
        scorer.onFavorite(store.getProfile(), ensureTags(candidate),
                candidate.categoryValue, candidate.authorKey);
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
        scorer.onUnfavorite(store.getProfile(), ensureTags(candidate),
                candidate.categoryValue, candidate.authorKey);
        if (store.getAction(candidate.viewKey()) == RecoStore.ACTION_FAVORITE) {
            store.setAction(candidate.viewKey(), 0);
        } else {
            store.markDirty();
        }
    }

    /** 标记已曝光，后续召回会自动去重 */
    public void markSeen(String viewKey) {
        store.markSeen(viewKey);
    }

    /** 隐式反馈：观看比例 */
    public void onWatchRatio(RecoCandidate candidate, float ratio) {
        if (candidate == null) {
            return;
        }
        scorer.onWatchRatio(store.getProfile(), ensureTags(candidate),
                candidate.categoryValue, candidate.authorKey, ratio);
        store.markDirty();
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
