package com.m3man.data.reco;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推荐状态持久化。
 * <p>
 * 刻意不使用 greenDAO：新增实体需要 bump schemaVersion 并走 MigrationHelper 全表重建，
 * 对用户既有的收藏 / 下载 / 历史数据有风险；推荐状态体量很小（几十 KB），
 * 用应用私有目录下的一个 JSON 文件即可，升级零风险、可随时清空重置。
 * <p>
 * 文件：{filesDir}/reco/reco_state.json
 *
 * @author 3mman
 */
public class RecoStore implements RecoScorer.StatSource {

    public static final int ACTION_LIKE = 1;
    public static final int ACTION_DISLIKE = 2;
    public static final int ACTION_FAVORITE = 3;

    private static final String DIR = "reco";
    private static final String FILE = "reco_state.json";
    private static final String TMP = "reco_state.json.tmp";

    /** 已展示过的视频（用于去重），超过后按插入顺序淘汰最老的 */
    private static final int MAX_SEEN = 1500;
    /** 交互记录上限 */
    private static final int MAX_ACTIONS = 800;
    /** 作者名缓存上限 */
    private static final int MAX_AUTHOR_NAMES = 300;
    /** v3 P0-1：隐式反馈结算记录上限（viewKey → 正/负封顶位图），LRU 淘汰最老 */
    private static final int MAX_IMPLICIT = 500;
    /** v3 P1-3：UCB 特征统计上限（分类 + 标签键），超出淘汰信息量最小的 */
    private static final int MAX_STATS = 1000;

    private final Context appContext;

    private RecoProfile profile = new RecoProfile();
    /** viewKey -> action，LinkedHashMap 保序便于淘汰 */
    private final LinkedHashMap<String, Integer> actions = new LinkedHashMap<>();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    /** 作者 id -> 昵称，画像里只有 id，展示学习记录时需要它翻译成人能看懂的名字 */
    private final LinkedHashMap<String, String> authorNames = new LinkedHashMap<>();
    /** v3 P0-1：viewKey -> 位图（bit1=正反馈已全额学过，bit2=负反馈已全额学过），防重复加权 */
    private final LinkedHashMap<String, Integer> implicitFlags = new LinkedHashMap<>();
    /** v3 P1-3：特征键 -> [served, positive]，UCB 乐观项与采样倾斜的数据源 */
    private final LinkedHashMap<String, long[]> featureStats = new LinkedHashMap<>();
    /** v3 P1-3：served 总曝光数（ln(1+total) 用），随 bumpStatServed 递增 */
    private long statTotalServed = 0L;

    private boolean loaded = false;
    private boolean dirty = false;
    /** M98：脏变更序号——锁外写盘期间若有新 markDirty，可据此判断不能清 dirty 位 */
    private long dirtySeq = 0L;
    /** M158：写盘互斥锁——写线程串行化（tmp 文件不被并发写），且不阻塞持 this 锁的读路径 */
    private final Object ioLock = new Object();
    /** 上次画像学习所用的词典版本；与当前词典不一致时 RecoEngine 会自动重置画像 */
    private int dictVersion = 0;

    public RecoStore(Context context) {
        this.appContext = context == null ? null : context.getApplicationContext();
    }

    public synchronized RecoProfile getProfile() {
        ensureLoaded();
        return profile;
    }

    public synchronized int getAction(String viewKey) {
        ensureLoaded();
        if (TextUtils.isEmpty(viewKey)) {
            return 0;
        }
        Integer a = actions.get(viewKey);
        return a == null ? 0 : a;
    }

    public synchronized void setAction(String viewKey, int action) {
        ensureLoaded();
        if (TextUtils.isEmpty(viewKey)) {
            return;
        }
        if (action == 0) {
            actions.remove(viewKey);
        } else {
            actions.remove(viewKey);
            actions.put(viewKey, action);
            while (actions.size() > MAX_ACTIONS) {
                Iterator<String> it = actions.keySet().iterator();
                if (!it.hasNext()) {
                    break;
                }
                it.next();
                it.remove();
            }
        }
        markDirtyInternal();
    }

    /** 记录作者昵称（供「学习记录」展示） */
    public synchronized void putAuthorName(String authorId, String name) {
        ensureLoaded();
        if (TextUtils.isEmpty(authorId) || TextUtils.isEmpty(name)) {
            return;
        }
        if (name.equals(authorNames.get(authorId))) {
            return;
        }
        authorNames.remove(authorId);
        authorNames.put(authorId, name);
        while (authorNames.size() > MAX_AUTHOR_NAMES) {
            Iterator<String> it = authorNames.keySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();
        }
        markDirtyInternal();
    }

    public synchronized String authorName(String authorId) {
        ensureLoaded();
        if (TextUtils.isEmpty(authorId)) {
            return null;
        }
        return authorNames.get(authorId);
    }

    /** 全部交互记录快照（新的在后面） */
    public synchronized LinkedHashMap<String, Integer> snapshotActions() {
        ensureLoaded();
        return new LinkedHashMap<>(actions);
    }

    public synchronized int actionSize() {
        ensureLoaded();
        return actions.size();
    }

    public synchronized boolean isSeen(String viewKey) {
        ensureLoaded();
        return !TextUtils.isEmpty(viewKey) && seen.contains(viewKey);
    }

    public synchronized void markSeen(String viewKey) {
        ensureLoaded();
        if (TextUtils.isEmpty(viewKey)) {
            return;
        }
        if (seen.contains(viewKey)) {
            return;
        }
        seen.add(viewKey);
        while (seen.size() > MAX_SEEN) {
            Iterator<String> it = seen.iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();
        }
        markDirtyInternal();
    }

    // ==================== v3：隐式反馈封顶（P0-1） ====================

    /** 位图：bit1 = 正反馈已全额学过，bit2 = 负反馈已全额学过 */
    public static final int IMPLICIT_POS_DONE = 1;
    public static final int IMPLICIT_NEG_DONE = 2;

    public synchronized int implicitFlags(String viewKey) {
        ensureLoaded();
        if (TextUtils.isEmpty(viewKey)) {
            return 0;
        }
        Integer f = implicitFlags.get(viewKey);
        return f == null ? 0 : f;
    }

    /** 标记某极性已全额学过一次；返回标记前是否从未学过该极性 */
    public synchronized boolean markImplicit(String viewKey, boolean positive) {
        ensureLoaded();
        if (TextUtils.isEmpty(viewKey)) {
            return false;
        }
        int cur = implicitFlags(viewKey);
        int bit = positive ? IMPLICIT_POS_DONE : IMPLICIT_NEG_DONE;
        boolean first = (cur & bit) == 0;
        implicitFlags.remove(viewKey);
        implicitFlags.put(viewKey, cur | bit);
        while (implicitFlags.size() > MAX_IMPLICIT) {
            Iterator<String> it = implicitFlags.keySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();
        }
        markDirtyInternal();
        return first;
    }

    // ==================== v3：UCB 特征统计（P1-3） ====================

    /** 特征键（"c:分类" / "t:标签"）累计曝光数，实现 {@link RecoScorer.StatSource} */
    public synchronized long served(String key) {
        ensureLoaded();
        if (TextUtils.isEmpty(key)) {
            return 0L;
        }
        long[] v = featureStats.get(key);
        return v == null ? 0L : v[0];
    }

    /** 全部特征累计曝光总数（UCB 的 ln(1+total) 项），实现 {@link RecoScorer.StatSource} */
    public synchronized long totalServed() {
        ensureLoaded();
        return statTotalServed;
    }

    public synchronized void bumpStatServed(String key) {
        ensureLoaded();
        if (TextUtils.isEmpty(key)) {
            return;
        }
        long[] v = featureStats.get(key);
        if (v == null) {
            v = new long[]{0L, 0L};
            featureStats.remove(key);
            featureStats.put(key, v);
            evictSmallestStat();
        }
        v[0]++;
        statTotalServed++;
        markDirtyInternal();
    }

    public synchronized void bumpStatPositive(String key) {
        ensureLoaded();
        if (TextUtils.isEmpty(key)) {
            return;
        }
        long[] v = featureStats.get(key);
        if (v == null) {
            v = new long[]{0L, 0L};
            featureStats.remove(key);
            featureStats.put(key, v);
            evictSmallestStat();
        }
        v[1]++;
        markDirtyInternal();
    }

    /** 统计表超限时淘汰 served+positive 最小的键（信息量最低） */
    private void evictSmallestStat() {
        while (featureStats.size() > MAX_STATS) {
            String worst = null;
            long worstSum = Long.MAX_VALUE;
            for (Map.Entry<String, long[]> e : featureStats.entrySet()) {
                long sum = e.getValue()[0] + e.getValue()[1];
                if (sum < worstSum) {
                    worstSum = sum;
                    worst = e.getKey();
                }
            }
            if (worst == null) {
                break;
            }
            featureStats.remove(worst);
        }
    }

    public synchronized void markDirty() {
        markDirtyInternal();
    }

    /** M98：置脏 + 递增变更序号（供锁外写盘后判断期间是否有新变更） */
    private void markDirtyInternal() {
        dirty = true;
        dirtySeq++;
    }

    public synchronized int seenSize() {
        ensureLoaded();
        return seen.size();
    }

    /** 上次画像学习所用的词典版本；与当前词典不一致时 RecoEngine 会自动重置画像 */
    public synchronized int getDictVersion() {
        ensureLoaded();
        return dictVersion;
    }

    /**
     * 记录画像当前对应的词典版本（仅在重置画像后调用）。
     * M158：不再在持 this 锁时调 save()（会形成 this→ioLock 反序，与 writeSnapshot 的
     * ioLock→this 构成 AB-BA 死锁隐患）——改为锁内改状态+快照、锁外统一写盘。
     */
    public void setDictVersion(int version) {
        long seqAtStart;
        File dir;
        StateSnapshot snap;
        synchronized (this) {
            ensureLoaded();
            dictVersion = version;
            markDirtyInternal();
            seqAtStart = dirtySeq;
            dir = prepareStateDir();
            if (dir == null) {
                return;
            }
            snap = snapshotState();
        }
        writeSnapshot(dir, snap, seqAtStart);
    }

    /**
     * 清空推荐记忆（画像 + 交互 + 去重表；词典版本号保留，不属于用户数据）。
     * M158：同 setDictVersion——锁内清状态+快照，锁外写盘。
     */
    public void reset() {
        long seqAtStart;
        File dir;
        StateSnapshot snap;
        synchronized (this) {
            ensureLoaded();
            profile.clear();
            actions.clear();
            seen.clear();
            authorNames.clear();
            implicitFlags.clear();
            featureStats.clear();
            statTotalServed = 0L;
            markDirtyInternal();
            seqAtStart = dirtySeq;
            dir = prepareStateDir();
            if (dir == null) {
                return;
            }
            snap = snapshotState();
        }
        writeSnapshot(dir, snap, seqAtStart);
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        File f = stateFile();
        if (f == null || !f.exists()) {
            return;
        }
        FileInputStream fis = null;
        InputStreamReader reader = null;
        try {
            fis = new FileInputStream(f);
            reader = new InputStreamReader(fis, "UTF-8");
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int len;
            while ((len = reader.read(buf)) > 0) {
                sb.append(buf, 0, len);
            }
            parse(new JSONObject(sb.toString()));
        } catch (Exception e) {
            // 文件损坏时丢弃重来，不影响功能
            profile = new RecoProfile();
            actions.clear();
            seen.clear();
            implicitFlags.clear();
            featureStats.clear();
            statTotalServed = 0L;
        } finally {
            close(reader);
            close(fis);
        }
    }

    private void parse(JSONObject root) {
        dictVersion = root.optInt("dictVersion", 0);
        RecoProfile p = new RecoProfile();
        JSONObject prof = root.optJSONObject("profile");
        if (prof != null) {
            readWeights(prof.optJSONObject("tags"), p.tagWeights);
            readWeights(prof.optJSONObject("authors"), p.authorWeights);
            readWeights(prof.optJSONObject("categories"), p.categoryWeights);
            readWeights(prof.optJSONObject("durations"), p.durationWeights);
            readWeights(prof.optJSONObject("orientations"), p.orientationWeights);
            p.lastDecayTime = prof.optLong("lastDecayTime", 0L);
            p.likeCount = prof.optInt("likeCount", 0);
            p.dislikeCount = prof.optInt("dislikeCount", 0);
            p.favoriteCount = prof.optInt("favoriteCount", 0);
            p.watchCount = prof.optInt("watchCount", 0);
        }
        profile = p;

        actions.clear();
        JSONObject act = root.optJSONObject("actions");
        if (act != null) {
            Iterator<String> it = act.keys();
            while (it.hasNext()) {
                String k = it.next();
                actions.put(k, act.optInt(k, 0));
            }
        }

        seen.clear();
        JSONArray arr = root.optJSONArray("seen");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, null);
                if (!TextUtils.isEmpty(s)) {
                    seen.add(s);
                }
            }
        }

        authorNames.clear();
        JSONObject names = root.optJSONObject("authorNames");
        if (names != null) {
            Iterator<String> it = names.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = names.optString(k, null);
                if (!TextUtils.isEmpty(k) && !TextUtils.isEmpty(v)) {
                    authorNames.put(k, v);
                }
            }
        }

        implicitFlags.clear();
        JSONObject flags = root.optJSONObject("implicitFlags");
        if (flags != null) {
            Iterator<String> it = flags.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!TextUtils.isEmpty(k)) {
                    implicitFlags.put(k, flags.optInt(k, 0));
                }
            }
        }

        featureStats.clear();
        statTotalServed = 0L;
        JSONObject stats = root.optJSONObject("featureStats");
        if (stats != null) {
            Iterator<String> it = stats.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject v = stats.optJSONObject(k);
                if (TextUtils.isEmpty(k) || v == null) {
                    continue;
                }
                long served = Math.max(0L, v.optLong("s", 0L));
                long positive = Math.max(0L, v.optLong("p", 0L));
                featureStats.put(k, new long[]{served, positive});
                statTotalServed += served;
            }
        }
    }

    private static void readWeights(JSONObject src, Map<String, Double> dst) {
        if (src == null) {
            return;
        }
        Iterator<String> it = src.keys();
        while (it.hasNext()) {
            String k = it.next();
            double v = src.optDouble(k, 0.0d);
            if (!Double.isNaN(v) && v != 0.0d) {
                dst.put(k, v);
            }
        }
    }

    /**
     * 写盘（原子替换）。调用方应放到 IO 线程。
     * <p>
     * M98：锁内只做画像/交互快照拷贝（三张权重表 + actions/seen/authorNames 浅拷贝），
     * 写盘成功且期间无新变更时才清 dirty 位。
     * <p>
     * M158：修复 M98 的名不符实——原方法整体被 synchronized 修饰，JSON 构建 + 文件 IO
     * 实际仍在持锁执行，主线程的 getAction/isSeen（推荐流点赞/翻页渲染路径）会被写盘阻塞。
     * 现拆为「锁内快照 → ioLock 内写盘 → 锁内核对清脏」三段；锁序恒为
     * this → (释放) → ioLock → this，无 AB-BA 死锁；并发写盘由 ioLock 串行化。
     */
    public void save() {
        long seqAtStart;
        File dir;
        StateSnapshot snap;
        synchronized (this) {
            ensureLoaded();
            if (!dirty) {
                return;
            }
            dir = prepareStateDir();
            if (dir == null) {
                return;
            }
            seqAtStart = dirtySeq;
            snap = snapshotState();
        }
        writeSnapshot(dir, snap, seqAtStart);
    }

    /** M158：锁内目录准备（纯内存/快速 mkdir，不做慢 IO） */
    private File prepareStateDir() {
        File dir = stateDir();
        if (dir == null) {
            return null;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        return dir;
    }

    /** M158：锁内快照拷贝（仅内存操作，须在 synchronized(this) 内调用） */
    private StateSnapshot snapshotState() {
        StateSnapshot snap = new StateSnapshot();
        snap.tags = new HashMap<>(profile.tagWeights);
        snap.authors = new HashMap<>(profile.authorWeights);
        snap.categories = new HashMap<>(profile.categoryWeights);
        snap.durations = new HashMap<>(profile.durationWeights);
        snap.orientations = new HashMap<>(profile.orientationWeights);
        snap.lastDecayTime = profile.lastDecayTime;
        snap.likeCount = profile.likeCount;
        snap.dislikeCount = profile.dislikeCount;
        snap.favoriteCount = profile.favoriteCount;
        snap.watchCount = profile.watchCount;
        snap.actions = new LinkedHashMap<>(actions);
        snap.seen = new ArrayList<>(seen);
        snap.authorNames = new LinkedHashMap<>(authorNames);
        snap.implicitFlags = new LinkedHashMap<>(implicitFlags);
        snap.featureStats = new LinkedHashMap<String, long[]>();
        for (Map.Entry<String, long[]> e : featureStats.entrySet()) {
            snap.featureStats.put(e.getKey(), new long[]{e.getValue()[0], e.getValue()[1]});
        }
        snap.statTotalServed = statTotalServed;
        snap.dictVersion = dictVersion;
        return snap;
    }

    /** M158：ioLock 内执行 JSON 构建 + 原子写盘，结束后短暂取 this 核对 dirtySeq 清脏位 */
    private void writeSnapshot(File dir, StateSnapshot snap, long seqAtStart) {
        synchronized (ioLock) {
            boolean ok = writeToDisk(snap, dir);
            synchronized (this) {
                if (ok && dirtySeq == seqAtStart) {
                    // 写盘期间没有新变更才清脏位；否则保留 dirty 等下次合并落盘
                    dirty = false;
                }
            }
        }
    }

    /** M158：在 ioLock（而非 this）内执行的 JSON 构建 + 原子替换写盘，只读快照不碰成员状态 */
    private boolean writeToDisk(StateSnapshot snap, File dir) {
        File tmp = new File(dir, TMP);
        File dst = new File(dir, FILE);
        FileOutputStream fos = null;
        OutputStreamWriter writer = null;
        try {
            JSONObject root = new JSONObject();
            JSONObject prof = new JSONObject();
            prof.put("tags", new JSONObject(snap.tags));
            prof.put("authors", new JSONObject(snap.authors));
            prof.put("categories", new JSONObject(snap.categories));
            prof.put("durations", new JSONObject(snap.durations));
            prof.put("orientations", new JSONObject(snap.orientations));
            prof.put("lastDecayTime", snap.lastDecayTime);
            prof.put("likeCount", snap.likeCount);
            prof.put("dislikeCount", snap.dislikeCount);
            prof.put("favoriteCount", snap.favoriteCount);
            prof.put("watchCount", snap.watchCount);
            root.put("profile", prof);
            root.put("actions", new JSONObject(snap.actions));
            JSONArray arr = new JSONArray();
            for (String s : snap.seen) {
                arr.put(s);
            }
            root.put("seen", arr);
            root.put("authorNames", new JSONObject(snap.authorNames));
            JSONObject flags = new JSONObject();
            for (Map.Entry<String, Integer> e : snap.implicitFlags.entrySet()) {
                flags.put(e.getKey(), e.getValue());
            }
            root.put("implicitFlags", flags);
            JSONObject stats = new JSONObject();
            for (Map.Entry<String, long[]> e : snap.featureStats.entrySet()) {
                JSONObject v = new JSONObject();
                v.put("s", e.getValue()[0]);
                v.put("p", e.getValue()[1]);
                stats.put(e.getKey(), v);
            }
            root.put("featureStats", stats);
            root.put("statTotalServed", snap.statTotalServed);
            root.put("dictVersion", snap.dictVersion);
            root.put("v", 2);

            fos = new FileOutputStream(tmp);
            writer = new OutputStreamWriter(fos, "UTF-8");
            writer.write(root.toString());
            writer.flush();
            close(writer);
            close(fos);
            writer = null;
            fos = null;
            if (dst.exists() && !dst.delete()) {
                // 删除失败也尝试 rename（部分文件系统允许覆盖）
                android.util.Log.w("RecoStore", "old state delete failed");
            }
            if (!tmp.renameTo(dst)) {
                android.util.Log.w("RecoStore", "state rename failed");
                return false;
            }
            return true;
        } catch (Exception e) {
            android.util.Log.w("RecoStore", "save reco state failed: " + e.getMessage());
            return false;
        } finally {
            close(writer);
            close(fos);
        }
    }

    /** M98：save() 锁内拷贝出的不可变快照（字段即拷贝时刻的值） */
    private static final class StateSnapshot {
        Map<String, Double> tags;
        Map<String, Double> authors;
        Map<String, Double> categories;
        Map<String, Double> durations;
        Map<String, Double> orientations;
        long lastDecayTime;
        int likeCount;
        int dislikeCount;
        int favoriteCount;
        int watchCount;
        Map<String, Integer> actions;
        List<String> seen;
        Map<String, String> authorNames;
        Map<String, Integer> implicitFlags;
        Map<String, long[]> featureStats;
        long statTotalServed;
        int dictVersion;
    }

    private File stateDir() {
        if (appContext == null) {
            return null;
        }
        return new File(appContext.getFilesDir(), DIR);
    }

    private File stateFile() {
        File dir = stateDir();
        return dir == null ? null : new File(dir, FILE);
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 供调试/自测用 */
    public synchronized Set<String> snapshotSeen() {
        ensureLoaded();
        return new LinkedHashSet<>(seen);
    }
}
