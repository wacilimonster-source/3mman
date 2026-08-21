package com.m3man.data.network;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.orhanobut.logger.Logger;
import com.m3man.constants.Constants;
import com.m3man.data.cache.CacheProviders;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.VideoResult;
import com.m3man.data.model.BaseResult;
import com.m3man.data.model.FavoriteJsonResult;
import com.m3man.data.model.Notice;
import com.m3man.data.model.ProxyModel;
import com.m3man.data.model.UpdateVersion;
import com.m3man.data.model.User;
import com.m3man.data.model.VideoComment;
import com.m3man.data.model.VideoCommentResult;
import com.m3man.data.network.apiservice.GitHubServiceApi;
import com.m3man.data.network.apiservice.PornyServiceApi;
import com.m3man.data.network.apiservice.ProxyServiceApi;
import com.m3man.data.network.apiservice.V9MmanServiceApi;
import com.m3man.data.network.okhttp.HeaderUtils;
import com.m3man.data.network.okhttp.MyProxySelector;
import com.m3man.exception.FavoriteException;
import com.m3man.exception.MessageException;
import com.m3man.parser.Parse91PornyVideo;
import com.m3man.parser.ParseProxy;
import com.m3man.parser.ParseV9MmanVideo;
import com.m3man.rxjava.RetryWhenProcess;
import com.m3man.utils.AddressHelper;
import me.jessyan.retrofiturlmanager.RetrofitUrlManager;
import com.m3man.utils.UserHelper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.schedulers.Schedulers;
import io.rx_cache2.DynamicKey;
import io.rx_cache2.DynamicKeyGroup;
import io.rx_cache2.EvictDynamicKey;
import io.rx_cache2.EvictDynamicKeyGroup;
import io.rx_cache2.EvictProvider;
import io.rx_cache2.Reply;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

/**
 * @author flymegoc
 * @date 2018/3/4
 */

@Singleton
public class AppApiHelper implements ApiHelper {

    private static final String TAG = AppApiHelper.class.getSimpleName();

//    private final static String CHECK_UPDATE_URL = "https://raw.githubusercontent.com/techGay/3mman/master/version.txt";
    private final static String CHECK_UPDATE_URL = "https://raw.githubusercontent.com/wacilimonster-source/3mman/master/version.txt";
    private final static String CHECK_NEW_NOTICE_URL = "https://raw.githubusercontent.com/wacilimonster-source/3mman/master/notice.txt";
    private final static String COMMON_QUESTIONS_URL = "https://raw.githubusercontent.com/wacilimonster-source/3mman/master/COMMON_QUESTION.md";
    private CacheProviders cacheProviders;

    private V9MmanServiceApi     v9MmanServiceApi;
    private GitHubServiceApi     gitHubServiceApi;
    private ProxyServiceApi      proxyServiceApi;
    private PornyServiceApi       pornyServiceApi;
    private AddressHelper        addressHelper;
    private MyProxySelector      myProxySelector;
    private Gson                 gson;
    private User                 user;

    @Inject
    public AppApiHelper(CacheProviders cacheProviders, V9MmanServiceApi v9MmanServiceApi, GitHubServiceApi gitHubServiceApi, ProxyServiceApi proxyServiceApi, PornyServiceApi pornyServiceApi, AddressHelper addressHelper, Gson gson, MyProxySelector myProxySelector, User user) {
        this.cacheProviders = cacheProviders;
        this.v9MmanServiceApi = v9MmanServiceApi;
        this.gitHubServiceApi = gitHubServiceApi;
        this.proxyServiceApi = proxyServiceApi;
        this.pornyServiceApi = pornyServiceApi;
        this.addressHelper = addressHelper;
        this.gson = gson;
        this.myProxySelector = myProxySelector;
        this.user = user;
    }

    @Override
    public Observable<List<V9MmanItem>> loadMman9VideoIndex(boolean cleanCache) {
        Observable<String> indexPhpObservable = v9MmanServiceApi.mman9VideoIndexPhp(HeaderUtils.getIndexHeader(addressHelper));
        return cacheProviders.getIndexPhp(indexPhpObservable, new EvictProvider(cleanCache))
                .map(responseBodyReply -> {
                    switch (responseBodyReply.getSource()) {
                        case CLOUD:
                            Logger.t(TAG).d("数据来自：网络");
                            break;
                        case MEMORY:
                            Logger.t(TAG).d("数据来自：内存");
                            break;
                        case PERSISTENCE:
                            Logger.t(TAG).d("数据来自：磁盘缓存");
                            break;
                        default:
                            break;
                    }
                    return responseBodyReply.getData();
                })
                .map(ParseV9MmanVideo::parseIndex);
    }

    @Override
    public Observable<BaseResult<List<V9MmanItem>>> loadMman9VideoByCategory(String category, String viewType, int page, String m, boolean cleanCache, boolean isLoadMoreCleanCache) {
        //RxCache条件区别
        String condition;
        if (TextUtils.isEmpty(m)) {
            condition = category;
        } else {
            condition = category + m;
        }
        DynamicKeyGroup dynamicKeyGroup = new DynamicKeyGroup(condition, page);
        EvictDynamicKey evictDynamicKey = new EvictDynamicKey(cleanCache || isLoadMoreCleanCache);

        Observable<String> categoryPage = v9MmanServiceApi.getCategoryPage(category, viewType, page, m, HeaderUtils.getIndexHeader(addressHelper));
        return cacheProviders.getCategoryPage(categoryPage, dynamicKeyGroup, evictDynamicKey)
                .map(Reply::getData)
                .map(ParseV9MmanVideo::parseByCategory);
    }

    @Override
    public Observable<BaseResult<List<V9MmanItem>>> loadMman9authorVideos(String uid, String type, int page, boolean cleanCache) {
        //RxCache条件区别
        String condition = null;
        if (!TextUtils.isEmpty(uid)) {
            condition = uid;
        }
        DynamicKeyGroup dynamicKeyGroup = new DynamicKeyGroup(condition, page);
        EvictDynamicKey evictDynamicKey = new EvictDynamicKey(cleanCache);

        Observable<String> stringObservable = v9MmanServiceApi.authorVideos(uid, type, page);
        return cacheProviders.authorVideos(stringObservable, dynamicKeyGroup, evictDynamicKey)
                .map(Reply::getData)
                .map(ParseV9MmanVideo::parseAuthorVideos);
    }

    @Override
    public Observable<BaseResult<List<V9MmanItem>>> loadMman9VideoRecentUpdates(String next, int page, boolean cleanCache, boolean isLoadMoreCleanCache) {

        DynamicKeyGroup dynamicKeyGroup = new DynamicKeyGroup(next, page);
        EvictDynamicKey evictDynamicKey = new EvictDynamicKey(cleanCache || isLoadMoreCleanCache);

        Observable<String> categoryPage = v9MmanServiceApi.recentUpdates(next, page, HeaderUtils.getIndexHeader(addressHelper));
        return cacheProviders.getRecentUpdates(categoryPage, dynamicKeyGroup, evictDynamicKey)
                .map(Reply::getData)
                .map(ParseV9MmanVideo::parseByCategory);
    }

    @Override
    public Observable<VideoResult> loadMman9VideoUrl(String viewKey) {

        String[] queryMap = viewKey.split("&");
        Map<String, String> viewKeyQuery = new LinkedHashMap<>(queryMap.length);
        for (String q : queryMap) {
            String[] keyValue = q.split("=");
            if (keyValue.length == 0) {
                continue;
            } else if (keyValue.length == 1) {
                viewKeyQuery.put(keyValue[0], "");
            } else {
                viewKeyQuery.put(keyValue[0], keyValue[1]);
            }
        }


        String ip = addressHelper.getRandomIPAddress();
        //因为登录后不在返回用户uid，需要在此页面获取，所以当前页面不在缓存，确保用户登录后刷新当前页面可以获取到用户uid
        return v9MmanServiceApi.getVideoPlayPage(viewKeyQuery, ip, HeaderUtils.getIndexHeader(addressHelper))
                .map(html -> ParseV9MmanVideo.parseVideoPlayUrl(html, user));
    }

    @Override
    public Observable<List<VideoComment>> loadMman9VideoComments(String videoId, int page, String viewKey) {
        return v9MmanServiceApi.getVideoComments(videoId, page, Constants.PORN9_VIDEO_COMMENT_PER_PAGE_NUM, HeaderUtils.getPlayVideoReferer(viewKey, addressHelper))
                .map(ParseV9MmanVideo::parseVideoComment);
    }

    @Override
    public Observable<String> commentMman9Video(String cpaintFunction, String comment, String uid, String vid, String viewKey, String responseType) {
        return v9MmanServiceApi.commentVideo(cpaintFunction, comment, uid, vid, responseType, HeaderUtils.getPlayVideoReferer(viewKey, addressHelper))
                .map(s -> new Gson().fromJson(s, VideoCommentResult.class))
                .map(videoCommentResult -> {
                    String msg = "评论错误，未知错误";
                    //M16：接口返回空/错误页时 gson 会产出 null 或 a 为 null，需先判空避免 NPE
                    if (videoCommentResult == null || videoCommentResult.getA() == null
                            || videoCommentResult.getA().isEmpty()) {
                        throw new MessageException("评论错误，未知错误");
                    } else if (videoCommentResult.getA().get(0).getData() == VideoCommentResult.COMMENT_SUCCESS) {
                        msg = "留言已经提交，审核后通过";
                    } else if (videoCommentResult.getA().get(0).getData() == VideoCommentResult.COMMENT_ALLREADY) {
                        throw new MessageException("你已经在这个视频下留言过");
                    } else if (videoCommentResult.getA().get(0).getData() == VideoCommentResult.COMMENT_NO_PERMISION) {
                        throw new MessageException("不允许留言!");
                    }
                    return msg;
                });
    }

    @Override
    public Observable<String> replyMman9VideoComment(String comment, String username, String vid, String commentId, String viewKey) {
        return v9MmanServiceApi.replyVideoComment(comment, username, vid, commentId, HeaderUtils.getPlayVideoReferer(viewKey, addressHelper));
    }

    @Override
    public Observable<BaseResult<List<V9MmanItem>>> searchMman9Videos(String viewType, int page, String searchType, String searchId, String sort) {
        return v9MmanServiceApi.searchVideo(viewType, page, searchType, searchId, sort, HeaderUtils.getIndexHeader(addressHelper), addressHelper.getRandomIPAddress())
                .map(ParseV9MmanVideo::parseSearchVideos);
    }

    @Override
    public Observable<BaseResult<List<V9MmanItem>>> searchPornyVideos(String keywords, int page) {
        return searchPornyVideos(keywords, page, "", "", "");
    }

    @Override
    public Observable<BaseResult<List<V9MmanItem>>> searchPornyVideos(String keywords, int page, String sort, String time, String views) {
        return pornyServiceApi.search(keywords, page, sort, time, views, HeaderUtils.getPornyHeader(addressHelper))
                .map(Parse91PornyVideo::parseSearchVideos);
    }

    @Override
    public Observable<VideoResult> loadPornyVideoUrl(String viewKey) {
        return pornyServiceApi.getVideoPlayPage(viewKey, HeaderUtils.getPornyHeader(addressHelper))
                .map(html -> Parse91PornyVideo.parseVideoPlayUrl(html, addressHelper.getPornyAddress()));
    }

    @Override
    public Observable<BaseResult<List<V9MmanItem>>> loadPornyAuthorVideos(String authorId, int page) {
        return pornyServiceApi.authorVideos(authorId, page, HeaderUtils.getPornyHeader(addressHelper))
                .map(Parse91PornyVideo::parseSearchVideos);
    }

    @Override
    public Observable<Boolean> testPornyAddress(String url) {
        RetrofitUrlManager.getInstance().putDomain(Api.PORNY_DOMAIN_NAME, url);
        return pornyServiceApi.search("test", 1, "", "", "", HeaderUtils.getPornyHeader(addressHelper))
                .map(s -> Parse91PornyVideo.parseSearchVideos(s).getData().size() > 0);
    }

    @Override
    public Observable<String> favoriteMman9Video(String uId, String videoId, String ownnerId) {
        String cpaintFunction = "addToFavorites";
        String responseType = "json";
        return v9MmanServiceApi.favoriteVideo(uId,videoId,ownnerId,HeaderUtils.getIndexHeader(addressHelper))
                .map(s -> {
                    Logger.t(TAG).d("favoriteStr: " + s);
                    int code;
                    try {
                        code = Integer.parseInt(s == null ? "" : s.trim());
                    } catch (NumberFormatException e) {
                        // 错误页 / CDN 异常会把非数字串（如 HTML、空串）塞进响应，直接拆包会崩
                        Logger.t(TAG).e("favoriteVideo parse fail, resp=" + s);
                        throw new FavoriteException("收藏失败，服务器返回异常");
                    }
                    String msg;
                    switch (code) {
                        case FavoriteJsonResult.FAVORITE_SUCCESS:
                            msg = "收藏成功";
                            break;
                        case FavoriteJsonResult.FAVORITE_FAIL:
                            throw new FavoriteException("收藏失败");
                        case FavoriteJsonResult.FAVORITE_YOURSELF:
                            throw new FavoriteException("不能收藏自己的视频");
                        default:
                            throw new FavoriteException("收藏失败");
                    }
                    return msg;
                });
//        return v9MmanServiceApi.favoriteVideo(cpaintFunction, uId, videoId, ownnerId, responseType, HeaderUtils.getIndexHeader(addressHelper))
//                .map(s -> {
//                    Logger.t(TAG).d("favoriteStr: " + s);
//                    return new Gson().fromJson(s, FavoriteJsonResult.class);
//                })
//                .map(favoriteJsonResult -> favoriteJsonResult.getAddFavMessage().get(0).getData())
//                .map(code -> {
//                    String msg;
//                    switch (code) {
//                        case FavoriteJsonResult.FAVORITE_SUCCESS:
//                            msg = "收藏成功";
//                            break;
//                        case FavoriteJsonResult.FAVORITE_FAIL:
//                            throw new FavoriteException("收藏失败");
//                        case FavoriteJsonResult.FAVORITE_YOURSELF:
//                            throw new FavoriteException("不能收藏自己的视频");
//                        default:
//                            throw new FavoriteException("收藏失败");
//                    }
//                    return msg;
//                });
    }

    @Override
    public Observable<BaseResult<List<V9MmanItem>>> loadMman9MyFavoriteVideos(String userName, int page, boolean cleanCache) {
        Observable<String> favoriteObservable = v9MmanServiceApi.myFavoriteVideo(page, HeaderUtils.getIndexHeader(addressHelper));
        DynamicKeyGroup dynamicKeyGroup = new DynamicKeyGroup(userName, page);
        EvictDynamicKey evictDynamicKey = new EvictDynamicKey(cleanCache);
        return cacheProviders.getFavorite(favoriteObservable, dynamicKeyGroup, evictDynamicKey)
                .map(Reply::getData)
                .map(ParseV9MmanVideo::parseMyFavorite);
    }

    @Override
    public Observable<List<V9MmanItem>> deleteMman9MyFavoriteVideo(String rvid) {
        String removeFavour = "Remove Favorite";
        String submit = "submit";
        return v9MmanServiceApi.deleteMyFavoriteVideo(rvid, removeFavour, submit, HeaderUtils.getFavHeader(addressHelper))
                .map(ParseV9MmanVideo::parseMyFavorite)
                .map(baseResult -> {
                    if (baseResult.getCode() == BaseResult.ERROR_CODE) {
                        throw new FavoriteException(baseResult.getMessage());
                    }
                    if (baseResult.getCode() != BaseResult.SUCCESS_CODE || TextUtils.isEmpty(baseResult.getMessage())) {
                        throw new FavoriteException("删除失败了");
                    }
                    return baseResult.getData();
                });
    }

    @Override
    public Observable<Bitmap> mman9VideoLoginCaptcha() {
        return v9MmanServiceApi.captcha().map(responseBody -> BitmapFactory.decodeStream(responseBody.byteStream()));
    }

    @Override
    public Observable<User> userLoginMman9Video(String username, String password, String captcha) {

        String fingerprint = UserHelper.randomFingerprint();
        String fingerprint2 = UserHelper.randomFingerprint2();
        String actionLogin = "Log In";
        String x = "47";
        String y = "12";
        return v9MmanServiceApi.login(username, password, fingerprint, fingerprint2, captcha, actionLogin, x, y, HeaderUtils.getUserHeader(addressHelper, "login"))
                .retryWhen(new RetryWhenProcess(2))
                .map(s -> {
                    if (!UserHelper.isMmanVideoLoginSuccess(s)) {
                        String errorInfo = ParseV9MmanVideo.parseErrorInfo(s);
                        if (TextUtils.isEmpty(errorInfo)) {
                            errorInfo = "未知错误，请确认地址是否正确";
                        }
                        throw new MessageException(errorInfo);
                    }
                    return ParseV9MmanVideo.parseUserInfo(s);
                });
    }

    @Override
    public Observable<User> userRegisterMman9Video(String username, String password1, String password2, String email, String captchaInput) {
        String next = "";
//        String fingerprint = "2192328486";
        String fingerprint = UserHelper.randomFingerprint();
        String vip = "";
        String actionSignUp = "Sign Up";
        String submitX = "45";
        String submitY = "13";
        String ipAddress = addressHelper.getRandomIPAddress();
        return v9MmanServiceApi.register(next, username, password1, password2, email, captchaInput, fingerprint, vip, actionSignUp, submitX, submitY, HeaderUtils.getUserHeader(addressHelper, "signup"), ipAddress)
                .retryWhen(new RetryWhenProcess(2))
                .map(s -> {
                    if (!UserHelper.isMmanVideoLoginSuccess(s)) {
                        String errorInfo = ParseV9MmanVideo.parseErrorInfo(s);
                        throw new MessageException(errorInfo);
                    }
                    return ParseV9MmanVideo.parseUserInfo(s);
                });
    }

    @Override
    public Observable<UpdateVersion> checkUpdate() {
        return gitHubServiceApi.checkUpdate(CHECK_UPDATE_URL)
                .map(s -> {
                    // M10：version.txt 为空 / 被 CDN 错误页替换时，gson 会返回 null，
                    // 直接解引用会 NPE；此处提前拦截并抛出明确异常走 onError。
                    if (TextUtils.isEmpty(s)) {
                        throw new MessageException("检查更新失败：返回内容为空");
                    }
                    UpdateVersion version = gson.fromJson(s, UpdateVersion.class);
                    if (version == null) {
                        throw new MessageException("检查更新失败：返回内容解析失败");
                    }
                    return version;
                });
    }

    @Override
    public Observable<Notice> checkNewNotice() {
        return gitHubServiceApi.checkNewNotice(CHECK_NEW_NOTICE_URL)
                .map(s -> {
                    if (TextUtils.isEmpty(s)) {
                        throw new MessageException("检查公告失败：返回内容为空");
                    }
                    Notice notice = gson.fromJson(s, Notice.class);
                    if (notice == null) {
                        throw new MessageException("检查公告失败：返回内容解析失败");
                    }
                    return notice;
                });
    }

    @Override
    public Observable<String> commonQuestions() {
        return gitHubServiceApi.commonQuestions(COMMON_QUESTIONS_URL);
    }

    @Override
    public Observable<BaseResult<List<ProxyModel>>> loadXiCiDaiLiProxyData(final int page) {
        return proxyServiceApi.proxyXiciDaili(page)
                .map(s -> ParseProxy.parseXiCiDaiLi(s, page));
    }

    @Override
    public Observable<Boolean> testProxy(String proxyIpAddress, int proxyPort) {
        myProxySelector.setTest(true, proxyIpAddress, proxyPort);
        return v9MmanServiceApi.mman9VideoIndexPhp(HeaderUtils.getIndexHeader(addressHelper))
                .map(s -> {
                    List<V9MmanItem> list = ParseV9MmanVideo.parseIndex(s);
                    return list.size() != 0;
                })
                //M15：无论成功/失败/被取消，都必须退出测试态，
                //否则残留的测试代理会劫持后续所有请求（含检查更新）
                .doFinally(myProxySelector::clearTest);
    }

    @Override
    public void existProxyTest() {
        myProxySelector.setTest(false, null, 0);
    }

    @Override
    public Observable<Boolean> testMman9VideoAddress() {
        return v9MmanServiceApi.mman9VideoIndexPhp(HeaderUtils.getIndexHeader(addressHelper))
                .map(s -> {
                    List<V9MmanItem> list = ParseV9MmanVideo.parseIndex(s);
                    return list.size() != 0;
                });
    }

    @Override
    public Observable<Response<ResponseBody>> testV9Mman(String url) {
        return v9MmanServiceApi.testV9Mman(url);
    }

    @Override
    public Observable<Response<ResponseBody>> verifyGoogleRecaptcha(String action, String r, String id, String recaptcha) {
        return v9MmanServiceApi.verifyGoogleRecaptcha(action, r, id, recaptcha);
    }

}
