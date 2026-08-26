package com.m3man.parser;

import android.text.TextUtils;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.VideoResult;
import com.m3man.data.model.BaseResult;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 91porny 第二视频源解析（首版仅搜索）
 *
 * 搜索结果结构（已真实验证）：
 *   <div class="video-elem">
 *     <a class="display d-block" href="/video/view/<24位hex>"> ... <div class="img" style="background-image:url(...)"> <small class="layer">时长</small> </a>
 *     <a class="title ..." href="/video/view/<24位hex>">标题</a>
 *   </div>
 * 分页：Bootstrap <a class="page-link" href="...&page=N">
 *
 * 顶部广告条目会在 .video-elem 内携带 ad- 相关类或外链 href，这里在解析时过滤掉。
 */
public class Parse91PornyVideo {

    private static final String TAG = Parse91PornyVideo.class.getSimpleName();
    public static final String SOURCE = "91porny";
    private static final Pattern PAGE_NUM = Pattern.compile("page=(\\d+)");
    private static final Pattern BG_URL = Pattern.compile("url\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)");
    /** 匹配 mp4 / m3u8 等直链 */
    private static final Pattern MP4_URL = Pattern.compile("https?://[^\\s\"'<>]+\\.(?:mp4|m3u8)(?:\\?[^\\s\"'<>]*)?", Pattern.CASE_INSENSITIVE);
    /** 兼容以 // 或 / 开头的相对协议链接 */
    private static final Pattern MP4_URL_LOOSE = Pattern.compile("(?:https?:)?//[^\\s\"'<>]+\\.(?:mp4|m3u8)(?:\\?[^\\s\"'<>]*)?", Pattern.CASE_INSENSITIVE);
    /** 匹配 JS 变量中的视频地址：file/videoUrl/mp4_url/url/data-src 后跟 mp4/m3u8 */
    private static final Pattern JS_VIDEO_URL = Pattern.compile("(?:file|videoUrl|video_url|mp4_url|mp4Url|data-src|src)\\s*[:=]\\s*['\"]([^'\"]+\\.(?:mp4|m3u8)(?:\\?[^'\"]*)?)['\"]", Pattern.CASE_INSENSITIVE);
    /** 匹配以单个斜杠开头的相对视频路径 */
    private static final Pattern RELATIVE_MP4 = Pattern.compile("/[^\\s\"'<>]+\\.(?:mp4|m3u8)(?:\\?[^\\s\"'<>]*)?", Pattern.CASE_INSENSITIVE);
    /** M95：91porny 站内基准地址（与 AppPreferencesHelper 默认源一致），供外链广告判定的 host 比较基准 */
    private static final String SITE_BASE_URL = "https://91porny.com/";

    public static BaseResult<List<V9MmanItem>> parseSearchVideos(String html) {
        List<V9MmanItem> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        Elements items = doc.getElementsByClass("video-elem");
        // 没有结果也可能返回空列表（末页）；只有完全拿不到页面才当作错误
        if (items == null || items.isEmpty()) {
            BaseResult<List<V9MmanItem>> empty = new BaseResult<>();
            empty.setTotalPage(1);
            empty.setData(list);
            return empty;
        }

        for (Element item : items) {
            // 过滤顶部广告条：命中以下任一特征视为广告
            if (isAdItem(item)) {
                continue;
            }

            V9MmanItem v = new V9MmanItem();

            Element link = item.selectFirst("a.display");
            String href = link != null ? link.attr("href") : "";
            // 标题链接也兜底取一次
            if (TextUtils.isEmpty(href)) {
                Element titleA = item.selectFirst("a.title");
                if (titleA != null) {
                    href = titleA.attr("href");
                }
            }

            // 广告过滤兜底：href 是外链、指向 /ads/ 等推广位、或根本不是 /video/view/ 内部链接，都视为广告跳过
            if (isExternalAdHref(href) || !isInternalVideoHref(href)) {
                continue;
            }

            v.setViewKey(extractViewKey(href));

            Element imgDiv = item.selectFirst("div.img");
            v.setImgUrl(extractBackgroundUrl(imgDiv != null ? imgDiv.attr("style") : ""));

            Element titleA = item.selectFirst("a.title");
            v.setTitle(titleA != null ? titleA.text() : "");

            Element dur = item.selectFirst("small.layer");
            v.setDuration(dur != null ? dur.text().trim() : "");

            // 作者信息：作者: <a class="text-dark" href="/author/xxx">xxx</a>
            String authorInfo = "";
            try {
                Element authorA = item.selectFirst("a[href^=/author/]");
                if (authorA != null) {
                    String authorName = authorA.text().trim();
                    if (!TextUtils.isEmpty(authorName)) {
                        authorInfo = "作者: " + authorName;
                    }
                }
            } catch (Exception ignored) {
            }
            v.setInfo(authorInfo);
            v.setSource(SOURCE);
            // M66b：解析时打持久化来源标记，供路由权威判定（source 是 transient 不落库）
            v.setSourceName(SOURCE);
            list.add(v);
        }

        int totalPage = computeTotalPage(doc);
        BaseResult<List<V9MmanItem>> baseResult = new BaseResult<>();
        baseResult.setTotalPage(totalPage);
        baseResult.setData(list);
        return baseResult;
    }

    /**
     * 解析 91porny 视频播放页，返回包含视频直链的 VideoResult。
     *
     * 91porny 播放页真实结构（2026-08-03 抓包验证）：
     *   <video id="video-play" ... data-src="https://cdn2.xxx/hls/1018639/index.m3u8?t=...&amp;m=...">
     *     <track ...>
     *   </video>
     * 视频地址在 video 标签的 data-src 属性中，为 m3u8 HLS 流（需 ExoPlayer 播放，JiaoZi/MediaPlayer 不支持 HLS）。
     * HTML 中 &amp; 是转义实体，必须还原为 & 才能播放。
     * 同时解析页面中的作者信息（/author/xxx），供"作者"页使用。
     *
     * @param html    播放页 HTML
     * @param baseUrl 91porny 站点地址，用于补全相对路径
     */
    public static VideoResult parseVideoPlayUrl(String html, String baseUrl) {
        VideoResult videoResult = new VideoResult();
        if (TextUtils.isEmpty(html)) {
            return videoResult;
        }

        Document doc = Jsoup.parse(html);
        String videoUrl = null;

        // 1) 优先 <video data-src="...">（91porny 真实结构）
        try {
            Element video = doc.select("video").first();
            if (video != null) {
                videoUrl = video.attr("data-src");
                if (TextUtils.isEmpty(videoUrl)) {
                    videoUrl = video.attr("src");
                }
                // <source> 子标签兜底
                if (TextUtils.isEmpty(videoUrl)) {
                    Element source = video.selectFirst("source");
                    if (source != null) {
                        videoUrl = source.attr("src");
                    }
                }
            }
        } catch (Exception e) {
            // 忽略，按下一策略继续
        }

        // 2) 兜底：整页匹配 mp4 / m3u8 / JS 变量
        if (TextUtils.isEmpty(videoUrl)) {
            videoUrl = findVideoUrlInHtml(html);
        }

        // 3) HTML 实体还原（&amp; -> &）与相对路径补全
        if (!TextUtils.isEmpty(videoUrl)) {
            videoUrl = videoUrl.replace("&amp;", "&");
        }
        videoUrl = resolveUrl(videoUrl, baseUrl);

        videoResult.setVideoUrl(videoUrl);
        // 91porny 元数据：作者链接与名称（/author/xxx）
        String[] author = findAuthorInPlayPage(doc);
        // M73：以 m3u8 路径中的视频 id 作为 videoId 去重键——此前恒存空串，
        // 空 videoId 绕过 saveVideoResult 的去重逻辑，VIDEO_RESULT 表对 porny 源无限膨胀。
        // data-src 形如 https://cdn2.xxx/hls/1018639/index.m3u8?t=...，取 /hls/<id>/ 段。
        String pornyVideoId = extractPornyVideoId(videoUrl);
        if (TextUtils.isEmpty(pornyVideoId)) {
            pornyVideoId = author[0]; // 兜底：作者页路径里的标识
        }
        videoResult.setVideoId(pornyVideoId == null ? "" : "porny_" + pornyVideoId);
        videoResult.setOwnerId(author[0]);
        videoResult.setAuthorId(author[0]);
        videoResult.setVideoName("");
        videoResult.setOwnerName(author[1]);
        videoResult.setAddDate("");
        videoResult.setUserOtherInfo("");
        return videoResult;
    }

    /**
     * 从播放页提取作者信息：返回 [作者标识, 作者显示名]
     * 结构：<a href="/author/liguvipa">liguvipa</a>
     * M73：从 m3u8 直链提取 porny 视频 id（/hls/<数字>/ 段），作为 DB 去重键。
     */
    private static String extractPornyVideoId(String videoUrl) {
        if (TextUtils.isEmpty(videoUrl)) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/hls/([A-Za-z0-9_-]+)/").matcher(videoUrl);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private static String[] findAuthorInPlayPage(Document doc) {
        String[] result = new String[]{"", ""};
        try {
            Element a = doc.select("a[href^=/author/]").first();
            if (a != null) {
                String href = a.attr("href");
                int idx = href.indexOf("/author/");
                if (idx >= 0) {
                    String authorId = href.substring(idx + "/author/".length());
                    result[0] = authorId;
                    result[1] = a.text().trim();
                }
            }
        } catch (Exception e) {
            // 忽略
        }
        return result;
    }

    private static String findVideoUrlInHtml(String html) {
        String url = null;
        // 3.1) 整页直接匹配 http(s) mp4/m3u8
        Matcher m = MP4_URL.matcher(html);
        if (m.find()) {
            url = m.group();
        }
        // 3.2) 匹配 // 开头的协议相对链接
        if (TextUtils.isEmpty(url)) {
            Matcher m2 = MP4_URL_LOOSE.matcher(html);
            if (m2.find()) {
                url = m2.group();
            }
        }
        // 3.3) 匹配 JS 变量：file: 'xxx.mp4' / videoUrl = "xxx" / mp4_url:'xxx' / url:"xxx"
        if (TextUtils.isEmpty(url)) {
            Matcher js = JS_VIDEO_URL.matcher(html);
            if (js.find()) {
                url = js.group(1);
            }
        }
        // 3.4) 匹配单个斜杠开头的相对路径 /uploads/xxx.mp4
        // M95：该兜底仅当路径包含 "/hls/" 或 "/mp4/" 才采纳——RELATIVE_MP4 会把页面里
        // 任意相对 .mp4/.m3u8 字符串（广告脚本、统计资源、缩略图等）误判为视频直链。
        // 其余兜底顺序（整页 http(s) → 协议相对 → JS 变量）不变。
        if (TextUtils.isEmpty(url)) {
            Matcher rel = RELATIVE_MP4.matcher(html);
            if (rel.find()) {
                String candidate = rel.group();
                String lowerCandidate = candidate.toLowerCase();
                if (lowerCandidate.contains("/hls/") || lowerCandidate.contains("/mp4/")) {
                    url = candidate;
                }
            }
        }
        return url;
    }

    /**
     * 把相对路径视频链接补全为绝对地址
     */
    private static String resolveUrl(String url, String baseUrl) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(baseUrl)) {
            return url;
        }
        String trimmed = url.trim();
        try {
            if (trimmed.startsWith("//")) {
                return new URI("https:" + trimmed).toString();
            }
            // URI.resolve 同时正确处理 /、./、../ 和站点 base path。
            URI base = new URI(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
            return base.resolve(trimmed).toString();
        } catch (Exception ignored) {
            // 解析异常时保留原值，由上层记录真实失败原因。
            return trimmed;
        }
    }

    private static boolean isAdItem(Element item) {
        if (item == null) {
            return false;
        }
        String ownClass = item.className();
        if (!TextUtils.isEmpty(ownClass) && (ownClass.contains("ad-") || ownClass.contains("advert") || ownClass.contains("banner"))) {
            return true;
        }
        // 命中常见的广告容器
        Elements adContainers = item.select("[class*=advert], [class*=banner], [class*=ad-]");
        if (adContainers != null && !adContainers.isEmpty()) {
            return true;
        }
        return false;
    }

    /**
     * M95：广告链接判定改为 host 比较（旧实现把所有 http(s) 绝对链接一律判广告，
     * 会误杀指向站内自身的绝对形式链接）。规则：
     * 1) 相对链接（无 http(s) 前缀）视为站内，不是广告；
     * 2) 绝对链接用 java.net.URI 解析出非空 host，且与基准 baseUrl（SITE_BASE_URL）host
     *    不同才判定为广告；
     * 3) 保留既有推广位规则：ads. / /ads/ / affiliate 特征仍然命中。
     * URI 解析失败时保守回退旧规则（绝对链接视为广告），不抛异常。
     */
    private static boolean isExternalAdHref(String href) {
        if (TextUtils.isEmpty(href)) {
            return false;
        }
        String lower = href.toLowerCase();
        // 保留既有 /ads/ 等推广特征规则
        if (lower.contains("ads.") || lower.contains("/ads/") || lower.contains("affiliate")) {
            return true;
        }
        // 相对链接视为内部链接
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        try {
            URI uri = new URI(href);
            String host = uri.getHost();
            if (TextUtils.isEmpty(host)) {
                // 解析不出 host 的绝对链接按旧规则保守视为广告
                return true;
            }
            String baseHost = new URI(SITE_BASE_URL).getHost();
            return baseHost != null && !host.equalsIgnoreCase(baseHost);
        } catch (Exception e) {
            // URI 解析异常时保守按旧规则处理：绝对 http(s) 链接视为广告
            return true;
        }
    }

    /**
     * 只接受站内 /video/view/ 开头的链接，其余（包括空 href、纯锚点、推广链接等）一律视为非视频条目
     */
    private static boolean isInternalVideoHref(String href) {
        return !TextUtils.isEmpty(href) && href.contains("/video/view/");
    }

    private static String extractViewKey(String href) {
        if (TextUtils.isEmpty(href)) {
            return "";
        }
        int idx = href.indexOf("/video/view/");
        if (idx >= 0) {
            String key = href.substring(idx + "/video/view/".length());
            // 去掉末尾可能的查询参数
            int q = key.indexOf('?');
            return q > 0 ? key.substring(0, q) : key;
        }
        return href;
    }

    private static String extractBackgroundUrl(String style) {
        if (TextUtils.isEmpty(style)) {
            return "";
        }
        Matcher m = BG_URL.matcher(style);
        return m.find() ? m.group(1) : "";
    }

    private static int computeTotalPage(Document doc) {
        int maxPage = 1;
        Elements pageLinks = doc.select("a.page-link");
        for (Element a : pageLinks) {
            Matcher m = PAGE_NUM.matcher(a.attr("href"));
            while (m.find()) {
                try {
                    int n = Integer.parseInt(m.group(1));
                    if (n > maxPage) {
                        maxPage = n;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return maxPage;
    }
}