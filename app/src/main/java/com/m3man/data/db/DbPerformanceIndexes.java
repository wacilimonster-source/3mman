package com.m3man.data.db;

import android.text.TextUtils;

import com.m3man.perf.Perf;
import com.m3man.utils.AppLog;

import org.greenrobot.greendao.database.Database;

import java.util.ArrayList;
import java.util.List;

/**
 * T08：查询性能索引（方案 §5-T08）。
 *
 * <p>只加非唯一索引，不改字段、不提高 greenDAO schemaVersion（不触发全表迁移）。
 * {@link #ensure(Database)} 幂等（CREATE INDEX IF NOT EXISTS），接入
 * MySQLiteOpenHelper 的 onCreate/onUpgrade 之后及 onOpen 后台路径；
 * 建前查 sqlite_master 确认已有定义匹配，建后记录耗时成本。
 *
 * <p>索引选择依据 50,000 行 fixture 的 EXPLAIN QUERY PLAN（T00 基线任务验证）：
 * <ul>
 *   <li>历史：VIEW_HISTORY_DATE 非空部分索引 (VIEW_HISTORY_DATE, _id)
 *       ——谓词 {@code VIEW_HISTORY_DATE IS NOT NULL AND (date < ? OR (date = ? AND _id < ?))}
 *       ORDER BY date DESC, _id DESC</li>
 *   <li>本地收藏：(IS_LOCAL_FAVORITE, _id) ——谓词 isLocalFavorite=1 ORDER BY _id DESC</li>
 *   <li>完成下载：(STATUS, FINISHED_DOWNLOAD_DATE, _id)
 *       ——谓词 status=completed AND downloadId!=0 ORDER BY finishedDownloadDate DESC；
 *       downloadId!=0 对普通索引无额外收益（计划注明），不加独立索引</li>
 *   <li>viewKey 唯一索引已存在（schema v10），不重复增加</li>
 * </ul>
 *
 * @author 3mman
 */
public final class DbPerformanceIndexes {

    private static final String TAG = "DbPerfIndexes";

    /** 索引定义：名称 + DDL。仅非唯一索引；列名与 greenDAO 生成 schema 一致。 */
    private static final String[] INDEX_DDL = {
            // 历史（部分索引：只覆盖有历史记录的行）
            "CREATE INDEX IF NOT EXISTS idx_v9_history_date_id ON V9_MMAN_ITEM ("
                    + "VIEW_HISTORY_DATE, _id) WHERE VIEW_HISTORY_DATE IS NOT NULL",
            // 本地收藏
            "CREATE INDEX IF NOT EXISTS idx_v9_local_fav_id ON V9_MMAN_ITEM ("
                    + "IS_LOCAL_FAVORITE, _id)",
            // 完成下载
            "CREATE INDEX IF NOT EXISTS idx_v9_finished_status_date_id ON V9_MMAN_ITEM ("
                    + "STATUS, FINISHED_DOWNLOAD_DATE, _id)",
    };

    private static final Object ensureLock = new Object();
    private static volatile boolean ensuredInProcess = false;

    private DbPerformanceIndexes() {
    }

    /**
     * 幂等建索引。调用方放后台线程（onOpen 之后/DB ready 后）；
     * 同进程只执行一次（sqlite_master 校验后 IF NOT EXISTS 兜底跨进程/回滚场景）。
     */
    public static void ensure(Database db) {
        if (db == null) {
            return;
        }
        synchronized (ensureLock) {
            if (ensuredInProcess) {
                return;
            }
            long req = Perf.nextRequestId();
            long begin = Perf.recorder().begin("db.index.ensure", req);
            try {
                List<String> existing = existingIndexes(db);
                int created = 0;
                for (String ddl : INDEX_DDL) {
                    String name = indexName(ddl);
                    if (existing.contains(name)) {
                        continue;
                    }
                    long oneBegin = android.os.SystemClock.uptimeMillis();
                    db.execSQL(ddl);
                    created++;
                    AppLog.i(TAG, "建索引 " + name + " 耗时 "
                            + (android.os.SystemClock.uptimeMillis() - oneBegin) + "ms");
                }
                ensuredInProcess = true;
                Perf.recorder().end("db.index.ensure", req, begin, Perf.OUTCOME_OK);
                if (created > 0) {
                    AppLog.i(TAG, "索引就绪：新建 " + created + "/" + INDEX_DDL.length);
                }
            } catch (Exception e) {
                ensuredInProcess = true; // 失败不再重试（下次进程冷启再试），不影响功能
                Perf.recorder().end("db.index.ensure", req, begin, Perf.OUTCOME_FAIL);
                AppLog.e(TAG, "建索引失败（保留查询回退全表扫描）: " + AppLog.cause(e));
            }
        }
    }

    private static List<String> existingIndexes(Database db) {
        List<String> names = new ArrayList<>();
        android.database.Cursor c = null;
        try {
            c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='index'", null);
            while (c.moveToNext()) {
                String n = c.getString(0);
                if (!TextUtils.isEmpty(n)) {
                    names.add(n);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return names;
    }

    /** 从 DDL 提取索引名（CREATE INDEX IF NOT EXISTS <name> ON ...） */
    private static String indexName(String ddl) {
        int a = ddl.indexOf("IF NOT EXISTS ") + "IF NOT EXISTS ".length();
        int b = ddl.indexOf(' ', a);
        return ddl.substring(a, b);
    }
}
