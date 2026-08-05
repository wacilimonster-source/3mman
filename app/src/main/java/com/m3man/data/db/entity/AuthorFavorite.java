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
    @Generated(hash = 1546271591)
    public AuthorFavorite(Long id, @NotNull String authorKey,
            @NotNull String source, @NotNull String authorName, Date favoriteDate) {
        this.id = id;
        this.authorKey = authorKey;
        this.source = source;
        this.authorName = authorName;
        this.favoriteDate = favoriteDate;
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
}
