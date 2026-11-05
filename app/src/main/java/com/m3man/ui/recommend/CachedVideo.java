package com.m3man.ui.recommend;

import android.text.TextUtils;

import com.google.gson.annotations.SerializedName;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.reco.RecoCandidate;

import java.util.ArrayList;
import java.util.List;

/**
 * T01：轻量不可变缓存 DTO（方案 §5-T01）。
 *
 * <p>只携带渲染推荐卡片所需的标量字段；不持有 DaoSession、greenDAO 关系图或签名直链。
 * 主线程把活实体复制成本 DTO（标量拷贝），JSON 编解码全部发生在缓存执行器。
 *
 * @author 3mman
 */
public final class CachedVideo {

    @SerializedName("src")
    public final String source;
    @SerializedName("vk")
    public final String viewKey;
    @SerializedName("t")
    public final String title;
    @SerializedName("img")
    public final String imgUrl;
    @SerializedName("dur")
    public final String duration;
    @SerializedName("info")
    public final String info;
    /** 封面方向：RecoCandidate.ORIENT_* */
    @SerializedName("o")
    public final int orientation;
    @SerializedName("ak")
    public final String authorKey;
    @SerializedName("an")
    public final String authorName;
    /** 真实发布年份（解析不到 -1），画像新鲜度用 */
    @SerializedName("py")
    public final int publishYear;
    @SerializedName("tags")
    public final List<String> tags;

    public CachedVideo(String source, String viewKey, String title, String imgUrl, String duration,
                       String info, int orientation, String authorKey, String authorName,
                       int publishYear, List<String> tags) {
        this.source = source;
        this.viewKey = viewKey;
        this.title = title;
        this.imgUrl = imgUrl;
        this.duration = duration;
        this.info = info;
        this.orientation = orientation;
        this.authorKey = authorKey;
        this.authorName = authorName;
        this.publishYear = publishYear;
        this.tags = tags == null ? null : new ArrayList<>(tags);
    }

    /** 从活实体+候选提取标量（主线程调用，纯内存拷贝；不保存实体引用）。 */
    public static CachedVideo fromCandidate(RecoCandidate c) {
        if (c == null || c.item == null || TextUtils.isEmpty(c.item.getViewKey())) {
            return null;
        }
        V9MmanItem it = c.item;
        return new CachedVideo(it.getSourceName(), it.getViewKey(), it.getTitle(),
                it.getImgUrl(), it.getDuration(), it.getInfo(), c.orientation,
                c.authorKey, c.authorName, c.publishYear, c.tags);
    }

    /**
     * 还原为渲染候选。构造全新 detached V9MmanItem（id=null，无 DaoSession），
     * 不会触碰 greenDAO 身份缓存；可在任意线程调用。
     */
    public RecoCandidate toCandidate() {
        if (TextUtils.isEmpty(viewKey)) {
            return null;
        }
        V9MmanItem it = new V9MmanItem();
        it.setViewKey(viewKey);
        it.setTitle(title);
        it.setImgUrl(imgUrl);
        it.setDuration(duration);
        it.setInfo(info);
        it.setSourceName(source);
        it.setIsLocalFavorite(false);
        RecoCandidate c = new RecoCandidate(it, null, RecoCandidate.FROM_EXPLORE);
        c.orientation = orientation;
        c.authorKey = authorKey;
        c.authorName = authorName;
        c.publishYear = publishYear;
        if (tags != null && !tags.isEmpty()) {
            c.tags = new ArrayList<>(tags);
        }
        return c;
    }
}
