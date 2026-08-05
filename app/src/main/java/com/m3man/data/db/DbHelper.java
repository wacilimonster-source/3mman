package com.m3man.data.db;

import com.m3man.data.db.entity.AuthorFavorite;
import com.m3man.data.db.entity.Category;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.VideoResult;

import java.util.List;

/**
 * @author flymegoc
 * @date 2018/3/4
 */

public interface DbHelper {

    void initCategory(int type, String[] value, String[] name);

    void updateV9MmanItem(V9MmanItem v9MmanItem);

    List<V9MmanItem> loadDownloadingData();

    List<V9MmanItem> loadFinishedData();

    List<V9MmanItem> loadHistoryData(int page, int pageSize);

    long saveV9MmanItem(V9MmanItem v9MmanItem);

    long saveVideoResult(VideoResult videoResult);

    V9MmanItem findV9MmanItemByViewKey(String viewKey);

    V9MmanItem findV9MmanItemByDownloadId(int downloadId);

    List<V9MmanItem> loadV9MmanItems();

    List<V9MmanItem> loadLocalFavoriteItems();

    List<V9MmanItem> findV9MmanItemByDownloadStatus(int status);

    List<Category> loadAllCategoryDataByType(int type);

    List<Category> loadCategoryDataByType(int type);

    void updateCategoryData(List<Category> categoryList);

    Category findCategoryById(Long id);

    // ---- 作者收藏 ----

    List<AuthorFavorite> loadAuthorFavorites();

    AuthorFavorite findAuthorFavorite(String authorKey, String source);

    long saveAuthorFavorite(AuthorFavorite authorFavorite);

    void deleteAuthorFavorite(AuthorFavorite authorFavorite);

    boolean isAuthorFavorited(String authorKey, String source);
}
