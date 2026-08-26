package com.m3man.data.db.entity;

import org.greenrobot.greendao.annotation.Entity;
import org.greenrobot.greendao.annotation.Id;
import org.greenrobot.greendao.annotation.NotNull;

import java.util.Date;
import org.greenrobot.greendao.annotation.Generated;

/**
 * 作者收藏。
 *
 * 注意：本版 GreenDAO 不会把同名 {@code @Index} 合并为联合索引，反而会在建表时
 * 生成两条同名索引导致 SQLite 报“重复索引名”崩溃。因此不再用 DB 层索引，
 * 改由应用层保证 (authorKey, source) 唯一——收藏前已调用 isAuthorFavorited 判断。
 * source 取值见 {@link #SOURCE_MMAN9} / {@link #SOURCE_PORNY}。
 */
@Entity
public class AuthorFavorite {

    public static final String SOURCE_MMAN9 = "mman9";
    public static final String SOURCE_PORNY = "91porny";

    @Id(autoincrement = true)
    private Long id;
    /** 作者标识：91porny 为作者名（如 liguvipa），视频源为作者 uid */
    @NotNull
    private String authorKey;
    /** 来源：SOURCE_MMAN9 / SOURCE_PORNY（与 authorKey 组成联合唯一约束） */
    @NotNull
    private String source;
    @NotNull
    private String authorName;
    private Date favoriteDate;

    // ==================== v11：作者摘要缓存（全部可空，未刷新过为 null） ====================

    /** 作品总数（刷新时由 totalPage 与末页条数精确推算；失败时用首页估算） */
    private Integer videoCount;
    /** 最新一部作品的标识（viewKey），用于变更检测发现新作品 */
    private String topViewKey;
    /** 上次成功刷新摘要的时刻（6 小时节流依据） */
    private Date lastRefreshTime;
    /** 最近一次检测到「有新作品」的时刻（≈更新时间，列表页无发布日期只能变更检测） */
    private Date lastNewTime;
    /** 是否有未读新作品（进作者页后清除） */
    private Boolean hasNew;
    /** 作者最新作品的封面图 URL（条目缩略图） */
    private String coverUrl;
    /** M92g：该作者任一作品的 viewKey——加密 UID 过期时，作者页用它重拉详情换取新 ownerId */
    private String lastViewKey;

    @Generated(hash = 120087474)
    public AuthorFavorite(Long id, @NotNull String authorKey,
            @NotNull String source, @NotNull String authorName, Date favoriteDate,
            Integer videoCount, String topViewKey, Date lastRefreshTime,
            Date lastNewTime, Boolean hasNew, String coverUrl, String lastViewKey) {
        this.id = id;
        this.authorKey = authorKey;
        this.source = source;
        this.authorName = authorName;
        this.favoriteDate = favoriteDate;
        this.videoCount = videoCount;
        this.topViewKey = topViewKey;
        this.lastRefreshTime = lastRefreshTime;
        this.lastNewTime = lastNewTime;
        this.hasNew = hasNew;
        this.coverUrl = coverUrl;
        this.lastViewKey = lastViewKey;
    }
    @Generated(hash = 1432017897)
    public AuthorFavorite() {
    }
    public Long getId() {
        return this.id;
    }
    public void setId(Long id) {
        this.id = id;
    }
    public String getAuthorKey() {
        return this.authorKey;
    }
    public void setAuthorKey(String authorKey) {
        this.authorKey = authorKey;
    }
    public String getSource() {
        return this.source;
    }
    public void setSource(String source) {
        this.source = source;
    }
    public String getAuthorName() {
        return this.authorName;
    }
    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }
    public Date getFavoriteDate() {
        return this.favoriteDate;
    }
    public void setFavoriteDate(Date favoriteDate) {
        this.favoriteDate = favoriteDate;
    }
    public Integer getVideoCount() {
        return this.videoCount;
    }
    public void setVideoCount(Integer videoCount) {
        this.videoCount = videoCount;
    }
    public String getTopViewKey() {
        return this.topViewKey;
    }
    public void setTopViewKey(String topViewKey) {
        this.topViewKey = topViewKey;
    }
    public Date getLastRefreshTime() {
        return this.lastRefreshTime;
    }
    public void setLastRefreshTime(Date lastRefreshTime) {
        this.lastRefreshTime = lastRefreshTime;
    }
    public Date getLastNewTime() {
        return this.lastNewTime;
    }
    public void setLastNewTime(Date lastNewTime) {
        this.lastNewTime = lastNewTime;
    }
    public Boolean getHasNew() {
        return this.hasNew;
    }
    public void setHasNew(Boolean hasNew) {
        this.hasNew = hasNew;
    }
    public String getCoverUrl() {
        return this.coverUrl;
    }
    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }
    public String getLastViewKey() {
        return this.lastViewKey;
    }
    public void setLastViewKey(String lastViewKey) {
        this.lastViewKey = lastViewKey;
    }
}
