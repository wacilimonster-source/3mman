package com.m3man.data.db;

import com.m3man.data.db.entity.AuthorFavorite;
import com.m3man.data.db.entity.Category;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.VideoResult;

import java.util.Date;
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

    // ==================== R-fix：下载状态定向列写 ====================
    // LESSONS §3-1：V9_MMAN_ITEM 的下载进度/状态历史上有多入口整行回写
    // （DownloadManager/HlsDownloadService/Presenter/Fragment），读改写之间会用
    // 陈旧快照互相覆盖 localFilePath/收藏/来源/历史时间。规则：
    // 一切下载进度/状态/绑定/成品路径写只走下列定向方法，终态（completed）只允许
    // 该任务自己的下载通道（FileDownloader 回调 / HlsDownloadService）提交一次。

    /**
     * 定向更新下载状态列（DOWNLOAD_ID/STATUS/PROGRESS/SO_FAR_BYTES/TOTAL_FAR_BYTES/
     * SPEED/FINISHED_DOWNLOAD_DATE）。传 null 的列不更新；finishedDownloadDate 仅在
     * 提交 completed 终态时传（内部按 epoch 毫秒落盘，与 greenDAO Date 转换一致）。
     */
    void updateDownloadStateColumns(long rowId, Integer downloadId, int status, Integer progress,
                                    Long soFarBytes, Long totalFarBytes, Long speed,
                                    Date finishedDownloadDate);

    /** 定向更新成品路径列（LOCAL_FILE_PATH）。path 可为 null（删除下载时复位）。 */
    void updateLocalFilePathColumn(long rowId, String localFilePath);

    /** 定向更新任务绑定列（DOWNLOAD_ID + 可选 ADD_DOWNLOAD_DATE，date 传 null 不更新该列）。 */
    void updateDownloadBindingColumns(long rowId, int downloadId, Date addDownloadDate);

    /** 定向更新解析结果引用列（VIDEO_RESULT_ID）。重新解析成功后写回，不整行覆盖。 */
    void updateVideoResultIdColumn(long rowId, long videoResultId);

    /** 定向更新来源标记列（SOURCE_NAME）。 */
    void updateVideoSourceColumn(long rowId, String sourceName);

    /** 定向更新本地收藏列（IS_LOCAL_FAVORITE）。收藏切换不整行覆盖。 */
    void updateLocalFavoriteColumn(long rowId, boolean favorite);

    /** 定向更新观看历史列（VIEW_HISTORY_DATE）。 */
    void updateViewHistoryColumn(long rowId, Date viewHistoryDate);

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
