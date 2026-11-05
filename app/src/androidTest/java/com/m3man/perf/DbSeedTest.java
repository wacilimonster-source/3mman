package com.m3man.perf;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.m3man.data.db.MySQLiteOpenHelper;
import com.m3man.data.db.entity.DaoMaster;
import com.m3man.data.db.entity.DaoSession;
import com.m3man.data.db.entity.V9MmanItem;

import org.greenrobot.greendao.database.Database;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * T00 数据规模种子器（方案 §3.1）：向测试包（com.m3man.perf）专属数据库写入
 * 0 / 1,000 / 10,000 / 50,000 行确定性数据，不清空用户数据（正式包不受影响）。
 *
 * 用法：
 *   adb shell am instrument -w \
 *     -e class com.m3man.perf.DbSeedTest \
 *     -e seed_rows 50000 \
 *     com.m3man.perf.test/androidx.test.runner.AndroidJUnitRunner
 *
 * 说明：种子只通过测试代码注入（方案 §5 T00-1），线上包无任何调试入口。
 */
@RunWith(AndroidJUnit4.class)
public class DbSeedTest {

    private static final String TAG = "DbSeedTest";

    @Test
    public void seedDatabase() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // 与 Constants.DB_NAME 同名：benchmark 包名隔离（com.m3man.perf），不触碰正式包数据
        MySQLiteOpenHelper helper = new MySQLiteOpenHelper(context, "9mman");
        Database db = helper.getWritableDb();
        DaoSession session = new DaoMaster(db).newSession();

        int rows = Integer.parseInt(
                InstrumentationRegistry.getArguments().getString("seed_rows", "0"));
        if (rows <= 0) {
            android.util.Log.i(TAG, "seed_rows<=0，仅建库不写入");
            return;
        }

        long begin = android.os.SystemClock.uptimeMillis();
        List<V9MmanItem> batch = new ArrayList<>(1000);
        for (int i = 0; i < rows; i++) {
            V9MmanItem item = new V9MmanItem();
            item.setViewKey("seedview" + i);
            item.setTitle("种子视频 " + i);
            item.setImgUrl("file:///seed/cover" + (i % 500) + ".jpg");
            item.setDuration("00:" + String.format("%02d", i % 60) + ":00");
            item.setSourceName(i % 3 == 0 ? "91porny" : "mman9");
            item.setIsLocalFavorite(i % 7 == 0);
            // 历史时间倒序铺开：最新在 i=0，供 (date DESC, _id DESC) 分页测试
            item.setViewHistoryDate(new Date(1_700_000_000_000L - i * 60_000L));
            if (i % 5 == 0) {
                // 完成下载子集：status=2(completed 近似)、downloadId!=0、完成时间
                item.setStatus(2);
                item.setDownloadId(10_000 + i);
                item.setFinishedDownloadDate(new Date(1_700_000_000_000L - i * 30_000L));
            }
            batch.add(item);
            if (batch.size() >= 1000) {
                session.getV9MmanItemDao().insertInTx(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            session.getV9MmanItemDao().insertInTx(batch);
        }
        long cost = android.os.SystemClock.uptimeMillis() - begin;
        long total = session.getV9MmanItemDao().count();
        android.util.Log.i(TAG, "seed done rows=" + rows + " total=" + total + " costMs=" + cost);
    }
}
