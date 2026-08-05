package com.m3man.data.network.apiservice;

import com.m3man.data.network.Api;

import io.reactivex.Observable;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * 91porny 第二视频源接口（无限制搜索）
 *
 * 91porny 为第三方聚合站，搜索不受官方 91porn 账号级冷却限制。
 * 搜索：/search?keywords=<kw>&page=<N>&sort=<...>&time=<...>&views=<...>
 * 播放：/video/view/<24位hex>（video 标签 data-src 属性，m3u8 HLS 流）
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