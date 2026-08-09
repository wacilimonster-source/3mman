package com.m3man.data.reco;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 推荐算法的可人工调节参数。
 * <p>
 * 独立使用一个 SharedPreferences 文件（reco_prefs），不侵入 AppPreferencesHelper，
 * 避免影响既有设置项的读写与迁移。
 *
 * @author 3mman
 */
public class RecoParams {

    public static final String PREF_NAME = "reco_prefs";

    private static final String K_LIKE_BOOST = "like_boost";
    private static final String K_DISLIKE_PENALTY = "dislike_penalty";
    private static final String K_FAVORITE_BOOST = "favorite_boost";
    private static final String K_EXPLORATION_RATE = "exploration_rate";
    private static final String K_RECENCY_BIAS = "recency_bias";
    private static final String K_DECAY_DAYS = "decay_days";
    private static final String K_PREFETCH_AHEAD = "prefetch_ahead";
    private static final String K_AUTHOR_RECALL_RATIO = "author_recall_ratio";
    private static final String K_ENABLE_DISLIKE_FILTER = "enable_dislike_filter";
    private static final String K_TAG_COEF = "tag_coef";
    private static final String K_CATEGORY_COEF = "category_coef";
    private static final String K_AUTHOR_COEF = "author_coef";
    private static final String K_FINISH_BOOST = "finish_boost";
    private static final String K_SKIP_PENALTY = "skip_penalty";
    private static final String K_MAX_AGE_YEARS = "max_age_years";

    /** 点赞对画像的正向加权 */
    public static final float DEF_LIKE_BOOST = 3.0f;
    /** 不喜欢对画像的负向加权（不是永久屏蔽，只是降权） */
    public static final float DEF_DISLIKE_PENALTY = 5.0f;
    /** 收藏对画像的正向加权（比点赞更强） */
    public static final float DEF_FAVORITE_BOOST = 4.0f;
    /** ε-greedy 探索比例：15% 的位置放"非最优但新鲜"的内容 */
    public static final float DEF_EXPLORATION_RATE = 0.15f;
    /** 新片加成系数 */
    public static final float DEF_RECENCY_BIAS = 1.0f;
    /** 兴趣半衰期（天） */
    public static final int DEF_DECAY_DAYS = 30;
    /** 预加载条数（当前页之后再预解析 N 条） */
    public static final int DEF_PREFETCH_AHEAD = 3;
    /** 作者召回占比 */
    public static final float DEF_AUTHOR_RECALL_RATIO = 0.30f;
    /** 是否把强不喜欢的作者直接过滤掉（默认关闭，仅降权） */
    public static final boolean DEF_ENABLE_DISLIKE_FILTER = false;
    /** 标题标签得分权重 */
    public static final float DEF_TAG_COEF = 1.0f;
    /** 分类得分权重 */
    public static final float DEF_CATEGORY_COEF = 0.6f;
    /** 作者得分权重 */
    public static final float DEF_AUTHOR_COEF = 1.2f;
    /** 完播加成 */
    public static final float DEF_FINISH_BOOST = 1.0f;
    /** 秒划惩罚 */
    public static final float DEF_SKIP_PENALTY = 0.8f;
    /** 推荐可纳入的最长发布年限（默认 10 年）：超出该年限的老片不进入推荐池 */
    public static final int DEF_MAX_AGE_YEARS = 10;

    public float likeBoost = DEF_LIKE_BOOST;
    public float dislikePenalty = DEF_DISLIKE_PENALTY;
    public float favoriteBoost = DEF_FAVORITE_BOOST;
    public float explorationRate = DEF_EXPLORATION_RATE;
    public float recencyBias = DEF_RECENCY_BIAS;
    public int decayDays = DEF_DECAY_DAYS;
    public int prefetchAhead = DEF_PREFETCH_AHEAD;
    public float authorRecallRatio = DEF_AUTHOR_RECALL_RATIO;
    public boolean enableDislikeFilter = DEF_ENABLE_DISLIKE_FILTER;
    public float tagCoef = DEF_TAG_COEF;
    public float categoryCoef = DEF_CATEGORY_COEF;
    public float authorCoef = DEF_AUTHOR_COEF;
    public float finishBoost = DEF_FINISH_BOOST;
    public float skipPenalty = DEF_SKIP_PENALTY;
    public int maxAgeYears = DEF_MAX_AGE_YEARS;

    private RecoParams() {
    }

    public static RecoParams load(Context context) {
        RecoParams p = new RecoParams();
        if (context == null) {
            return p;
        }
        SharedPreferences sp = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        p.likeBoost = sp.getFloat(K_LIKE_BOOST, DEF_LIKE_BOOST);
        p.dislikePenalty = sp.getFloat(K_DISLIKE_PENALTY, DEF_DISLIKE_PENALTY);
        p.favoriteBoost = sp.getFloat(K_FAVORITE_BOOST, DEF_FAVORITE_BOOST);
        p.explorationRate = sp.getFloat(K_EXPLORATION_RATE, DEF_EXPLORATION_RATE);
        p.recencyBias = sp.getFloat(K_RECENCY_BIAS, DEF_RECENCY_BIAS);
        p.decayDays = sp.getInt(K_DECAY_DAYS, DEF_DECAY_DAYS);
        p.prefetchAhead = sp.getInt(K_PREFETCH_AHEAD, DEF_PREFETCH_AHEAD);
        p.authorRecallRatio = sp.getFloat(K_AUTHOR_RECALL_RATIO, DEF_AUTHOR_RECALL_RATIO);
        p.enableDislikeFilter = sp.getBoolean(K_ENABLE_DISLIKE_FILTER, DEF_ENABLE_DISLIKE_FILTER);
        p.tagCoef = sp.getFloat(K_TAG_COEF, DEF_TAG_COEF);
        p.categoryCoef = sp.getFloat(K_CATEGORY_COEF, DEF_CATEGORY_COEF);
        p.authorCoef = sp.getFloat(K_AUTHOR_COEF, DEF_AUTHOR_COEF);
        p.finishBoost = sp.getFloat(K_FINISH_BOOST, DEF_FINISH_BOOST);
        p.skipPenalty = sp.getFloat(K_SKIP_PENALTY, DEF_SKIP_PENALTY);
        p.maxAgeYears = sp.getInt(K_MAX_AGE_YEARS, DEF_MAX_AGE_YEARS);
        p.clamp();
        return p;
    }

    public void save(Context context) {
        if (context == null) {
            return;
        }
        clamp();
        context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(K_LIKE_BOOST, likeBoost)
                .putFloat(K_DISLIKE_PENALTY, dislikePenalty)
                .putFloat(K_FAVORITE_BOOST, favoriteBoost)
                .putFloat(K_EXPLORATION_RATE, explorationRate)
                .putFloat(K_RECENCY_BIAS, recencyBias)
                .putInt(K_DECAY_DAYS, decayDays)
                .putInt(K_PREFETCH_AHEAD, prefetchAhead)
                .putFloat(K_AUTHOR_RECALL_RATIO, authorRecallRatio)
                .putBoolean(K_ENABLE_DISLIKE_FILTER, enableDislikeFilter)
                .putFloat(K_TAG_COEF, tagCoef)
                .putFloat(K_CATEGORY_COEF, categoryCoef)
                .putFloat(K_AUTHOR_COEF, authorCoef)
        .putFloat(K_FINISH_BOOST, finishBoost)
        .putFloat(K_SKIP_PENALTY, skipPenalty)
        .putInt(K_MAX_AGE_YEARS, maxAgeYears)
        .apply();
    }

    /** 恢复默认参数 */
    public void resetToDefault() {
        likeBoost = DEF_LIKE_BOOST;
        dislikePenalty = DEF_DISLIKE_PENALTY;
        favoriteBoost = DEF_FAVORITE_BOOST;
        explorationRate = DEF_EXPLORATION_RATE;
        recencyBias = DEF_RECENCY_BIAS;
        decayDays = DEF_DECAY_DAYS;
        prefetchAhead = DEF_PREFETCH_AHEAD;
        authorRecallRatio = DEF_AUTHOR_RECALL_RATIO;
        enableDislikeFilter = DEF_ENABLE_DISLIKE_FILTER;
        tagCoef = DEF_TAG_COEF;
        categoryCoef = DEF_CATEGORY_COEF;
        authorCoef = DEF_AUTHOR_COEF;
        finishBoost = DEF_FINISH_BOOST;
        skipPenalty = DEF_SKIP_PENALTY;
        maxAgeYears = DEF_MAX_AGE_YEARS;
    }

    /** 参数越界保护，避免用户拖出病态值导致推荐完全失效 */
    public void clamp() {
        likeBoost = clampF(likeBoost, 0f, 20f);
        dislikePenalty = clampF(dislikePenalty, 0f, 20f);
        favoriteBoost = clampF(favoriteBoost, 0f, 20f);
        explorationRate = clampF(explorationRate, 0f, 0.8f);
        recencyBias = clampF(recencyBias, 0f, 5f);
        decayDays = clampI(decayDays, 1, 365);
        prefetchAhead = clampI(prefetchAhead, 0, 8);
        authorRecallRatio = clampF(authorRecallRatio, 0f, 1f);
        tagCoef = clampF(tagCoef, 0f, 5f);
        categoryCoef = clampF(categoryCoef, 0f, 5f);
        authorCoef = clampF(authorCoef, 0f, 5f);
        finishBoost = clampF(finishBoost, 0f, 10f);
        skipPenalty = clampF(skipPenalty, 0f, 10f);
        maxAgeYears = clampI(maxAgeYears, 1, 20);
    }

    private static float clampF(float v, float min, float max) {
        if (Float.isNaN(v)) {
            return min;
        }
        return v < min ? min : (v > max ? max : v);
    }

    private static int clampI(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}
