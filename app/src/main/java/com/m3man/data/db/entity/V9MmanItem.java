package com.m3man.data.db.entity;

import android.text.TextUtils;

import com.m3man.data.DataManager;
import com.m3man.utils.SDCardUtils;

import org.greenrobot.greendao.DaoException;
import org.greenrobot.greendao.annotation.Entity;
import org.greenrobot.greendao.annotation.Generated;
import org.greenrobot.greendao.annotation.Id;
import org.greenrobot.greendao.annotation.Index;
import org.greenrobot.greendao.annotation.NotNull;
import org.greenrobot.greendao.annotation.Property;
import org.greenrobot.greendao.annotation.ToOne;
import org.greenrobot.greendao.annotation.Transient;

import java.io.Serializable;
import java.util.Date;

/**
 * 视频
 *
 * @author flymegoc
 * @date 2017/11/14
 * @describe
 */
@Entity
public class V9MmanItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id(autoincrement = true)
    public Long id;
    @Index(unique = true)
    private String viewKey;
    private String title;
    private String imgUrl;
    private String duration;
    private String info;
    /** M75：列表页解析出的作者名兜底；@Transient 不落库，详情页回填时以权威值为准 */
    @Transient
    private String authorText;
    @Transient
    private String source;
    /** 持久化的来源标记（如 91porny），本地收藏后重启也能识别来源 */
    private String sourceName;
    /** 是否本地收藏（分分钟视频的本地收藏标记）；用 Boolean 而非 boolean，避免升级迁移时 NOT NULL 无默认值导致整表被清空（D1） */
    private Boolean isLocalFavorite;
    /**
     * Scoped Storage（targetSdk 34）：下载完成后归档进 MediaStore 公共 Movies/3mman 的成品
     * 物理路径（V13，Android 10+ 生效）。null/空 = 未归档或老数据，走原路径解析（后退兼容）。
     * 历史行为对该列 NULL → 用 String 包装类型，避免拆箱 NPE。
     */
    @Property
    private String localFilePath;
    private long videoResultId;
    @ToOne(joinProperty = "videoResultId")
    private VideoResult videoResult;
    private int downloadId;

    private int progress;
    private long speed;
    private long soFarBytes;
    private long totalFarBytes;
    private int status;
    private Date addDownloadDate;
    private Date finishedDownloadDate;
    private Date viewHistoryDate;

    /** Used to resolve relations */
    @Generated(hash = 2040040024)
    private transient DaoSession daoSession;

    /** Used for active entity operations. */
    @Generated(hash = 1318472791)
    private transient V9MmanItemDao myDao;


    @Generated(hash = 324508229)
    public V9MmanItem(Long id, String viewKey, String title, String imgUrl, String duration, String info, String sourceName, Boolean isLocalFavorite, String localFilePath, long videoResultId, int downloadId,
            int progress, long speed, long soFarBytes, long totalFarBytes, int status, Date addDownloadDate, Date finishedDownloadDate, Date viewHistoryDate) {
        this.id = id;
        this.viewKey = viewKey;
        this.title = title;
        this.imgUrl = imgUrl;
        this.duration = duration;
        this.info = info;
        this.sourceName = sourceName;
        this.isLocalFavorite = isLocalFavorite;
        this.localFilePath = localFilePath;
        this.videoResultId = videoResultId;
        this.downloadId = downloadId;
        this.progress = progress;
        this.speed = speed;
        this.soFarBytes = soFarBytes;
        this.totalFarBytes = totalFarBytes;
        this.status = status;
        this.addDownloadDate = addDownloadDate;
        this.finishedDownloadDate = finishedDownloadDate;
        this.viewHistoryDate = viewHistoryDate;
    }

    @Generated(hash = 779058639)
    public V9MmanItem() {
    }

    @Generated(hash = 430646239)
    private transient Long videoResult__resolvedKey;


    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        V9MmanItem that = (V9MmanItem) o;

        //M4：网络解析出来的对象尚未入库，id为null，直接equals会NPE。
        //此时退化为按viewKey判等（viewKey是业务唯一键，且已建立索引）。
        if (id != null && that.id != null) {
            return id.equals(that.id);
        }
        if (viewKey != null) {
            return viewKey.equals(that.viewKey);
        }
        return that.viewKey == null && that.id == null && id == null;
    }

    @Override
    public int hashCode() {
        //M4：优先用业务唯一键viewKey，保证对象入库前后hash稳定，同时避免id为null时NPE
        if (viewKey != null) {
            return viewKey.hashCode();
        }
        return id != null ? id.hashCode() : 0;
    }

    public String getDownLoadPath(String customDownloadVideoDirPath) {
        // 规范化目录：保证尾部有分隔符，避免拼接错位写到用户所选目录之外（C7）
        String dir;
        if (!TextUtils.isEmpty(customDownloadVideoDirPath)) {
            dir = customDownloadVideoDirPath;
        } else {
            dir = SDCardUtils.DOWNLOAD_VIDEO_PATH;
        }
        if (!dir.endsWith("/")) {
            dir = dir + "/";
        }
        // 清洗标题中的非法字符与路径分隔符，防止目录穿越 / 写入异常位置（C8）
        String safeTitle = SDCardUtils.sanitizeFileName(getTitle());
        return dir + safeTitle + "_" + getViewKey() + ".mp4";
    }

    public Date getAddDownloadDate() {
        return addDownloadDate;
    }

    public void setAddDownloadDate(Date addDownloadDate) {
        this.addDownloadDate = addDownloadDate;
    }

    public Date getFinishedDownloadDate() {
        return finishedDownloadDate;
    }

    public void setFinishedDownloadDate(Date finishedDownloadDate) {
        this.finishedDownloadDate = finishedDownloadDate;
    }

    public Date getViewHistoryDate() {
        return viewHistoryDate;
    }

    public void setViewHistoryDate(Date viewHistoryDate) {
        this.viewHistoryDate = viewHistoryDate;
    }

    public String getTitleWithDuration() {
        return getTitle() + "  (" + getDuration() + ")";
    }


    public Long getId() {
        return this.id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getViewKey() {
        return this.viewKey;
    }

    public void setViewKey(String viewKey) {
        this.viewKey = viewKey;
    }

    public String getTitle() {
        return this.title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getImgUrl() {
        return this.imgUrl;
    }

    public void setImgUrl(String imgUrl) {
        this.imgUrl = imgUrl;
    }

    public String getDuration() {
        return this.duration;
    }

    public void setDuration(String duration) {
        this.duration = duration;
    }

    public String getInfo() {
        return this.info;
    }

    public void setInfo(String info) {
        this.info = info;
    }

    /** M75：列表页作者兜底（详情页解析失败时推荐流仍显示 @作者） */
    public String getAuthorText() {
        return this.authorText;
    }

    public void setAuthorText(String authorText) {
        this.authorText = authorText;
    }

    public String getSource() {
        return this.source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSourceName() {
        return this.sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    /**
     * D1：该列升级后对历史行为 NULL，getter/setter 必须用包装类型，
     * 否则 greenDAO 生成的读写代码会在拆箱时抛 NPE。
     *
     * @return 可能为 null
     */
    public Boolean getIsLocalFavorite() {
        return this.isLocalFavorite;
    }

    public void setIsLocalFavorite(Boolean isLocalFavorite) {
        this.isLocalFavorite = isLocalFavorite;
    }

    /**
     * 业务侧安全判断：null 视为未收藏
     *
     * @return 是否本地收藏
     */
    public boolean isLocalFavorite() {
        return Boolean.TRUE.equals(this.isLocalFavorite);
    }

    public int getDownloadId() {
        return this.downloadId;
    }

    public void setDownloadId(int downloadId) {
        this.downloadId = downloadId;
    }

    public String getLocalFilePath() {
        return this.localFilePath;
    }

    public void setLocalFilePath(String localFilePath) {
        this.localFilePath = localFilePath;
    }

    public int getProgress() {
        return this.progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public long getSpeed() {
        return this.speed;
    }

    public void setSpeed(long speed) {
        this.speed = speed;
    }

    public long getSoFarBytes() {
        return this.soFarBytes;
    }

    public void setSoFarBytes(long soFarBytes) {
        this.soFarBytes = soFarBytes;
    }

    public long getTotalFarBytes() {
        return this.totalFarBytes;
    }

    public void setTotalFarBytes(long totalFarBytes) {
        this.totalFarBytes = totalFarBytes;
    }

    public int getStatus() {
        return this.status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    /** To-one relationship, resolved on first access. */
    @Generated(hash = 1066672592)
    public VideoResult getVideoResult() {
        long __key = this.videoResultId;
        if (videoResult__resolvedKey == null || !videoResult__resolvedKey.equals(__key)) {
            final DaoSession daoSession = this.daoSession;
            if (daoSession == null) {
                throw new DaoException("Entity is detached from DAO context");
            }
            VideoResultDao targetDao = daoSession.getVideoResultDao();
            VideoResult videoResultNew = targetDao.load(__key);
            synchronized (this) {
                videoResult = videoResultNew;
                videoResult__resolvedKey = __key;
            }
        }
        return videoResult;
    }

    /** called by internal mechanisms, do not call yourself. */
    @Generated(hash = 1756630021)
    public void setVideoResult(@NotNull VideoResult videoResult) {
        if (videoResult == null) {
            throw new DaoException("To-one property 'videoResultId' has not-null constraint; cannot set to-one to null");
        }
        synchronized (this) {
            this.videoResult = videoResult;
            videoResultId = videoResult.getId();
            videoResult__resolvedKey = videoResultId;
        }
    }

    /**
     * Convenient call for {@link org.greenrobot.greendao.AbstractDao#delete(Object)}.
     * Entity must attached to an entity context.
     */
    @Generated(hash = 128553479)
    public void delete() {
        if (myDao == null) {
            throw new DaoException("Entity is detached from DAO context");
        }
        myDao.delete(this);
    }

    /**
     * Convenient call for {@link org.greenrobot.greendao.AbstractDao#refresh(Object)}.
     * Entity must attached to an entity context.
     */
    @Generated(hash = 1942392019)
    public void refresh() {
        if (myDao == null) {
            throw new DaoException("Entity is detached from DAO context");
        }
        myDao.refresh(this);
    }

    /**
     * Convenient call for {@link org.greenrobot.greendao.AbstractDao#update(Object)}.
     * Entity must attached to an entity context.
     */
    @Generated(hash = 713229351)
    public void update() {
        if (myDao == null) {
            throw new DaoException("Entity is detached from DAO context");
        }
        myDao.update(this);
    }

    public long getVideoResultId() {
        return this.videoResultId;
    }

    public void setVideoResultId(long videoResultId) {
        this.videoResultId = videoResultId;
    }

    @Override
    public String toString() {
        return "V9MmanItem{" +
                "id=" + id +
                ", viewKey='" + viewKey + '\'' +
                ", title='" + title + '\'' +
                ", imgUrl='" + imgUrl + '\'' +
                ", duration='" + duration + '\'' +
                ", info='" + info + '\'' +
                ", videoResultId=" + videoResultId +
                ", videoResult=" + videoResult +
                ", downloadId=" + downloadId +
                ", progress=" + progress +
                ", speed=" + speed +
                ", soFarBytes=" + soFarBytes +
                ", totalFarBytes=" + totalFarBytes +
                ", status=" + status +
                ", addDownloadDate=" + addDownloadDate +
                ", finishedDownloadDate=" + finishedDownloadDate +
                ", viewHistoryDate=" + viewHistoryDate +
                ", daoSession=" + daoSession +
                ", myDao=" + myDao +
                ", videoResult__resolvedKey=" + videoResult__resolvedKey +
                '}';
    }

    /** called by internal mechanisms, do not call yourself. */
    @Generated(hash = 827255066)
    public void __setDaoSession(DaoSession daoSession) {
        this.daoSession = daoSession;
        myDao = daoSession != null ? daoSession.getV9MmanItemDao() : null;
    }
}
