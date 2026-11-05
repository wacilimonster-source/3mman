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

    /**
     * T05：仅更新 HLS 下载进度列（PROGRESS/SO_FAR_BYTES），按行 id 定位。
     * 分片进度高频写必须避免整实体 update——防止用启动时的过时实体快照
     * 覆盖下载期间用户改过的字段（收藏/来源等）。
     */
    void updateHlsProgressColumns(long rowId, int progress, long soFarBytes);

    // ==================== T08：游标分页（方案 §5-T08） ====================
    // 说明：既有 offset/全量接口先保留兼容（历史 page/pageSize、收藏/完成全量），
    // 界面迁移到分页后再移除。limit 上限 100（方案约定默认 50 最大 100）。

    /**
     * 历史分页：(VIEW_HISTORY_DATE DESC, _id DESC) 排序，cursor 用最后一行 (date,id)。
     * 首页传 lastDateMs=null。谓词：VIEW_HISTORY_DATE IS NOT NULL AND (date < ? OR (date = ? AND _id < ?))
     */
    Page<V9MmanItem> loadHistoryAfter(Long lastDateMs, Long lastId, int limit);

    /** 本地收藏分页：_id DESC，cursor 用最后一行 id。首页传 lastId=null。 */
    Page<V9MmanItem> loadLocalFavoritesBefore(Long lastId, int limit);

    /** 完成下载分页：(FINISHED_DOWNLOAD_DATE DESC, _id DESC)，status=completed AND downloadId!=0 */
    Page<V9MmanItem> loadFinishedAfter(Long lastDateMs, Long lastId, int limit);

    List<V9MmanItem> loadDownloadingData();

    List<V9MmanItem> loadFinishedData();

    List<V9MmanItem> loadHistoryData(int page, int pageSize);

    long saveV9MmanItem(V9MmanItem v9MmanItem);

    long saveVideoResult(VideoResult videoResult);

    V9MmanItem findV9MmanItemByViewKey(String viewKey);

    V9MmanItem findV9MmanItemByDownloadId(int downloadId);

    List<V9MmanItem> loadV9MmanItems();

    List<V9MmanItem> loadLocalFavoriteItems();

    /** EX-06：本地收藏数（isLocalFavorite = true）。 */
    long countLocalFavorites();

    /** EX-06：观看历史数（viewHistoryDate 非空）。 */
    long countHistoryItems();

    /** EX-06：下载任务数（downloadId 非 0，含进行中/已完成）。 */
    long countDownloadTasks();

    List<V9MmanItem> findV9MmanItemByDownloadStatus(int status);

    List<Category> loadAllCategoryDataByType(int type);

    List<Category> loadCategoryDataByType(int type);

    void updateCategoryData(List<Category> categoryList);

    Category findCategoryById(Long id);

    // ---- 作者收藏 ----

    List<AuthorFavorite> loadAuthorFavorites();

    AuthorFavorite findAuthorFavorite(String authorKey, String source);

    long saveAuthorFavorite(AuthorFavorite authorFavorite);

    /** 更新已有作者收藏（摘要刷新用），按主键更新，不做查重 */
    void updateAuthorFavorite(AuthorFavorite authorFavorite);

    void deleteAuthorFavorite(AuthorFavorite authorFavorite);

    boolean isAuthorFavorited(String authorKey, String source);

    // ---- 自动补全（地址 / 用户名）建议 ----

    List<String> getAutoCompleteNames(int type);

    void saveAutoComplete(String name, int type);

    // ---- 搜索历史（复用 AutoCompleteEntity 表，type = TYPE_SEARCH_HISTORY） ----

    /** 按最近使用时间倒序返回搜索历史（最多 limit 条） */
    List<String> getSearchHistory(int type, int limit);

    /** 清空指定类型的全部搜索历史 */
    void clearSearchHistory(int type);

    /** 删除单条搜索历史 */
    void deleteSearchHistory(String name, int type);
}
