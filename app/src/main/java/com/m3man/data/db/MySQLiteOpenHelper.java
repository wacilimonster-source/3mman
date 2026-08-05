package com.m3man.data.db;

import android.content.Context;

import com.github.yuweiguocn.library.greendao.MigrationHelper;

import com.m3man.data.db.entity.AuthorFavoriteDao;
import com.m3man.data.db.entity.AutoCompleteEntityDao;
import com.m3man.data.db.entity.CategoryDao;
import com.m3man.data.db.entity.DaoMaster;
import com.m3man.data.db.entity.V9MmanItemDao;
import com.m3man.data.db.entity.VideoResultDao;
import com.m3man.di.ApplicationContext;
import com.m3man.di.DatabaseInfo;

import org.greenrobot.greendao.database.Database;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * @author flymegoc
 * @date 2018/1/13
 */
@Singleton
public class MySQLiteOpenHelper extends DaoMaster.OpenHelper {

    @Inject
    public MySQLiteOpenHelper(@ApplicationContext Context context, @DatabaseInfo String name) {
        super(context, name);
    }

    @Override
    public void onUpgrade(Database db, int oldVersion, int newVersion) {
        MigrationHelper.migrate(db, new MigrationHelper.ReCreateAllTableListener() {

            @Override
            public void onCreateAllTables(Database db, boolean ifNotExists) {
                DaoMaster.createAllTables(db, ifNotExists);
            }

            @Override
            public void onDropAllTables(Database db, boolean ifExists) {
                DaoMaster.dropAllTables(db, ifExists);
            }
        }, V9MmanItemDao.class, VideoResultDao.class, CategoryDao.class, AuthorFavoriteDao.class, AutoCompleteEntityDao.class);
    }
}

