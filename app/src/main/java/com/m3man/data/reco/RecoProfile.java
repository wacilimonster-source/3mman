package com.m3man.data.reco;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地用户兴趣画像。
 * <p>
 * 三张权重表：标题标签、作者、分类。权重是「带时间衰减的累计反馈」，
 * 正数表示喜欢、负数表示不喜欢。不喜欢只降权、不做永久屏蔽（用户决策 #2）。
 *
 * @author 3mman
 */
public class RecoProfile {

    /** 单个权重的上下限，防止长期正反馈把某个标签推到无穷大 */
    public static final double WEIGHT_MAX = 30.0d;
    public static final double WEIGHT_MIN = -30.0d;
    /** 权重表容量上限，超出后淘汰绝对值最小的条目 */
    private static final int MAX_TAG_ENTRIES = 600;
    private static final int MAX_AUTHOR_ENTRIES = 300;

    // M98：三张权重表换 ConcurrentHashMap——打分（UI 线程）与衰减/落盘快照（IO 线程）并发读写，
    // 迭代处（decayMap/topAuthors/外部快照拷贝）天然弱一致安全；JSON 序列化 Map 本就无序，无顺序依赖。
    public final Map<String, Double> tagWeights = new ConcurrentHashMap<>();
    public final Map<String, Double> authorWeights = new ConcurrentHashMap<>();
    public final Map<String, Double> categoryWeights = new ConcurrentHashMap<>();

    public long lastDecayTime = 0L;
    public int likeCount = 0;
    public int dislikeCount = 0;
    public int favoriteCount = 0;
    public int watchCount = 0;

    public double tag(String key) {
        return value(tagWeights, key);
    }

    public double author(String key) {
        return value(authorWeights, key);
    }

    public double category(String key) {
        return value(categoryWeights, key);
    }

    private static double value(Map<String, Double> map, String key) {
        if (TextUtils.isEmpty(key)) {
            return 0.0d;
        }
        Double v = map.get(key);
        return v == null ? 0.0d : v;
    }

    public void bumpTag(String key, double delta) {
        bump(tagWeights, key, delta, MAX_TAG_ENTRIES);
    }

    public void bumpAuthor(String key, double delta) {
        bump(authorWeights, key, delta, MAX_AUTHOR_ENTRIES);
    }

    public void bumpCategory(String key, double delta) {
        // 分类总量固定（十来个），不需要淘汰
        bump(categoryWeights, key, delta, Integer.MAX_VALUE);
    }

    private static void bump(Map<String, Double> map, String key, double delta, int capacity) {
        if (TextUtils.isEmpty(key) || delta == 0.0d || Double.isNaN(delta)) {
            return;
        }
        double next = value(map, key) + delta;
        if (next > WEIGHT_MAX) {
            next = WEIGHT_MAX;
        } else if (next < WEIGHT_MIN) {
            next = WEIGHT_MIN;
        }
        map.put(key, next);
        if (map.size() > capacity) {
            evictSmallest(map, capacity);
        }
    }

    /** 淘汰绝对值最小（信息量最低）的条目 */
    private static void evictSmallest(Map<String, Double> map, int capacity) {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(map.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Double>>() {
            @Override
            public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
                return Double.compare(Math.abs(a.getValue()), Math.abs(b.getValue()));
            }
        });
        int toRemove = map.size() - capacity;
        for (int i = 0; i < toRemove && i < entries.size(); i++) {
            map.remove(entries.get(i).getKey());
        }
    }

    /**
     * 按半衰期衰减全部权重。半衰期 = decayDays 天。
     * <p>
     * M98：返回是否有实际变更，供引擎决定是否 markDirty（避免每次启动都触发一次全量落盘）。
     *
     * @param nowMillis 当前时间
     * @param decayDays 半衰期（天）
     * @return 本次是否发生了真实衰减
     */
    public boolean applyDecay(long nowMillis, int decayDays) {
        if (lastDecayTime <= 0L) {
            lastDecayTime = nowMillis;
            return false;
        }
        if (decayDays <= 0) {
            lastDecayTime = nowMillis;
            return false;
        }
        long elapsed = nowMillis - lastDecayTime;
        if (elapsed <= 0) {
            return false;
        }
        double days = elapsed / (24.0d * 3600.0d * 1000.0d);
        if (days < 0.5d) {
            // 不足半天不衰减，避免频繁开关 App 造成的精度损失
            return false;
        }
        double factor = Math.pow(0.5d, days / decayDays);
        decayMap(tagWeights, factor);
        decayMap(authorWeights, factor);
        decayMap(categoryWeights, factor);
        lastDecayTime = nowMillis;
        return true;
    }

    private static void decayMap(Map<String, Double> map, double factor) {
        Iterator<Map.Entry<String, Double>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Double> e = it.next();
            double v = e.getValue() * factor;
            if (Math.abs(v) < 0.02d) {
                it.remove();
            } else {
                e.setValue(v);
            }
        }
    }

    /** 画像是否还处于冷启动（几乎没有反馈） */
    public boolean isColdStart() {
        return likeCount + dislikeCount + favoriteCount == 0 && tagWeights.isEmpty();
    }

    /** 取权重最高的 N 个作者（用于作者召回） */
    public List<String> topAuthors(int n, double minWeight) {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(authorWeights.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Double>>() {
            @Override
            public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
                return Double.compare(b.getValue(), a.getValue());
            }
        });
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : entries) {
            if (e.getValue() < minWeight) {
                break;
            }
            out.add(e.getKey());
            if (out.size() >= n) {
                break;
            }
        }
        return out;
    }

    public void clear() {
        tagWeights.clear();
        authorWeights.clear();
        categoryWeights.clear();
        lastDecayTime = 0L;
        likeCount = 0;
        dislikeCount = 0;
        favoriteCount = 0;
        watchCount = 0;
    }
}
