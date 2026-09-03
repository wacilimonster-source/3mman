package com.m3man.ui.mman9video.play;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import androidx.annotation.NonNull;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;
import androidx.viewpager.widget.ViewPager;
import androidx.appcompat.app.AlertDialog;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.github.rubensousa.floatingtoolbar.FloatingToolbar;
import com.helper.loadviewhelper.load.LoadViewHelper;
import com.jaeger.library.StatusBarUtil;
import com.orhanobut.logger.Logger;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.R;
import com.m3man.MyApplication;
import com.liulishuo.filedownloader.model.FileDownloadStatus;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.m3man.adapter.SimpleFragmentPagerAdapter;
import com.m3man.constants.Keys;
import com.m3man.constants.KeysActivityRequestResultCode;
import com.m3man.data.db.entity.AuthorFavorite;
import com.m3man.data.db.entity.Category;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.VideoResult;
import com.m3man.parser.Parse91PornyVideo;
import com.m3man.service.DownloadVideoService;
import com.m3man.service.HlsDownloadService;
import com.m3man.ui.MvpActivity;
import com.m3man.ui.mman9video.author.AuthorFragment;
import com.m3man.ui.mman9video.comment.CommentFragment;
import com.m3man.ui.mman9video.user.UserLoginActivity;
import com.m3man.ui.mman9video.videolist.VideoListFragment;
import com.m3man.utils.AppLog;
import com.m3man.utils.DialogUtils;
import com.m3man.utils.LoadHelperUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

/**
 * @author flymegoc
 */
public abstract class BasePlayVideo extends MvpActivity<PlayVideoView, PlayVideoPresenter> implements PlayVideoView {

    private final String TAG = BasePlayVideo.class.getSimpleName();

    /**
     * M112：播放页禁用左边缘滑动返回。
     * 全屏播放时 jiaozivideoplayer 的左边缘手势是「亮度调节」，左滑返回与它争用：
     * 用户想调亮度却触发了返回动画。播放页有明确的返回键路径，禁用边缘返回无损可达性。
     */
    @Override
    public boolean isSupportSwipeBack() {
        return false;
    }

    /** R1：收藏相关裸订阅统一回收，避免销毁后回调操作已释放的UI */
    private final io.reactivex.disposables.CompositeDisposable mDisposables = new io.reactivex.disposables.CompositeDisposable();

    @BindView(R.id.floatingToolbar)
    FloatingToolbar floatingToolbar;
    @BindView(R.id.fab)
    FloatingActionButton fab;
    @BindView(R.id.tv_play_video_title)
    TextView tvPlayVideoTitle;
    @BindView(R.id.tv_play_video_author)
    TextView tvPlayVideoAuthor;
    @BindView(R.id.tv_favorite_author)
    TextView tvFavoriteAuthor;
    @BindView(R.id.tv_play_video_add_date)
    TextView tvPlayVideoAddDate;
    @BindView(R.id.tv_play_video_info)
    TextView tvPlayVideoInfo;

    @BindView(R.id.btn_play_back)
    ImageView btnPlayBack;

    @BindView(R.id.video_player_container)
    FrameLayout videoPlayerContainer;
    @BindView(R.id.tab_play)
    TabLayout tabPlay;
    @BindView(R.id.viewPager_play)
    ViewPager viewPagerPlay;

    private AlertDialog mAlertDialog;
    private AlertDialog favoriteDialog;

    private LoadViewHelper helper;

    protected V9MmanItem v9MmanItem;

    /** 非空时直接播放下载好的本地文件，不再解析远程地址。 */
    protected String localVideoPath;

    private boolean isAuthorFavorited;

    protected Category category;

    protected int skipPage;

    protected int position;

    @Inject
    protected CommentFragment commentFragment;

    @Inject
    protected AuthorFragment authorFragment;

    @Inject
    protected SimpleFragmentPagerAdapter playFragmentAdapter;

    @Inject
    protected PlayVideoPresenter playVideoPresenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_base_play_video);
        ButterKnife.bind(this);
        setupFullScreenPlayer();
        initPlayerView();
        initIntentData();
        initDialog();
        initLoadHelper();
        initData();
        initBottomMenu();

        // 悬浮返回按钮：点击返回上一页
        if (btnPlayBack != null) {
            btnPlayBack.setOnClickListener(v -> onBackPressed());
            // M92e：初始方向决定显隐（竖屏隐藏避免与顶栏返回重复；onConfigurationChanged 只管后续变化）
            boolean landscapeNow = getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
            updateFloatingBackVisibility(landscapeNow);
        }

        initTab();
    }

    private void initIntentData() {
        v9MmanItem = (V9MmanItem) getIntent().getSerializableExtra(Keys.KEY_INTENT_V9MMAN_ITEM);
        category = (Category) getIntent().getSerializableExtra(Keys.KEY_INTENT_CATEGORY_ITEM);
        skipPage = getIntent().getIntExtra(Keys.KEY_INTENT_SKIP_PAGE, 0);
        position = getIntent().getIntExtra(Keys.KEY_INTENT_SCROLL_TO_POSITION, 0);
        localVideoPath = getIntent().getStringExtra(Keys.KEY_INTENT_LOCAL_VIDEO_PATH);
    }

    /**
     * 底部切换标签tab
     */
    private void initTab() {
        boolean isPornySource = PlayVideoPresenter.isPornySource(v9MmanItem);
        if (isPornySource) {
            // 91porny 有作者体系（/author/xxx），但没有评论体系。
            // 因此只显示"作者"tab，隐藏评论与分类视频。
            tabPlay.setVisibility(View.VISIBLE);
            viewPagerPlay.setVisibility(View.VISIBLE);
            List<Fragment> pornyFragments = new ArrayList<>();
            pornyFragments.add(authorFragment);
            playFragmentAdapter.setData(pornyFragments);
            viewPagerPlay.setAdapter(playFragmentAdapter);
            tabPlay.setupWithViewPager(viewPagerPlay);
            return;
        }

        List<Fragment> fragments = new ArrayList<>();
        fragments.add(commentFragment);
        if (category != null) {
            if (!"index".equalsIgnoreCase(category.getCategoryValue())) {
                // 仅当不是从主页(index)进入时才显示中间的"分类视频"标签；
                // 从主页进入时不显示"主页"标签。
                VideoListFragment videoListFragment = VideoListFragment.getInstance();
                videoListFragment.setCategory(category);
                videoListFragment.setSkipPage(skipPage);
                videoListFragment.setPosition(position);
                fragments.add(videoListFragment);
            }
        } else {
            // M-13: category 为 null 时（如直接进入播放页），不显示分类视频标签
            // 仅保留评论和作者标签，避免空指针
        }

        fragments.add(authorFragment);
        playFragmentAdapter.setData(fragments);
        viewPagerPlay.setAdapter(playFragmentAdapter);
        tabPlay.setupWithViewPager(viewPagerPlay);
    }

    /**
     * 让视频播放区与评论/作者内容区都占据整屏高度：
     * 用户向下滑动页面时，播放区上移、下方标签与内容才进入视野。
     */
    private void setupFullScreenPlayer() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        int screenHeight = metrics.heightPixels;

        ViewGroup.LayoutParams playerParams = videoPlayerContainer.getLayoutParams();
        playerParams.height = screenHeight;
        videoPlayerContainer.setLayoutParams(playerParams);

        ViewGroup.LayoutParams pagerParams = viewPagerPlay.getLayoutParams();
        pagerParams.height = screenHeight;
        viewPagerPlay.setLayoutParams(pagerParams);
    }

    /**
     * 初始化视频引擎视图
     */
    public abstract void initPlayerView();

    public void initData() {
        // 本地下载播放不依赖 viewKey 或远程 VideoResult，直接交给当前播放引擎。
        if (!TextUtils.isEmpty(localVideoPath)) {
            // M69：Intent 反序列化出来的 V9MmanItem 已脱离 DaoSession（transient 字段不参与序列化），
            // 直接调 getVideoResult() 会抛 DaoException("Entity is detached")导致本页闪退。
            // 展示信息优先改用 DB 中附着的同 key 实体；拿不到也照常播本地文件。
            boolean attachedFromDb = false;
            if (v9MmanItem != null && !TextUtils.isEmpty(v9MmanItem.getViewKey())) {
                try {
                    V9MmanItem attached = presenter.findV9MmanItemByViewKey(v9MmanItem.getViewKey());
                    if (attached != null) {
                        v9MmanItem = attached;
                        attachedFromDb = true;
                        AppLog.i(TAG, "本地播放：已从DB附着视频信息 viewKey=" + v9MmanItem.getViewKey());
                    }
                } catch (Exception e) {
                    AppLog.w(TAG, "本地播放：附着DB实体失败 " + AppLog.cause(e));
                }
            }
            videoPlayerContainer.setVisibility(View.VISIBLE);
            try {
                setToolBarLayoutInfo(v9MmanItem);
            } catch (Exception e) {
                // 信息栏失败绝不影响播放本身
                AppLog.w(TAG, "本地播放：信息栏渲染失败 " + AppLog.cause(e));
            }
            String displayTitle = v9MmanItem == null ? "本地视频" : v9MmanItem.getTitle();
            AppLog.i(TAG, "本地播放开始 标题=" + displayTitle + " path=" + localVideoPath);
            playVideo(displayTitle, "file://" + localVideoPath, "", null);
            // M102：本地播放也要把视频信息交给「作者」tab——远程路径(304/313/540/550)
            // 均有接线，唯独本分支此前直接 return，AuthorFragment.v9MmanItem 恒为 null，
            // canLoadAuthorVideos()=false，下滑到“作者”页永远空白。
            // 仅在成功附着 DB 实体时接线（detached 实体 getVideoResult() 会抛 DaoException）；
            // 只调 setV9MmanItem 不直接 loadAuthorVideos：视图未创建时加载回调会触碰
            // null 的 swipeLayout/recyclerView，交给 onLazyLoadOnce 在视图就绪后触发；
            // 91mman 记录的旧加密 UID 过期时由 AuthorFragment 的 M92 自愈逻辑兜底换新。
            if (authorFragment != null && attachedFromDb
                    && v9MmanItem != null && v9MmanItem.getVideoResultId() != 0) {
                authorFragment.setV9MmanItem(v9MmanItem);
            }
            return;
        }
        // C9：入参零校验，避免 v9MmanItem 或 viewKey 为 null 时直接 NPE
        if (v9MmanItem == null || TextUtils.isEmpty(v9MmanItem.getViewKey())) {
            Logger.t(TAG).e("initData: v9MmanItem 为空或 viewKey 缺失，无法初始化播放页");
            // M44：作者视频等场景 viewKey 可能缺失，给出明确提示而非静默无反应
            showMessage("视频信息不完整，无法播放，请返回重试", TastyToast.ERROR);
            helper.showError();
            return;
        }
        boolean isPornySource = PlayVideoPresenter.isPornySource(v9MmanItem);
        V9MmanItem tmp = presenter.findV9MmanItemByViewKey(v9MmanItem.getViewKey());
        // M73：DB 命中的 9mman 直链带时效签名（secure=...,unix秒），历史记录可能已过期。
        // 过期直链交给 videocache 本地代理建流失败 → 播放器连不上 127.0.0.1 无限转圈
        // （v1.0.70 已修推荐流入口，这里是收藏/历史/作者页等入口的残留路径）。
        if (tmp != null && tmp.getVideoResultId() != 0 && !isPornySource) {
            try {
                VideoResult cachedVr = tmp.getVideoResult();
                String cachedUrl = cachedVr == null ? null : cachedVr.getVideoUrl();
                if (com.m3man.ui.recommend.RecommendPrefetcher.isSecureUrlExpired(cachedUrl)) {
                    // M96：日志脱敏——DB 缓存直链只打 scheme://host/path，不打印签名 query
                    AppLog.i(TAG, "initData: DB缓存直链已过期，强制重新解析 viewKey=" + tmp.getViewKey()
                            + " 过期直链=" + maskUrl(cachedUrl));
                    presenter.loadVideoUrl(tmp);
                    return;
                }
            } catch (Exception e) {
                // 校验异常不影响原流程（按未过期处理）
                AppLog.w(TAG, "initData: 直链时效校验异常 " + AppLog.cause(e));
            }
        }
        //登录之后，第一次需要刷新获取uid,否则无法使用收藏功能
        if (tmp == null || tmp.getVideoResultId() == 0 || presenter.isLoadForUid()) {
            if (tmp == null) {
                presenter.loadVideoUrl(v9MmanItem);
            } else {
                presenter.loadVideoUrl(tmp);
            }
        } else {
            v9MmanItem = tmp;
            videoPlayerContainer.setVisibility(View.VISIBLE);
            Logger.t(TAG).d("使用已有播放地址");
            //浏览历史
            v9MmanItem.setViewHistoryDate(new Date());
            presenter.updateV9MmanItemForHistory(v9MmanItem);
            VideoResult videoResult = v9MmanItem.getVideoResult();
            setToolBarLayoutInfo(v9MmanItem);
            playVideo(v9MmanItem.getTitle(), resolvePlayUrl(videoResult.getVideoUrl(), isPornySource), videoResult.getVideoName(), videoResult.getThumbImgUrl());
            if (isPornySource) {
                // 91porny 只设置作者
                if (authorFragment != null) {
                    authorFragment.setV9MmanItem(v9MmanItem);
                }
            } else {
                //加载评论
                if (commentFragment != null) {
                    commentFragment.setV9MmanItem(v9MmanItem);
                    commentFragment.loadVideoComment(videoResult.getVideoId(), v9MmanItem.getViewKey(), true);
                }
                if (authorFragment != null) {
                    authorFragment.setV9MmanItem(v9MmanItem);
                }
            }
        }
    }

    /**
     * 91porny 视频为 m3u8 HLS 流，videocache 代理（HttpProxyCacheServer 2.7.1）对 HLS
     * 分片相对路径与签名参数的转发有缺陷，直接播放原始地址；其余来源走原有代理缓存。
     */
    private String resolvePlayUrl(String originalUrl, boolean isPornySource) {
        if (TextUtils.isEmpty(originalUrl) || isHlsUrl(originalUrl)) {
            // 91porny 返回 HLS；旧 videocache 会把 index0.ts 当成独立地址，
            // 因此所有 m3u8 都直接交给 ExoPlayer/系统 HLS 播放器。
            return originalUrl;
        }
        return presenter.getVideoCacheProxyUrl(originalUrl);
    }

    private static boolean isHlsUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase(java.util.Locale.US);
        int query = lower.indexOf('?');
        if (query >= 0) {
            lower = lower.substring(0, query);
        }
        return lower.endsWith(".m3u8") || lower.contains(".m3u8/");
    }

    /**
     * M96：日志脱敏——URL 只保留 scheme://host/path（丢弃 query 中的签名/token），
     * 追加原始长度便于排查；解析失败时兜底去掉 query 再截断，绝不打印完整直链。
     */
    private static String maskUrl(String u) {
        if (TextUtils.isEmpty(u)) {
            return "";
        }
        int len = u.length();
        String head = u;
        try {
            java.net.URI uri = java.net.URI.create(u);
            StringBuilder sb = new StringBuilder();
            if (uri.getScheme() != null) {
                sb.append(uri.getScheme()).append("://");
            }
            if (uri.getHost() != null) {
                sb.append(uri.getHost());
            }
            if (uri.getPath() != null) {
                sb.append(uri.getPath());
            }
            if (sb.length() > 0) {
                head = sb.toString();
            }
        } catch (Exception e) {
            int q = u.indexOf('?');
            if (q >= 0) {
                head = u.substring(0, q);
            }
        }
        if (head.length() > 96) {
            head = head.substring(0, 96) + "...";
        }
        return head + "|len=" + len;
    }

    private void setToolBarLayoutInfo(final V9MmanItem v9MmanItem) {
        if (v9MmanItem == null) {
            return;
        }
        // 标题不依赖 DB 关联，先兜底展示（M69：本地播放时实体可能无 videoResult）
        String displayTitle = v9MmanItem.getTitle();
        if (v9MmanItem.getVideoResultId() == 0) {
            if (!TextUtils.isEmpty(displayTitle)) {
                tvPlayVideoTitle.setText(displayTitle);
            }
            return;
        }
        try {
            VideoResult videoResult = v9MmanItem.getVideoResult();
            String searchTitleTag = "...";
            if (displayTitle != null && (displayTitle.contains(searchTitleTag) || displayTitle.endsWith(searchTitleTag))) {
                tvPlayVideoTitle.setText(videoResult.getVideoName());
            } else {
                tvPlayVideoTitle.setText(displayTitle);
            }
            String ownerName = videoResult.getOwnerName();
            if (TextUtils.isEmpty(ownerName) || ownerName.trim().matches("\\d+")) {
                ownerName = v9MmanItem.getAuthorText();
            }
            tvPlayVideoAuthor.setText(ownerName);
            tvPlayVideoAddDate.setText(videoResult.getAddDate());
            tvPlayVideoInfo.setText(videoResult.getUserOtherInfo());
            refreshAuthorFavoriteState();
        } catch (Exception e) {
            // M69：detached 实体或关联缺失时保底只显示标题，不让信息栏拖垮播放
            AppLog.w(TAG, "setToolBarLayoutInfo 异常 " + AppLog.cause(e));
            if (!TextUtils.isEmpty(displayTitle)) {
                tvPlayVideoTitle.setText(displayTitle);
            }
        }
    }

    /**
     * 作者名旁的收藏按钮：点击切换当前视频作者的收藏状态（本地数据库）。
     */
    @OnClick(R.id.tv_favorite_author)
    public void onFavoriteAuthorClick() {
        if (v9MmanItem == null || v9MmanItem.getVideoResult() == null
                || TextUtils.isEmpty(v9MmanItem.getVideoResult().getOwnerId())) {
            showMessage("作者信息未就绪，无法收藏", TastyToast.DEFAULT);
            return;
        }
        final String authorKey = v9MmanItem.getVideoResult().getOwnerId();
        String authorName = v9MmanItem.getVideoResult().getOwnerName();
        if (TextUtils.isEmpty(authorName) || authorName.trim().matches("\\d+")) {
            authorName = v9MmanItem.getAuthorText();
        }
        final String finalAuthorName = authorName;
        final String source = PlayVideoPresenter.isPornySource(v9MmanItem)
                ? AuthorFavorite.SOURCE_PORNY : AuthorFavorite.SOURCE_MMAN9;
        mDisposables.add(Observable.just(1)
                .map(integer -> {
                    // 实时查库判断收藏态，避免异步刷新完成前点击导致的重复收藏 / 漏删
                    boolean currentlyFav = presenter.isAuthorFavorited(authorKey, source);
                    if (currentlyFav) {
                        presenter.removeAuthorFavorite(authorKey, source);
                    } else {
                        presenter.addAuthorFavorite(authorKey, finalAuthorName, source);
                    }
                    return !currentlyFav;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(newState -> {
                    isAuthorFavorited = newState;
                    updateFavoriteIcon();
                    showMessage(isAuthorFavorited ? "已收藏作者" : "已取消收藏作者",
                            isAuthorFavorited ? TastyToast.SUCCESS : TastyToast.DEFAULT);
                }, throwable -> showMessage("操作失败，请重试", TastyToast.ERROR)));
    }

    private void refreshAuthorFavoriteState() {
        try {
            if (v9MmanItem == null || v9MmanItem.getVideoResult() == null
                    || TextUtils.isEmpty(v9MmanItem.getVideoResult().getOwnerId())) {
                return;
            }
            final String authorKey = v9MmanItem.getVideoResult().getOwnerId();
            final String source = PlayVideoPresenter.isPornySource(v9MmanItem)
                    ? AuthorFavorite.SOURCE_PORNY : AuthorFavorite.SOURCE_MMAN9;
            mDisposables.add(Observable.just(1)
                    .map(integer -> presenter.isAuthorFavorited(authorKey, source))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(fav -> {
                        isAuthorFavorited = fav;
                        updateFavoriteIcon();
                    }, throwable -> Logger.t(TAG).e(throwable, "读取作者收藏态失败")));
        } catch (Exception e) {
            // M69：detached 实体访问 getVideoResult() 可能抛 DaoException，收藏态失败不影响播放
            AppLog.w(TAG, "refreshAuthorFavoriteState 异常 " + AppLog.cause(e));
        }
    }

    private void updateFavoriteIcon() {
        if (tvFavoriteAuthor != null) {
            tvFavoriteAuthor.setText(isAuthorFavorited
                    ? getString(R.string.author_favorite_on)
                    : getString(R.string.author_favorite_off));
        }
    }

    private void initLoadHelper() {
        helper = new LoadViewHelper(viewPagerPlay);
        helper.setListener(() -> presenter.loadVideoUrl(v9MmanItem));
    }

    private void initDialog() {
        mAlertDialog = DialogUtils.initLoadingDialog(this, "视频地址解析中...");
        favoriteDialog = DialogUtils.initLoadingDialog(this, "收藏中,请稍后...");
    }

    private void initBottomMenu() {
        floatingToolbar.attachFab(fab);
        floatingToolbar.setClickListener(new FloatingToolbar.ItemClickListener() {
            @Override
            public void onItemClick(MenuItem item) {
                onOptionsItemSelected(item);
            }

            @Override
            public void onItemLongClick(MenuItem item) {

            }
        });
    }

    /**
     * 开始播放视频
     *
     * @param title      视频标题
     * @param videoUrl   视频链接
     * @param name       视频名字
     * @param thumImgUrl 视频缩略图
     */
    public abstract void playVideo(String title, String videoUrl, String name, String thumImgUrl);


    @NonNull
    @Override
    public PlayVideoPresenter createPresenter() {
        return playVideoPresenter;
    }

    @Override
    public void showParsingDialog() {
        if (mAlertDialog == null) {
            return;
        }
        mAlertDialog.show();
    }

    @Override
    public void parseVideoUrlSuccess(V9MmanItem v9MmanItem) {
        this.v9MmanItem = v9MmanItem;
        videoPlayerContainer.setVisibility(View.VISIBLE);
        setToolBarLayoutInfo(v9MmanItem);
        VideoResult videoResult = v9MmanItem.getVideoResult();
        boolean isPornySource = PlayVideoPresenter.isPornySource(v9MmanItem);
        //开始播放
        playVideo(v9MmanItem.getTitle(), resolvePlayUrl(videoResult.getVideoUrl(), isPornySource), "", videoResult.getThumbImgUrl());
        helper.showContent();
        if (isPornySource) {
            // 91porny 只设置作者
            if (authorFragment != null) {
                authorFragment.setV9MmanItem(v9MmanItem);
            }
            dismissDialog();
            return;
        }
        if (commentFragment != null) {
            commentFragment.setV9MmanItem(v9MmanItem);
            commentFragment.loadVideoComment(videoResult.getVideoId(), v9MmanItem.getViewKey(), true);
        }
        if (authorFragment != null) {
            authorFragment.setV9MmanItem(v9MmanItem);
        }
        dismissDialog();
    }

    @Override
    public void errorParseVideoUrl(String errorMessage) {
        dismissDialog();
        helper.showError();
        AppLog.e("BasePlayVideo", "播放页解析失败: " + errorMessage);
        LoadHelperUtils.setErrorText(helper.getLoadError(), R.id.tv_error_text, "解析视频地址失败了，点击重试");
        showMessage(errorMessage, TastyToast.ERROR);
    }

    @Override
    public void favoriteSuccess() {
        presenter.setFavoriteNeedRefresh(true);
        showMessage("收藏成功", TastyToast.SUCCESS);
    }

    @Override
    public void showError(String message) {
        showMessage(message, TastyToast.ERROR);
        dismissDialog();
    }

    @Override
    public void showLoading(boolean pullToRefresh) {
        helper.showLoading();
        LoadHelperUtils.setLoadingText(helper.getLoadIng(), R.id.tv_loading_text, "拼命加载评论中...");
    }

    @Override
    public void showContent() {
        helper.showContent();
        dismissDialog();
    }

    @Override
    public void showMessage(String msg, int type) {
        super.showMessage(msg, type);
        dismissDialog();
    }

    private void dismissDialog() {
        if (mAlertDialog != null && mAlertDialog.isShowing() && !isFinishing()) {
            mAlertDialog.dismiss();
        }
        if (favoriteDialog != null && favoriteDialog.isShowing() && !isFinishing()) {
            favoriteDialog.dismiss();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.play_video, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.menu_play_collect) {
            favoriteVideo();
            return true;
        } else if (id == R.id.menu_play_download) {
            startDownloadVideo();
            return true;
        } else if (id == R.id.menu_play_close) {
            floatingToolbar.hide();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void startDownloadVideo() {
        // 91porny 为 m3u8 HLS，走专用下载器（下载分片并转 mp4）
        if (v9MmanItem != null && PlayVideoPresenter.isPornySource(v9MmanItem)) {
            downloadPornyVideo();
            return;
        }
        presenter.downloadVideo(v9MmanItem, false);
        Intent intent = new Intent(this, DownloadVideoService.class);
        startService(intent);
    }

    /**
     * 91porny HLS 视频下载：交给前台服务后台下载（通知栏实时显示进度），不阻塞当前播放。
     */
    private void downloadPornyVideo() {
        if (v9MmanItem == null || v9MmanItem.getVideoResult() == null) {
            showMessage("还未成功解析视频链接，不能下载！", TastyToast.INFO);
            return;
        }
        final String videoUrl = v9MmanItem.getVideoResult().getVideoUrl();
        if (TextUtils.isEmpty(videoUrl)) {
            showMessage("还未成功解析视频链接，不能下载！", TastyToast.INFO);
            return;
        }
        // 路径必须与「我的下载」播放路径严格一致：统一用 getDownLoadPath(customDir)
        String customDir = MyApplication.getInstance().getDataManager().getCustomDownloadVideoDirPath();
        final String savePath = v9MmanItem.getDownLoadPath(customDir);
        // 落「下载中」记录，使其出现在「我的下载」列表（伪 downloadId 避免与 FileDownloader 真实 id 冲突）
        // 使用 (hashCode & 0x7FFFFFFF) 避免 Math.abs(Integer.MIN_VALUE) 返回负数的已知 bug
        int pseudoId = videoUrl.hashCode() & 0x7FFFFFFF;
        v9MmanItem.setDownloadId(pseudoId);
        v9MmanItem.setStatus(FileDownloadStatus.progress);
        if (v9MmanItem.getAddDownloadDate() == null) {
            v9MmanItem.setAddDownloadDate(new Date());
        }
        MyApplication.getInstance().getDataManager().updateV9MmanItem(v9MmanItem);
        // 通知「正在下载」页刷新
        Intent progressIntent = new Intent(HlsDownloadService.ACTION_HLS_PROGRESS);
        progressIntent.putExtra(HlsDownloadService.EXTRA_VIEW_KEY, v9MmanItem.getViewKey());
        // M102：启动即下载中，running=true 让列表同步状态
        progressIntent.putExtra(HlsDownloadService.EXTRA_RUNNING, true);
        LocalBroadcastManager.getInstance(this).sendBroadcast(progressIntent);

        Intent serviceIntent = new Intent(this, HlsDownloadService.class);
        serviceIntent.setAction(HlsDownloadService.ACTION_START);
        serviceIntent.putExtra(HlsDownloadService.EXTRA_VIDEO_URL, videoUrl);
        serviceIntent.putExtra(HlsDownloadService.EXTRA_TITLE, v9MmanItem.getTitle());
        serviceIntent.putExtra(HlsDownloadService.EXTRA_FILE_NAME, sanitizeFileName(v9MmanItem.getTitle()));
        serviceIntent.putExtra(HlsDownloadService.EXTRA_VIEW_KEY, v9MmanItem.getViewKey());
        serviceIntent.putExtra(HlsDownloadService.EXTRA_SAVE_PATH, savePath);
        startService(serviceIntent);
        showMessage("已加入后台下载，进度请看通知栏", TastyToast.SUCCESS);
    }

    private static String sanitizeFileName(String title) {
        if (TextUtils.isEmpty(title)) {
            return "video_" + System.currentTimeMillis();
        }
        return title.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void favoriteVideo() {
        if (v9MmanItem == null || v9MmanItem.getVideoResultId() == 0) {
            showMessage("还未成功解析视频链接，不能收藏！", TastyToast.INFO);
            return;
        }
        // 91porny 无账号体系，走本地收藏；M42：本地收藏模式下所有源都走本地收藏（无需登录）
        if (PlayVideoPresenter.isPornySource(v9MmanItem) || presenter.isLocalFavoriteMode()) {
            favoriteDialog.show();
            mDisposables.add(io.reactivex.Observable.just(1)
                    .map(integer -> presenter.addLocalFavorite(v9MmanItem))
                    .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                    .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                    .subscribe(aBoolean -> {
                        dismissDialog();
                        showMessage("收藏成功（已保存到本地）", TastyToast.SUCCESS);
                    }, throwable -> {
                        dismissDialog();
                        showMessage("收藏失败", TastyToast.ERROR);
                    }));
            return;
        }
        VideoResult videoResult = v9MmanItem.getVideoResult();
        if (!presenter.isUserLogin()) {
            goToLogin(KeysActivityRequestResultCode.LOGIN_ACTION_FOR_GET_UID);
            // M62：提示语义修正——"请先登录"不该用成功样式
            showMessage("请先登录", TastyToast.WARNING);
            return;
        }
        favoriteDialog.show();
        presenter.favorite(String.valueOf(presenter.getLoginUserId()), videoResult.getVideoId(), videoResult.getAuthorId());
    }

    /**
     * 去登录
     *
     * @param actionKey 登录之后的动作key
     */
    private void goToLogin(int actionKey) {
        Intent intent = new Intent(this, UserLoginActivity.class);
        intent.putExtra(Keys.KEY_INTENT_LOGIN_FOR_ACTION, actionKey);
        startActivityForResultWithAnimation(intent, 0);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == KeysActivityRequestResultCode.RESULT_FOR_LOOK_AUTHOR_VIDEO) {
            if (authorFragment != null) {
                authorFragment.loadAuthorVideos();
            }
        } else if (resultCode == KeysActivityRequestResultCode.RESULT_CODE_FOR_REFRESH_GET_UID) {
            Logger.t(TAG).d("登录成功，需要刷新以获取uid");
            presenter.loadVideoUrl(v9MmanItem);
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // M96：修复回归——newConfig.orientation 是 Configuration.ORIENTATION_*（横屏=2/竖屏=1），
        // 误用 ActivityInfo.SCREEN_ORIENTATION_*（横屏=0）导致横竖屏分支永远判不中。
        try {
            if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                // 横屏时隐藏虚拟状态栏占位并显示悬浮返回按钮
                StatusBarUtil.hideFakeStatusBarView(this);
                updateFloatingBackVisibility(true);
            } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
                setStatusBarColor(ContextCompat.getColor(this, R.color.colorPrimary));
                updateFloatingBackVisibility(false);
            }
        } catch (Exception e) {
            AppLog.w(TAG, "onConfigurationChanged 处理异常: " + e.getMessage());
        }
    }

    /**
     * M92e：悬浮返回按钮改为「横屏专属」——竖屏时播放器顶栏/控制栏自带返回入口，
     * 悬浮按钮属于重复入口且遮挡画面；横屏全屏时顶栏隐藏，才需要它提供退出路径。
     */
    private void updateFloatingBackVisibility(boolean landscape) {
        if (btnPlayBack != null) {
            btnPlayBack.setVisibility(landscape ? View.VISIBLE : View.GONE);
        }
    }

    public void setV9MmanItems(V9MmanItem v9MmanItems) {
        this.v9MmanItem = v9MmanItems;
    }

    @Override
    protected void onDestroy() {
        // R1：释放收藏相关的裸订阅，避免页面销毁后异步回调操作已销毁的 UI
        if (mDisposables != null && !mDisposables.isDisposed()) {
            mDisposables.clear();
        }
        // M96：兜底收起加载框，防止「Activity 已销毁但 Dialog 未关闭」的窗口泄漏
        // （dismissDialog() 带 isFinishing 保护，在 onDestroy 里会被跳过，故这里直接 dismiss）
        if (mAlertDialog != null && mAlertDialog.isShowing()) {
            mAlertDialog.dismiss();
        }
        if (favoriteDialog != null && favoriteDialog.isShowing()) {
            favoriteDialog.dismiss();
        }
        super.onDestroy();
    }
}
