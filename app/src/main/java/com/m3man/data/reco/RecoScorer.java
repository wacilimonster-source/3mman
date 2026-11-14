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
 * v3 起分数 = 标签分 + 分类分 + 作者分 + 新鲜度 + 热度先验 + 软偏好 + UCB 乐观项 + 同分抖动，
 * 其中标签/分类/作者/软偏好均为「长期画像 + sessionCoef × 会话画像」双时间尺度混合：
 * <ul>
 *   <li>标签得分：Σ profile.tag(t) * idf(t) / sqrt(标签数)。长度归一化避免长标题天然占优。</li>
 *   <li>分类得分：profile.category(c) * categoryCoef。</li>
 *   <li>作者得分：只有当本地已知作者时生效；"n:" 前缀是列表页作者名键（重名风险），系数打折。</li>
 *   <li>新鲜度（M77）：优先用候选上记录的真实发布年份做「越老扣越多」的软衰减。</li>
 *   <li>热度先验（v3 P0-3）：info 里的播放量做轻量热度分，冷启动阶段翻倍——
 *       新用户无画像时优先出热门，而不是随机。</li>
 *   <li>软偏好（v3 P1-2）：时长桶 / 方向桶权重；方向在严格筛选下对同筛选候选是常数偏移，
 *       天然只在「全部」时影响排序。</li>
 *   <li>UCB 乐观项（v3 P1-3）：曝光少的特征（分类/主标签）天然加分，与画像分正交。</li>
 *   <li>探索坑位（M77）：出队时每批保留固定坑位给乐观项最高的探索候选，
 *       见 {@code RecoRepository.take}。这里只保留极小随机抖动用于打散同分项。</li>
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
    /** v3 P1-3：UCB 统计数据源（RecoStore），可为 null（单测/降级时乐观项恒 0） */
    private volatile StatSource stats;

    /** UCB 乐观项的数据源接口，由 {@link RecoStore} 实现 */
    public interface StatSource {
        long served(String key);

        long totalServed();
    }

    public RecoScorer(RecoTagDictionary dictionary, RecoParams params) {
        this(dictionary, params, new Random());
    }

    public RecoScorer(RecoTagDictionary dictionary, RecoParams params, Random random) {
        this(dictionary, params, random, null);
    }

    public RecoScorer(RecoTagDictionary dictionary, RecoParams params, Random random, StatSource stats) {
        this.dictionary = dictionary;
        this.params = params;
        this.random = random == null ? new Random() : random;
        this.stats = stats;
    }

    /** v3 P1-3：接线 UCB 统计源（引擎在构造/重建打分器后调用） */
    public void attachStats(StatSource source) {
        this.stats = source;
    }

    /**
     * 给单个候选打分（结果写回 candidate）。长期画像与会话画像按 sessionCoef 混合。
     *
     * @param candidate     候选
     * @param profile       长期画像
     * @param session       会话画像（短期兴趣，可为 null=不叠加）
     * @param positionInList 候选在其召回列表中的位置（0 表示最新/最靠前）
     * @param listSize      召回列表长度
     */
    public void score(RecoCandidate candidate, RecoProfile profile, RecoProfile session,
                      int positionInList, int listSize) {
        if (candidate == null || candidate.item == null) {
            return;
        }
        if (candidate.tags == null) {
            candidate.tags = dictionary.tokenize(candidate.title());
        }
        double ses = session == null ? 0.0d : params.sessionCoef;
        RecoProfile sess = session == null ? profile : session;
        candidate.tagScore = (computeTagScore(candidate.tags, profile)
                + ses * computeTagScore(candidate.tags, sess)) * params.tagCoef;
        candidate.categoryScore = (profile.category(candidate.categoryValue)
                + ses * sess.category(candidate.categoryValue)) * params.categoryCoef;
        candidate.authorScore = TextUtils.isEmpty(candidate.authorKey)
                ? 0.0d
                : (profile.author(candidate.authorKey) + ses * sess.author(candidate.authorKey))
                * params.authorCoef * authorKeyFactor(candidate.authorKey);
        candidate.recencyScore = computeRecency(candidate, positionInList, listSize) * params.recencyBias;
        candidate.noise = computeNoise();
        candidate.hotScore = computeHotness(candidate, profile);
        candidate.softScore = computeSoftScore(candidate, profile, sess, ses);
        candidate.exploreScore = optimismOf(stats, params.exploreCoef, statKeys(candidate));

        // 作者召回来的内容天然更贴合画像，给一点固定加成，避免被分类召回淹没
        double sourceBonus = candidate.from == RecoCandidate.FROM_AUTHOR ? 0.8d : 0.0d;

        candidate.score = candidate.tagScore
                + candidate.categoryScore
                + candidate.authorScore
                + candidate.recencyScore
                + candidate.hotScore
                + candidate.softScore
                + candidate.exploreScore
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

    /** v3 P0-2："n:" 作者名键（列表页可得，但有重名风险）系数打折，真 uid 不打折 */
    static double authorKeyFactor(String authorKey) {
        return authorKey != null && authorKey.startsWith("n:") ? 0.6d : 1.0d;
    }

    /**
     * v3 P0-3：热度先验。冷启动（显式互动 + 完整看过的不满 10 条）时系数翻倍，
     * 让无画像阶段优先出热门内容。
     */
    private double computeHotness(RecoCandidate candidate, RecoProfile profile) {
        if (candidate == null || candidate.item == null || params.hotnessCoef <= 0f) {
            return 0.0d;
        }
        boolean cold = profile.likeCount.get() + profile.dislikeCount.get()
                + profile.favoriteCount.get() + profile.watchCount.get() < 10;
        return parseHotness(candidate.item.getInfo()) * params.hotnessCoef * (cold ? 2.0d : 1.0d);
    }

    /** v3 P1-2：时长桶 + 方向桶软偏好分；未知桶不参与 */
    private double computeSoftScore(RecoCandidate candidate, RecoProfile profile,
                                    RecoProfile session, double ses) {
        if (candidate == null || candidate.item == null) {
            return 0.0d;
        }
        double out = 0.0d;
        String db = durationBucket(candidate.item.getDuration());
        if (db != null) {
            out += (profile.duration(db) + ses * session.duration(db)) * params.durationCoef;
        }
        String ob = orientationBucket(candidate.orientation);
        if (ob != null) {
            out += (profile.orientation(ob) + ses * session.orientation(ob)) * params.orientationCoef;
        }
        return out;
    }

    /** v3 P1-2：时长字符串 → 时长桶键（d:0≤1min / d:1 1~3 / d:2 3~10 / d:3>10）；无法解析返回 null */
    public static String durationBucket(String rawDuration) {
        Integer sec = RecoRepository.parseDurationSeconds(rawDuration);
        if (sec == null) {
            return null;
        }
        if (sec <= 60) {
            return "d:0";
        }
        if (sec <= 180) {
            return "d:1";
        }
        if (sec <= 600) {
            return "d:2";
        }
        return "d:3";
    }

    /** v3 P1-2：方向 → 桶键（o:p 竖 / o:l 横）；未知返回 null */
    public static String orientationBucket(int orientation) {
        switch (orientation) {
            case RecoCandidate.ORIENT_PORTRAIT:
                return "o:p";
            case RecoCandidate.ORIENT_LANDSCAPE:
                return "o:l";
            default:
                return null;
        }
    }

    /** v3 P1-3：候选的 UCB 特征键（分类 + 最高 idf 标签，规则跨用户稳定） */
    public java.util.ArrayList<String> statKeys(RecoCandidate candidate) {
        java.util.ArrayList<String> keys = new java.util.ArrayList<>(2);
        if (candidate == null) {
            return keys;
        }
        if (!TextUtils.isEmpty(candidate.categoryValue)) {
            keys.add("c:" + candidate.categoryValue);
        }
        String top = topIdfTag(candidate.tags, dictionary);
        if (top != null) {
            keys.add("t:" + top);
        }
        return keys;
    }

    /** 最高 idf 的标签（UCB 特征键与打散主标签共用口径；idf 同取首个） */
    public static String topIdfTag(List<String> tags, RecoTagDictionary dictionary) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        String best = null;
        double bestIdf = Double.NEGATIVE_INFINITY;
        for (String t : tags) {
            if (t == null) {
                continue;
            }
            double idf = dictionary.idf(t);
            if (idf > bestIdf) {
                bestIdf = idf;
                best = t;
            }
        }
        return best;
    }

    /**
     * v3 P1-3：UCB 乐观项。optimism(f) = coef × sqrt(ln(1+Σserved) / (1+served_f))，
     * 对候选的全部特征键取均值——试得少的特征天然加分，试过且效果差的自动沉底。
     */
    public static double optimismOf(StatSource stats, double coef, List<String> keys) {
        if (stats == null || keys == null || keys.isEmpty() || coef <= 0.0d) {
            return 0.0d;
        }
        double total = stats.totalServed();
        if (total < 0.0d) {
            total = 0.0d;
        }
        double logTotal = Math.log(1.0d + total);
        double sum = 0.0d;
        int n = 0;
        for (String k : keys) {
            if (k == null) {
                continue;
            }
            long served = stats.served(k);
            if (served < 0L) {
                served = 0L;
            }
            sum += Math.sqrt(logTotal / (1.0d + served));
            n++;
        }
        return n == 0 ? 0.0d : coef * sum / n;
    }

    /**
     * M77：新鲜度优先用真实发布年份（候选上由仓库层解析好）算「越老扣越多」的软分数；
     * 解析不出年份时退回旧的列表位置近似（位置 0 视为最新）。
     */
    private double computeRecency(RecoCandidate candidate, int positionInList, int listSize) {
        if (candidate != null && candidate.publishYear > 0) {
            int curYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
            int age = curYear - candidate.publishYear;
            // 新片 ≈ +1.0；每年线性衰减，到 maxAgeYears 年约 -0.3，
            // 之后继续往下扣但封底 -0.8，避免老片被无限惩罚到永不可见
            double step = 1.3d / Math.max(1, params.maxAgeYears);
            double f = 1.0d - age * step;
            if (f > 1.0d) {
                f = 1.0d;
            } else if (f < -0.8d) {
                f = -0.8d;
            }
            return f;
        }
        return computeRecencyByPosition(positionInList, listSize);
    }

    /** 旧逻辑兜底：列表内位置的线性衰减，第 0 位 +1.0，末位 0 */
    private double computeRecencyByPosition(int positionInList, int listSize) {
        if (listSize <= 1) {
            return 0.0d;
        }
        int pos = positionInList < 0 ? 0 : positionInList;
        return 1.0d - ((double) pos / (double) (listSize - 1));
    }

    /**
     * M77：探索改为「保留坑位」式（见 RecoRepository.take），打分层不再注入大噪声——
     * 高分垃圾挤占正常排序位且用户无感知的问题随之消除。只保留极小抖动打散同分项。
     */
    private double computeNoise() {
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
                // 当前中文标签均来自词典；保留统一判断，兼容有效 ASCII 标签。
                double learn = dictionary.isDictionaryWord(t) ? 1.0d : 0.3d;
                // idf 高（冷门）的词学习得更快
                double scaled = per * (dictionary.idf(t) / 3.0d) * learn;
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
        profile.likeCount.incrementAndGet();
    }

    public void onUnlike(RecoProfile profile, List<String> tags, String category, String authorKey) {
        applyFeedback(profile, tags, category, authorKey, -params.likeBoost);
        if (profile.likeCount.get() > 0) {
            profile.likeCount.decrementAndGet();
        }
    }

    public void onFavorite(RecoProfile profile, List<String> tags, String category, String authorKey) {
        applyFeedback(profile, tags, category, authorKey, params.favoriteBoost);
        profile.favoriteCount.incrementAndGet();
    }

    /** 取消收藏：把收藏时加的权重原样退回 */
    public void onUnfavorite(RecoProfile profile, List<String> tags, String category, String authorKey) {
        applyFeedback(profile, tags, category, authorKey, -params.favoriteBoost);
        if (profile.favoriteCount.get() > 0) {
            profile.favoriteCount.decrementAndGet();
        }
    }

    public void onDislike(RecoProfile profile, List<String> tags, String category, String authorKey) {
        applyFeedback(profile, tags, category, authorKey, -params.dislikePenalty);
        profile.dislikeCount.incrementAndGet();
    }

    /** 撤销不喜欢 */
    public void onUndislike(RecoProfile profile, List<String> tags, String category, String authorKey) {
        applyFeedback(profile, tags, category, authorKey, params.dislikePenalty);
        if (profile.dislikeCount.get() > 0) {
            profile.dislikeCount.decrementAndGet();
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
