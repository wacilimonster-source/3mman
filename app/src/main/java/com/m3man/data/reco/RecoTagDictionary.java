package com.m3man.data.reco;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 标签词典。
 * <p>
 * 词典由开发期脚本（build-tools/gen_reco_tags.py）对全量视频语料做 jieba 分词 + 统计
 * 文档频率（df）后产出，随包放在 assets/reco/tag_dictionary.json。
 * <p>
 * 运行时不做中文分词模型加载（体积/性能考虑），而是用「词典正向最大匹配」把标题切成标签：
 * 命中词典的词才成为标签；对完全未命中的中文片段，退化为相邻二元组（bigram），
 * 保证即使词典很小算法也能工作。
 * <p>
 * 词典格式：
 * <pre>
 * {
 *   "version": 1,
 *   "totalDocs": 123456,
 *   "maxTagLen": 8,
 *   "tags": { "制服": 3201, "户外": 890, ... }
 * }
 * </pre>
 *
 * @author 3mman
 */
public class RecoTagDictionary {

    public static final String ASSET_PATH = "reco/tag_dictionary.json";

    /** 词典未命中（bigram）标签的兜底 df，取一个较小值使其 idf 偏高但不至于爆炸 */
    private static final int FALLBACK_DF = 50;
    private static final int DEFAULT_TOTAL_DOCS = 20000;
    private static final int MAX_TAGS_PER_TITLE = 12;

    private static volatile RecoTagDictionary sInstance;

    private final Map<String, Integer> tagDf = new HashMap<>();
    private int totalDocs = DEFAULT_TOTAL_DOCS;
    private int maxTagLen = 6;
    private int version = 0;

    private RecoTagDictionary() {
    }

    public static RecoTagDictionary get(Context context) {
        if (sInstance == null) {
            synchronized (RecoTagDictionary.class) {
                if (sInstance == null) {
                    RecoTagDictionary dict = new RecoTagDictionary();
                    dict.loadFromAssets(context);
                    sInstance = dict;
                }
            }
        }
        return sInstance;
    }

    /** 单元测试 / 词表热更新用：直接注入词典数据 */
    public static RecoTagDictionary createForTest(Map<String, Integer> df, int totalDocs) {
        RecoTagDictionary dict = new RecoTagDictionary();
        if (df != null) {
            dict.tagDf.putAll(df);
            for (String k : df.keySet()) {
                if (k != null && k.length() > dict.maxTagLen) {
                    dict.maxTagLen = k.length();
                }
            }
        }
        dict.totalDocs = Math.max(1, totalDocs);
        return dict;
    }

    private void loadFromAssets(Context context) {
        if (context == null) {
            return;
        }
        InputStream is = null;
        BufferedReader reader = null;
        try {
            is = context.getApplicationContext().getAssets().open(ASSET_PATH);
            reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            version = root.optInt("version", 0);
            totalDocs = Math.max(1, root.optInt("totalDocs", DEFAULT_TOTAL_DOCS));
            maxTagLen = Math.max(2, root.optInt("maxTagLen", 6));
            JSONObject tags = root.optJSONObject("tags");
            if (tags != null) {
                Iterator<String> it = tags.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    if (TextUtils.isEmpty(key)) {
                        continue;
                    }
                    tagDf.put(key, Math.max(1, tags.optInt(key, 1)));
                    if (key.length() > maxTagLen) {
                        maxTagLen = key.length();
                    }
                }
            }
        } catch (Exception e) {
            // 词表缺失不应导致功能不可用：退化为纯 bigram 模式
            tagDf.clear();
        } finally {
            closeQuietly(reader);
            closeQuietly(is);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    public int getVersion() {
        return version;
    }

    public int getTotalDocs() {
        return totalDocs;
    }

    public int size() {
        return tagDf.size();
    }

    /**
     * idf = ln((N + 1) / (df + 1)) + 1，恒为正，越冷门的词权重越高。
     */
    public double idf(String tag) {
        if (TextUtils.isEmpty(tag)) {
            return 1.0d;
        }
        Integer df = tagDf.get(tag);
        int d = df == null ? FALLBACK_DF : df;
        double v = Math.log((totalDocs + 1.0d) / (d + 1.0d)) + 1.0d;
        // 上下限保护，避免极端词（df=1）在少量语料下把分数拉爆
        if (v < 0.5d) {
            return 0.5d;
        }
        if (v > 8.0d) {
            return 8.0d;
        }
        return v;
    }

    /**
     * 把标题切成标签集合（去重、有序、限量）。
     */
    public List<String> tokenize(String title) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(title)) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        String text = normalize(title);
        int n = text.length();
        int i = 0;
        StringBuilder pending = new StringBuilder();
        while (i < n) {
            char c = text.charAt(i);
            if (isSeparator(c)) {
                flushPending(pending, seen);
                i++;
                continue;
            }
            if (isCjk(c)) {
                int matchedLen = longestMatch(text, i);
                if (matchedLen > 0) {
                    flushPending(pending, seen);
                    seen.add(text.substring(i, i + matchedLen));
                    i += matchedLen;
                } else {
                    pending.append(c);
                    i++;
                }
                continue;
            }
            if (isAsciiWord(c)) {
                int j = i;
                while (j < n && isAsciiWord(text.charAt(j))) {
                    j++;
                }
                flushPending(pending, seen);
                String word = text.substring(i, j);
                if (word.length() >= 2) {
                    seen.add(word);
                }
                i = j;
                continue;
            }
            flushPending(pending, seen);
            i++;
        }
        flushPending(pending, seen);

        for (String s : seen) {
            out.add(s);
            if (out.size() >= MAX_TAGS_PER_TITLE) {
                break;
            }
        }
        return out;
    }

    /** 未命中词典的中文片段 → 相邻二元组兜底 */
    private void flushPending(StringBuilder pending, Set<String> seen) {
        if (pending.length() == 0) {
            return;
        }
        String s = pending.toString();
        pending.setLength(0);
        if (s.length() == 1) {
            // 单字信息量太低，丢弃
            return;
        }
        for (int k = 0; k + 2 <= s.length(); k++) {
            seen.add(s.substring(k, k + 2));
        }
    }

    /** 正向最大匹配 */
    private int longestMatch(String text, int start) {
        int max = Math.min(maxTagLen, text.length() - start);
        for (int len = max; len >= 2; len--) {
            String candidate = text.substring(start, start + len);
            if (tagDf.containsKey(candidate)) {
                return len;
            }
        }
        // 允许单字标签（词典中显式登记的才算）
        if (max >= 1 && tagDf.containsKey(text.substring(start, start + 1))) {
            return 1;
        }
        return 0;
    }

    private static String normalize(String s) {
        String t = s.toLowerCase();
        // 全角转半角空格，去掉常见装饰符
        t = t.replace('\u3000', ' ');
        return t;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }

    private static boolean isAsciiWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static boolean isSeparator(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
}
