package com.m3man.data.network;

import android.graphics.Bitmap;

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

import java.util.List;

import io.reactivex.Observable;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

/**
 * @author flymegoc
 * @date 2018/3/4
 */

public interface ApiHelper {
    Observable<List<V9MmanItem>> loadMman9VideoIndex(boolean cleanCache);

    Observable<BaseResult<List<V9MmanItem>>> loadMman9VideoByCategory(String category, String viewType, int page, String m, boolean cleanCache, boolean isLoadMoreCleanCache);

    Observable<BaseResult<List<V9MmanItem>>> loadMman9authorVideos(String uid, String type, int page, boolean cleanCache);

    Observable<BaseResult<List<V9MmanItem>>> loadMman9VideoRecentUpdates(String next, int page, boolean cleanCache, boolean isLoadMoreCleanCache);

    Observable<VideoResult> loadMman9VideoUrl(String viewKey);

    Observable<List<VideoComment>> loadMman9VideoComments(String videoId, int page, String viewKey);

    Observable<String> commentMman9Video(String cpaintFunction, String comment, String uid, String vid, String viewKey, String responseType);

    Observable<String> replyMman9VideoComment(String comment, String username, String vid, String commentId, String viewKey);

    Observable<BaseResult<List<V9MmanItem>>> searchMman9Videos(String viewType, int page, String searchType, String searchId, String sort);

    Observable<BaseResult<List<V9MmanItem>>> searchPornyVideos(String keywords, int page);

    Observable<BaseResult<List<V9MmanItem>>> searchPornyVideos(String keywords, int page, String sort, String time, String views);

    Observable<VideoResult> loadPornyVideoUrl(String viewKey);

    Observable<BaseResult<List<V9MmanItem>>> loadPornyAuthorVideos(String authorId, int page);

    Observable<Boolean> testPornyAddress(String url);

    Observable<String> favoriteMman9Video(String uId, String videoId, String ownnerId);

    Observable<BaseResult<List<V9MmanItem>>> loadMman9MyFavoriteVideos(String userName, int page, boolean cleanCache);

    Observable<List<V9MmanItem>> deleteMman9MyFavoriteVideo(String rvid);

    Observable<Bitmap> mman9VideoLoginCaptcha();

    Observable<User> userLoginMman9Video(String username, String password, String captcha);

    Observable<User> userRegisterMman9Video(String username, String password1, String password2, String email, String captchaInput);

    Observable<UpdateVersion> checkUpdate();

    Observable<Notice> checkNewNotice();

    Observable<String> commonQuestions();






    Observable<PxgavResultWithBlockId> loadPxgavListByCategory(String category, boolean pullToRefresh);

    Observable<PxgavResultWithBlockId> loadMorePxgavListByCategory(String category, int page, String lastBlockId, boolean pullToRefresh);

    Observable<PxgavVideoParserJsonResult> loadPxgavVideoUrl(String url, String pId, boolean pullToRefresh);

    Observable<BaseResult<List<ProxyModel>>> loadXiCiDaiLiProxyData(int page);

    Observable<Boolean> testProxy(String proxyIpAddress, int proxyPort);

    void existProxyTest();

    Observable<Boolean> testMman9VideoAddress();


    Observable<Boolean> testPavAddress(String url);

    Observable<Boolean> testAxgle();


    Observable<AxgleResponse> axgleVideos(int page, String o, String t, String type, String c, int limit);

    Observable<AxgleResponse> searchAxgleVideo(String keyWord, int page);

    Observable<AxgleResponse> searchAxgleJavVideo(String keyWord, int page);

    Call<ResponseBody> getPlayVideoUrl(String url);

    Observable<List<KeDouModel>> videoList(String category,int page,boolean pullToRefresh);

    Observable<List<KeDouModel>> videoListLatest(int page);

    Observable<List<KeDouModel>> videoListTop(int page);

    Observable<List<KeDouModel>> videoListPopular(int page);

    Observable<KeDouRelated> videoRelated(String url);

    Observable<String> getRealVideoUrl(String url);

    Observable<Response<ResponseBody>> testV9Mman(String url);

    Observable<Response<ResponseBody>> verifyGoogleRecaptcha(String action, String r, String id, String recaptcha);
}
