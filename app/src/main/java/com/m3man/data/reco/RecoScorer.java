package com.m3man.data.reco;

import android.text.TextUtils;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容式（content-based）打分器。
 * <p>
 * 分数 = 标签得分 + 分类得分 + 作者得分 + 新鲜度加成 + 探索噪声
 * <ul>
 *   <li>标签得分：Σ profile.tag(t) * idf(t) / sqrt(标签数)。长度归一化避免长标题天然占优。</li>
 *   <li>分类得分：profile.category(c) * categoryCoef。</li>
 *   <li>作者得分：只有当本地已知作者（该视频解析过）时才生效。</li>
 *   <li>新鲜度：分类页天然按时间倒序，用列表内位置近似"新"，越靠前加成越高。</li>
 *   <li>探索噪声：ε-greedy，以 explorationRate 的概率给候选注入较大随机量，
 *       保证画像不会把用户锁死在一个小圈子里。</li>
 * </ul>
 *
 * @author 3mman
 */
public class RecoScorer {

    /** 「添加时间：2024-05-01」这类信息里抽出播放量/评分做轻微热度加权 */
    private static final Pattern VIEW_PATTERN = Pattern.compile("([0-9]{2,})");

    private final RecoTagDictionary dictionary;
    private final RecoParams params;
    private final Random random;

    public RecoScorer(RecoTagDictionary dictionary, RecoParams params) {
        this(dictionary, params, new Random());
    }

    public RecoScorer(RecoTagDictionary dictionary, RecoParams params, Random random) {
        this.dictionary = dictionary;
        this.params = params;
        this.random = random == null ? new Random() : random;
    }

    /**
     * 给单个候选打分（结果写回 candidate）。
     *
     * @param candidate     候选
     * @param profile       画像
     * @param positionInList 候选在其召回列表中的位置（0 表示最新/最靠前）
     * @param listSize      召回列表长度
     */
    public void score(RecoCandidate candidate, RecoProfile profile, int positionInList, int listSize) {
        if (candidate == null || candidate.item == null) {
            return;
        }
        if (candidate.tags == null) {
            candidate.tags = dictionary.tokenize(candidate.title());
        }
        candidate.tagScore = computeTagScore(candidate.tags, profile) * params.tagCoef;
        candidate.categoryScore = profile.category(candidate.categoryValue) * params.categoryCoef;
        candidate.authorScore = TextUtils.isEmpty(candidate.authorKey)
                ? 0.0d
                : profile.author(candidate.authorKey) * params.authorCoef;
        candidate.recencyScore = computeRecency(positionInList, listSize) * params.recencyBias;
        candidate.noise = computeNoise();

        // 作者召回来的内容天然更贴合画像，给一点固定加成，避免被分类召回淹没
        double sourceBonus = candidate.from == RecoCandidate.FROM_AUTHOR ? 0.8d : 0.0d;

        candidate.score = candidate.tagScore
                + candidate.categoryScore
                + candidate.authorScore
                + candidate.recencyScore
                + sourceBonus
                + candidate.noise;
    }

    double computeTagScore(List<String> tags, RecoProfile profile) {
        if (tags == null || tags.isEmpty()) {
            return 0.0d;
        }
        double sum = 0.0d;
        for (String t : tags) {
            double w = profile.tag(t);
            if (w == 0.0d) {
                continue;
            }
            sum += w * dictionary.idf(t);
        }
        return sum / Math.sqrt(tags.size());
    }

    private double computeRecency(int positionInList, int listSize) {
        if (listSize <= 1) {
            return 0.0d;
        }
        int pos = positionInList < 0 ? 0 : positionInList;
        // 线性衰减：第 0 位 +1.0，末位 0
        return 1.0d - ((double) pos / (double) (listSize - 1));
    }

    /**
     * ε-greedy：以 explorationRate 的概率给出一个较大的正噪声（把非最优内容顶上来），
     * 其余时候只给极小抖动打散同分项。
     */
    private double computeNoise() {
        if (params.explorationRate > 0f && random.nextFloat() < params.explorationRate) {
            return 3.0d + random.nextDouble() * 3.0d;
        }
        return random.nextDouble() * 0.05d;
    }

    /** 按分数从高到低排序 */
    public static void sortByScoreDesc(List<RecoCandidate> list) {
        if (list == null || list.size() < 2) {
            return;
        }
        Collections.sort(list, new Comparator<RecoCandidate>() {
            @Override
            public int compare(RecoCandidate a, RecoCandidate b) {
                return Double.compare(b.score, a.score);
            }
        });
    }

    // ==================== 反馈写回 ====================

    /**
     * 用一次交互更新画像。
     *
     * @param profile  画像
     * @param tags     该视频的标签
     * @param category 分类
     * @param authorKey 作者（可空）
     * @param delta    正=喜欢，负=不喜欢，绝对值越大影响越强
     */
    public void applyFeedback(RecoProfile profile, List<String> tags, String category,
                              String authorKey, double delta) {
        if (profile == null || delta == 0.0d) {
            return;
        }
        if (tags != null && !tags.isEmpty()) {
            // 标签数越多，单个标签分到的信号越弱（避免长标题污染画像）
            double per = delta / Math.sqrt(tags.size());
            for (String t : tags) {
                // idf 高（冷门）的词学习得更快
                double scaled = per * (dictionary.idf(t) / 3.0d);
                profile.bumpTag(t, scaled);
            }
        }
        if (!TextUtils.isEmpty(category)) {
            profile.bumpCategory(category, delta * 0.35d);
        }
        if (!TextUtils.isEmpty(authorKey)) {
            profile.bumpAuthor(authorKey, delta * 0.8d);
        }
    }

    public void onLike(RecoProfile profile, List<String> tags, String category, String authorKey) {
        applyFeedback(profile, tags, category, authorKey, params.likeBoost);
        profile.likeCount++;
    }

    public void onUnlike(RecoProfile profile, List<String> tags, String category, String authorKey) {
        applyFeedback(profile, tags, category, authorKey, -params.likeBoost);
        if (profile.likeCount > 0) {
            profile.likeCount--;
        }
    }

    public void onFavorite(RecoProfile profile, List<String> tags, String category, String authorKey) {
        applyFeedback(profile, tags, category, authorKey, params.favoriteBoost);
        profile.favoriteCount++;
    }

    /** 取消收藏：把收藏时加的权重原样退回 */
    public void onUnfavorite(RecoProfile profile, List<String> tags, String category, String authorKey) {
        applyFeedback(profile, tags, category, authorKey, -params.favoriteBoost);
        if (profile.favoriteCount > 0) {
            profile.favoriteCount--;
        }
    }

    public void onDislike(RecoProfile profile, List<String> tags, String category, String authorKey) {
        applyFeedback(profile, tags, category, authorKey, -params.dislikePenalty);
        profile.dislikeCount++;
    }

    /** 撤销不喜欢 */
    public void onUndislike(RecoProfile profile, List<String> tags, String category, String authorKey) {
        applyFeedback(profile, tags, category, authorKey, params.dislikePenalty);
        if (profile.dislikeCount > 0) {
            profile.dislikeCount--;
        }
    }

    /**
     * 隐式反馈：观看时长比例。
     *
     * @param ratio 0~1，>=0.7 视为完播正反馈；<=0.15 视为秒划负反馈；中间不学习
     */
    public void onWatchRatio(RecoProfile profile, List<String> tags, String category,
                             String authorKey, float ratio) {
        if (profile == null) {
            return;
        }
        profile.watchCount++;
        if (ratio >= 0.7f) {
            applyFeedback(profile, tags, category, authorKey, params.finishBoost);
        } else if (ratio > 0f && ratio <= 0.15f) {
            applyFeedback(profile, tags, category, authorKey, -params.skipPenalty);
        }
    }

    /** 从 info 文本里抽播放量做极轻的热度先验（无则 0） */
    public static double parseHotness(String info) {
        if (TextUtils.isEmpty(info)) {
            return 0.0d;
        }
        Matcher m = VIEW_PATTERN.matcher(info);
        long max = 0L;
        while (m.find()) {
            try {
                long v = Long.parseLong(m.group(1));
                if (v > max) {
                    max = v;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (max <= 0L) {
            return 0.0d;
        }
        return Math.min(1.0d, Math.log10(max + 1.0d) / 6.0d);
    }
}
