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
import com.m3man.utils.AppLog;

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
        final String rawViewKey = v9MmanItem.getViewKey();
        // M62：loadMman9VideoUrl 契约要求 "viewkey=xxx" 完整形态（其入口已做归一化兜底），
        // 此处必须直传原始值；v1.0.60 在此剥前缀导致新视频解析必败（回归）。
        final String viewKey = rawViewKey;
        boolean porny = isPornySource(v9MmanItem);
        String source = porny ? "91porny" : (v9MmanItem.getSource() == null ? "9mman" : v9MmanItem.getSource());
        AppLog.i(TAG, "路由判断 rawViewKey=" + rawViewKey + " cleanViewKey=" + viewKey
                + " sourceField=" + v9MmanItem.getSource() + " sourceName=" + v9MmanItem.getSourceName()
                + " isPorny=" + porny);
        String videoAddr = porny ? dataManager.getPornyAddress() : dataManager.getMman9VideoAddress();
        AppLog.i(TAG, "解析开始 viewKey=" + viewKey + " 源=" + source
                + " 地址=" + (videoAddr == null ? "null" : videoAddr)
                + " 代理=" + (dataManager.isOpenHttpProxy()
                        ? dataManager.getProxyIpAddress() + ":" + dataManager.getProxyPort() : "关"));
        if (porny) {
            // 91porny 来源走单独的解析路径
            dataManager.loadPornyVideoUrl(viewKey)
                    .doOnSubscribe(d -> AppLog.i(TAG, "请求91porny播放页 viewKey=" + viewKey))
                    .map(videoResult -> {
                        if (videoResult == null || TextUtils.isEmpty(videoResult.getVideoUrl())) {
                            AppLog.e(TAG, "解析失败(空结果) viewKey=" + viewKey + " 源=91porny");
                            throw new VideoException("解析视频链接失败了");
                        }
                        AppLog.i(TAG, "解析成功 viewKey=" + viewKey + " 源=91porny url=" + shortUrl(videoResult.getVideoUrl()));
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
                            AppLog.e(TAG, "91porny解析失败 viewKey=" + viewKey + " msg=" + msg);
                            ifViewAttached(view -> view.errorParseVideoUrl(diagnoseMsg(msg, videoAddr)));
                        }
                    });
            return;
        }
        dataManager.loadMman9VideoUrl(viewKey)
                .doOnSubscribe(d -> AppLog.i(TAG, "请求9mman播放页 viewKey=" + viewKey))
                .map(videoResult -> {
                    if (videoResult == null || TextUtils.isEmpty(videoResult.getVideoUrl())) {
                        if (videoResult != null && VideoResult.OUT_OF_WATCH_TIMES.equals(videoResult.getId())) {
                            //尝试强行重置，并上报异常
                            dataManager.resetMman91VideoWatchTime(true);
                            AppLog.w(TAG, "观看次数达上限，已重置cookie viewKey=" + viewKey);
                            throw new VideoException("观看次数达到上限了,请更换地址或者代理服务器！");
                        } else {
                            AppLog.e(TAG, "9mman解析失败(空结果) viewKey=" + viewKey);
                            throw new VideoException("解析视频链接失败了");
                        }
                    }
                    AppLog.i(TAG, "解析成功 viewKey=" + viewKey + " 源=9mman url=" + shortUrl(videoResult.getVideoUrl()));
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
                        AppLog.e(TAG, "9mman解析失败 viewKey=" + viewKey + " msg=" + msg);
                        ifViewAttached(view -> view.errorParseVideoUrl(diagnoseMsg(msg, videoAddr)));
                    }
                });
    }

    /**
     * 解析失败的提示增强：给出可操作的排查方向，而不是一句笼统的「解析视频链接失败」。
     */
    private String diagnoseMsg(String msg, String videoAddr) {
        if (msg == null || msg.length() > 30) {
            // 已经有明确/较长描述（如观看上限）时不再拼接
            if (msg != null && !msg.contains("失败")) {
                return msg;
            }
        }
        boolean proxyOn = dataManager.isOpenHttpProxy();
        StringBuilder sb = new StringBuilder("解析视频失败");
        if (!proxyOn) {
            sb.append("（未开启HTTP代理，源站可能无法访问，可在 我的-HTTP代理 中开启）");
        } else {
            sb.append("（代理 ").append(dataManager.getProxyIpAddress()).append(':')
                    .append(dataManager.getProxyPort()).append("，地址：")
                    .append(TextUtils.isEmpty(videoAddr) ? "未设置" : videoAddr).append('）');
        }
        sb.append("，请点击「复制日志」后反馈");
        return sb.toString();
    }

    /** 截断 URL 防日志过长（只留 host + 前 80 字符） */
    private static String shortUrl(String url) {
        if (url == null) {
            return "null";
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            String tail = url;
            if (tail.length() > 120) {
                tail = tail.substring(0, 120) + "...";
            }
            return (host == null ? url : host) + " | " + tail;
        } catch (Exception e) {
            return url.length() > 120 ? url.substring(0, 120) + "..." : url;
        }
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
     *
     * M60：hex viewkey（20位十六进制）是 91porny 的视频 ID，
     * 当 source/sourceName 都为空（如列表页未设源）时，用 viewKey 格式作为兜底判断，
     * 避免 hex viewkey 被错误路由到 9mman 解析器导致「解析视频链接失败」。
     *
     * 注意：DB 中 viewKey 可能带 "viewkey=" 前缀（如 "viewkey=a6019455d1bdfa805355"），
     * 需先剥离前缀再判断。
     */
    public static boolean isPornySource(V9MmanItem item) {
        if (item == null) {
            return false;
        }
        // 先按 key 格式判断：旧数据库可能把 91porny 项目错误保存成 source=9mman，
        // source 字段不能覆盖 91porny 的 20~32 位十六进制视频 ID。
        String viewKey = item.getViewKey();
        if (viewKey != null) {
            if (viewKey.startsWith("viewkey=")) {
                viewKey = viewKey.substring(8);
            }
            if (viewKey.matches("[0-9a-fA-F]{16,32}")) {
                return true;
            }
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
