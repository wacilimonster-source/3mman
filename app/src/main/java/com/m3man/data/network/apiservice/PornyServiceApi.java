package com.m3man.data.network.apiservice;

import com.m3man.data.network.Api;

import io.reactivex.Observable;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * 91porny 第二视频源接口（无限制搜索与视频播放）
 *
 * 91porny 为第三方聚合站，搜索不受官方 91porn 账号级冷却限制。
 * 搜索：/search?keywords=<kw>&page=<N>&sort=<...>&time=<...>&views=<...>
 * 主源播放：/video/view/<24位hex>（video 标签 data-src 属性，m3u8 HLS 流）
 * 搜索结果可能包含长视频 /videos/view/<id>/<slug>/ 与精品 /vod/view/<key>；播放仍需支持对应路径。
 * 长视频 data-src 为 MP4 直链，精品 data-src 为 m3u8。
 * 作者：/author/<作者名>?page=<N>
 */
public interface PornyServiceApi {

    @Headers({"Domain-Name: " + Api.PORNY_DOMAIN_NAME})
    @GET("/search")
    Observable<String> search(@Query("keywords") String keywords,
                               @Query("page") int page,
                               @Query("sort") String sort,
                               @Query("time") String time,
                               @Query("views") String views,
                               @Header("Referer") String referer);

    /** 大神作者页（name 为路径段，可能含 URL 编码的中文，encoded=true 原样传递） */
    @Headers({"Domain-Name: " + Api.PORNY_DOMAIN_NAME})
    @GET("/dashen/{name}")
    Observable<String> dashenVideos(@Path(value = "name", encoded = true) String name,
                                    @Query("page") int page,
                                    @Header("Referer") String referer);

    /** 蝌蚪播放页（两段式 path + 尾斜杠；slug 已 URL 编码时原样传递） */
    @Headers({"Domain-Name: " + Api.PORNY_DOMAIN_NAME})
    @GET("/videos/view/{id}/{slug}/")
    Observable<String> getTadpoleVideoPlayPage(@Path("id") String id,
                                               @Path(value = "slug", encoded = true) String slug,
                                               @Header("Referer") String referer);

    /** 精品播放页 */
    @Headers({"Domain-Name: " + Api.PORNY_DOMAIN_NAME})
    @GET("/vod/view/{key}")
    Observable<String> getPremiumVideoPlayPage(@Path("key") String key,
                                               @Header("Referer") String referer);

    /**
     * 获取视频播放页 HTML，用于解析 m3u8 直链。
     */
    @Headers({"Domain-Name: " + Api.PORNY_DOMAIN_NAME})
    @GET("/video/view/{viewKey}")
    Observable<String> getVideoPlayPage(@Path("viewKey") String viewKey,
                                        @Header("Referer") String referer);

    /**
     * 获取作者视频列表页。
     *
     * @param authorId 作者名（如 liguvipa）
     * @param page     页码（从 1 开始）
     * @param referer  Referer
     * @return 作者视频列表页 HTML，结构与搜索页一致（video-elem 条目）
     */
    @Headers({"Domain-Name: " + Api.PORNY_DOMAIN_NAME})
    @GET("/author/{authorId}")
    Observable<String> authorVideos(@Path("authorId") String authorId,
                                    @Query("page") int page,
                                    @Header("Referer") String referer);
}
