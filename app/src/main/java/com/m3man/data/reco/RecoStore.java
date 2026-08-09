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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
        dirty = true;
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
        dirty = true;
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
        dirty = true;
    }

    public synchronized void markDirty() {
        dirty = true;
    }

    public synchronized int seenSize() {
        ensureLoaded();
        return seen.size();
    }

    /** 清空推荐记忆（画像 + 交互 + 去重表） */
    public synchronized void reset() {
        ensureLoaded();
        profile.clear();
        actions.clear();
        seen.clear();
        authorNames.clear();
        dirty = true;
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

    /** 写盘（原子替换）。调用方应放到 IO 线程。 */
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
        File tmp = new File(dir, TMP);
        File dst = new File(dir, FILE);
        FileOutputStream fos = null;
        OutputStreamWriter writer = null;
        try {
            JSONObject root = new JSONObject();
            JSONObject prof = new JSONObject();
            prof.put("tags", new JSONObject(profile.tagWeights));
            prof.put("authors", new JSONObject(profile.authorWeights));
            prof.put("categories", new JSONObject(profile.categoryWeights));
            prof.put("lastDecayTime", profile.lastDecayTime);
            prof.put("likeCount", profile.likeCount);
            prof.put("dislikeCount", profile.dislikeCount);
            prof.put("favoriteCount", profile.favoriteCount);
            prof.put("watchCount", profile.watchCount);
            root.put("profile", prof);
            root.put("actions", new JSONObject(actions));
            JSONArray arr = new JSONArray();
            for (String s : seen) {
                arr.put(s);
            }
            root.put("seen", arr);
            root.put("authorNames", new JSONObject(authorNames));
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
                return;
            }
            dirty = false;
        } catch (Exception e) {
            android.util.Log.w("RecoStore", "save reco state failed: " + e.getMessage());
        } finally {
            close(writer);
            close(fos);
        }
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
