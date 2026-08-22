package com.m3man.data.db;

import com.github.yuweiguocn.library.greendao.MigrationHelper;
import com.liulishuo.filedownloader.model.FileDownloadStatus;
import com.m3man.BuildConfig;
import com.m3man.data.db.entity.AuthorFavorite;
import com.m3man.data.db.entity.AuthorFavoriteDao;
import com.m3man.data.db.entity.Category;
import com.m3man.data.db.entity.CategoryDao;
import com.m3man.data.db.entity.DaoMaster;
import com.m3man.data.db.entity.DaoSession;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.V9MmanItemDao;
import com.m3man.data.db.entity.VideoResult;
import com.m3man.data.db.entity.VideoResultDao;
import com.m3man.data.db.entity.AutoCompleteEntity;
import com.m3man.data.db.entity.AutoCompleteEntityDao;
import android.text.TextUtils;
import java.util.Date;

import org.greenrobot.greendao.database.Database;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * @author flymegoc
 * @date 2018/3/4
 */

@Singleton
public class AppDbHelper implements DbHelper {
    private final DaoSession mDaoSession;

    @Inject
    AppDbHelper(MySQLiteOpenHelper helper) {
        //如果你想查看日志信息，请将DEBUG设置为true
        MigrationHelper.DEBUG = BuildConfig.DEBUG;
        Database db = helper.getWritableDb();
        this.mDaoSession = new DaoMaster(db).newSession();
        initCategory(Category.TYPE_91PORN, Category.CATEGORY_DEFAULT_91PORN_VALUE, Category.CATEGORY_DEFAULT_91PORN_NAME);
    }

    @Override
    public void initCategory(int type, String[] value, String[] name) {
        if (name == null || name.length == 0) {
            return;
        }
        int length = name.length;
        List<Category> existList = mDaoSession.getCategoryDao().queryBuilder()
                .where(CategoryDao.Properties.CategoryType.eq(type)).build().list();

        //M2：原实现仅比较"行数是否等于默认数组长度"，一旦默认分类数组增删，
        //每次启动都会把整组默认分类重复插入。改为按 categoryValue 精确判重，只补缺失项。
        Set<String> existValues = new HashSet<>();
        for (Category category : existList) {
            if (category.getCategoryValue() != null) {
                existValues.add(category.getCategoryValue());
            }
        }

        List<Category> needInsert = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            String categoryValue = (value == null) ? String.valueOf(i + 1) : value[i];
            if (existValues.contains(categoryValue)) {
                continue;
            }
            Category category = new Category();
            category.setCategoryName(name[i]);
            category.setCategoryValue(categoryValue);
            category.setCategoryType(type);
            category.setIsShow(true);
            category.setSortId(i);
            needInsert.add(category);
        }
        if (needInsert.isEmpty()) {
            return;
        }
        mDaoSession.getCategoryDao().insertOrReplaceInTx(needInsert);
    }

    @Override
    public synchronized void updateV9MmanItem(V9MmanItem v9MmanItem) {
        mDaoSession.getV9MmanItemDao().update(v9MmanItem);
    }

    @Override
    public List<V9MmanItem> loadDownloadingData() {
        return mDaoSession.getV9MmanItemDao().queryBuilder().where(V9MmanItemDao.Properties.Status.notEq(FileDownloadStatus.completed), V9MmanItemDao.Properties.DownloadId.notEq(0)).orderDesc(V9MmanItemDao.Properties.AddDownloadDate).build().list();

    }

    @Override
    public List<V9MmanItem> loadFinishedData() {
        return mDaoSession.getV9MmanItemDao().queryBuilder().where(V9MmanItemDao.Properties.Status.eq(FileDownloadStatus.completed), V9MmanItemDao.Properties.DownloadId.notEq(0)).orderDesc(V9MmanItemDao.Properties.FinishedDownloadDate).build().list();
    }

    @Override
    public List<V9MmanItem> loadHistoryData(int page, int pageSize) {
        return mDaoSession.getV9MmanItemDao().queryBuilder().where(V9MmanItemDao.Properties.ViewHistoryDate.isNotNull()).orderDesc(V9MmanItemDao.Properties.ViewHistoryDate).offset((page - 1) * pageSize).limit(pageSize).build().list();
    }

    @Override
    public synchronized long saveV9MmanItem(V9MmanItem v9MmanItem) {
        if (v9MmanItem == null) {
            return -1;
        }
        //M5：网络解析出的对象 id 为 null，直接 insertOrReplace 会因 viewKey 唯一索引冲突
        //而"销毁旧行 + 插入新行"，导致下载进度/状态/收藏标记/历史时间被重置。
        //这里先按 viewKey 找到旧行，把旧行中不该被网络数据覆盖的字段继承过来，再整行更新。
        if (v9MmanItem.getId() == null && v9MmanItem.getViewKey() != null) {
            V9MmanItem old = findV9MmanItemByViewKey(v9MmanItem.getViewKey());
            if (old != null) {
                v9MmanItem.setId(old.getId());
                mergeDownloadState(old, v9MmanItem);
            }
        }
        return mDaoSession.getV9MmanItemDao().insertOrReplace(v9MmanItem);
    }

    /**
     * 把旧记录中"仅本地产生"的字段继承到新记录上，避免被网络数据覆盖丢失。
     *
     * @param old    数据库中已有记录
     * @param target 待写入记录
     */
    private void mergeDownloadState(V9MmanItem old, V9MmanItem target) {
        if (target.getDownloadId() == 0) {
            target.setDownloadId(old.getDownloadId());
            target.setStatus(old.getStatus());
            target.setProgress(old.getProgress());
            target.setSoFarBytes(old.getSoFarBytes());
            target.setTotalFarBytes(old.getTotalFarBytes());
        }
        if (target.getAddDownloadDate() == null) {
            target.setAddDownloadDate(old.getAddDownloadDate());
        }
        if (target.getFinishedDownloadDate() == null) {
            target.setFinishedDownloadDate(old.getFinishedDownloadDate());
        }
        if (target.getViewHistoryDate() == null) {
            target.setViewHistoryDate(old.getViewHistoryDate());
        }
        if (target.getIsLocalFavorite() == null) {
            target.setIsLocalFavorite(old.getIsLocalFavorite());
        }
        if (target.getSourceName() == null) {
            target.setSourceName(old.getSourceName());
        }
        if (target.getVideoResultId() == 0) {
            target.setVideoResultId(old.getVideoResultId());
        }
    }

    @Override
    public long saveVideoResult(VideoResult videoResult) {
        // M62：按业务键 videoId 去重——所有调用方传入的 id 均为 null（GreenDAO 一律 INSERT），
        // 旧行为导致 VIDEO_RESULT 表随每次解析无限膨胀出孤儿行。
        // 已存在同 videoId 的记录时复用其主键做替换，外键引用保持有效。
        if (videoResult.getId() == null && !TextUtils.isEmpty(videoResult.getVideoId())) {
            try {
                VideoResult existing = mDaoSession.getVideoResultDao().queryBuilder()
                        .where(VideoResultDao.Properties.VideoId.eq(videoResult.getVideoId()))
                        .build().unique();
                if (existing != null) {
                    videoResult.setId(existing.getId());
                }
            } catch (Exception ignored) {
            }
        }
        return mDaoSession.getVideoResultDao().insertOrReplace(videoResult);
    }

    @Override
    public V9MmanItem findV9MmanItemByViewKey(String viewKey) {
        V9MmanItemDao v9MmanItemDao = mDaoSession.getV9MmanItemDao();
        try {
            // D3：viewKey 已建立唯一索引，直接用 unique() 即可；
            // 偶发异常不再删除命中行，避免误删用户数据。
            return v9MmanItemDao.queryBuilder().where(V9MmanItemDao.Properties.ViewKey.eq(viewKey)).build().unique();
        } catch (Exception e) {
            // 兜底：用 list 取第一条，绝不再删除用户数据
            List<V9MmanItem> tmp = v9MmanItemDao.queryBuilder().where(V9MmanItemDao.Properties.ViewKey.eq(viewKey)).build().list();
            if (tmp != null && !tmp.isEmpty()) {
                return tmp.get(0);
            }
            if (!BuildConfig.DEBUG) {
                e.printStackTrace();
            }
            return null;
        }
    }

    @Override
    public V9MmanItem findV9MmanItemByDownloadId(int downloadId) {
        try {
            return mDaoSession.getV9MmanItemDao().queryBuilder().where(V9MmanItemDao.Properties.DownloadId.eq(downloadId)).build().unique();
        } catch (Exception e) {
            //暂时先不处理这问题了，理论上一个不会发生，因为时根据url生成
            if (!BuildConfig.DEBUG) {
                //Bugsnag.notify(new Throwable("findV9MmanItemDaoByDownloadId DaoException", e), Severity.WARNING);
            }
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public List<V9MmanItem> loadV9MmanItems() {
        return mDaoSession.getV9MmanItemDao().loadAll();
    }

    @Override
    public List<V9MmanItem> loadLocalFavoriteItems() {
        //M6：读前 detach，避免跨线程拿到陈旧缓存实体
        V9MmanItemDao dao = mDaoSession.getV9MmanItemDao();
        dao.detachAll();
        return dao.queryBuilder()
                .where(V9MmanItemDao.Properties.IsLocalFavorite.eq(true))
                .orderDesc(V9MmanItemDao.Properties.Id)
                .build().list();
    }

    @Override
    public List<V9MmanItem> findV9MmanItemByDownloadStatus(int status) {
        return mDaoSession.getV9MmanItemDao().queryBuilder().where(V9MmanItemDao.Properties.Status.eq(status)).build().list();
    }

    @Override
    public List<Category> loadAllCategoryDataByType(int type) {
        CategoryDao categoryDao = mDaoSession.getCategoryDao();
        categoryDao.detachAll();
        return categoryDao.queryBuilder().where(CategoryDao.Properties.CategoryType.eq(type)).orderAsc(CategoryDao.Properties.SortId).build().list();
    }

    @Override
    public List<Category> loadCategoryDataByType(int type) {
        CategoryDao categoryDao = mDaoSession.getCategoryDao();
        categoryDao.detachAll();
        return categoryDao.queryBuilder().where(CategoryDao.Properties.CategoryType.eq(type), CategoryDao.Properties.IsShow.eq(true)).orderAsc(CategoryDao.Properties.SortId).build().list();
    }

    @Override
    public void updateCategoryData(List<Category> categoryList) {
        mDaoSession.getCategoryDao().updateInTx(categoryList);
    }

    @Override
    public Category findCategoryById(Long id) {
        CategoryDao categoryDao = mDaoSession.getCategoryDao();
        categoryDao.detachAll();
        return categoryDao.load(id);
    }

    @Override
    public List<AuthorFavorite> loadAuthorFavorites() {
        //M6：Session 级 IdentityScope 会缓存实体并跨 IO/主线程共享，读前先 detach 拿最新数据
        AuthorFavoriteDao dao = mDaoSession.getAuthorFavoriteDao();
        dao.detachAll();
        return dao.queryBuilder()
                .orderDesc(AuthorFavoriteDao.Properties.FavoriteDate)
                .build().list();
    }

    @Override
    public AuthorFavorite findAuthorFavorite(String authorKey, String source) {
        // D5：用 list().get(0) 而非 unique()，即便历史上已存在重复行也不会抛 DaoException 导致功能永久损坏
        // M6：读前 detach，避免拿到其它线程缓存的陈旧实体
        mDaoSession.getAuthorFavoriteDao().detachAll();
        List<AuthorFavorite> list = mDaoSession.getAuthorFavoriteDao().queryBuilder()
                .where(AuthorFavoriteDao.Properties.AuthorKey.eq(authorKey),
                        AuthorFavoriteDao.Properties.Source.eq(source))
                .build().list();
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    @Override
    public synchronized long saveAuthorFavorite(AuthorFavorite authorFavorite) {
        // D5：入库前查重（synchronized 单例实例，保证 check-then-act 原子），
        // 避免快速双击 / 多入口并发写入产生 (authorKey, source) 重复行
        if (findAuthorFavorite(authorKeyOrNull(authorFavorite), sourceOrNull(authorFavorite)) != null) {
            return -1;
        }
        return mDaoSession.getAuthorFavoriteDao().insertOrReplace(authorFavorite);
    }

    private static String authorKeyOrNull(AuthorFavorite f) {
        return f != null ? f.getAuthorKey() : null;
    }

    private static String sourceOrNull(AuthorFavorite f) {
        return f != null ? f.getSource() : null;
    }

    @Override
    public void deleteAuthorFavorite(AuthorFavorite authorFavorite) {
        mDaoSession.getAuthorFavoriteDao().delete(authorFavorite);
    }

    @Override
    public boolean isAuthorFavorited(String authorKey, String source) {
        return findAuthorFavorite(authorKey, source) != null;
    }

    // ---- 自动补全（地址 / 用户名）建议 ----

    @Override
    public List<String> getAutoCompleteNames(int type) {
        List<String> names = new ArrayList<>();
        try {
            List<AutoCompleteEntity> list = mDaoSession.getAutoCompleteEntityDao().queryBuilder()
                    .where(AutoCompleteEntityDao.Properties.Type.eq(type))
                    .orderDesc(AutoCompleteEntityDao.Properties.UseTime)
                    .list();
            for (AutoCompleteEntity e : list) {
                if (!TextUtils.isEmpty(e.getName()) && !names.contains(e.getName())) {
                    names.add(e.getName());
                }
            }
        } catch (Exception e) {
            // 查询异常不应影响 UI
        }
        return names;
    }

    @Override
    public void saveAutoComplete(String name, int type) {
        if (TextUtils.isEmpty(name)) {
            return;
        }
        try {
            AutoCompleteEntityDao dao = mDaoSession.getAutoCompleteEntityDao();
            AutoCompleteEntity exist = dao.queryBuilder()
                    .where(AutoCompleteEntityDao.Properties.Name.eq(name),
                            AutoCompleteEntityDao.Properties.Type.eq(type))
                    .unique();
            if (exist != null) {
                exist.setUseTime(exist.getUseTime() + 1);
                exist.setUpdateDate(new Date());
                dao.update(exist);
            } else {
                dao.insert(new AutoCompleteEntity(null, name, 1, type, new Date(), new Date()));
            }
        } catch (Exception e) {
            // 写入失败不应影响设置保存
        }
    }

    // ---- 搜索历史（复用 AutoCompleteEntity 表，type = TYPE_SEARCH_HISTORY） ----

    @Override
    public List<String> getSearchHistory(int type, int limit) {
        List<String> names = new ArrayList<>();
        try {
            List<AutoCompleteEntity> list = mDaoSession.getAutoCompleteEntityDao().queryBuilder()
                    .where(AutoCompleteEntityDao.Properties.Type.eq(type))
                    .orderDesc(AutoCompleteEntityDao.Properties.UpdateDate)
                    .limit(limit)
                    .list();
            for (AutoCompleteEntity e : list) {
                if (!TextUtils.isEmpty(e.getName()) && !names.contains(e.getName())) {
                    names.add(e.getName());
                }
            }
        } catch (Exception e) {
            // 查询异常不应影响 UI
        }
        return names;
    }

    @Override
    public void clearSearchHistory(int type) {
        try {
            List<AutoCompleteEntity> list = mDaoSession.getAutoCompleteEntityDao().queryBuilder()
                    .where(AutoCompleteEntityDao.Properties.Type.eq(type))
                    .list();
            if (list != null && !list.isEmpty()) {
                mDaoSession.getAutoCompleteEntityDao().deleteInTx(list);
            }
        } catch (Exception e) {
            // 删除失败不应影响 UI
        }
    }

    @Override
    public void deleteSearchHistory(String name, int type) {
        if (TextUtils.isEmpty(name)) {
            return;
        }
        try {
            AutoCompleteEntity entity = mDaoSession.getAutoCompleteEntityDao().queryBuilder()
                    .where(AutoCompleteEntityDao.Properties.Name.eq(name),
                            AutoCompleteEntityDao.Properties.Type.eq(type))
                    .unique();
            if (entity != null) {
                mDaoSession.getAutoCompleteEntityDao().delete(entity);
            }
        } catch (Exception e) {
            // 删除失败不应影响 UI
        }
    }
}
