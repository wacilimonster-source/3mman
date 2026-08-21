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






    Observable<BaseResult<List<ProxyModel>>> loadXiCiDaiLiProxyData(int page);

    Observable<Boolean> testProxy(String proxyIpAddress, int proxyPort);

    void existProxyTest();

    Observable<Boolean> testMman9VideoAddress();

    Observable<Response<ResponseBody>> testV9Mman(String url);
}
