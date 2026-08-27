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
import android.content.Context;
import android.text.TextUtils;
import java.util.Date;

import com.m3man.di.ApplicationContext;
import com.m3man.utils.AppLog;
import org.greenrobot.greendao.AbstractDao;
import org.greenrobot.greendao.database.Database;
import org.greenrobot.greendao.query.QueryBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
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

    /** M66b：持久化来源标记取值，与 PlayVideoPresenter.SOURCE_MMAN9_PERSIST 一致 */
    private static final String SOURCE_MMAN9 = "mman9";

    /** M99：porny 误标修复的一次性 SP 标记（PreferencesHelper 无通用 SP 能力，直读 SharedPreferences） */
    private static final String REPAIR_PORNY_SP_NAME = "repair_porny_v1";
    private static final String REPAIR_PORNY_DONE_KEY = "repair_porny_v1_done";

    @Inject
    AppDbHelper(MySQLiteOpenHelper helper, @ApplicationContext Context context) {
        this.mAppContext = context;
        //如果你想查看日志信息，请将DEBUG设置为true
        MigrationHelper.DEBUG = BuildConfig.DEBUG;
        Database db = helper.getWritableDb();
        this.mDaoSession = new DaoMaster(db).newSession();
        // 复查修正（C-02）：原来通过每次读前 detachAll() 规避脏读，导致缓存形同虚设。
        // 现设计：全工程仅此一个 DaoSession（单写入方），greenDAO 的 IdentityScope 内部已
        // 做同步保护，跨线程读写同一 Session 不会产生撕裂数据；若未来引入第二个
        // 写入口/裸连接，必须自行调用 mDaoSession.clear() 刷新缓存。此处仅清空一次。
        // 注：原修复曾试图 dao.setIdentityScope(null) 禁用缓存，但 greenDAO 3.2.2 的
        // AbstractDao 并未公开该方法（字段为 protected final），已改为上述单会话约束。
        this.mDaoSession.clear();
        // M73：启动时的写库与全表扫描修复移到后台线程执行——
        // 构造发生在主线程（Dagger 初始化），数据量大时拖慢冷启动
        java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    initCategory(Category.TYPE_91PORN, Category.CATEGORY_DEFAULT_91PORN_VALUE, Category.CATEGORY_DEFAULT_91PORN_NAME);
                    repairMisflaggedPornyRows();
                } catch (Exception e) {
                    // M99：种子失败不再静默吞掉，至少留一条错误日志便于排查
                    AppLog.e("AppDbHelper", "initCategory/repairMisflaggedPornyRows failed: " + AppLog.cause(e));
                } finally {
                    io.shutdown();
                }
            }
        });
    }

    /** M99：应用上下文，供 repairMisflaggedPornyRows 读写一次性标记 */
    private final Context mAppContext;

    /**
     * M66b 一次性数据修复：旧版 isPornySource 的 hex 正则误判把大量 9mman 视频标成了
     * "91porny"，导致播放走错源、作者页 404、详情数据错乱。
     * 可安全校正的子集：viewKey 带 "viewkey=" 前缀（9mman extractViewKey 契约，
     * porny 条目恒为裸 hex）但 sourceName 被标成 91porny 的行 → 改回 mman9。
     */
    private void repairMisflaggedPornyRows() {
        // M99：一次性修复标记——已执行过直接返回，避免每次启动都做全表扫描。
        // PreferencesHelper 无通用 SP 能力，这里直读 SharedPreferences。
        try {
            if (mAppContext.getSharedPreferences(REPAIR_PORNY_SP_NAME, Context.MODE_PRIVATE)
                    .getBoolean(REPAIR_PORNY_DONE_KEY, false)) {
                return;
            }
        } catch (Exception e) {
            // SP 不可用时仍执行一次修复，失败不影响启动
        }
        try {
            List<V9MmanItem> misflagged = mDaoSession.getV9MmanItemDao().queryBuilder()
                    .where(V9MmanItemDao.Properties.SourceName.eq("91porny"))
                    .build().list();
            // M99：只把实际修改的行收集起来提交，避免把未变更行整批 updateInTx
            List<V9MmanItem> modified = new ArrayList<>();
            for (V9MmanItem item : misflagged) {
                String key = item.getViewKey();
                if (key != null && key.startsWith("viewkey=")) {
                    item.setSourceName(SOURCE_MMAN9);
                    modified.add(item);
                }
            }
            if (!modified.isEmpty()) {
                mDaoSession.getV9MmanItemDao().updateInTx(modified);
            }
            // 全部成功后才落"已执行"标记；中途异常则下次启动重试
            mAppContext.getSharedPreferences(REPAIR_PORNY_SP_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(REPAIR_PORNY_DONE_KEY, true).apply();
        } catch (Exception e) {
            // 修复失败不影响启动，且不落标记，下次启动重试
            AppLog.w("AppDbHelper", "repairMisflaggedPornyRows failed: " + AppLog.cause(e));
        }
    }

    @Override
    public void initCategory(int type, String[] value, String[] name) {
        if (name == null || name.length == 0) {
            return;
        }
        // M99：name/value 必须等长，否则下方按索引配对会越界/错位插入脏数据
        if (value != null && value.length != name.length) {
            AppLog.w("AppDbHelper", "initCategory 配置非法：name/value 长度不一致 name="
                    + name.length + " value=" + value.length + " type=" + type);
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
        V9MmanItemDao dao = mDaoSession.getV9MmanItemDao();
        return dao.queryBuilder().where(V9MmanItemDao.Properties.Status.notEq(FileDownloadStatus.completed), V9MmanItemDao.Properties.DownloadId.notEq(0)).orderDesc(V9MmanItemDao.Properties.AddDownloadDate).build().list();

    }

    @Override
    public List<V9MmanItem> loadFinishedData() {
        V9MmanItemDao dao = mDaoSession.getV9MmanItemDao();
        return dao.queryBuilder().where(V9MmanItemDao.Properties.Status.eq(FileDownloadStatus.completed), V9MmanItemDao.Properties.DownloadId.notEq(0)).orderDesc(V9MmanItemDao.Properties.FinishedDownloadDate).build().list();
    }

    @Override
    public List<V9MmanItem> loadHistoryData(int page, int pageSize) {
        V9MmanItemDao dao = mDaoSession.getV9MmanItemDao();
        return dao.queryBuilder().where(V9MmanItemDao.Properties.ViewHistoryDate.isNotNull()).orderDesc(V9MmanItemDao.Properties.ViewHistoryDate).offset((page - 1) * pageSize).limit(pageSize).build().list();
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
     * 合并下载状态：把旧记录中"仅本地产生/仅本地有效"的字段继承到新记录，防止网络数据覆盖丢失。
     * <p>
     * 优先级规则（新 > 旧，但保留旧值的场景）：
     * <ol>
     *   <li>下载核心字段：仅当 target.getDownloadId() == 0 时，从 old 继承 downloadId/status/progress/soFarBytes/totalFarBytes。
     *       保护正在进行/已完成的下载任务不被重置。</li>
     *   <li>时间戳字段：target 为 null 时从 old 继承（addDownloadDate/finishedDownloadDate/viewHistoryDate）。
     *       保留首次加入下载时间、完成时间、观看历史时间。</li>
     *   <li>本地状态字段：target 为 null 时从 old 继承（isLocalFavorite/sourceName/videoResultId）。
     *       保留本地收藏标记、来源标记、视频结果关联 ID。</li>
     * </ol>
     * 设计原则：网络数据（标题/封面/时长/视频地址）永远以新为准；本地产生的进度/状态/收藏/时间只增不减、以旧为准。
     *
     * @param old    数据库中已有记录（旧值来源）
     * @param target 待写入记录（新值来源，作为输出参数被修改）
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
                // M99：unique() 在历史脏数据（同 videoId 多行）下会抛 DaoException，
                // 改用 list() 取第一条，保留 insertOrReplace 主键复用语义
                List<VideoResult> existList = mDaoSession.getVideoResultDao().queryBuilder()
                        .where(VideoResultDao.Properties.VideoId.eq(videoResult.getVideoId()))
                        .build().list();
                VideoResult existing = (existList != null && !existList.isEmpty()) ? existList.get(0) : null;
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
        if (TextUtils.isEmpty(viewKey)) {
            return null;
        }
        V9MmanItemDao v9MmanItemDao = mDaoSession.getV9MmanItemDao();
        // M66b/M-09：历史数据存在「同一视频存成两行」的分裂（裸 key / 带前缀 key）。
        // M-09：合并为单次查询，使用 OR 条件一次性匹配三种形态，减少 3 次 DB 往返。
        // 复查修正：三形态必须用 queryBuilder.or() 组合，直接塞进同一 where() 会被 AND 连接，
        // 导致恒空结果（上一版把唯一命中路径打成了“永远查不到”的功能回归，见 M-09 复查）。
        String bare = viewKey.startsWith("viewkey=") ? viewKey.substring(8) : viewKey;
        String prefixed = "viewkey=" + bare;
        try {
            QueryBuilder<V9MmanItem> qb = v9MmanItemDao.queryBuilder();
            qb.where(qb.or(V9MmanItemDao.Properties.ViewKey.eq(viewKey),
                           V9MmanItemDao.Properties.ViewKey.eq(bare),
                           V9MmanItemDao.Properties.ViewKey.eq(prefixed)));
            return qb.build().uniqueOrThrow();
        } catch (Exception e) {
            // 兜底：用 list 取第一条，绝不再删除用户数据
            QueryBuilder<V9MmanItem> qb2 = v9MmanItemDao.queryBuilder();
            qb2.where(qb2.or(V9MmanItemDao.Properties.ViewKey.eq(viewKey),
                             V9MmanItemDao.Properties.ViewKey.eq(bare),
                             V9MmanItemDao.Properties.ViewKey.eq(prefixed)));
            List<V9MmanItem> tmp = qb2.build().list();
            return (tmp != null && !tmp.isEmpty()) ? tmp.get(0) : null;
        }
    }

    @Override
    public V9MmanItem findV9MmanItemByDownloadId(int downloadId) {
        V9MmanItemDao dao = mDaoSession.getV9MmanItemDao();
        try {
            return dao.queryBuilder().where(V9MmanItemDao.Properties.DownloadId.eq(downloadId)).build().unique();
        } catch (Exception e) {
            // 暂时先不处理这问题了，理论上一个不会发生，因为时根据url生成
            // M99：修正写反的日志方向——仅 DEBUG 打印堆栈，RELEASE 静默；清理空 if 块
            if (BuildConfig.DEBUG) {
                e.printStackTrace();
            }
        }
        return null;
    }

    @Override
    public List<V9MmanItem> loadV9MmanItems() {
        V9MmanItemDao dao = mDaoSession.getV9MmanItemDao();
        return dao.loadAll();
    }

    @Override
    public List<V9MmanItem> loadLocalFavoriteItems() {
        V9MmanItemDao dao = mDaoSession.getV9MmanItemDao();
        return dao.queryBuilder()
                .where(V9MmanItemDao.Properties.IsLocalFavorite.eq(true))
                .orderDesc(V9MmanItemDao.Properties.Id)
                .build().list();
    }

    @Override
    public List<V9MmanItem> findV9MmanItemByDownloadStatus(int status) {
        V9MmanItemDao dao = mDaoSession.getV9MmanItemDao();
        return dao.queryBuilder().where(V9MmanItemDao.Properties.Status.eq(status)).build().list();
    }

    @Override
    public List<Category> loadAllCategoryDataByType(int type) {
        CategoryDao categoryDao = mDaoSession.getCategoryDao();
        return categoryDao.queryBuilder().where(CategoryDao.Properties.CategoryType.eq(type)).orderAsc(CategoryDao.Properties.SortId).build().list();
    }

    @Override
    public List<Category> loadCategoryDataByType(int type) {
        CategoryDao categoryDao = mDaoSession.getCategoryDao();
        return categoryDao.queryBuilder().where(CategoryDao.Properties.CategoryType.eq(type), CategoryDao.Properties.IsShow.eq(true)).orderAsc(CategoryDao.Properties.SortId).build().list();
    }

    @Override
    public void updateCategoryData(List<Category> categoryList) {
        mDaoSession.getCategoryDao().updateInTx(categoryList);
    }

    @Override
    public Category findCategoryById(Long id) {
        CategoryDao categoryDao = mDaoSession.getCategoryDao();
        return categoryDao.load(id);
    }

    @Override
    public List<AuthorFavorite> loadAuthorFavorites() {
        AuthorFavoriteDao dao = mDaoSession.getAuthorFavoriteDao();
        return dao.queryBuilder()
                .orderDesc(AuthorFavoriteDao.Properties.FavoriteDate)
                .build().list();
    }

    @Override
    public AuthorFavorite findAuthorFavorite(String authorKey, String source) {
        // D5：用 list().get(0) 而非 unique()，即便历史上已存在重复行也不会抛 DaoException 导致功能永久损坏
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
    public void updateAuthorFavorite(AuthorFavorite authorFavorite) {
        if (authorFavorite == null || authorFavorite.getId() == null) {
            return;
        }
        mDaoSession.getAuthorFavoriteDao().update(authorFavorite);
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
        Set<String> nameSet = new LinkedHashSet<>();
        try {
            List<AutoCompleteEntity> list = mDaoSession.getAutoCompleteEntityDao().queryBuilder()
                    .where(AutoCompleteEntityDao.Properties.Type.eq(type))
                    .orderDesc(AutoCompleteEntityDao.Properties.UseTime)
                    .list();
            for (AutoCompleteEntity e : list) {
                if (!TextUtils.isEmpty(e.getName())) {
                    nameSet.add(e.getName());
                }
            }
        } catch (Exception e) {
            // 查询异常不应影响 UI
        }
        return new ArrayList<>(nameSet);
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
            Set<String> nameSet = new LinkedHashSet<>();
            for (AutoCompleteEntity e : list) {
                if (!TextUtils.isEmpty(e.getName())) {
                    nameSet.add(e.getName());
                }
            }
            names.addAll(nameSet);
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
