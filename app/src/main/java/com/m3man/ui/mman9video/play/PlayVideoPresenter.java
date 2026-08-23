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

    /** M66b：持久化来源标记（sourceName 列）取值，与 AuthorFavorite.SOURCE_MMAN9 保持一致 */
    public static final String SOURCE_MMAN9_PERSIST = "mman9";

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
            // M65b：porny 播放页契约是 /video/view/<裸hex>（无 "viewkey=" 前缀）。
            // 直传原始值会拼出 /video/view/viewkey=xxx 导致解析必败（与 9mman 契约相反）。
            final String pornyViewKey = rawViewKey != null && rawViewKey.startsWith("viewkey=")
                    ? rawViewKey.substring("viewkey=".length()) : rawViewKey;
            dataManager.loadPornyVideoUrl(pornyViewKey)
                    .doOnSubscribe(d -> AppLog.i(TAG, "请求91porny播放页 viewKey=" + pornyViewKey))
                    .map(videoResult -> {
                        if (videoResult == null || TextUtils.isEmpty(videoResult.getVideoUrl())) {
                            AppLog.e(TAG, "解析失败(空结果) viewKey=" + pornyViewKey + " 源=91porny");
                            throw new VideoException("解析视频链接失败了");
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
     *
     * M66b（方案B 彻底改造）：来源判定以「解析时写入的持久化标记」为唯一权威：
     * 1. sourceName（持久化列）== "91porny" → porny；
     * 2. transient source == "91porny"（本次会话内存中标记）→ porny；
     * 3. sourceName/sourceSource 明确标为 mman9 的直接判 9mman（权威短路，
     *    不再进入格式兜底——测试用例发现的漏洞：裸 hex + mman9 标记会被兜底误判）；
     * 4. 无任何标记时才用 viewKey 格式兜底，且规则收紧：
     *    - 91porny 的视频 ID 恒为纯 hex（16~32 位）且【不带 "viewkey=" 前缀】；
     *    - 9mman 的 viewKey 可能恰好是纯 hex（如 c9236ce81db652e4b5d9），但
     *      【恒带 "viewkey=" 前缀】（extractViewKey 契约），带前缀的一律按 9mman。
     * 旧版「只要裸值是 hex 就算 porny」把大量 9mman hex key 误判成 porny，
     * 导致作者页 404、播放走错源、DB 写入错误 VideoResult（标题/缩略图错乱）。
     */
    public static boolean isPornySource(V9MmanItem item) {
        if (item == null) {
            return false;
        }
        // 权威判定：持久化标记 / 内存标记
        boolean markedPorny = Parse91PornyVideo.SOURCE.equals(item.getSourceName())
                || Parse91PornyVideo.SOURCE.equals(item.getSource());
        if (markedPorny) {
            return true;
        }
        // 权威短路：明确标为 mman9 的不再走格式推断（防裸 hex 被兜底误判）
        if (SOURCE_MMAN9_PERSIST.equals(item.getSourceName())
                || SOURCE_MMAN9_PERSIST.equals(item.getSource())) {
            return false;
        }
        // 兜底：格式推断（仅在无任何标记时）
        String viewKey = item.getViewKey();
        if (TextUtils.isEmpty(viewKey)) {
            return false;
        }
        boolean prefixed = viewKey.startsWith("viewkey=");
        String bare = prefixed ? viewKey.substring(8) : viewKey;
        if (bare.matches("[0-9a-fA-F]{16,32}")) {
            // 带前缀的 hex = 9mman（extractViewKey 契约）；裸 hex 才可能是 porny
            return !prefixed;
        }
        return false;
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
