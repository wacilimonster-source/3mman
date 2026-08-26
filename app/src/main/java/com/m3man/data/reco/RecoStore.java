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
public class RecoStore {

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

    private final Context appContext;

    private RecoProfile profile = new RecoProfile();
    /** viewKey -> action，LinkedHashMap 保序便于淘汰 */
    private final LinkedHashMap<String, Integer> actions = new LinkedHashMap<>();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    /** 作者 id -> 昵称，画像里只有 id，展示学习记录时需要它翻译成人能看懂的名字 */
    private final LinkedHashMap<String, String> authorNames = new LinkedHashMap<>();

    private boolean loaded = false;
    private boolean dirty = false;
    /** M98：脏变更序号——锁外写盘期间若有新 markDirty，可据此判断不能清 dirty 位 */
    private long dirtySeq = 0L;
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

    /** 记录画像当前对应的词典版本（仅在重置画像后调用） */
    public synchronized void setDictVersion(int version) {
        ensureLoaded();
        dictVersion = version;
        markDirtyInternal();
        save();
    }

    /** 清空推荐记忆（画像 + 交互 + 去重表；词典版本号保留，不属于用户数据） */
    public synchronized void reset() {
        ensureLoaded();
        profile.clear();
        actions.clear();
        seen.clear();
        authorNames.clear();
        markDirtyInternal();
        save();
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
                if (!TextUtils.isEmpty(v)) {
                    authorNames.put(k, v);
                }
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
     * JSON 构建与文件 IO 全部移到锁外执行，避免持锁写盘阻塞 UI 线程的
     * getAction/isSeen 等同步读路径。写盘成功且期间无新变更时才清 dirty 位。
     */
    public synchronized void save() {
        ensureLoaded();
        if (!dirty) {
            return;
        }
        File dir = stateDir();
        if (dir == null) {
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        long seqAtStart = dirtySeq;
        // ---- 锁内：快照拷贝（仅内存操作）----
        StateSnapshot snap = new StateSnapshot();
        snap.tags = new HashMap<>(profile.tagWeights);
        snap.authors = new HashMap<>(profile.authorWeights);
        snap.categories = new HashMap<>(profile.categoryWeights);
        snap.lastDecayTime = profile.lastDecayTime;
        snap.likeCount = profile.likeCount;
        snap.dislikeCount = profile.dislikeCount;
        snap.favoriteCount = profile.favoriteCount;
        snap.watchCount = profile.watchCount;
        snap.actions = new LinkedHashMap<>(actions);
        snap.seen = new ArrayList<>(seen);
        snap.authorNames = new LinkedHashMap<>(authorNames);
        snap.dictVersion = dictVersion;
        // ---- 锁外（同线程、无监视器）：JSON 构建 + 文件 IO ----
        boolean ok = writeToDisk(snap, dir);
        if (ok && dirtySeq == seqAtStart) {
            // 写盘期间没有新变更才清脏位；否则保留 dirty 等下次合并落盘
            dirty = false;
        }
    }

    /** M98：锁外执行的 JSON 构建 + 原子替换写盘，只读快照不碰成员状态 */
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
            root.put("dictVersion", snap.dictVersion);
            root.put("v", 1);

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
        long lastDecayTime;
        int likeCount;
        int dislikeCount;
        int favoriteCount;
        int watchCount;
        Map<String, Integer> actions;
        List<String> seen;
        Map<String, String> authorNames;
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
