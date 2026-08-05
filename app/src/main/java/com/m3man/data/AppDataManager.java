package com.m3man.data;

import android.graphics.Bitmap;
import android.text.TextUtils;

import com.danikula.videocache.HttpProxyCacheServer;
import com.m3man.cookie.CookieManager;
import com.m3man.data.db.DbHelper;
import com.m3man.data.db.entity.AuthorFavorite;
import com.m3man.data.db.entity.Category;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.VideoResult;
import com.m3man.data.model.BaseResult;
import com.m3man.data.model.Notice;
import com.m3man.data.model.ProxyModel;
import com.m3man.data.model.UpdateVersion;
import com.m3man.data.model.User;
import com.m3man.data.model.VideoComment;
import com.m3man.data.model.axgle.AxgleResponse;
import com.m3man.data.model.kedouwo.KeDouModel;
import com.m3man.data.model.kedouwo.KeDouRelated;
import com.m3man.data.model.pxgav.PxgavResultWithBlockId;
import com.m3man.data.model.pxgav.PxgavVideoParserJsonResult;
import com.m3man.data.network.ApiHelper;
import com.m3man.data.prefs.PreferencesHelper;
import com.m3man.utils.UserHelper;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.Observable;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

/**
 * @author flymegoc
 * @date 2017/11/22
 * @describe
 */

@Singleton
public class AppDataManager implements DataManager {

    private final DbHelper mDbHelper;
    private final PreferencesHelper mPreferencesHelper;
    private final ApiHelper mApiHelper;

    private final HttpProxyCacheServer httpProxyCacheServer;

    private CookieManager cookieManager;
    private User user;

    @Inject
    AppDataManager(DbHelper mDbHelper, PreferencesHelper mPreferencesHelper, ApiHelper mApiHelper, HttpProxyCacheServer httpProxyCacheServer, CookieManager cookieManager, User user) {
        this.mDbHelper = mDbHelper;
        this.mPreferencesHelper = mPreferencesHelper;
        this.mApiHelper = mApiHelper;
        this.httpProxyCacheServer = httpProxyCacheServer;
        this.cookieManager = cookieManager;
        this.user = user;
        // 启动时从已保存的登录账号恢复登录态：会话 cookie 由 cookie 持久化保持，
        // 这里仅恢复用户名展示，使“我的”页与收藏功能在重启后仍能识别已登录用户
        String savedLoginUserName = mPreferencesHelper.getMman9VideoLoginUserName();
        if (!TextUtils.isEmpty(savedLoginUserName)) {
            user.setUserName(savedLoginUserName);
            user.setLogin(true);
        }
    }

    @Override
    public void initCategory(int type, String[] value, String[] name) {
        mDbHelper.initCategory(type, value, name);
    }

    @Override
    public void updateV9MmanItem(V9MmanItem v9MmanItem) {
        mDbHelper.updateV9MmanItem(v9MmanItem);
    }

    @Override
    public List<V9MmanItem> loadDownloadingData() {
        return mDbHelper.loadDownloadingData();
    }

    @Override
    public List<V9MmanItem> loadFinishedData() {
        return mDbHelper.loadFinishedData();
    }

    @Override
    public List<V9MmanItem> loadHistoryData(int page, int pageSize) {
        return mDbHelper.loadHistoryData(page, pageSize);
    }

    @Override
    public long saveV9MmanItem(V9MmanItem v9MmanItem) {
        return mDbHelper.saveV9MmanItem(v9MmanItem);
    }

    @Override
    public long saveVideoResult(VideoResult videoResult) {
        return mDbHelper.saveVideoResult(videoResult);
    }

    @Override
    public V9MmanItem findV9MmanItemByViewKey(String viewKey) {
        return mDbHelper.findV9MmanItemByViewKey(viewKey);
    }

    @Override
    public V9MmanItem findV9MmanItemByDownloadId(int downloadId) {
        return mDbHelper.findV9MmanItemByDownloadId(downloadId);
    }

    @Override
    public List<V9MmanItem> loadV9MmanItems() {
        return mDbHelper.loadV9MmanItems();
    }

    @Override
    public List<V9MmanItem> loadLocalFavoriteItems() {
        return mDbHelper.loadLocalFavoriteItems();
    }

    @Override
    public List<V9MmanItem> findV9MmanItemByDownloadStatus(int status) {
        return mDbHelper.findV9MmanItemByDownloadStatus(status);
    }

    @Override
    public List<Category> loadAllCategoryDataByType(int type) {
        return mDbHelper.loadAllCategoryDataByType(type);
    }

    @Override
    public List<Category> loadCategoryDataByType(int type) {
        return mDbHelper.loadCategoryDataByType(type);
    }

    @Override
    public void updateCategoryData(List<Category> categoryList) {
        mDbHelper.updateCategoryData(categoryList);
    }

    @Override
    public Category findCategoryById(Long id) {
        return mDbHelper.findCategoryById(id);
    }

    @Override
    public Observable<List<V9MmanItem>> loadMman9VideoIndex(boolean cleanCache) {
        return mApiHelper.loadMman9VideoIndex(cleanCache);
    }

    @Override
    public Observable<BaseResult<List<V9MmanItem>>> loadMman9VideoByCategory(String category, String viewType, int page, String m, boolean cleanCache, boolean isLoadMoreCleanCache) {
        return mApiHelper.loadMman9VideoByCategory(category, viewType, page, m, cleanCache, isLoadMoreCleanCache);
    }

    @Override
    public Observable<BaseResult<List<V9MmanItem>>> loadMman9authorVideos(String uid, String type, int page, boolean cleanCache) {
        return mApiHelper.loadMman9authorVideos(uid, type, page, cleanCache);
    }

    @Override
    public Observable<BaseResult<List<V9MmanItem>>> loadMman9VideoRecentUpdates(String next, int page, boolean cleanCache, boolean isLoadMoreCleanCache) {
        return mApiHelper.loadMman9VideoRecentUpdates(next, page, cleanCache, isLoadMoreCleanCache);
    }

    @Override
    public Observable<VideoResult> loadMman9VideoUrl(String viewKey) {
        return mApiHelper.loadMman9VideoUrl(viewKey);
    }

    @Override
    public Observable<List<VideoComment>> loadMman9VideoComments(String videoId, int page, String viewKey) {
        return mApiHelper.loadMman9VideoComments(videoId, page, viewKey);
    }

    @Override
    public Observable<String> commentMman9Video(String cpaintFunction, String comment, String uid, String vid, String viewKey, String responseType) {
        return mApiHelper.commentMman9Video(cpaintFunction, comment, uid, vid, viewKey, responseType);
    }

    @Override
    public Observable<String> replyMman9VideoComment(String comment, String username, String vid, String commentId, String viewKey) {
        return mApiHelper.replyMman9VideoComment(comment, username, vid, commentId, viewKey);
    }

    @Override
    public Observable<BaseResult<List<V9MmanItem>>> searchMman9Videos(String viewType, int page, String searchType, String searchId, String sort) {
        return mApiHelper.searchMman9Videos(viewType, page, searchType, searchId, sort);
    }

    @Override
    public Observable<BaseResult<List<V9MmanItem>>> searchPornyVideos(String keywords, int page) {
        return mApiHelper.searchPornyVideos(keywords, page);
    }

    @Override
    public Observable<BaseResult<List<V9MmanItem>>> searchPornyVideos(String keywords, int page, String sort, String time, String views) {
        return mApiHelper.searchPornyVideos(keywords, page, sort, time, views);
    }

    @Override
    public Observable<VideoResult> loadPornyVideoUrl(String viewKey) {
        return mApiHelper.loadPornyVideoUrl(viewKey);
    }

    @Override
    public Observable<BaseResult<List<V9MmanItem>>> loadPornyAuthorVideos(String authorId, int page) {
        return mApiHelper.loadPornyAuthorVideos(authorId, page);
    }

    @Override
    public Observable<String> favoriteMman9Video(String uId, String videoId, String ownnerId) {
        return mApiHelper.favoriteMman9Video(uId, videoId, ownnerId);
    }

    @Override
    public Observable<BaseResult<List<V9MmanItem>>> loadMman9MyFavoriteVideos(String userName, int page, boolean cleanCache) {
        return mApiHelper.loadMman9MyFavoriteVideos(userName, page, cleanCache);
    }

    @Override
    public Observable<List<V9MmanItem>> deleteMman9MyFavoriteVideo(String rvid) {
        return mApiHelper.deleteMman9MyFavoriteVideo(rvid);
    }

    @Override
    public Observable<Bitmap> mman9VideoLoginCaptcha() {
        return mApiHelper.mman9VideoLoginCaptcha();
    }

    @Override
    public Observable<User> userLoginMman9Video(String username, String password, String captcha) {
        return mApiHelper.userLoginMman9Video(username, password, captcha);
    }

    @Override
    public Observable<User> userRegisterMman9Video(String username, String password1, String password2, String email, String captchaInput) {
        return mApiHelper.userRegisterMman9Video(username, password1, password2, email, captchaInput);
    }

    @Override
    public Observable<UpdateVersion> checkUpdate() {
        return mApiHelper.checkUpdate();
    }

    @Override
    public Observable<Notice> checkNewNotice() {
        return mApiHelper.checkNewNotice();
    }

    @Override
    public Observable<String> commonQuestions() {
        return mApiHelper.commonQuestions();
    }

    @Override
    public Observable<PxgavResultWithBlockId> loadPxgavListByCategory(String category, boolean pullToRefresh) {
        return mApiHelper.loadPxgavListByCategory(category, pullToRefresh);
    }

    @Override
    public Observable<PxgavResultWithBlockId> loadMorePxgavListByCategory(String category, int page, String lastBlockId, boolean pullToRefresh) {
        return mApiHelper.loadMorePxgavListByCategory(category, page, lastBlockId, pullToRefresh);
    }

    @Override
    public Observable<PxgavVideoParserJsonResult> loadPxgavVideoUrl(String url, String pId, boolean pullToRefresh) {
        return mApiHelper.loadPxgavVideoUrl(url, pId, pullToRefresh);
    }

    @Override
    public Observable<BaseResult<List<ProxyModel>>> loadXiCiDaiLiProxyData(int page) {
        return mApiHelper.loadXiCiDaiLiProxyData(page);
    }

    @Override
    public Observable<Boolean> testProxy(String proxyIpAddress, int proxyPort) {
        return mApiHelper.testProxy(proxyIpAddress, proxyPort);
    }

    @Override
    public void setMman9VideoAddress(String address) {
        mPreferencesHelper.setMman9VideoAddress(address);
    }

    @Override
    public String getMman9VideoAddress() {
        return mPreferencesHelper.getMman9VideoAddress();
    }

    @Override
    public void setMman9ProxyCookie(String cookie) {
        mPreferencesHelper.setMman9ProxyCookie(cookie);
    }

    @Override
    public String getMman9ProxyCookie() {
        return mPreferencesHelper.getMman9ProxyCookie();
    }

    @Override
    public void setPavAddress(String address) {
        mPreferencesHelper.setPavAddress(address);
    }

    @Override
    public String getPavAddress() {
        return mPreferencesHelper.getPavAddress();
    }

    @Override
    public void setMman9VideoLoginUserName(String userName) {
        mPreferencesHelper.setMman9VideoLoginUserName(userName);
    }

    @Override
    public String getMman9VideoLoginUserName() {
        return mPreferencesHelper.getMman9VideoLoginUserName();
    }

    @Override
    public void setMman9VideoLoginUserPassWord(String passWord) {
        mPreferencesHelper.setMman9VideoLoginUserPassWord(passWord);
    }

    @Override
    public String getMman9VideoLoginUserPassword() {
        return mPreferencesHelper.getMman9VideoLoginUserPassword();
    }

    @Override
    public void setMman9VideoUserAutoLogin(boolean autoLogin) {
        mPreferencesHelper.setMman9VideoUserAutoLogin(autoLogin);
    }

    @Override
    public boolean isMman9VideoUserAutoLogin() {
        return mPreferencesHelper.isMman9VideoUserAutoLogin();
    }

    @Override
    public void setFavoriteNeedRefresh(boolean needRefresh) {
        mPreferencesHelper.setFavoriteNeedRefresh(needRefresh);
    }

    @Override
    public boolean isFavoriteNeedRefresh() {
        return mPreferencesHelper.isFavoriteNeedRefresh();
    }

    @Override
    public void setPlaybackEngine(int playbackEngine) {
        mPreferencesHelper.setPlaybackEngine(playbackEngine);
    }

    @Override
    public int getPlaybackEngine() {
        return mPreferencesHelper.getPlaybackEngine();
    }

    @Override
    public void setFirstInSearchMman91Video(boolean firstInSearchMman91Video) {
        mPreferencesHelper.setFirstInSearchMman91Video(firstInSearchMman91Video);
    }

    @Override
    public boolean isFirstInSearchMman91Video() {
        return mPreferencesHelper.isFirstInSearchMman91Video();
    }

    @Override
    public void setDownloadVideoNeedWifi(boolean downloadVideoNeedWifi) {
        mPreferencesHelper.setDownloadVideoNeedWifi(downloadVideoNeedWifi);
    }

    @Override
    public boolean isDownloadVideoNeedWifi() {
        return mPreferencesHelper.isDownloadVideoNeedWifi();
    }

    @Override
    public void setOpenHttpProxy(boolean openHttpProxy) {
        mPreferencesHelper.setOpenHttpProxy(openHttpProxy);
    }

    @Override
    public boolean isOpenHttpProxy() {
        return mPreferencesHelper.isOpenHttpProxy();
    }

    @Override
    public void setOpenNightMode(boolean openNightMode) {
        mPreferencesHelper.setOpenNightMode(openNightMode);
    }

    @Override
    public boolean isOpenNightMode() {
        return mPreferencesHelper.isOpenNightMode();
    }

    @Override
    public void setProxyIpAddress(String proxyIpAddress) {
        mPreferencesHelper.setProxyIpAddress(proxyIpAddress);
    }

    @Override
    public String getProxyIpAddress() {
        return mPreferencesHelper.getProxyIpAddress();
    }

    @Override
    public void setProxyPort(int port) {
        mPreferencesHelper.setProxyPort(port);
    }

    @Override
    public int getProxyPort() {
        return mPreferencesHelper.getProxyPort();
    }

    @Override
    public void setIgnoreUpdateVersionCode(int versionCode) {
        mPreferencesHelper.setIgnoreUpdateVersionCode(versionCode);
    }

    @Override
    public int getIgnoreUpdateVersionCode() {
        return mPreferencesHelper.getIgnoreUpdateVersionCode();
    }

    @Override
    public void setForbiddenAutoReleaseMemory(boolean autoReleaseMemory) {
        mPreferencesHelper.setForbiddenAutoReleaseMemory(autoReleaseMemory);
    }

    @Override
    public boolean isForbiddenAutoReleaseMemory() {
        return mPreferencesHelper.isForbiddenAutoReleaseMemory();
    }

    @Override
    public void setNoticeVersionCode(int noticeVersionCode) {
        mPreferencesHelper.setNoticeVersionCode(noticeVersionCode);
    }

    @Override
    public int getNoticeVersionCode() {
        return mPreferencesHelper.getNoticeVersionCode();
    }

    @Override
    public void setMainFirstTabShow(String firstTabShow) {
        mPreferencesHelper.setMainFirstTabShow(firstTabShow);
    }

    @Override
    public String getMainFirstTabShow() {
        return mPreferencesHelper.getMainFirstTabShow();
    }

    @Override
    public void setMainSecondTabShow(String secondTabShow) {
        mPreferencesHelper.setMainSecondTabShow(secondTabShow);
    }

    @Override
    public String getMainSecondTabShow() {
        return mPreferencesHelper.getMainSecondTabShow();
    }

    @Override
    public void setSettingScrollViewScrollPosition(int position) {
        mPreferencesHelper.setSettingScrollViewScrollPosition(position);
    }

    @Override
    public int getSettingScrollViewScrollPosition() {
        return mPreferencesHelper.getSettingScrollViewScrollPosition();
    }

    @Override
    public void setOpenSkipPage(boolean openSkipPage) {
        mPreferencesHelper.setOpenSkipPage(openSkipPage);
    }

    @Override
    public boolean isOpenSkipPage() {
        return mPreferencesHelper.isOpenSkipPage();
    }

    @Override
    public void setCustomDownloadVideoDirPath(String customDirPath) {
        mPreferencesHelper.setCustomDownloadVideoDirPath(customDirPath);
    }

    @Override
    public String getCustomDownloadVideoDirPath() {
        return mPreferencesHelper.getCustomDownloadVideoDirPath();
    }

    @Override
    public boolean isShowUrlRedirectTipDialog() {
        return mPreferencesHelper.isShowUrlRedirectTipDialog();
    }

    @Override
    public void setShowUrlRedirectTipDialog(boolean showUrlRedirectTipDialog) {
        mPreferencesHelper.setShowUrlRedirectTipDialog(showUrlRedirectTipDialog);
    }

    @Override
    public void setAxgleAddress(String address) {
        mPreferencesHelper.setAxgleAddress(address);
    }

    @Override
    public String getAxgleAddress() {
        return mPreferencesHelper.getAxgleAddress();
    }

    @Override
    public void setKeDouWoAddress(String address) {
        mPreferencesHelper.setKeDouWoAddress(address);
    }

    @Override
    public String getKeDouWoAddress() {
        return mPreferencesHelper.getKeDouWoAddress();
    }

    @Override
    public void setPornyAddress(String address) {
        mPreferencesHelper.setPornyAddress(address);
    }

    @Override
    public String getPornyAddress() {
        return mPreferencesHelper.getPornyAddress();
    }

    @Override
    public void setPornyEnabled(boolean enabled) {
        mPreferencesHelper.setPornyEnabled(enabled);
    }

    @Override
    public boolean isPornyEnabled() {
        return mPreferencesHelper.isPornyEnabled();
    }

    @Override
    public Observable<Boolean> testPornyAddress(String url) {
        return mApiHelper.testPornyAddress(url);
    }

    @Override
    public boolean isFixMainNavigation() {
        return mPreferencesHelper.isFixMainNavigation();
    }

    @Override
    public void setFixMainNavigation(boolean fixMainNavigation) {
        mPreferencesHelper.setFixMainNavigation(fixMainNavigation);
    }

    @Override
    public void existProxyTest() {
        mApiHelper.existProxyTest();
    }

    @Override
    public Observable<Boolean> testMman9VideoAddress() {
        return mApiHelper.testMman9VideoAddress();
    }

    @Override
    public Observable<Boolean> testPavAddress(String url) {
        return mApiHelper.testPavAddress(url);
    }

    @Override
    public Observable<Boolean> testAxgle() {
        return mApiHelper.testAxgle();
    }

    @Override
    public Observable<AxgleResponse> axgleVideos(int page, String o, String t, String type, String c, int limit) {
        return mApiHelper.axgleVideos(page, o, t, type, c, limit);
    }

    @Override
    public Observable<AxgleResponse> searchAxgleVideo(String keyWord, int page) {
        return mApiHelper.searchAxgleVideo(keyWord, page);
    }

    @Override
    public Observable<AxgleResponse> searchAxgleJavVideo(String keyWord, int page) {
        return mApiHelper.searchAxgleJavVideo(keyWord, page);
    }

    @Override
    public Call<ResponseBody> getPlayVideoUrl(String url) {
        return mApiHelper.getPlayVideoUrl(url);
    }

    @Override
    public Observable<List<KeDouModel>> videoList(String category, int page,boolean pullToRefresh) {
        return mApiHelper.videoList(category,page,pullToRefresh);
    }

    @Override
    public Observable<List<KeDouModel>> videoListLatest(int page) {
        return mApiHelper.videoListLatest(page);
    }

    @Override
    public Observable<List<KeDouModel>> videoListTop(int page) {
        return mApiHelper.videoListTop(page);
    }

    @Override
    public Observable<List<KeDouModel>> videoListPopular(int page) {
        return mApiHelper.videoListPopular(page);
    }

    @Override
    public Observable<KeDouRelated> videoRelated(String url) {
        return mApiHelper.videoRelated(url);
    }

    @Override
    public Observable<String> getRealVideoUrl(String url) {
        return mApiHelper.getRealVideoUrl(url);
    }

    @Override
    public Observable<Response<ResponseBody>> testV9Mman(String url) {
        return mApiHelper.testV9Mman(url);
    }

    @Override
    public Observable<Response<ResponseBody>> verifyGoogleRecaptcha(String action, String r, String id, String recaptcha) {
        return mApiHelper.verifyGoogleRecaptcha(action, r, id, recaptcha);
    }

    @Override
    public String getVideoCacheProxyUrl(String originalVideoUrl) {
        return httpProxyCacheServer.getProxyUrl(originalVideoUrl, true);
    }

    @Override
    public boolean isVideoCacheByProxy(String originalVideoUrl) {
        return httpProxyCacheServer.isCached(originalVideoUrl);
    }

    @Override
    public void existLogin() {
        cookieManager.cleanAllCookies();
        user.cleanProperties();
    }

    @Override
    public void resetMman91VideoWatchTime(boolean reset) {
        cookieManager.resetMman91VideoWatchTime(reset);
    }

    @Override
    public void resetKeDouWoVideoWatchTime() {
        cookieManager.resetKeDouWoVideoWatchTime();
    }

    @Override
    public User getUser() {
        return user;
    }

    @Override
    public boolean isUserLogin() {
        return UserHelper.isUserInfoComplete(user);
    }

    @Override
    public List<AuthorFavorite> loadAuthorFavorites() {
        return mDbHelper.loadAuthorFavorites();
    }

    @Override
    public AuthorFavorite findAuthorFavorite(String authorKey, String source) {
        return mDbHelper.findAuthorFavorite(authorKey, source);
    }

    @Override
    public long saveAuthorFavorite(AuthorFavorite authorFavorite) {
        return mDbHelper.saveAuthorFavorite(authorFavorite);
    }

    @Override
    public void deleteAuthorFavorite(AuthorFavorite authorFavorite) {
        mDbHelper.deleteAuthorFavorite(authorFavorite);
    }

    @Override
    public boolean isAuthorFavorited(String authorKey, String source) {
        return mDbHelper.isAuthorFavorited(authorKey, source);
    }
}
