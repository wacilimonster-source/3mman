package com.m3man.ui.mman9video.play;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AlertDialog;
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
import com.m3man.adapter.PlayFragmentAdapter;
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

    private boolean isAuthorFavorited;

    protected Category category;

    protected int skipPage;

    protected int position;

    @Inject
    protected CommentFragment commentFragment;

    @Inject
    protected AuthorFragment authorFragment;

    @Inject
    protected PlayFragmentAdapter playFragmentAdapter;

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
        }

        initTab();
    }

    private void initIntentData() {
        v9MmanItem = (V9MmanItem) getIntent().getSerializableExtra(Keys.KEY_INTENT_V9MMAN_ITEM);
        category = (Category) getIntent().getSerializableExtra(Keys.KEY_INTENT_CATEGORY_ITEM);
        skipPage = getIntent().getIntExtra(Keys.KEY_INTENT_SKIP_PAGE, 0);
        position = getIntent().getIntExtra(Keys.KEY_INTENT_SCROLL_TO_POSITION, 0);
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
            //TODO
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
        boolean isPornySource = PlayVideoPresenter.isPornySource(v9MmanItem);
        V9MmanItem tmp = presenter.findV9MmanItemByViewKey(v9MmanItem.getViewKey());
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
        if (isPornySource || TextUtils.isEmpty(originalUrl)) {
            return originalUrl;
        }
        return presenter.getVideoCacheProxyUrl(originalUrl);
    }

    private void setToolBarLayoutInfo(final V9MmanItem v9MmanItem) {
        if (v9MmanItem.getVideoResultId() == 0) {
            return;
        }
        String searchTitleTag = "...";
        VideoResult videoResult = v9MmanItem.getVideoResult();
        if (v9MmanItem.getTitle().contains(searchTitleTag) || v9MmanItem.getTitle().endsWith(searchTitleTag)) {
            tvPlayVideoTitle.setText(videoResult.getVideoName());
        } else {
            tvPlayVideoTitle.setText(v9MmanItem.getTitle());
        }
        tvPlayVideoAuthor.setText(videoResult.getOwnerName());
        tvPlayVideoAddDate.setText(videoResult.getAddDate());
        tvPlayVideoInfo.setText(videoResult.getUserOtherInfo());
        refreshAuthorFavoriteState();
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
        final String authorName = v9MmanItem.getVideoResult().getOwnerName();
        final String source = PlayVideoPresenter.isPornySource(v9MmanItem)
                ? AuthorFavorite.SOURCE_PORNY : AuthorFavorite.SOURCE_MMAN9;
        Observable.just(1)
                .map(integer -> {
                    // 实时查库判断收藏态，避免异步刷新完成前点击导致的重复收藏 / 漏删
                    boolean currentlyFav = presenter.isAuthorFavorited(authorKey, source);
                    if (currentlyFav) {
                        presenter.removeAuthorFavorite(authorKey, source);
                    } else {
                        presenter.addAuthorFavorite(authorKey, authorName, source);
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
                }, throwable -> showMessage("操作失败，请重试", TastyToast.ERROR));
    }

    private void refreshAuthorFavoriteState() {
        if (v9MmanItem == null || v9MmanItem.getVideoResult() == null
                || TextUtils.isEmpty(v9MmanItem.getVideoResult().getOwnerId())) {
            return;
        }
        final String authorKey = v9MmanItem.getVideoResult().getOwnerId();
        final String source = PlayVideoPresenter.isPornySource(v9MmanItem)
                ? AuthorFavorite.SOURCE_PORNY : AuthorFavorite.SOURCE_MMAN9;
        Observable.just(1)
                .map(integer -> presenter.isAuthorFavorited(authorKey, source))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(fav -> {
                    isAuthorFavorited = fav;
                    updateFavoriteIcon();
                }, throwable -> Logger.t(TAG).e(throwable, "读取作者收藏态失败"));
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
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.play_video, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.menu_play_collect) {
            favoriteVideo();
            return true;
        } else if (id == R.id.menu_play_download) {
            startDownloadVideo();
            return true;
        } else if (id == R.id.menu_play_share) {
            shareVideoUrl();
            return true;
        } else if (id == R.id.menu_play_comment) {
            showMessage("向下滑动即可评论", TastyToast.INFO);
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
        Intent serviceIntent = new Intent(this, HlsDownloadService.class);
        serviceIntent.setAction(HlsDownloadService.ACTION_START);
        serviceIntent.putExtra(HlsDownloadService.EXTRA_VIDEO_URL, videoUrl);
        serviceIntent.putExtra(HlsDownloadService.EXTRA_TITLE, v9MmanItem.getTitle());
        serviceIntent.putExtra(HlsDownloadService.EXTRA_FILE_NAME, sanitizeFileName(v9MmanItem.getTitle()));
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
        // 91porny 无账号体系，走本地收藏
        if (PlayVideoPresenter.isPornySource(v9MmanItem)) {
            favoriteDialog.show();
            io.reactivex.Observable.just(1)
                    .map(integer -> presenter.addLocalFavorite(v9MmanItem))
                    .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                    .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                    .subscribe(aBoolean -> {
                        dismissDialog();
                        showMessage("收藏成功（已保存到本地）", TastyToast.SUCCESS);
                    }, throwable -> {
                        dismissDialog();
                        showMessage("收藏失败", TastyToast.ERROR);
                    });
            return;
        }
        VideoResult videoResult = v9MmanItem.getVideoResult();
        if (!presenter.isUserLogin()) {
            goToLogin(KeysActivityRequestResultCode.LOGIN_ACTION_FOR_GET_UID);
            showMessage("请先登录", TastyToast.SUCCESS);
            return;
        }
        favoriteDialog.show();
        presenter.favorite(String.valueOf(presenter.getLoginUserId()), videoResult.getVideoId(), videoResult.getAuthorId());
    }

    private void shareVideoUrl() {
        if (v9MmanItem == null || v9MmanItem.getVideoResultId() == 0) {
            showMessage("还未成功解析视频链接，不能分享！", TastyToast.INFO);
            return;
        }
        String url = v9MmanItem.getVideoResult().getVideoUrl();
        if (TextUtils.isEmpty(url)) {
            showMessage("还未成功解析视频链接，不能分享！", TastyToast.INFO);
            return;
        }
        Intent textIntent = new Intent(Intent.ACTION_SEND);
        textIntent.setType("text/plain");
        textIntent.putExtra(Intent.EXTRA_TEXT, "链接：" + url);
        startActivity(Intent.createChooser(textIntent, "分享视频地址"));
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
        if (newConfig.orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE || newConfig.orientation == ActivityInfo.SCREEN_ORIENTATION_USER) {
            //这里没必要，因为我们使用的是setColorForSwipeBack，并不会有这个虚拟的view，而是设置的padding
            StatusBarUtil.hideFakeStatusBarView(this);
        } else if (newConfig.orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            setStatusBarColor(ContextCompat.getColor(this, R.color.colorPrimary));
        }
    }

    public void setV9MmanItems(V9MmanItem v9MmanItems) {
        this.v9MmanItem = v9MmanItems;
    }
}
