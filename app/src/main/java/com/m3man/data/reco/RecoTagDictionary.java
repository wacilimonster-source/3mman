package com.m3man.data.reco;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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

    /** 词典未命中（bigram）标签的兜底 df：取较大值压低噪声标签的 idf（噪声不应高于主题词） */
    private static final int FALLBACK_DF = 500;
    private static final int DEFAULT_TOTAL_DOCS = 20000;
    private static final int MAX_TAGS_PER_TITLE = 12;
    /** 未命中片段只有足够长（>=4 字）才切成相邻二元组，2~3 字碎片直接丢弃（多为连接词/无意义组合） */
    private static final int MIN_BIGRAM_RUN = 4;
    /** 无信息量的常见 ASCII 短词（丢弃，不进标签） */
    private static final java.util.Set<String> STOP_ASCII = new java.util.HashSet<>(java.util.Arrays.asList(
            "hd", "4k", "2k", "8k", "av", "sub", "tv", "dvd", "vip", "app",
            "www", "com", "net", "org", "mp4", "m3u8", "720", "480", "360",
            "1080p", "720p", "480p", "360p", "2160p"));

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
     * <p>
     * 词典命中的词（含有效 ASCII 词）排在最前，未命中片段的 bigram 兜底排最后，
     * 避免噪声标签占满名额。bigram 标签可用 {@link #isDictionaryWord(String)} 区分。
     */
    public List<String> tokenize(String title) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(title)) {
            return out;
        }
        List<String> dictTags = new ArrayList<>();
        List<String> fallbackTags = new ArrayList<>();
        Set<String> seenDict = new HashSet<>();
        Set<String> seenFallback = new HashSet<>();
        String text = normalize(title);
        int n = text.length();
        int i = 0;
        StringBuilder pending = new StringBuilder();
        while (i < n) {
            char c = text.charAt(i);
            if (isSeparator(c)) {
                flushPending(pending, seenFallback, fallbackTags);
                i++;
                continue;
            }
            if (isCjk(c)) {
                int matchedLen = longestMatch(text, i);
                if (matchedLen > 0) {
                    flushPending(pending, seenFallback, fallbackTags);
                    String w = text.substring(i, i + matchedLen);
                    if (seenDict.add(w)) {
                        dictTags.add(w);
                    }
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
                flushPending(pending, seenFallback, fallbackTags);
                String word = text.substring(i, j);
                if (isUsefulAsciiWord(word) && seenDict.add(word)) {
                    dictTags.add(word);
                }
                i = j;
                continue;
            }
            flushPending(pending, seenFallback, fallbackTags);
            i++;
        }
        flushPending(pending, seenFallback, fallbackTags);

        for (String s : dictTags) {
            out.add(s);
            if (out.size() >= MAX_TAGS_PER_TITLE) {
                return out;
            }
        }
        for (String s : fallbackTags) {
            out.add(s);
            if (out.size() >= MAX_TAGS_PER_TITLE) {
                break;
            }
        }
        return out;
    }

    /** 该标签是否为词典中的正式词（false = bigram 兜底噪声标签） */
    public boolean isDictionaryWord(String tag) {
        return !TextUtils.isEmpty(tag) && tagDf.containsKey(tag);
    }

    /** 未命中词典的中文片段 → 足够长才切相邻二元组兜底（短碎片丢弃） */
    private void flushPending(StringBuilder pending, Set<String> seen, List<String> fallbackTags) {
        if (pending.length() == 0) {
            return;
        }
        String s = pending.toString();
        pending.setLength(0);
        if (s.length() < MIN_BIGRAM_RUN) {
            return;
        }
        for (int k = 0; k + 2 <= s.length(); k++) {
            String bg = s.substring(k, k + 2);
            if (seen.add(bg)) {
                fallbackTags.add(bg);
            }
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
        // '-' 纳入 ASCII 词，保证番号形态（如 PRED-130）整体保留
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-';
    }

    /** ASCII 词是否值得作为标签：过滤纯数字、无信息量短词，保留番号/演员名等长词 */
    private static boolean isUsefulAsciiWord(String word) {
        String lower = word.toLowerCase();
        if (lower.matches("[0-9]+")) {
            return false;
        }
        if (STOP_ASCII.contains(lower)) {
            return false;
        }
        // 含数字的混合串（番号形态）保留；纯字母需 >= 4 位（2~3 位多为缩写噪声）
        if (lower.matches(".*[0-9].*")) {
            return lower.length() >= 4;
        }
        return lower.length() >= 4;
    }

    private static boolean isSeparator(char c) {
        switch (c) {
            case ' ': case '\t': case '\n': case '\r':
            case ',': case '.': case '，': case '。': case '!': case '！': case '?': case '？':
            case ';': case '；': case ':': case '：': case '、': case '·': case '•':
            case '(': case ')': case '（': case '）': case '[': case ']': case '【': case '】':
            case '{': case '}': case '<': case '>': case '《': case '》':
            case '|': case '\\': case '/': case '_': case '=': case '+': case '*':
            case '&': case '%': case '$': case '#': case '@': case '~': case '`': case '^':
            case '\'': case '"': case '\u2018': case '\u2019': case '\u201C': case '\u201D':
            case '\u300C': case '\u300D': case '\u300E': case '\u300F':
                return true;
            default:
                return false;
        }
    }
}
