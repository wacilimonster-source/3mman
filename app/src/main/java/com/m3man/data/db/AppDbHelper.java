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

import org.greenrobot.greendao.database.Database;

import java.util.List;

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
        initCategory(Category.TYPE_PXG_AV, Category.CATEGORY_DEFAULT_PXG_AV_VALUE, Category.CATEGORY_DEFAULT_PXG_AV_NAME);
        initCategory(Category.TYPE_AXGLE, Category.CATEGORY_DEFAULT_AXGLE_VALUE, Category.CATEGORY_DEFAULT_AXGLE_NAME);
        initCategory(Category.TYPE_KE_DOU_WO, Category.CATEGORY_DEFAULT_KE_DOU_WO_VALUE, Category.CATEGORY_DEFAULT_KE_DOU_WO_NAME);
    }

    @Override
    public void initCategory(int type, String[] value, String[] name) {
        int length = name.length;
        List<Category> categoryList = mDaoSession.getCategoryDao().queryBuilder().where(CategoryDao.Properties.CategoryType.eq(type)).build().list();
        if (categoryList.size() == length) {
            return;
        }
        for (int i = 0; i < length; i++) {
            Category category = new Category();
            category.setCategoryName(name[i]);
            if (value == null) {
                category.setCategoryValue(String.valueOf(i + 1));
            } else {
                category.setCategoryValue(value[i]);
            }

            category.setCategoryType(type);
            category.setIsShow(true);
            category.setSortId(i);
            categoryList.add(category);
        }
        mDaoSession.getCategoryDao().insertOrReplaceInTx(categoryList);
    }

    @Override
    public void updateV9MmanItem(V9MmanItem v9MmanItem) {
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
    public long saveV9MmanItem(V9MmanItem v9MmanItem) {
        return mDaoSession.getV9MmanItemDao().insertOrReplace(v9MmanItem);
    }

    @Override
    public long saveVideoResult(VideoResult videoResult) {
        return mDaoSession.getVideoResultDao().insertOrReplace(videoResult);
    }

    @Override
    public V9MmanItem findV9MmanItemByViewKey(String viewKey) {
        V9MmanItemDao v9MmanItemDao = mDaoSession.getV9MmanItemDao();
        try {
            return v9MmanItemDao.queryBuilder().where(V9MmanItemDao.Properties.ViewKey.eq(viewKey)).build().unique();
        } catch (Exception e) {
            //暂时先都删除了，之前没有设置唯一约束
            List<V9MmanItem> tmp = v9MmanItemDao.queryBuilder().where(V9MmanItemDao.Properties.ViewKey.eq(viewKey)).build().list();
            for (V9MmanItem v9MmanItem : tmp) {
                v9MmanItemDao.delete(v9MmanItem);
            }
            if (!BuildConfig.DEBUG) {
                //Bugsnag.notify(new Throwable("findV9MmanItemDaoByViewKey DaoException", e), Severity.WARNING);
            }
            e.printStackTrace();
        }
        return null;
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
        return mDaoSession.getV9MmanItemDao().queryBuilder()
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
        return mDaoSession.getAuthorFavoriteDao().queryBuilder()
                .orderDesc(AuthorFavoriteDao.Properties.FavoriteDate)
                .build().list();
    }

    @Override
    public AuthorFavorite findAuthorFavorite(String authorKey, String source) {
        return mDaoSession.getAuthorFavoriteDao().queryBuilder()
                .where(AuthorFavoriteDao.Properties.AuthorKey.eq(authorKey),
                        AuthorFavoriteDao.Properties.Source.eq(source))
                .build().unique();
    }

    @Override
    public long saveAuthorFavorite(AuthorFavorite authorFavorite) {
        return mDaoSession.getAuthorFavoriteDao().insertOrReplace(authorFavorite);
    }

    @Override
    public void deleteAuthorFavorite(AuthorFavorite authorFavorite) {
        mDaoSession.getAuthorFavoriteDao().delete(authorFavorite);
    }

    @Override
    public boolean isAuthorFavorited(String authorKey, String source) {
        return findAuthorFavorite(authorKey, source) != null;
    }
}
