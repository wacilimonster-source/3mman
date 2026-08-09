package com.m3man.parser;

import android.text.TextUtils;
import android.util.Base64;

import com.orhanobut.logger.Logger;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.VideoResult;
import com.m3man.data.model.BaseResult;
import com.m3man.data.model.User;
import com.m3man.data.model.VideoComment;
import com.m3man.utils.StringUtils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author flymegoc
 * @date 2017/11/15
 * @fix tech on 2020/04/12
 * @describe
 */

public class ParseV9MmanVideo {

    private static final String TAG = ParseV9MmanVideo.class.getSimpleName();

    /**
     * 解析主页
     *
     * @param html 主页html
     * @return 视频列表
     */
    public static List<V9MmanItem> parseIndex(String html) {

        Document doc = Jsoup.parse(html);

        Element body = doc.getElementById("wrapper");

        Element container = body.selectFirst("div.container");

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

        Element container = body.selectFirst("div.container");
        List<V9MmanItem> v9MmanItemList = parserByDivContainer(container);


        // get page info
        int totalPage = 1;
        Element paging = body.getElementById("paging");
        Elements a = paging.select("a");
        if (a.size() > 2) {
            String ppp = a.get(a.size() - 2).text();
            if (TextUtils.isDigitsOnly(ppp)) {
                totalPage = Integer.parseInt(ppp);
                //Logger.d("总页数：" + totalPage);
            }
        }

        /*
        int totalPage = 1;
        List<V9MmanItem> v9MmanItemList = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        Element body = doc.getElementById("fullside");

        Elements listchannel = body.getElementsByClass("listchannel");
        for (Element element : listchannel) {
            V9MmanItem v9MmanItem = new V9MmanItem();
            String contentUrl = element.select("a").first().attr("href");
            //Logger.d(contentUrl);
            contentUrl = contentUrl.substring(0, contentUrl.indexOf("&"));
            // Logger.d(contentUrl);
            String viewKey = contentUrl.substring(contentUrl.indexOf("=") + 1);
            v9MmanItem.setViewKey(viewKey);

            String imgUrl = element.select("a").first().select("img").first().attr("src");
            //  Logger.d(imgUrl);
            v9MmanItem.setImgUrl(imgUrl);

            String title = element.select("a").first().select("img").first().attr("title");
            //  Logger.d(title);
            v9MmanItem.setTitle(title);


            String allInfo = element.text();

            int sindex = allInfo.indexOf("时长");

            String duration = allInfo.substring(sindex + 3, sindex + 8);
            v9MmanItem.setDuration(duration);

            int start = allInfo.indexOf("添加时间");
            String info = allInfo.substring(start);
            v9MmanItem.setInfo(info.replace("还未被评分", ""));
            //  Logger.d(info);

            v9MmanItemList.add(v9MmanItem);
        }
        //总页数
        Element pagingnav = body.getElementById("paging");
        Elements a = pagingnav.select("a");
        if (a.size() > 2) {
            String ppp = a.get(a.size() - 2).text();
            if (TextUtils.isDigitsOnly(ppp)) {
                totalPage = Integer.parseInt(ppp);
                //    Logger.d("总页数：" + totalPage);
            }
        }
        */

        BaseResult<List<V9MmanItem>> baseResult = new BaseResult<>();
        baseResult.setTotalPage(totalPage);
        baseResult.setData(v9MmanItemList);
        return baseResult;
    }


    private static List<V9MmanItem> parserByDivContainer(Element container) {
        List<V9MmanItem> v9MmanItemList = new ArrayList<>();
        // 按「标题 + 封面」去重：站点常把同一个片子/同封面用不同 viewKey 发多遍，
        // 仅按 viewKey 去重无法合并这些同名同图的重复条目。
        java.util.Set<String> seenDedupKeys = new java.util.HashSet<>();
        Elements select = container.select("div.row>div.col-sm-12>div.row>div");

        for (Element item : select) {
            Element a = item.selectFirst("a");
            if (a == null) {
                continue;
            }
            V9MmanItem v9MmanItem = new V9MmanItem();

            String title = a.getElementsByClass("video-title").first().text().trim();
            v9MmanItem.setTitle(title);

            Element imgEle = a.selectFirst("img.img-responsive");
            String imgUrl = (imgEle != null) ? imgEle.attr("src") : "";
            if (imgEle != null) {
                v9MmanItem.setImgUrl(imgUrl);
            }

            Element durationEle = a.selectFirst("span.duration");
            if (durationEle != null) {
                v9MmanItem.setDuration(durationEle.text().trim());
            } else {
                v9MmanItem.setDuration("00:00");
            }


            String contentUrl = a.attr("href");

            // 稳健抽取 viewkey 参数值。站点不同页面（分类 / 作者 / 收藏）的视频链接结构不一：
            // 作者页 href 形如 uvideos.php?UID=xxx&viewkey=ABC&type=public，旧的
            // 「取 ? 之后第一个 & 之前」会把 UID=xxx 误当成 viewKey，导致播放作者其他视频时
            // 「解析链接失败」。这里始终只认 viewkey= 参数本身，并以 viewkey=<value> 形式
            // 存储（loadMman9VideoUrl 内部会按 & 切分并当作 key=value 解析，必须带前缀）。
            String viewKey = extractViewKey(contentUrl);
            v9MmanItem.setViewKey(viewKey);

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

            // Added: / 添加時間: / 添加时间:

            int start = allInfo.indexOf("添加时间:");
            if (start == -1) {
                start = allInfo.indexOf("Added:");
                if (start == -1) {
                    start = allInfo.indexOf("添加時間:");
                }
            }

            String info = allInfo.substring(start);
            try {
                if (TextUtils.equals(v9MmanItem.getDuration(), "00:00")) {
                    String duration = allInfo.substring(allInfo.indexOf("时长:") + 3, allInfo.indexOf("查看"));
                    v9MmanItem.setDuration(duration);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            v9MmanItem.setInfo(info);
            // Logger.d(info);
            v9MmanItemList.add(v9MmanItem);

        }


        return v9MmanItemList;
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
            String contentUrl = element.select("a").first().attr("href");
            //Logger.d(contentUrl);
            // M34：href 可能为空或不含 &，直接 substring(0, indexOf("&")) 会在空串上抛
            // StringIndexOutOfBoundsException（begin 0, end -1, length 0）
            if (!TextUtils.isEmpty(contentUrl) && contentUrl.contains("&")) {
                contentUrl = contentUrl.substring(0, contentUrl.indexOf("&"));
            }
            //Logger.d(contentUrl);

            String viewKey = "";
            int eqIdx = contentUrl.indexOf("=");
            if (eqIdx >= 0) {
                viewKey = contentUrl.substring(eqIdx + 1);
            }
            v9MmanItem.setViewKey(viewKey);
            //Logger.d(viewKey);

            String imgUrl = element.select("a").first().select("img").first().attr("src");
            //Logger.d(imgUrl);
            v9MmanItem.setImgUrl(imgUrl);

            String title = element.select("span.title").text();
            //Logger.d(title);
            v9MmanItem.setTitle(title);

            String duration = element.select("span.duration").text();
            v9MmanItem.setDuration(duration);

            String allInfo = element.text();

            int start = allInfo.indexOf("添加时间");
            String info = allInfo.substring(start);
            v9MmanItem.setInfo(info.replace("还未被评分", ""));
            //Logger.d(info);

            v9MmanItemList.add(v9MmanItem);
        }
        //总页数
        Element pagingnav = body.getElementById("paging");
        Elements a = pagingnav.select("a");
        if (a.size() > 2) {
            String ppp = a.get(a.size() - 2).text();
            if (TextUtils.isDigitsOnly(ppp)) {
                totalPage = Integer.parseInt(ppp);
                //Logger.d("总页数：" + totalPage);
            }
        }
        BaseResult<List<V9MmanItem>> baseResult = new BaseResult<>();
        baseResult.setTotalPage(totalPage);
        baseResult.setData(v9MmanItemList);
        return baseResult;
    }

    /**
     * 解析视频播放连接
     *
     * @param html 视频页
     * @return 视频连接
     */
    public static VideoResult parseVideoPlayUrl(String html, User user) {
        VideoResult videoResult = new VideoResult();
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
            try {
                String shareLink = doc.select("#linkForm2 #fm-video_link").text();
                Document shareDoc = Jsoup.connect(shareLink)
                        .timeout(3000)
                        .get();
//                videoUrl = shareDoc.select("source").first().attr("src");
                videoUrl = decodeVideoUrl(shareDoc.html());
            } catch (Exception e) {
                e.printStackTrace();
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

        //这里解析的作者id已经变了，非纯数字了
        //        Document doc = Jsoup.parse(html);
        Element ownerLink = doc.select("a[href*=UID]").first();
        if (ownerLink != null) {
            String ownerUrl = ownerLink.attr("href");
            int eq = ownerUrl.indexOf("=");
            if (eq >= 0 && eq + 1 <= ownerUrl.length()) {
                String ownerId = ownerUrl.substring(eq + 1);
                videoResult.setOwnerId(ownerId);
                Logger.t(TAG).d("作者Id：" + ownerId);
            }
            videoResult.setOwnerName(ownerLink.text());
            Logger.t(TAG).d("作者：" + ownerLink.text());
        }

        Element addToFavLink = doc.getElementById("addToFavLink");
        if (addToFavLink != null) {
            Element favorite = addToFavLink.getElementById("favorite");
            if (favorite != null) {
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

    private static String decodeVideoUrl(String html) {

        // 找不到的话 解密
        final String reg = "document.write\\(strencode\\(\"(.+)\",\"(.+)\",\"(.+)\"+\\)\\);";
//        final String reg = "document.write\\(strencode\\(\"(.+)\",\"(.+)\",.+\\)\\);";
        Pattern p = Pattern.compile(reg);
        Matcher m = p.matcher(html);
        String param1 = "", param2 = "",param3 = "";

        if (m.find()) {
            param1 = m.group(1);
            param2 = m.group(2);
            param3 = m.group(3);
            if(param3.substring(param3.length()-1).equals("2")){
                String tmp=param1;
                param1=param2;
                param2=tmp;
            }
            param1 = new String(Base64.decode(param1.getBytes(), Base64.DEFAULT));
            String source_str = "";
            for (int i = 0, k = 0; i < param1.length(); i++) {
                k = i % param2.length();
                source_str += "" + (char) (param1.codePointAt(i) ^ param2.codePointAt(k));
            }
            Logger.t(TAG).d("视频source1：" + source_str);
            source_str = new String(Base64.decode(source_str.getBytes(), Base64.DEFAULT));
            Logger.t(TAG).d("视频source2：" + source_str);
            Document source = Jsoup.parse(source_str);
            return source.select("source").first().attr("src");
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
            int uid = Integer.parseInt(StringUtils.subString(userLinks, userLinks.indexOf("=") + 1, userLinks.length()));
            user.setUserId(uid);
            Logger.t(TAG).d(userLinks);
            Logger.t(TAG).d(uid);
        } else {
            Logger.t(TAG).d("无法解析用户uid");
        }
        String userInfoTitle = doc.getElementById("userinfo-title").text();
        String userName = StringUtils.subString(userInfoTitle, userInfoTitle.indexOf("欢迎") + 3, userInfoTitle.indexOf("用户状态"));
        Logger.t(TAG).d(userName);
        user.setUserName(userName);

        String userAccountStatus = doc.getElementById("userinfo-title").select("font").first().text();
        Logger.t(TAG).d(userAccountStatus);
        user.setStatus(userAccountStatus);

        String userContent = doc.getElementById("userinfo-content").text();
        Logger.t(TAG).d(userContent);

        String lastLoginTime = userContent.substring(userContent.indexOf("最后登录"), userContent.indexOf("IP:"));
        String lastLoginIP = userContent.substring(userContent.indexOf("IP:"), userContent.indexOf("点此查看"));
        user.setLastLoginTime(lastLoginTime);
        user.setLastLoginIP(lastLoginIP);

        Logger.t(TAG).d(lastLoginTime);
        Logger.t(TAG).d(lastLoginIP);

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

        Element container = body.selectFirst("div.container");

        List<V9MmanItem> v9MmanItemList = new ArrayList<>();
        java.util.Set<String> seenViewKeys = new java.util.HashSet<>();
        Elements select = container.select("div.row>div.col-sm-12>div.row>div");
        for (Element item : select) {
            Element a = item.selectFirst("a");
            if (a == null) {
                continue;
            }
            V9MmanItem v9MmanItem = new V9MmanItem();

            String title = a.getElementsByClass("video-title").first().text().trim();
            v9MmanItem.setTitle(title);

            Element imgEle = a.selectFirst("img.img-responsive");
            if (imgEle != null) {
                v9MmanItem.setImgUrl(imgEle.attr("src"));
            }

            Element durationEle = a.selectFirst("span.duration");
            if (durationEle != null) {
                v9MmanItem.setDuration(durationEle.text().trim());
            } else {
                v9MmanItem.setDuration("00:00");
            }


            String contentUrl = a.attr("href");

            // 与列表解析一致：稳健抽取 viewkey 参数（见 extractViewKey 注释）
            String viewKey = extractViewKey(contentUrl);
            v9MmanItem.setViewKey(viewKey);

            if (!seenViewKeys.add(viewKey)) {
                continue;
            }

            String allInfo = item.text();

            // Added: / 添加時間: / 添加时间:

            int start = allInfo.indexOf("添加时间:");
            if (start == -1) {
                start = allInfo.indexOf("Added:");
                if (start == -1) {
                    start = allInfo.indexOf("添加時間:");
                }
            }

            String info = allInfo.substring(start);
            try {
                if (TextUtils.equals(v9MmanItem.getDuration(), "00:00")) {
                    String duration = allInfo.substring(allInfo.indexOf("时长:") + 3, allInfo.indexOf("查看"));
                    v9MmanItem.setDuration(duration);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            v9MmanItem.setInfo(info);
            // Logger.d(info);
            String rvid = item.select("input").first().attr("value");
            Logger.t(TAG).d("rvid::" + rvid);
            VideoResult videoResult = new VideoResult();
            videoResult.setId(VideoResult.OUT_OF_WATCH_TIMES);
            videoResult.setVideoId(rvid);
            v9MmanItem.setVideoResult(videoResult);
            v9MmanItemList.add(v9MmanItem);

        }
        // get page info
        int totalPage = 1;
        Element paging = body.getElementById("paging");
        Elements a = paging.select("a");
        if (a.size() > 2) {
            String ppp = a.get(a.size() - 2).text();
            if (TextUtils.isDigitsOnly(ppp)) {
                totalPage = Integer.parseInt(ppp);
                //Logger.d("总页数：" + totalPage);
            }
        }

        BaseResult<List<V9MmanItem>> baseResult = new BaseResult<>();
        //尝试解析删除信息
        Elements msgElements = doc.select("div.msgbox");
        if (msgElements != null) {
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

        Element container = body.selectFirst("div.container");
        List<V9MmanItem> v9MmanItemList = parserByDivContainer(container);


        //总页数
        Element pagingnav = doc.getElementById("paging");
        if (pagingnav != null) {
            Elements a = pagingnav.select("a");
            if (a.size() >= 2) {
                String ppp = a.get(a.size() - 2).text();
                if (TextUtils.isDigitsOnly(ppp)) {
                    totalPage = Integer.parseInt(ppp);
                    //Logger.d("总页数：" + totalPage);
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
     * @return 错误洗洗脑
     */
    public static String parseErrorInfo(String html) {
        String errorInfo = "";
        Document doc = Jsoup.parse(html);
        Elements errorElements = doc.select("div.errorbox");
        if (errorElements != null) {
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

            String ownnerUrl = element.select("a[href*=UID]").first().attr("href");
            String uid = ownnerUrl.substring(ownnerUrl.indexOf("=") + 1, ownnerUrl.length());
            videoComment.setUid(uid);
            //Logger.t(TAG).d(uid);

            String uName = element.select("a[href*=UID]").first().text();
            videoComment.setuName(uName);
            Logger.t(TAG).d(uName);

            String replyTime = element.select("span.comment-info").first().text();
            videoComment.setReplyTime(replyTime.replace("(", "").replace(")", ""));
            // Logger.t(TAG).d(replyTime);

            String tmpreplyId = element.select("div.comment-body").first().attr("id");
            String replyId = tmpreplyId.substring(tmpreplyId.lastIndexOf("_") + 1, tmpreplyId.length());
            videoComment.setReplyId(replyId);
            // Logger.t(TAG).d("replyId:" + replyId);

            String comment = element.select("div.comment-body").first().text().replace("举报", "").replace("Show", "");
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

            String info = element.select("td").first().text();
            int bracket = info.indexOf("(");
            String titleInfo = bracket > 0 ? info.substring(0, bracket) : info;
            videoComment.setTitleInfo(titleInfo.replace(uName, ""));
            // Logger.t(TAG).d(titleInfo);

            videoCommentList.add(videoComment);
        }
        return videoCommentList;
    }
}
