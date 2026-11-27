package com.m3man.ui.mman9video.play;

import androidx.lifecycle.Lifecycle;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Pair;
import android.webkit.WebView;

import com.hannesdorfmann.mosby3.mvp.MvpBasePresenter;
import com.liulishuo.filedownloader.model.FileDownloadStatus;
import com.orhanobut.logger.Logger;
import com.sdsmdg.tastytoast.TastyToast;
import com.trello.rxlifecycle2.LifecycleProvider;
import com.m3man.R;
import com.m3man.data.DataManager;
import com.m3man.data.network.Source441447Client;
import com.m3man.data.db.entity.AuthorFavorite;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.VideoResult;
import com.m3man.data.model.User;
import com.m3man.di.ApplicationContext;
import com.m3man.exception.ApiException;
import com.m3man.exception.VideoException;
import com.m3man.parser.Parse91PornyVideo;
import com.m3man.rxjava.CallBackWrapper;
import com.m3man.rxjava.RetryWhenProcess;
import com.m3man.rxjava.RxSchedulersHelper;
import com.m3man.ui.download.DownloadPresenter;
import com.m3man.ui.mman9video.favorite.FavoritePresenter;
import com.m3man.utils.AppLog;
import com.m3man.utils.SDCardUtils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.Optional;

import javax.inject.Inject;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/**
 * @author flymegoc
 * @date 2017/11/15
 * @describe play
 */
public class PlayVideoPresenter extends MvpBasePresenter<PlayVideoView> implements IPlay {

    private static final String TAG = PlayVideoPresenter.class.getSimpleName();

    /** M66b：持久化来源标记（sourceName 列）取值，与 AuthorFavorite.SOURCE_MMAN9 保持一致 */
    public static final String SOURCE_MMAN9_PERSIST = "mman9";

    private FavoritePresenter favoritePresenter;
    private DownloadPresenter downloadPresenter;

    private LifecycleProvider<Lifecycle.Event> provider;

    private DataManager dataManager;
    private final Source441447Client source441447Client;
    private final Context context;
    private int playbackGeneration;

//    private final WebView webView;

    @Inject
    public PlayVideoPresenter(FavoritePresenter favoritePresenter, DownloadPresenter downloadPresenter,
                              LifecycleProvider<Lifecycle.Event> provider, DataManager dataManager,
                              Source441447Client source441447Client, @ApplicationContext Context context) {
        this.favoritePresenter = favoritePresenter;
        this.downloadPresenter = downloadPresenter;
        this.provider = provider;
        this.dataManager = dataManager;
        this.source441447Client = source441447Client;
        this.context = context;
    }

    void loadMainSiteCard(V9MmanItem item) {
        if (isLoadForUid()) {
            loadVideoUrl(item);
            return;
        }
        final int generation = ++playbackGeneration;
        final String viewKey = item.getViewKey();
        Runnable loadOnline = () -> {
            if (generation == playbackGeneration && isViewAttached()) {
                loadVideoUrl(item);
            }
        };
        Observable.fromCallable(() -> Optional.ofNullable(findLocalMainSiteVideo(viewKey)))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(provider.bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(local -> {
                    if (generation != playbackGeneration) return;
                    if (local.isPresent()) {
                        Pair<V9MmanItem, String> saved = local.get();
                        ifViewAttached(view -> view.playSavedVideo(saved.first, saved.second));
                    } else {
                        loadOnline.run();
                    }
                }, error -> {
                    AppLog.w(TAG, "读取已下载视频失败 " + AppLog.cause(error));
                    loadOnline.run();
                });
    }

    private Pair<V9MmanItem, String> findLocalMainSiteVideo(String viewKey) {
        V9MmanItem saved = dataManager.findV9MmanItemByViewKey(viewKey);
        if (saved == null || saved.getStatus() != FileDownloadStatus.completed
                || isPornySource(saved) || Source441447Client.isSource(saved)) {
            return null;
        }
        String mediaUri = resolveCompletedFile(saved);
        if (mediaUri == null) {
            return null;
        }
        try {
            if (saved.getVideoResultId() != 0) {
                saved.getVideoResult();
            }
            if (saved.getId() != null) {
                Date now = new Date();
                dataManager.updateViewHistoryColumn(saved.getId(), now);
                saved.setViewHistoryDate(now);
            }
        } catch (Exception e) {
            AppLog.w(TAG, "本地播放信息读取或历史更新失败 " + AppLog.cause(e));
        }
        return Pair.create(saved, mediaUri);
    }

    private String resolveCompletedFile(V9MmanItem saved) {
        String archived = saved.getLocalFilePath();
        if (!TextUtils.isEmpty(archived)) {
            Uri uri = Uri.parse(archived);
            if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) && "media".equals(uri.getAuthority())) {
                try (AssetFileDescriptor media = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                    if (media != null && media.getLength() != 0) {
                        return uri.toString();
                    }
                } catch (IOException | SecurityException e) {
                    AppLog.w(TAG, "已归档视频无法打开 " + AppLog.cause(e));
                }
            }
        }
        String path = SDCardUtils.resolvePlayablePath(context, archived,
                saved.getDownLoadPath(dataManager.getCustomDownloadVideoDirPath()));
        File file = path == null ? null : new File(path);
        return file != null && file.isAbsolute() && file.isFile() && file.canRead() && file.length() > 0
                ? Uri.fromFile(file).toString() : null;
    }

    @Override
    public void loadVideoUrl(final V9MmanItem v9MmanItem) {
        final int generation = ++playbackGeneration;
        if (Source441447Client.isSource(v9MmanItem)) {
            Observable.fromCallable(() -> {
                String url = source441447Client.loadPlayUrl(v9MmanItem.getViewKey());
                v9MmanItem.setViewHistoryDate(new Date());
                try {
                    dataManager.saveV9MmanItem(v9MmanItem);
                } catch (Exception error) {
                    AppLog.w(TAG, "441447 播放历史保存失败 " + AppLog.cause(error));
                }
                return url;
            }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                    .compose(provider.bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                    .doOnSubscribe(disposable -> ifViewAttached(PlayVideoView::showParsingDialog))
                    .subscribe(url -> ifViewAttached(view -> view.play441447Video(v9MmanItem, url)),
                            error -> ifViewAttached(view -> view.errorParseVideoUrl(Source441447Client.errorMessage(error))));
            return;
        }
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
                + " App内代理=" + (dataManager.isOpenHttpProxy()
                        ? dataManager.getProxyIpAddress() + ":" + dataManager.getProxyPort() : "关"));
        if (porny) {
            // 91porny 来源走单独的解析路径
            // M65b：porny 播放页契约是 /video/view/<裸hex>（无 "viewkey=" 前缀）。
            // 直传原始值会拼出 /video/view/viewkey=xxx 导致解析必败（与 9mman 契约相反）。
            final String pornyViewKey = rawViewKey != null && rawViewKey.startsWith("viewkey=")
                    ? rawViewKey.substring("viewkey=".length()) : rawViewKey;
            dataManager.loadPornyVideoUrl(pornyViewKey)
                    .doOnSubscribe(d -> AppLog.i(TAG, "请求91porny播放页 viewKey=" + pornyViewKey))
                    .map(videoResult -> {
                        if (videoResult == null || TextUtils.isEmpty(videoResult.getVideoUrl())) {
                            AppLog.e(TAG, "解析失败(空结果) viewKey=" + pornyViewKey + " 源=91porny");
                            throw new VideoException(context.getString(R.string.play_no_media_url));
                        }
                        AppLog.i(TAG, "解析成功 viewKey=" + pornyViewKey + " 源=91porny url=" + shortUrl(videoResult.getVideoUrl()));
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
                            // M96：saveVideoUrl 含数据库写入，改到 IO 线程异步执行，不再阻塞主线程；
                            // 生命周期复用 provider.bindUntilEvent，页面销毁自动断流。
                            Observable.fromCallable(() -> saveVideoUrl(videoResult, v9MmanItem))
                                    .subscribeOn(Schedulers.io())
                                    .observeOn(AndroidSchedulers.mainThread())
                                    .compose(provider.bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                                    .subscribe(saved -> ifViewAttached(view -> view.parseVideoUrlSuccess(saved)),
                                            t -> {
                                                AppLog.e(TAG, "91porny解析结果保存DB失败 viewKey="
                                                        + (v9MmanItem == null ? "null" : v9MmanItem.getViewKey())
                                                        + " err=" + AppLog.cause(t));
                                                // 写库失败不阻断播放：内存挂载结果后照常回调
                                                if (v9MmanItem != null) {
                                                    try {
                                                        v9MmanItem.setVideoResult(videoResult);
                                                    } catch (Exception ignored) {
                                                    }
                                                    final V9MmanItem fallbackItem = v9MmanItem;
                                                    ifViewAttached(view -> view.parseVideoUrlSuccess(fallbackItem));
                                                }
                                            });
                        }

                        @Override
                        public void onError(final String msg, int code) {
                            reportPlaybackPageError(generation, viewKey, msg, code);
                        }
                    });
            return;
        }
        dataManager.loadMman9VideoUrl(viewKey)
                .doOnSubscribe(d -> AppLog.i(TAG, "请求9mman播放页 viewKey=" + viewKey))
                .map(videoResult -> {
                    // 源站对游客隐藏了 3 天内新视频的播放器：解析结果为空但带登录标记，
                    // 交给 onSuccess 引导登录，不能当成“解析失败”。
                    if (videoResult != null && videoResult.requiresLogin()) {
                        AppLog.w(TAG, "源站要求登录后才能观看 viewKey=" + viewKey);
                        return videoResult;
                    }
                    if (videoResult == null || TextUtils.isEmpty(videoResult.getVideoUrl())) {
                        if (videoResult != null && VideoResult.OUT_OF_WATCH_TIMES.equals(videoResult.getId())) {
                            AppLog.w(TAG, "源站观看次数达上限 viewKey=" + viewKey);
                            throw new VideoException(context.getString(R.string.play_view_limit_reached));
                        } else if (videoResult != null && videoResult.requiresSitePlayback()) {
                            throw new VideoException(context.getString(R.string.play_source_verification_required));
                        } else {
                            AppLog.e(TAG, "9mman解析失败(空结果) viewKey=" + viewKey);
                            throw new VideoException(context.getString(R.string.play_no_media_url));
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
                        if (generation != playbackGeneration) return;
                        if (videoResult.requiresLogin()) {
                            // 不写库、不播放：空直链落库会把脏数据缓存下来
                            ifViewAttached(view -> view.needLoginForPlayback(
                                    context.getString(R.string.play_login_required)));
                            return;
                        }
                        // M96：saveVideoUrl 含数据库写入，改到 IO 线程异步执行，不再阻塞主线程；
                        // 生命周期复用 provider.bindUntilEvent，页面销毁自动断流。
                        Observable.fromCallable(() -> saveVideoUrl(videoResult, v9MmanItem))
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .compose(provider.bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                                .subscribe(saved -> ifViewAttached(view -> view.parseVideoUrlSuccess(saved)),
                                        t -> {
                                            AppLog.e(TAG, "9mman解析结果保存DB失败 viewKey="
                                                    + (v9MmanItem == null ? "null" : v9MmanItem.getViewKey())
                                                    + " err=" + AppLog.cause(t));
                                            // 写库失败不阻断播放：内存挂载结果后照常回调
                                            if (v9MmanItem != null) {
                                                try {
                                                    v9MmanItem.setVideoResult(videoResult);
                                                } catch (Exception ignored) {
                                                }
                                                final V9MmanItem fallbackItem = v9MmanItem;
                                                ifViewAttached(view -> view.parseVideoUrlSuccess(fallbackItem));
                                            }
                                        });
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        reportPlaybackPageError(generation, viewKey, msg, code);
                    }
                });
    }

    private void reportPlaybackPageError(int generation, String viewKey, String detail, int code) {
        if (generation != playbackGeneration) return;
        AppLog.e(TAG, "播放页请求或解析失败 viewKey=" + viewKey + " code=" + code);
        final String message;
        if (code >= 400 && code < 600) {
            message = context.getString(R.string.play_source_http_error, code);
        } else if (TextUtils.isEmpty(detail) || code == ApiException.Error.UNKNOWN) {
            message = context.getString(R.string.play_load_failed);
        } else {
            message = detail;
        }
        ifViewAttached(view -> view.errorParseVideoUrl(message));
    }

    /** 截断 URL 防日志过长（只留 host + 前 80 字符） */
    private static String shortUrl(String url) {
        if (url == null) {
            return "null";
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            StringBuilder sb = new StringBuilder();
            if (uri.getScheme() != null) sb.append(uri.getScheme()).append("://");
            if (uri.getHost() != null) sb.append(uri.getHost());
            if (uri.getPath() != null) sb.append(uri.getPath());
            // M165：日志不再包含 query/fragment（签名、token、viewkey 等敏感参数）。
            String safe = sb.length() == 0 ? "***" : sb.toString();
            if (safe.length() > 120) {
                safe = safe.substring(0, 120) + "...";
            }
            return safe;
        } catch (Exception e) {
            int q = url.indexOf('?');
            String safe = q >= 0 ? url.substring(0, q) : url;
            return safe.length() > 120 ? safe.substring(0, 120) + "..." : safe;
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
     *
     * M66b（方案B 彻底改造）：来源判定以「解析时写入的持久化标记」为唯一权威。
     * R-fix（LESSONS §3-3）：判定逻辑收敛到 {@link Parse91PornyVideo#isPornySource}
     * 全仓库唯一实现（下载/卡片标签同源），此处保留公共 API 兼容既有调用点与测试。
     * 旧版「只要裸值是 hex 就算 porny」把大量 9mman hex key 误判成 porny，
     * 导致作者页 404、播放走错源、DB 写入错误 VideoResult（标题/缩略图错乱）。
     */
    public static boolean isPornySource(V9MmanItem item) {
        return Parse91PornyVideo.isPornySource(item);
    }

    @Override
    public void updateV9MmanItemForHistory(V9MmanItem v9MmanItem) {
        // R-fix：观看历史只写 VIEW_HISTORY_DATE 列，不整行覆盖
        //（播放页实体可能持有较久，整行 update 会回写陈旧的下载状态/收藏）
        if (v9MmanItem != null && v9MmanItem.getId() != null
                && v9MmanItem.getViewHistoryDate() != null) {
            dataManager.updateViewHistoryColumn(v9MmanItem.getId(), v9MmanItem.getViewHistoryDate());
        } else if (v9MmanItem != null) {
            dataManager.updateV9MmanItem(v9MmanItem);
        }
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
        // M71：91porn 列表页服务端渲染存在标题/封面串位，列表解析出的 title 不可信；
        // 播放页 h4 标题是权威数据，用它覆盖条目标题并回写 DB，
        // 修复"推荐/详情进入后标题与实际视频不符 / 多条视频同标题"的问题。
        if (videoResult != null && !TextUtils.isEmpty(videoResult.getVideoName())
                && v9MmanItem != null
                && !TextUtils.equals(v9MmanItem.getTitle(), videoResult.getVideoName())) {
            AppLog.i(TAG, "标题修正(站点列表页串位) viewKey=" + v9MmanItem.getViewKey()
                    + " 列表页=\"" + v9MmanItem.getTitle() + "\" -> 播放页=\"" + videoResult.getVideoName() + '"');
            v9MmanItem.setTitle(videoResult.getVideoName());
        }
        v9MmanItem.setVideoResult(videoResult);
        v9MmanItem.setViewHistoryDate(new Date());
        // M66b：来源标记权威化——解析成功即按本次路由结果写入持久化标记，
        // 不再沿用可能为空的旧值；这是后续 isPornySource 判定的唯一权威。
        v9MmanItem.setSourceName(isPornySource(v9MmanItem)
                ? Parse91PornyVideo.SOURCE : SOURCE_MMAN9_PERSIST);
        dataManager.saveV9MmanItem(v9MmanItem);
        return v9MmanItem;
    }

    /**
     * 91porny 本地收藏：写入数据库并标记收藏。需在 IO 线程调用。
     * R-fix（LESSONS §3-4）：实现收敛到 FavoritePresenter.setLocalFavorite 唯一实现。
     */
    public boolean addLocalFavorite(V9MmanItem v9MmanItem) {
        return favoritePresenter.addLocalFavorite(v9MmanItem);
    }

    /** M42：是否本地收藏模式（true=本地收藏，与分分钟合并展示；false=服务器收藏） */
    public boolean isLocalFavoriteMode() {
        return dataManager.isLocalFavoriteMode();
    }

    /**
     * 收藏作者（本地）。authorKey 对 91porny 即作者名、对视频源即作者 uid；
     * authorName 用于收藏列表展示，lastViewKey 供主站加密 UID 过期时自愈。需在 IO 线程调用。
     */
    public void addAuthorFavorite(String authorKey, String authorName, String source, String lastViewKey) {
        AuthorFavorite authorFavorite = new AuthorFavorite();
        authorFavorite.setAuthorKey(authorKey);
        authorFavorite.setAuthorName(authorName);
        authorFavorite.setSource(source);
        authorFavorite.setFavoriteDate(new Date());
        if (!TextUtils.isEmpty(lastViewKey)) {
            authorFavorite.setLastViewKey(lastViewKey);
        }
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
