package com.m3man.ui.mman9video.play;

import android.arch.lifecycle.Lifecycle;
import android.text.TextUtils;
import android.webkit.WebView;

import com.hannesdorfmann.mosby3.mvp.MvpBasePresenter;
import com.orhanobut.logger.Logger;
import com.sdsmdg.tastytoast.TastyToast;
import com.trello.rxlifecycle2.LifecycleProvider;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.AuthorFavorite;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.VideoResult;
import com.m3man.data.model.User;
import com.m3man.exception.VideoException;
import com.m3man.parser.Parse91PornyVideo;
import com.m3man.rxjava.CallBackWrapper;
import com.m3man.rxjava.RetryWhenProcess;
import com.m3man.rxjava.RxSchedulersHelper;
import com.m3man.ui.download.DownloadPresenter;
import com.m3man.ui.mman9video.favorite.FavoritePresenter;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.Date;

import javax.inject.Inject;

import io.reactivex.disposables.Disposable;

/**
 * @author flymegoc
 * @date 2017/11/15
 * @describe play
 */
public class PlayVideoPresenter extends MvpBasePresenter<PlayVideoView> implements IPlay {

    private static final String TAG = PlayVideoPresenter.class.getSimpleName();

    private FavoritePresenter favoritePresenter;
    private DownloadPresenter downloadPresenter;

    private LifecycleProvider<Lifecycle.Event> provider;

    private DataManager dataManager;

//    private final WebView webView;

    @Inject
    public PlayVideoPresenter(FavoritePresenter favoritePresenter, DownloadPresenter downloadPresenter, LifecycleProvider<Lifecycle.Event> provider, DataManager dataManager) {
        this.favoritePresenter = favoritePresenter;
        this.downloadPresenter = downloadPresenter;
        this.provider = provider;
        this.dataManager = dataManager;
    }

    @Override
    public void loadVideoUrl(final V9MmanItem v9MmanItem) {
        String viewKey = v9MmanItem.getViewKey();
        if (isPornySource(v9MmanItem)) {
            // 91porny 来源走单独的解析路径
            dataManager.loadPornyVideoUrl(viewKey)
                    .map(videoResult -> {
                        if (TextUtils.isEmpty(videoResult.getVideoUrl())) {
                            throw new VideoException("解析视频链接失败了");
                        }
                        return videoResult;
                    })
                    .retryWhen(new RetryWhenProcess(RetryWhenProcess.PROCESS_TIME))
                    .compose(RxSchedulersHelper.ioMainThread())
                    .compose(provider.bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                    .subscribe(new CallBackWrapper<VideoResult>() {
                        @Override
                        public void onBegin(Disposable d) {
                            ifViewAttached(PlayVideoView::showParsingDialog);
                        }

                        @Override
                        public void onSuccess(final VideoResult videoResult) {
                            ifViewAttached(view -> view.parseVideoUrlSuccess(saveVideoUrl(videoResult, v9MmanItem)));
                        }

                        @Override
                        public void onError(final String msg, int code) {
                            ifViewAttached(view -> view.errorParseVideoUrl(msg));
                        }
                    });
            return;
        }
        dataManager.loadMman9VideoUrl(viewKey)
                .map(videoResult -> {
                    if (TextUtils.isEmpty(videoResult.getVideoUrl())) {
                        if (VideoResult.OUT_OF_WATCH_TIMES.equals(videoResult.getId())) {
                            //尝试强行重置，并上报异常
                            dataManager.resetMman91VideoWatchTime(true);
                            // Bugsnag.notify(new Throwable(TAG + "Ten videos each day address: " + dataManager.getMman9VideoAddress()), Severity.WARNING);
                            throw new VideoException("观看次数达到上限了,请更换地址或者代理服务器！");
                        } else {
                            throw new VideoException("解析视频链接失败了");
                        }
                    }
                    return videoResult;
                })
                .retryWhen(new RetryWhenProcess(RetryWhenProcess.PROCESS_TIME))
                .compose(RxSchedulersHelper.ioMainThread())
                .compose(provider.bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<VideoResult>() {
                    @Override
                    public void onBegin(Disposable d) {
                        ifViewAttached(PlayVideoView::showParsingDialog);
                    }

                    @Override
                    public void onSuccess(final VideoResult videoResult) {
                        dataManager.resetMman91VideoWatchTime(false);
                        ifViewAttached(view -> view.parseVideoUrlSuccess(saveVideoUrl(videoResult, v9MmanItem)));
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        ifViewAttached(view -> view.errorParseVideoUrl(msg));
                    }
                });
    }

    /**
     * 需要在UI线程执行
     * 借助webView, 动态加载md5.js，传入相关的参数也是可用解析得到地址
     *
     * @param mWebView webView
     */
    private void decodeUrl(WebView mWebView) {
        String a = "MXoqQlMPfiwrPSYKNCFiWwVRCldRCgZffBdgKTZzBiYiNlU/IgcMQXwuPU8CT2FbLAkTS3hVGAQoHjEQOSFzQBYCKFwOfStgHCECTmZyMhg+YXovMAwdEjw6Lw8GVzQmDBAMIjYSPAsnHQ1YJTUjLx0gTFQFCScoIQQ9RgIlD0wLf3EIbAY9BCF2d0cvcQcf";
        String b = "a2d47W4FqndpWL/bOcbg5BGi0nXQy7SSoL2JoSA41zp8N6X/OMB14/UsfdVgtHF4uFysmNzYKtez57ZIkSKFTKKEfVuUbgXJZGdVcAfgwIHikanWSt+eKMrFhLosabZuAL+x6AkrmDF0";
        //Javascript返回add()函数的计算结果。
        mWebView.evaluateJavascript("parserVideoUrl('" + a + "','" + b + "')", value -> {
            Logger.t(TAG).d(value);
            if (TextUtils.isEmpty(value)) {
                return;
            }
            Document source = Jsoup.parse(value.replace("\\u003C", "<"));
            String videoUrl = source.select("source").first().attr("src");
            Logger.t(TAG).d(videoUrl);
        });
    }

    @Override
    public String getVideoCacheProxyUrl(String originalVideoUrl) {
        return dataManager.getVideoCacheProxyUrl(originalVideoUrl);
    }

    @Override
    public boolean isUserLogin() {
        return dataManager.isUserLogin();
    }

    @Override
    public int getLoginUserId() {
        return dataManager.getUser().getUserId();
    }

    /**
     * 判断视频是否来自 91porny 源。
     * source 是 transient 字段（不落库），本地收藏/历史页从数据库加载后为 null，
     * 因此同时检查持久化的 sourceName。
     */
    public static boolean isPornySource(V9MmanItem item) {
        if (item == null) {
            return false;
        }
        return Parse91PornyVideo.SOURCE.equals(item.getSource())
                || Parse91PornyVideo.SOURCE.equals(item.getSourceName());
    }

    @Override
    public void updateV9MmanItemForHistory(V9MmanItem v9MmanItem) {
        dataManager.updateV9MmanItem(v9MmanItem);
    }

    @Override
    public V9MmanItem findV9MmanItemByViewKey(String viewKey) {
        return dataManager.findV9MmanItemByViewKey(viewKey);
    }

    @Override
    public void setFavoriteNeedRefresh(boolean favoriteNeedRefresh) {
        dataManager.setFavoriteNeedRefresh(favoriteNeedRefresh);
    }

    private V9MmanItem saveVideoUrl(VideoResult videoResult, V9MmanItem v9MmanItem) {
        dataManager.saveVideoResult(videoResult);
        v9MmanItem.setVideoResult(videoResult);
        v9MmanItem.setViewHistoryDate(new Date());
        // 同步持久化来源标记，保证本地收藏/历史页重启后仍能识别
        if (v9MmanItem.getSourceName() == null) {
            v9MmanItem.setSourceName(v9MmanItem.getSource());
        }
        dataManager.saveV9MmanItem(v9MmanItem);
        return v9MmanItem;
    }

    /**
     * 91porny 本地收藏：写入数据库并标记收藏。需在 IO 线程调用。
     */
    public boolean addLocalFavorite(V9MmanItem v9MmanItem) {
        if (v9MmanItem == null) {
            return false;
        }
        if (v9MmanItem.getSourceName() == null) {
            v9MmanItem.setSourceName(v9MmanItem.getSource());
        }
        if (v9MmanItem.getSourceName() == null) {
            v9MmanItem.setSourceName(Parse91PornyVideo.SOURCE);
        }
        v9MmanItem.setIsLocalFavorite(true);
        if (v9MmanItem.getVideoResult() != null) {
            dataManager.saveVideoResult(v9MmanItem.getVideoResult());
        }
        dataManager.saveV9MmanItem(v9MmanItem);
        return true;
    }

    /** M42：是否本地收藏模式（true=本地收藏，与分分钟合并展示；false=服务器收藏） */
    public boolean isLocalFavoriteMode() {
        return dataManager.isLocalFavoriteMode();
    }

    /**
     * 收藏作者（本地）。authorKey 对 91porny 即作者名、对视频源即作者 uid；
     * authorName 用于收藏列表展示。需在 IO 线程调用。
     */
    public void addAuthorFavorite(String authorKey, String authorName, String source) {
        AuthorFavorite authorFavorite = new AuthorFavorite();
        authorFavorite.setAuthorKey(authorKey);
        authorFavorite.setAuthorName(authorName);
        authorFavorite.setSource(source);
        authorFavorite.setFavoriteDate(new Date());
        dataManager.saveAuthorFavorite(authorFavorite);
    }

    /**
     * 取消收藏作者（本地）。需在 IO 线程调用。
     */
    public void removeAuthorFavorite(String authorKey, String source) {
        AuthorFavorite authorFavorite = dataManager.findAuthorFavorite(authorKey, source);
        if (authorFavorite != null) {
            dataManager.deleteAuthorFavorite(authorFavorite);
        }
    }

    /**
     * 是否已收藏该作者。需在 IO 线程调用。
     */
    public boolean isAuthorFavorited(String authorKey, String source) {
        return dataManager.isAuthorFavorited(authorKey, source);
    }

    @Override
    public void downloadVideo(V9MmanItem v9MmanItem, boolean isForceReDownload) {

        downloadPresenter.downloadVideo(v9MmanItem, isForceReDownload, new DownloadPresenter.DownloadListener() {
            @Override
            public void onSuccess(final String message) {
                ifViewAttached(view -> view.showMessage(message, TastyToast.SUCCESS));
            }

            @Override
            public void onError(final String message) {
                ifViewAttached(view -> view.showMessage(message, TastyToast.ERROR));
            }
        });
    }

    @Override
    public void favorite(String uId, String videoId, String ownnerId) {
        favoritePresenter.favorite(uId, videoId, ownnerId, new FavoritePresenter.FavoriteListener() {
            @Override
            public void onSuccess(String message) {
                ifViewAttached(PlayVideoView::favoriteSuccess);
            }

            @Override
            public void onError(final String message) {
                ifViewAttached(view -> view.showError(message));
            }
        });
    }


    /**
     * 是否需要为了解析uid，只有登录状态下且uid还未解析过才需要解析
     *
     * @return true
     */
    public boolean isLoadForUid() {
        User user = dataManager.getUser();
        return dataManager.isUserLogin() && user.getUserId() == 0;
    }
}
