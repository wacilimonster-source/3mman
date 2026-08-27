package com.m3man.parser;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import com.orhanobut.logger.Logger;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.VideoResult;
import com.m3man.data.model.BaseResult;
import com.m3man.data.model.User;
import com.m3man.data.model.VideoComment;
import com.m3man.utils.NetworkClientHolder;
import com.m3man.utils.StringUtils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.support.annotation.WorkerThread;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * @author flymegoc
 * @date 2017/11/15
 * @fix tech on 2020/04/12
 * @describe 9mman 站点 HTML 解析器：列表/搜索/作者/收藏页结构与分页、播放地址抽取、strencode 解密
 */

public class ParseV9MmanVideo {

    /** 正则：匹配 strencode2 单参数格式 document.write(strencode2("...")) */
    private static final Pattern STR_ENCODE2_PATTERN = Pattern.compile(
            "document\\.write\\(\\s*strencode2\\(\"([^\"]+)\"\\s*\\)"
    );

    /** 正则：匹配 strencode 三参数格式 document.write(strencode("...","...","...")) */
    private static final Pattern STR_ENCODE_PATTERN = Pattern.compile(
            "document\\.write\\(strencode\\(\"([A-Za-z0-9+/=]+?)\",\"([A-Za-z0-9+/=]+?)\",\"([A-Za-z0-9+/=]+?)\""
    );

    private static final String TAG = ParseV9MmanVideo.class.getSimpleName();
    /** M95：通用 Chrome UA（与 CommonHeaderInterceptor 保持一致），供分享链接兜底请求使用 */
    private static final String FALLBACK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/68.0.3440.84 Safari/537.36";

    /**
     * 解析主页
     *
     * @param html 主页html
     * @return 视频列表
     */
    public static List<V9MmanItem> parseIndex(String html) {

        Document doc = Jsoup.parse(html);

        Element body = doc.getElementById("wrapper");
        // M62：CDN/代理拦截页、错误页可能不含 #wrapper，判空避免 NPE
        if (body == null) {
            return new ArrayList<>();
        }

        Element container = body.selectFirst("div.container");
        if (container == null) {
            return new ArrayList<>();
        }

        return parserByDivContainer(container);

    }


    /**
     * 解析其他类别
     *
     * @param html 类别
     * @return 列表
     */
    public static BaseResult<List<V9MmanItem>> parseByCategory(String html) {

        Document doc = Jsoup.parse(html);

        Element body = doc.getElementById("wrapper");
        // M62：判空避免 NPE（拦截页/错误页）
        if (body == null) {
            BaseResult<List<V9MmanItem>> emptyResult = new BaseResult<>();
            emptyResult.setTotalPage(1);
            emptyResult.setData(new ArrayList<>());
            return emptyResult;
        }

        Element container = body.selectFirst("div.container");
        List<V9MmanItem> v9MmanItemList = parserByDivContainer(container);


        // get page info
        int totalPage = 1;
        Element paging = body.getElementById("paging");
        // M73：paging 缺失（拦截页/改版）时按单页处理，不 NPE
        Elements pagingLinks;
        if (paging == null) {
            pagingLinks = new Elements();
        } else {
            pagingLinks = paging.select("a");
        }
        if (pagingLinks.size() > 2) {
            String lastPageText = pagingLinks.get(pagingLinks.size() - 2).text();
            if (TextUtils.isDigitsOnly(lastPageText)) {
                totalPage = Integer.parseInt(lastPageText);
            }
        }

        BaseResult<List<V9MmanItem>> baseResult = new BaseResult<>();
        baseResult.setTotalPage(totalPage);
        baseResult.setData(v9MmanItemList);
        return baseResult;
    }


    private static List<V9MmanItem> parserByDivContainer(Element container) {
        if (container == null) {
            return new ArrayList<>();
        }
        List<V9MmanItem> v9MmanItemList = new ArrayList<>();
        // 按「标题 + 封面」去重：站点常把同一个片子/同封面用不同 viewKey 发多遍，
        // 仅按 viewKey 去重无法合并这些同名同图的重复条目。
        java.util.Set<String> seenDedupKeys = new java.util.HashSet<>();
        Elements select = container.select("div.row>div.col-sm-12>div.row>div");

        for (Element item : select) {
            // M71：过滤站点的废弃渲染残留。91porn 列表页服务端会多渲染一批
            // 带 col-lg-8 类的条目，页面 CSS 用 `.col-lg-8{display:none}` 把它们隐藏——
            // 这些块里的 viewKey/标题/封面互相串位（实测一页 41 块中 17 个隐藏块、16 个错位）。
            // 浏览器不显示它们，但 Jsoup 不执行 CSS 会全部解析进来；去重只留第一行时
            // 错误标题/封面就会进入推荐流（表现为"列表 A 视频点进去是 B 的信息/两条视频同标题"）。
            // 站点可见条目的容器类是 col-lg-3，与浏览器实际展示的 24 个/页完全一致。
            if (item.hasClass("col-lg-8")) {
                continue;
            }
            Element anchor = item.selectFirst("a");
            if (anchor == null) {
                continue;
            }
            V9MmanItem v9MmanItem = new V9MmanItem();

            // M73：video-title 缺失时跳过该条目，避免 NPE 炸掉整页列表
            Element titleEle = anchor.getElementsByClass("video-title").first();
            if (titleEle == null) {
                Logger.w(TAG, "parserByDivContainer: 条目缺 video-title，跳过 class=" + item.className());
                continue;
            }
            String title = titleEle.text().trim();
            v9MmanItem.setTitle(title);

            Element imgEle = anchor.selectFirst("img.img-responsive");
            String imgUrl = (imgEle != null) ? imgEle.attr("src") : "";
            if (imgEle != null) {
                v9MmanItem.setImgUrl(imgUrl);
            }

            Element durationEle = anchor.selectFirst("span.duration");
            if (durationEle != null) {
                v9MmanItem.setDuration(durationEle.text().trim());
            } else {
                v9MmanItem.setDuration("00:00");
            }


            String contentUrl = anchor.attr("href");

            // 稳健抽取 viewkey 参数值。站点不同页面（分类 / 作者 / 收藏）的视频链接结构不一：
            // 作者页 href 形如 uvideos.php?UID=xxx&viewkey=ABC&type=public，旧的
            // 「取 ? 之后第一个 & 之前」会把 UID=xxx 误当成 viewKey，导致播放作者其他视频时
            // 「解析链接失败」。这里始终只认 viewkey= 参数本身，并以 viewkey=<value> 形式
            // 存储（loadMman9VideoUrl 内部会按 & 切分并当作 key=value 解析，必须带前缀）。
            String viewKey = extractViewKey(contentUrl);
            v9MmanItem.setViewKey(viewKey);
            // M66b：解析时打持久化来源标记（9mman），供路由权威判定
            v9MmanItem.setSourceName("mman9");

            // 相同视频（归一化 viewKey 一致）只保留第一条；viewKey 解析异常（为空）时
            // 退化为按「标题 + 封面」去重，避免误删正常条目
            String dedupKey;
            if (!TextUtils.isEmpty(viewKey)) {
                dedupKey = "vk:" + viewKey;
            } else {
                dedupKey = "ti:" + title + "##"
                        + (imgUrl.contains("?") ? imgUrl.substring(0, imgUrl.indexOf("?")) : imgUrl);
            }
            if (!seenDedupKeys.add(dedupKey)) {
                continue;
            }

            String allInfo = item.text();

            // M75：列表页顺带提取作者名作兜底，存到 authorText。
            // 详情页解析失败时（如 c48a0932 类静默失败），推荐流仍能显示 @作者，
            // 而不是直接缺段（此前缺段是因为 authorName 唯一来源是详情页成功解析）。
            String listAuthor = extractAuthor(allInfo);
            if (!TextUtils.isEmpty(listAuthor)) {
                v9MmanItem.setAuthorText(listAuthor);
            }

            // Added: / 添加時間: / 添加时间:

            int start = allInfo.indexOf("添加时间:");
            if (start == -1) {
                start = allInfo.indexOf("Added:");
                if (start == -1) {
                    start = allInfo.indexOf("添加時間:");
                }
            }

            // M62：三语文案都未命中时 start==-1，直接 substring 会越界并拖垮整页列表；
            // 退化为整段文本，保证单条异常不炸全页
            String info = start >= 0 ? allInfo.substring(start) : allInfo;
            try {
                if (TextUtils.equals(v9MmanItem.getDuration(), "00:00")) {
                    String duration = allInfo.substring(allInfo.indexOf("时长:") + 3, allInfo.indexOf("查看"));
                    v9MmanItem.setDuration(duration);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            v9MmanItem.setInfo(info);
            v9MmanItemList.add(v9MmanItem);

        }


        return v9MmanItemList;
    }

    /**
     * M75：从列表项整段文本里抽取作者名，作为「详情页解析失败时」的兜底。
     * 列表项文本形如「... 作者:xxx 时长:xx 添加时间:... 热度:...」，作者段可能出现在
     * 添加时间之前；站点还可能用英文 From: / 中文 来自:。截到下一个已知字段标签为止。
     */
    private static String extractAuthor(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String[] labels = {"作者:", "作者：", "From:", "From", "来自:", "來自:"};
        int start = -1;
        for (String l : labels) {
            int i = text.indexOf(l);
            if (i >= 0) {
                start = i + l.length();
                break;
            }
        }
        if (start < 0 || start >= text.length()) {
            return null;
        }
        String rest = text.substring(start).trim();
        if (rest.isEmpty()) {
            return null;
        }
        // 截到下一个已知字段标签（防止把 时长/添加时间 等一并吃进来）
        String[] stop = {"时长", "添加时间", "Added", "热度", "收藏", "查看",
                "留言", "评论", "积分", "Point", "From", "作者", "来自", "來自"};
        int end = rest.length();
        for (String s : stop) {
            int i = rest.indexOf(s);
            if (i >= 0 && i < end) {
                end = i;
            }
        }
        String name = rest.substring(0, end).trim();
        return name.isEmpty() ? null : name;
    }


    /**
     * 从视频链接里稳健抽出 viewkey 参数值，并以 {@code viewkey=<value>} 形式返回。
     * <p>
     * 原因：{@link #loadMman9VideoUrl} 内部对 viewKey 做 {@code split("&")} 再按
     * {@code key=value} 解析，因此存储的 viewKey 必须带 {@code viewkey=} 前缀。
     * 直接取「? 之后第一个 & 之前」在作者页（href 形如
     * {@code uvideos.php?UID=xxx&viewkey=ABC&type=public}）会把 {@code UID=xxx}
     * 误当成 viewKey，导致播放作者其他视频时「解析链接失败」。这里始终只认
     * {@code viewkey=} 参数本身，与分类页行为完全一致。
     *
     * @param href 视频链接
     * @return 形如 {@code viewkey=ABC} 的字符串；解析不出时退化为旧逻辑兜底，绝不抛异常
     */
    private static String extractViewKey(String href) {
        if (TextUtils.isEmpty(href)) {
            return "";
        }
        int vk = href.indexOf("viewkey=");
        if (vk >= 0) {
            int start = vk + "viewkey=".length();
            int end = href.indexOf("&", start);
            String value = (end > 0) ? href.substring(start, end) : href.substring(start);
            if (!TextUtils.isEmpty(value)) {
                return "viewkey=" + value;
            }
        }
        // 兜底：保持旧行为（取 ? 之后第一段），避免结构异常的链接直接崩掉
        int q = href.indexOf("?");
        String raw = (q >= 0) ? href.substring(q + 1) : href;
        int amp = raw.indexOf("&");
        String fallback = (amp > 0) ? raw.substring(0, amp) : raw;
        return "viewkey=" + fallback;
    }

    /**
     * 从 URL 查询串中取指定参数值（只取到下一个 & 为止）。
     * 按 "?key=" / "&key=" 边界匹配，避免 VUID= 被当成 UID= 命中。
     */
    private static String extractQueryParam(String url, String key) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(key)) {
            return "";
        }
        int p1 = url.indexOf("?" + key + "=");
        int p2 = url.indexOf("&" + key + "=");
        int start;
        if (p1 >= 0) {
            start = p1 + key.length() + 2;
        } else if (p2 >= 0) {
            start = p2 + key.length() + 2;
        } else {
            return "";
        }
        if (start > url.length()) {
            return "";
        }
        int end = url.indexOf('&', start);
        return (end > start) ? url.substring(start, end) : url.substring(start);
    }

    public static BaseResult<List<V9MmanItem>> parseSearchVideos(String html) {
        int totalPage = 1;
        List<V9MmanItem> v9MmanItemList = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        Element body = doc.getElementById("fullside");
        if (body == null) {
            String errorMsg = parseErrorInfo(html);
            Logger.t(TAG).d(errorMsg);
            BaseResult<List<V9MmanItem>> baseResult = new BaseResult<>();

            baseResult.setCode(BaseResult.ERROR_CODE);
            baseResult.setMessage(errorMsg);
            return baseResult;
        }
        Elements listchannel = body.getElementsByClass("listchannel");
        for (Element element : listchannel) {
            V9MmanItem v9MmanItem = new V9MmanItem();
            // M73：a/img 缺失时跳过该条目，避免 NPE 炸掉整个搜索结果页
            Element firstA = element.select("a").first();
            if (firstA == null) {
                continue;
            }
            Element imgInA = firstA.select("img").first();
            String contentUrl = firstA.attr("href");
            // M34：href 可能为空或不含 &，直接 substring(0, indexOf("&")) 会在空串上抛
            // StringIndexOutOfBoundsException（begin 0, end -1, length 0）
            if (!TextUtils.isEmpty(contentUrl) && contentUrl.contains("&")) {
                contentUrl = contentUrl.substring(0, contentUrl.indexOf("&"));
            }

            String viewKey = "";
            int eqIdx = contentUrl.indexOf("=");
            if (eqIdx >= 0) {
                viewKey = contentUrl.substring(eqIdx + 1);
            }
            v9MmanItem.setViewKey(viewKey);
            // M66b：解析时打持久化来源标记（9mman），供路由权威判定
            v9MmanItem.setSourceName("mman9");

            String imgUrl = imgInA != null ? imgInA.attr("src") : "";
            v9MmanItem.setImgUrl(imgUrl);

            String title = element.select("span.title").text();
            v9MmanItem.setTitle(title);

            String duration = element.select("span.duration").text();
            v9MmanItem.setDuration(duration);

            String allInfo = element.text();

            // M75：列表页作者兜底（同 parserByDivContainer）
            String listAuthor = extractAuthor(allInfo);
            if (!TextUtils.isEmpty(listAuthor)) {
                v9MmanItem.setAuthorText(listAuthor);
            }

            int start = allInfo.indexOf("添加时间");
            // M62：start==-1 时直接 substring 会越界并拖垮整页列表
            String info = start >= 0 ? allInfo.substring(start) : allInfo;
            v9MmanItem.setInfo(info.replace("还未被评分", ""));

            v9MmanItemList.add(v9MmanItem);
        }
        //总页数
        Element pagingnav = body.getElementById("paging");
        // M73：paging 缺失（拦截页/改版）时按单页处理，不 NPE
        Elements pagingLinks;
        if (pagingnav == null) {
            pagingLinks = new Elements();
        } else {
            pagingLinks = pagingnav.select("a");
        }
        if (pagingLinks.size() > 2) {
            String lastPageText = pagingLinks.get(pagingLinks.size() - 2).text();
            if (TextUtils.isDigitsOnly(lastPageText)) {
                totalPage = Integer.parseInt(lastPageText);
            }
        }
        BaseResult<List<V9MmanItem>> baseResult = new BaseResult<>();
        baseResult.setTotalPage(totalPage);
        baseResult.setData(v9MmanItemList);
        return baseResult;
    }

    /**
     * 解析视频播放地址（兜底逻辑含同步网络调用，必须在 IO 线程执行）
     *
     * @param html 视频页
     * @return 视频连接
     */
    @WorkerThread
    public static VideoResult parseVideoPlayUrl(String html, User user) {
        VideoResult videoResult = new VideoResult();
        if (TextUtils.isEmpty(html)) {
            return videoResult;
        }
        if (html.contains("你每天只可观看10个视频")) {
            Logger.d("已经超出观看上限了");
            //设置标志位,用于上传日志
            videoResult.setId(VideoResult.OUT_OF_WATCH_TIMES);
            return videoResult;
        }

        Document doc = Jsoup.parse(html);


        // 先直接取source
        String videoUrl = null;
        try {
            videoUrl = doc.select("video").first().select("source").first().attr("src");
        } catch (Exception e) {
            Logger.t(TAG).e("解析source失败，尝试获取加密链接");
            e.printStackTrace();
        }
        try {
            if (TextUtils.isEmpty(videoUrl)) {
                videoUrl = decodeVideoUrl(html);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if(TextUtils.isEmpty(videoUrl)) {
            //如果都获取不到就找分享链接
            Logger.t(TAG).e("解析加密链接失败，尝试获取分享链接");
            // M95：兜底结果初始为空串——shareLink 为空或请求异常时直接以空串收尾，不再携带 null
            videoUrl = "";
            // M95：裸 Jsoup.connect 每次新建连接池且绕过全局 Cookie/代理配置，
            // 改走 NetworkClientHolder 统一持有的 OkHttpClient（MyApplication 主进程启动时注入）。
            // 兜底请求使用短超时（5s），避免 CDN 慢响应长时间占用 IO 线程
            OkHttpClient fallbackClient = NetworkClientHolder.get().newBuilder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build();
            Response response = null;
            try {
                String shareLink = doc.select("#linkForm2 #fm-video_link").text();
                if (!TextUtils.isEmpty(shareLink)) {
                    Request request = new Request.Builder()
                            .url(shareLink)
                            .header("User-Agent", FALLBACK_UA)
                            .build();
                    response = fallbackClient.newCall(request).execute();
                    String shareHtml = response.body() != null ? response.body().string() : "";
                    videoUrl = decodeVideoUrl(shareHtml);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (response != null) {
                    response.close();
                }
            }
        }

        videoResult.setVideoUrl(videoUrl);
        Logger.t(TAG).d("视频链接：" + videoUrl);

        // M32：站点改版/视频下架/反爬时 videoUrl 可能为空，不再触发 substring 崩溃
        String videoId = "";
        if (!TextUtils.isEmpty(videoUrl)) {
            int endIndex = videoUrl.indexOf(".mp4");
            if (endIndex > 0) {
                int startIndex = videoUrl.substring(0, endIndex).lastIndexOf("/");
                videoId = videoUrl.substring(startIndex + 1, endIndex);
            }
        }
        videoResult.setVideoId(videoId);
        Logger.t(TAG).d("视频Id：" + videoId);

        //这里解析的作者id已经变了，非纯数字了（加密临时 token，会过期，见 AuthorPresenter 自愈逻辑）
        // M92：优先精确匹配作者页链接（uvideos.php），避免抓到导航栏「我的视频」等同样含 UID 的链接；
        // 并只取 UID= 到下一个 & 之间的值——旧写法 substring(eq+1) 会把后续参数一起带进 ownerId
        Element ownerLink = doc.select("a[href*=uvideos.php]").first();
        if (ownerLink == null) {
            ownerLink = doc.select("a[href*=UID]").first();
        }
        if (ownerLink != null) {
            String ownerUrl = ownerLink.attr("href");
            String ownerId = extractQueryParam(ownerUrl, "UID");
            if (!TextUtils.isEmpty(ownerId)) {
                videoResult.setOwnerId(ownerId);
                Logger.t(TAG).d("作者Id：" + ownerId);
            }
            String ownerName = ownerLink.text();
            if (!TextUtils.isEmpty(ownerName)) {
                videoResult.setOwnerName(ownerName);
                Logger.t(TAG).d("作者：" + ownerName);
            }
        }

        Element addToFavLink = doc.getElementById("addToFavLink");
        if (addToFavLink != null) {
            Element favorite = addToFavLink.getElementById("favorite");
            if (favorite != null) {
                // M73：UID/VID/VUID 任一元素缺失时跳过收藏信息提取，
                // 不让 NPE 拖垮整个 map——直链已提取成功，不应因此"播放失败"
                try {
                    String uid = favorite.getElementById("UID").text();
                    String vid = favorite.getElementById("VID").text();
                    String vuid = favorite.getElementById("VUID").text();

                    Logger.t(TAG).d("userId:::" + uid);
                    // 登录后页面才返回 uid；解析失败（站点改版等）时保留原有 uid，
                    // 避免把已有 uid 重置成 0 导致收藏接口报错
                    if (!TextUtils.isEmpty(uid)) {
                        try {
                            user.setUserId(Integer.parseInt(uid));
                        } catch (NumberFormatException e) {
                            Logger.t(TAG).d("解析用户uid失败，保留原有uid");
                        }
                    }

                    //原始纯数字作者id，用于收藏接口
                    Logger.t(TAG).d("authorId:::" + vuid);
                    videoResult.setAuthorId(vuid);
                } catch (Exception e) {
                    Logger.t(TAG).e(TAG + ": 收藏信息元素缺失，跳过(不影响播放) " + e.getMessage());
                }
            }
        }

        Element playerOne = doc.getElementById("player_one");
        if (playerOne != null) {
            videoResult.setThumbImgUrl(playerOne.attr("poster"));
            Logger.t(TAG).d("缩略图：" + playerOne.attr("poster"));
        }

        Elements elementsByClass = doc.getElementsByClass("videodetails-yakov");


        for (Element element : elementsByClass) {

            Element header = element.selectFirst("h4.login_register_header");
            if (header == null) {
                continue;
            }
            String h4Header = header.text().trim();
            if ("视频信息".equals(h4Header)) {
                String allInfo = element.text();

                int addTime = allInfo.indexOf("添加时间");
                int author = allInfo.indexOf("作者");
                String addDate = "";
                if (addTime != -1 && author != -1) {
                    addDate = allInfo.substring(addTime, author);
                }
                videoResult.setAddDate(addDate);
                Logger.t(TAG).d("添加时间：" + addDate);

                int regIndex = allInfo.indexOf("注册");
                int introduction = allInfo.indexOf("简介");
                String otherInfo = "";
                if (regIndex != -1 && introduction != -1) {
                    otherInfo = allInfo.substring(regIndex, introduction);
                }
                videoResult.setUserOtherInfo(otherInfo);
                Logger.t(TAG).d(otherInfo);
            } else if ("此视频留言".equals(h4Header)) {
            } else {
                videoResult.setVideoName(h4Header);
                Logger.t(TAG).d("视频标题：" + h4Header);
            }
        }
        return videoResult;
    }

    /**
     * 从 91porn/91porny 视频页面 HTML 中解码提取真实视频 URL。
     * <p>
     * 站点采用两种加密格式混淆视频地址，本方法按顺序尝试两种格式：
     * <ol>
     *   <li><strong>strencode2 单参数格式（新版）</strong>：
     *       HTML 中含 <code>document.write(strencode2("...%3c%73..."))</code>，
     *       参数为百分号编码的 <code><source src="..."></code> 片段。
     *       解码流程：正则提取参数 -> {@link Uri#decode} 还原百分号编码 ->
     *       Jsoup 解析 <code><source></code> 标签提取 <code>src</code> 属性。
     *   </li>
     *   <li><strong>strencode 三参数格式（旧版兼容）</strong>：
     *       HTML 中含 <code>document.write(strencode("param1","param2","param3"))</code>，
     *       三个参数均为 Base64 编码。
     *       解码流程：
     *       <ul>
     *         <li>若第三个参数以 "2" 结尾，交换前两个参数顺序（历史版本差异）</li>
     *         <li>param1: Base64 解码 -> 得到字节数组 A</li>
     *         <li>param2: Base64 解码 -> 得到字节数组 B（作为密钥）</li>
     *         <li>逐字节异或：C[i] = A[i] ^ B[i % B.length]（循环密钥 XOR）</li>
     *         <li>将异或结果 C 再次 Base64 解码 -> 得到包含 <code><source></code> 的 HTML</li>
     *         <li>Jsoup 解析提取 <code>src</code> 属性</li>
     *       </ul>
     *   </li>
     * </ol>
     * 两种格式均以 <code>document.write(strencode...</code> 为特征，正则已预编译为
     * {@link #STR_ENCODE2_PATTERN} 与 {@link #STR_ENCODE_PATTERN}。
     *
     * @param html 视频播放页完整 HTML
     * @return 视频真实 URL；若解析失败返回空字符串
     */
    private static String decodeVideoUrl(String html) {
        if (TextUtils.isEmpty(html)) {
            return "";
        }

        // M96：91porn 当前页面使用 strencode2("%3c%73...") 单参数格式。
        // 该参数是百分号编码后的 <source ...> HTML，Android 的 Uri.decode 可直接还原。
        Matcher encoded = STR_ENCODE2_PATTERN.matcher(html);
        if (encoded.find()) {
            String decodedHtml = Uri.decode(encoded.group(1));
            if (!TextUtils.isEmpty(decodedHtml)) {
                Document decodedDoc = Jsoup.parse(decodedHtml);
                Element source = decodedDoc.selectFirst("source[src]");
                if (source != null && !TextUtils.isEmpty(source.attr("src"))) {
                    Logger.t(TAG).d("strencode2 视频source：" + source.attr("src"));
                    return source.attr("src");
                }
            }
        }

        // 兼容旧版 strencode 三参数格式，保留 M95 的非贪婪/字符集限制。
        Matcher m = STR_ENCODE_PATTERN.matcher(html);
        if (m.find()) {
            String param1 = m.group(1);
            String param2 = m.group(2);
            String param3 = m.group(3);
            if (!TextUtils.isEmpty(param3) && param3.endsWith("2")) {
                String tmp = param1;
                param1 = param2;
                param2 = tmp;
            }
            if (TextUtils.isEmpty(param2)) {
                return "";
            }
            try {
                param1 = new String(Base64.decode(param1.getBytes(), Base64.DEFAULT));
                StringBuilder sourceBuilder = new StringBuilder(param1.length());
                for (int i = 0; i < param1.length(); i++) {
                    sourceBuilder.append((char) (param1.codePointAt(i)
                            ^ param2.codePointAt(i % param2.length())));
                }
                String sourceHtml = new String(Base64.decode(
                        sourceBuilder.toString().getBytes(), Base64.DEFAULT));
                Document sourceDoc = Jsoup.parse(sourceHtml);
                Element source = sourceDoc.selectFirst("source[src]");
                return source == null ? "" : source.attr("src");
            } catch (IllegalArgumentException e) {
                Logger.t(TAG).w("旧 strencode 解码失败: " + e.getMessage());
            }
        }
        return "";
    }

    /**
     * 解析登录用户信息
     *
     * @return 用户
     */
    public static User parseUserInfo(String html) {
        User user = new User();
        Document doc = Jsoup.parse(html);
        //新帐号注册成功登录后信息不一样，导致无法解析
        Element element = doc.getElementById("userinfo-title");
        if (element == null) {
            user.setLogin(true);
            user.setUserName("无法解析用户信息...");
            return user;
        }

        //解析用户uid，2018年3月29日 似乎已经失效了,可在播放界面获取
        Element userLickElement = element.select("a").first();
        if (userLickElement != null) {
            String userLinks = userLickElement.attr("href");
            // M62：parseInt 加捕获，异常页结构变化时不再让登录成功路径中断
            try {
                int uid = Integer.parseInt(StringUtils.subString(userLinks, userLinks.indexOf("=") + 1, userLinks.length()));
                user.setUserId(uid);
                Logger.t(TAG).d(userLinks);
                Logger.t(TAG).d(uid);
            } catch (Exception e) {
                Logger.t(TAG).e("parse uid failed: " + e.getMessage());
            }
        } else {
            Logger.t(TAG).d("无法解析用户uid");
        }
        String userInfoTitle = doc.getElementById("userinfo-title").text();
        String userName = StringUtils.subString(userInfoTitle, userInfoTitle.indexOf("欢迎") + 3, userInfoTitle.indexOf("用户状态"));
        Logger.t(TAG).d(userName);
        user.setUserName(userName);

        // M62：font 元素可能缺失，判空避免登录成功路径 NPE
        Element fontEle = doc.getElementById("userinfo-title").select("font").first();
        if (fontEle != null) {
            String userAccountStatus = fontEle.text();
            Logger.t(TAG).d(userAccountStatus);
            user.setStatus(userAccountStatus);
        }

        String userContent = doc.getElementById("userinfo-content").text();
        Logger.t(TAG).d(userContent);

        // M62：任一锚点文案缺失或顺序颠倒时 indexOf 返回 -1，直接 substring 会越界
        try {
            int tStart = userContent.indexOf("最后登录");
            int ipIdx = userContent.indexOf("IP:");
            if (tStart >= 0 && ipIdx > tStart) {
                String lastLoginTime = userContent.substring(tStart, ipIdx);
                user.setLastLoginTime(lastLoginTime);
                int viewIdx = userContent.indexOf("点此查看");
                String lastLoginIP = viewIdx > ipIdx
                        ? userContent.substring(ipIdx, viewIdx)
                        : userContent.substring(ipIdx);
                user.setLastLoginIP(lastLoginIP);
                Logger.t(TAG).d(lastLoginTime);
                Logger.t(TAG).d(lastLoginIP);
            }
        } catch (Exception e) {
            Logger.t(TAG).e("parse lastLogin failed: " + e.getMessage());
        }

        return user;
    }

    /**
     * 解析我的收藏
     *
     * @param html html
     * @return list
     */
    public static BaseResult<List<V9MmanItem>> parseMyFavorite(String html) {
        Document doc = Jsoup.parse(html);
        Element body = doc.getElementById("wrapper");
        // M62：判空避免 NPE（拦截页/错误页）
        if (body == null) {
            BaseResult<List<V9MmanItem>> emptyResult = new BaseResult<>();
            emptyResult.setTotalPage(1);
            emptyResult.setData(new ArrayList<>());
            return emptyResult;
        }

        Element container = body.selectFirst("div.container");
        if (container == null) {
            BaseResult<List<V9MmanItem>> emptyResult = new BaseResult<>();
            emptyResult.setTotalPage(1);
            emptyResult.setData(new ArrayList<>());
            return emptyResult;
        }

        List<V9MmanItem> v9MmanItemList = new ArrayList<>();
        java.util.Set<String> seenViewKeys = new java.util.HashSet<>();
        Elements select = container.select("div.row>div.col-sm-12>div.row>div");
        for (Element item : select) {
            Element anchor = item.selectFirst("a");
            if (anchor == null) {
                continue;
            }
            V9MmanItem v9MmanItem = new V9MmanItem();

            // M73：video-title 缺失时跳过该条目，避免 NPE 炸掉整页列表（parserByDivContainer 同步修复）
            Element titleEle = anchor.getElementsByClass("video-title").first();
            if (titleEle == null) {
                Logger.w(TAG, "parseMyFavorite: 条目缺 video-title，跳过 class=" + item.className());
                continue;
            }
            String title = titleEle.text().trim();
            v9MmanItem.setTitle(title);

            Element imgEle = anchor.selectFirst("img.img-responsive");
            if (imgEle != null) {
                v9MmanItem.setImgUrl(imgEle.attr("src"));
            }

            Element durationEle = anchor.selectFirst("span.duration");
            if (durationEle != null) {
                v9MmanItem.setDuration(durationEle.text().trim());
            } else {
                v9MmanItem.setDuration("00:00");
            }


            String contentUrl = anchor.attr("href");

            // 与列表解析一致：稳健抽取 viewkey 参数（见 extractViewKey 注释）
            String viewKey = extractViewKey(contentUrl);
            v9MmanItem.setViewKey(viewKey);
            // M66b：解析时打持久化来源标记（9mman），供路由权威判定
            v9MmanItem.setSourceName("mman9");

            if (!seenViewKeys.add(viewKey)) {
                continue;
            }

            String allInfo = item.text();

            // M75：列表页作者兜底（同 parserByDivContainer）
            String listAuthor = extractAuthor(allInfo);
            if (!TextUtils.isEmpty(listAuthor)) {
                v9MmanItem.setAuthorText(listAuthor);
            }

            // Added: / 添加時間: / 添加时间:

            int start = allInfo.indexOf("添加时间:");
            if (start == -1) {
                start = allInfo.indexOf("Added:");
                if (start == -1) {
                    start = allInfo.indexOf("添加時間:");
                }
            }

            // M62：三语文案都未命中时 start==-1，直接 substring 会越界并拖垮整页列表；
            // 退化为整段文本，保证单条异常不炸全页
            String info = start >= 0 ? allInfo.substring(start) : allInfo;
            try {
                if (TextUtils.equals(v9MmanItem.getDuration(), "00:00")) {
                    String duration = allInfo.substring(allInfo.indexOf("时长:") + 3, allInfo.indexOf("查看"));
                    v9MmanItem.setDuration(duration);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            v9MmanItem.setInfo(info);
            // M73：input 元素缺失时跳过 rvid 提取（保留条目，仅 videoId 为空），
            // 避免 NPE 炸掉整个"我的收藏"页
            Element rvidInput = item.select("input").first();
            if (rvidInput != null) {
                String rvid = rvidInput.attr("value");
                Logger.t(TAG).d("rvid::" + rvid);
                VideoResult videoResult = new VideoResult();
                videoResult.setId(VideoResult.OUT_OF_WATCH_TIMES);
                videoResult.setVideoId(rvid);
                v9MmanItem.setVideoResult(videoResult);
            } else {
                Logger.w(TAG, "parseMyFavorite: 条目缺 input(rvid)，跳过该字段 title=" + title);
            }
            v9MmanItemList.add(v9MmanItem);

        }
        // get page info
        int totalPage = 1;
        Element paging = body.getElementById("paging");
        // M73：paging 缺失（拦截页/改版）时按单页处理，不 NPE
        Elements pagingLinks;
        if (paging == null) {
            pagingLinks = new Elements();
        } else {
            pagingLinks = paging.select("a");
        }
        if (pagingLinks.size() > 2) {
            String lastPageText = pagingLinks.get(pagingLinks.size() - 2).text();
            if (TextUtils.isDigitsOnly(lastPageText)) {
                totalPage = Integer.parseInt(lastPageText);
            }
        }

        BaseResult<List<V9MmanItem>> baseResult = new BaseResult<>();
        //尝试解析删除信息
        // M95：Jsoup.select 永不返回 null，旧 != null 判断恒真导致 errorbox 兜底分支不可达；
        // 改为 isEmpty 判断，并在 msgbox 缺失时补 errorbox 错误信息检查兜底
        Elements msgElements = doc.select("div.msgbox");
        if (!msgElements.isEmpty()) {
            String msgInfo = msgElements.text();
            if (!TextUtils.isEmpty(msgInfo)) {
                baseResult.setCode(BaseResult.SUCCESS_CODE);
                baseResult.setMessage(msgInfo);
            }
        } else {
            String errorMsg = parseErrorInfo(html);
            if (!TextUtils.isEmpty(errorMsg)) {
                baseResult.setMessage(errorMsg);
                baseResult.setCode(BaseResult.ERROR_CODE);
            }
        }

        baseResult.setTotalPage(totalPage);
        baseResult.setData(v9MmanItemList);

        return baseResult;
    }

    /**
     * 解析作者更多视频
     *
     * @param html html
     * @return list
     */
    public static BaseResult<List<V9MmanItem>> parseAuthorVideos(String html) {
        int totalPage = 1;

        Document doc = Jsoup.parse(html);


        Element body = doc.getElementById("wrapper");
        // M62：判空避免 NPE（拦截页/错误页）
        if (body == null) {
            BaseResult<List<V9MmanItem>> emptyResult = new BaseResult<>();
            emptyResult.setTotalPage(1);
            emptyResult.setData(new ArrayList<>());
            return emptyResult;
        }

        Element container = body.selectFirst("div.container");
        if (container == null) {
            BaseResult<List<V9MmanItem>> emptyResult = new BaseResult<>();
            emptyResult.setTotalPage(1);
            emptyResult.setData(new ArrayList<>());
            return emptyResult;
        }
        List<V9MmanItem> v9MmanItemList = parserByDivContainer(container);


        //总页数
        Element pagingnav = doc.getElementById("paging");
        if (pagingnav != null) {
            Elements pagingLinks = pagingnav.select("a");
            if (pagingLinks.size() >= 2) {
                String lastPageText = pagingLinks.get(pagingLinks.size() - 2).text();
                if (TextUtils.isDigitsOnly(lastPageText)) {
                    totalPage = Integer.parseInt(lastPageText);
                }
            }
        }

        BaseResult<List<V9MmanItem>> baseResult = new BaseResult<>();
        baseResult.setTotalPage(totalPage);
        baseResult.setData(v9MmanItemList);

        return baseResult;
    }

    /**
     * 解析错误提示
     *
     * @param html html
     * @return 错误信息
     */
    public static String parseErrorInfo(String html) {
        String errorInfo = "";
        Document doc = Jsoup.parse(html);
        Elements errorElements = doc.select("div.errorbox");
        // M95：select 永不返回 null，旧 != null 判断恒真；改 isEmpty 判断
        if (!errorElements.isEmpty()) {
            errorInfo = errorElements.text();
        }
        return errorInfo;
    }

    /**
     * 解析视频评论
     *
     * @param html 评论html
     * @return 评论列表
     */
    public static List<VideoComment> parseVideoComment(String html) {
        List<VideoComment> videoCommentList = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        Elements elements = doc.select("table.comment-divider");
        for (Element element : elements) {
            VideoComment videoComment = new VideoComment();

            // M62：注销用户等场景可能缺 UID 链接，判空跳过该条而不是拖垮整页评论
            Element uidLink = element.select("a[href*=UID]").first();
            if (uidLink == null) {
                continue;
            }
            String ownnerUrl = uidLink.attr("href");
            String uid = ownnerUrl.substring(ownnerUrl.indexOf("=") + 1, ownnerUrl.length());
            videoComment.setUid(uid);
            //Logger.t(TAG).d(uid);

            String uName = uidLink.text();
            videoComment.setuName(uName);
            Logger.t(TAG).d(uName);

            // M62：comment-info 缺失时跳过时间解析，不中断整页
            Element infoEle = element.select("span.comment-info").first();
            if (infoEle != null) {
                String replyTime = infoEle.text();
                videoComment.setReplyTime(replyTime.replace("(", "").replace(")", ""));
            } // Logger.t(TAG).d(replyTime);

            // M62：comment-body 判空必须先于 replyId 解析——
            // replyId 取自同一元素的 attr("id")，若把判空放在其后，缺 body 的行会先在取 id 处 NPE
            Element bodyEle = element.select("div.comment-body").first();
            if (bodyEle == null) {
                continue;
            }
            String replyIdSrc = bodyEle.attr("id");
            String replyId = replyIdSrc.substring(replyIdSrc.lastIndexOf("_") + 1, replyIdSrc.length());
            videoComment.setReplyId(replyId);
            // Logger.t(TAG).d("replyId:" + replyId);

            String comment = bodyEle.text().replace("举报", "").replace("Show", "");
            //            videoComment.setContentMessage(comment.replace("Show", ""));
            //Logger.t(TAG).d(comment);

            List<String> tmpQuoteList = new ArrayList<>();
            tmpQuoteList.add(comment);
            Elements quotes = element.select("div.comment-body").select("div.comment_quote");
            for (Element element1 : quotes) {
                String quote = element1.text();
                tmpQuoteList.add(quote);
            }

            List<String> quoteList = new ArrayList<>();
            for (int i = 0; i < tmpQuoteList.size(); i++) {
                String quote;
                if (i + 1 >= tmpQuoteList.size()) {
                    quote = tmpQuoteList.get(i);
                    quoteList.add(0, quote.trim());
                    //Logger.t(TAG).d(quote);
                    break;
                }
                quote = tmpQuoteList.get(i).replace(tmpQuoteList.get(i + 1), "");
                quoteList.add(0, quote.trim());
                //Logger.t(TAG).d(quote);
            }

            videoComment.setCommentQuoteList(quoteList);

            // M62：td 元素缺失时跳过标题信息解析，不中断整页（评论正文已在上方就绪）
            Element infoTd = element.select("td").first();
            if (infoTd != null) {
                String info = infoTd.text();
                int bracket = info.indexOf("(");
                String titleInfo = bracket > 0 ? info.substring(0, bracket) : info;
                videoComment.setTitleInfo(titleInfo.replace(uName, ""));
            }
            // Logger.t(TAG).d(titleInfo);

            videoCommentList.add(videoComment);
        }
        return videoCommentList;
    }
}
