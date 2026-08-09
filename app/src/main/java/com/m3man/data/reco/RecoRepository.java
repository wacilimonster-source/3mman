package com.m3man.data.reco;

import android.text.TextUtils;

import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.model.BaseResult;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.reactivex.Observable;
import io.reactivex.functions.Function;

/**
 * 推荐候选仓库：负责「召回 → 打分 → 排序 → 出队」。
 * <p>
 * 召回来源（主源为视频分类，用户决策 #1）：
 * <ul>
 *   <li>分类召回：按画像里的分类权重做加权采样，翻页游标各分类独立。</li>
 *   <li>作者召回：画像里高权重作者的视频列表，占比由 authorRecallRatio 控制。</li>
 *   <li>探索召回：随机分类，避免陷入信息茧房。</li>
 * </ul>
 *
 * @author 3mman
 */
public class RecoRepository {

    /** 参与推荐的分类（排除 index 主页与 hd 会员专区） */
    static final String[] RECO_CATEGORIES = {
            "watch", "hot", "rp", "long", "md", "tf", "mf", "rf", "top", "top1"
    };

    private static final String VIEW_TYPE = "basic";
    private static final int MAX_PAGE = 30;
    /** 分类召回的翻页深度：撒到更深的页才能覆盖近 10 年里的较老视频 */
    private static final int CATEGORY_DEPTH = 50;
    /** 从「添加时间: 2024-05-01」这类文本里抽年份，锚定到日期分隔符避免误判播放量 */
    private static final Pattern YEAR_PATTERN = Pattern.compile("((?:19|20)\\d{2})[-/.年月]");
    /** 池子里最多缓存多少候选，超出丢弃低分项 */
    private static final int MAX_POOL = 120;

    private final DataManager dataManager;
    private final RecoEngine engine;
    private final Random random = new Random();

    private final List<RecoCandidate> pool = new ArrayList<>();
    private final Set<String> servedKeys = new HashSet<>();
    private final Map<String, Integer> categoryPage = new HashMap<>();
    private final Map<String, Integer> authorPage = new HashMap<>();

    /** 连续拉取失败次数，用于让 UI 提示错误 */
    private int consecutiveFailures = 0;

    public RecoRepository(DataManager dataManager, RecoEngine engine) {
        this.dataManager = dataManager;
        this.engine = engine;
    }

    public int poolSize() {
        return pool.size();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void resetSession() {
        pool.clear();
        servedKeys.clear();
        categoryPage.clear();
        authorPage.clear();
        consecutiveFailures = 0;
    }

    /**
     * 取下一批推荐。池子够就直接出队，不够就先拉一页再出队。
     *
     * @param want 期望条数
     */
    public Observable<List<RecoCandidate>> nextBatch(final int want) {
        return Observable.defer(new java.util.concurrent.Callable<Observable<List<RecoCandidate>>>() {
            @Override
            public Observable<List<RecoCandidate>> call() {
                if (pool.size() >= want) {
                    return Observable.just(take(want));
                }
                return fetchOneSource()
                        .map(new Function<List<RecoCandidate>, List<RecoCandidate>>() {
                            @Override
                            public List<RecoCandidate> apply(List<RecoCandidate> fetched) {
                                addToPool(fetched);
                                return take(want);
                            }
                        });
            }
        });
    }

    /** 只拉取、不出队（预热池子用） */
    public Observable<Integer> prefetchPool() {
        return fetchOneSource().map(new Function<List<RecoCandidate>, Integer>() {
            @Override
            public Integer apply(List<RecoCandidate> fetched) {
                addToPool(fetched);
                return pool.size();
            }
        });
    }

    // ==================== 召回 ====================

    private Observable<List<RecoCandidate>> fetchOneSource() {
        RecoProfile profile = engine.getProfile();
        RecoParams params = engine.getParams();

        List<String> topAuthors = profile.topAuthors(5, 1.0d);
        boolean useAuthor = !topAuthors.isEmpty()
                && random.nextFloat() < params.authorRecallRatio;
        if (useAuthor) {
            String uid = topAuthors.get(random.nextInt(topAuthors.size()));
            return fetchAuthor(uid);
        }
        boolean explore = random.nextFloat() < params.explorationRate;
        String category = explore ? randomCategory() : pickCategoryByWeight(profile);
        return fetchCategory(category, explore ? RecoCandidate.FROM_EXPLORE : RecoCandidate.FROM_CATEGORY);
    }

    private Observable<List<RecoCandidate>> fetchCategory(final String rawCategory, final int from) {
        // 翻页游标：以较高概率落在前面的「较新」页，其余均匀撒到更深的页，
        // 让近 10 年（含较老）的视频都有机会进入推荐，而不是只推最新一批。
        final int page;
        if (random.nextFloat() < 0.55f) {
            page = 1 + random.nextInt(3);              // 1~3：较新
        } else {
            page = 1 + random.nextInt(CATEGORY_DEPTH); // 1~50：较旧，覆盖更早年代
        }
        // top1（上月最热）在服务端是 category=top&m=-1
        final String category = "top1".equals(rawCategory) ? "top" : rawCategory;
        final String m = "top1".equals(rawCategory) ? "-1" : null;

        Observable<BaseResult<List<V9MmanItem>>> ob;
        if ("watch".equals(rawCategory)) {
            ob = dataManager.loadMman9VideoRecentUpdates(rawCategory, page, false, false);
        } else {
            ob = dataManager.loadMman9VideoByCategory(category, VIEW_TYPE, page, m, false, false);
        }
        return ob.map(new Function<BaseResult<List<V9MmanItem>>, List<RecoCandidate>>() {
            @Override
            public List<RecoCandidate> apply(BaseResult<List<V9MmanItem>> result) {
                return toCandidates(result, rawCategory, from);
            }
        }).onErrorReturn(new Function<Throwable, List<RecoCandidate>>() {
            @Override
            public List<RecoCandidate> apply(Throwable throwable) {
                consecutiveFailures++;
                return new ArrayList<>();
            }
        });
    }

    private Observable<List<RecoCandidate>> fetchAuthor(final String uid) {
        final int page = nextPage(authorPage, uid);
        return dataManager.loadMman9authorVideos(uid, "public", page, false)
                .map(new Function<BaseResult<List<V9MmanItem>>, List<RecoCandidate>>() {
                    @Override
                    public List<RecoCandidate> apply(BaseResult<List<V9MmanItem>> result) {
                        List<RecoCandidate> list = toCandidates(result, null, RecoCandidate.FROM_AUTHOR);
                        for (RecoCandidate c : list) {
                            c.authorKey = uid;
                        }
                        return list;
                    }
                })
                .onErrorReturn(new Function<Throwable, List<RecoCandidate>>() {
                    @Override
                    public List<RecoCandidate> apply(Throwable throwable) {
                        consecutiveFailures++;
                        return new ArrayList<>();
                    }
                });
    }

    private List<RecoCandidate> toCandidates(BaseResult<List<V9MmanItem>> result,
                                             String categoryValue, int from) {
        List<RecoCandidate> out = new ArrayList<>();
        if (result == null || result.getCode() == BaseResult.ERROR_CODE || result.getData() == null) {
            consecutiveFailures++;
            return out;
        }
        consecutiveFailures = 0;
        List<V9MmanItem> items = result.getData();
        int size = items.size();
        RecoProfile profile = engine.getProfile();
        RecoScorer scorer = engine.getScorer();
        RecoStore store = engine.getStore();
        for (int i = 0; i < size; i++) {
            V9MmanItem item = items.get(i);
            if (item == null || TextUtils.isEmpty(item.getViewKey())) {
                continue;
            }
            // 年代过滤：超出可推荐年限（默认 10 年）的老片直接跳过；解析不出年份的保留
            RecoParams params = engine.getParams();
            if (params.maxAgeYears > 0) {
                int year = parseAddYear(item.getInfo());
                if (year > 0) {
                    int curYear = Calendar.getInstance().get(Calendar.YEAR);
                    if (curYear - year > params.maxAgeYears) {
                        continue;
                    }
                }
            }
            String key = item.getViewKey();
            if (servedKeys.contains(key) || store.isSeen(key)) {
                continue;
            }
            RecoCandidate c = new RecoCandidate(item, categoryValue, from);
            c.tags = engine.getDictionary().tokenize(item.getTitle());
            scorer.score(c, profile, i, size);
            out.add(c);
        }
        return out;
    }

    /** 从「添加时间: 2024-05-01 ...」这类 info 里抽出 4 位发布年份；抽不到返回 -1 */
    private static int parseAddYear(String info) {
        if (TextUtils.isEmpty(info)) {
            return -1;
        }
        Matcher m = YEAR_PATTERN.matcher(info);
        int curYear = Calendar.getInstance().get(Calendar.YEAR);
        while (m.find()) {
            try {
                int y = Integer.parseInt(m.group(1));
                // 合理区间：2005~当前年（含），避免把播放量等纯数字误判为年份
                if (y >= 2005 && y <= curYear) {
                    return y;
                }
            } catch (NumberFormatException ignored) {
                // 忽略非法分组
            }
        }
        return -1;
    }

    // ==================== 池子管理 ====================

    private void addToPool(List<RecoCandidate> fetched) {
        if (fetched == null || fetched.isEmpty()) {
            return;
        }
        Set<String> inPool = new HashSet<>();
        for (RecoCandidate c : pool) {
            inPool.add(c.viewKey());
        }
        for (RecoCandidate c : fetched) {
            String key = c.viewKey();
            if (TextUtils.isEmpty(key) || inPool.contains(key) || servedKeys.contains(key)) {
                continue;
            }
            inPool.add(key);
            pool.add(c);
        }
        RecoScorer.sortByScoreDesc(pool);
        while (pool.size() > MAX_POOL) {
            pool.remove(pool.size() - 1);
        }
    }

    private List<RecoCandidate> take(int want) {
        List<RecoCandidate> out = new ArrayList<>();
        RecoScorer.sortByScoreDesc(pool);
        while (!pool.isEmpty() && out.size() < want) {
            RecoCandidate c = pool.remove(0);
            String key = c.viewKey();
            if (TextUtils.isEmpty(key) || servedKeys.contains(key)) {
                continue;
            }
            servedKeys.add(key);
            out.add(c);
        }
        return out;
    }

    // ==================== 分类采样 ====================

    private int nextPage(Map<String, Integer> cursor, String key) {
        Integer cur = cursor.get(key);
        int page = cur == null ? 1 : cur + 1;
        if (page > MAX_PAGE) {
            page = 1;
        }
        cursor.put(key, page);
        return page;
    }

    String randomCategory() {
        return RECO_CATEGORIES[random.nextInt(RECO_CATEGORIES.length)];
    }

    /**
     * 按画像里的分类权重做加权采样（softmax 后归一化）。
     * 冷启动（全 0）时退化为均匀随机。
     */
    String pickCategoryByWeight(RecoProfile profile) {
        double[] w = new double[RECO_CATEGORIES.length];
        double sum = 0.0d;
        for (int i = 0; i < RECO_CATEGORIES.length; i++) {
            // exp 前先压一压，避免单个分类权重过大后完全垄断
            double raw = profile.category(RECO_CATEGORIES[i]) * 0.35d;
            if (raw > 3.0d) {
                raw = 3.0d;
            } else if (raw < -3.0d) {
                raw = -3.0d;
            }
            w[i] = Math.exp(raw);
            sum += w[i];
        }
        if (sum <= 0.0d || Double.isNaN(sum) || Double.isInfinite(sum)) {
            return randomCategory();
        }
        double r = random.nextDouble() * sum;
        double acc = 0.0d;
        for (int i = 0; i < RECO_CATEGORIES.length; i++) {
            acc += w[i];
            if (r <= acc) {
                return RECO_CATEGORIES[i];
            }
        }
        return RECO_CATEGORIES[RECO_CATEGORIES.length - 1];
    }
}
