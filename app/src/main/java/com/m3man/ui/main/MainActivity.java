package com.m3man.ui.main;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.IntRange;
import android.support.annotation.NonNull;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.ashokvarma.bottomnavigation.BottomNavigationBar;
import com.ashokvarma.bottomnavigation.BottomNavigationItem;
import com.devbrackets.android.exomedia.util.ResourceUtil;
import com.liulishuo.filedownloader.FileDownloader;
import com.orhanobut.logger.Logger;
import com.qmuiteam.qmui.util.QMUIDisplayHelper;
import com.qmuiteam.qmui.widget.dialog.QMUIBottomSheet;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.BuildConfig;
import com.m3man.R;
import com.m3man.constants.Constants;
import com.m3man.constants.Keys;
import com.m3man.constants.KeysActivityRequestResultCode;
import com.m3man.constants.PermissionConstants;
import com.m3man.data.model.Notice;
import com.m3man.data.model.UpdateVersion;
import com.m3man.data.network.Api;
import com.m3man.eventbus.LowMemoryEvent;
import com.m3man.eventbus.UrlRedirectEvent;
import com.m3man.service.UpdateDownloadService;
import com.m3man.ui.MvpActivity;
import com.m3man.ui.basemain.BaseMainFragment;
import com.m3man.ui.download.DownloadActivity;
import com.m3man.ui.mine.MineFragment;
import com.m3man.ui.mman9video.Main9MmanVideoFragment;
import com.m3man.ui.mman9video.search.SearchActivity;
import com.m3man.ui.update.UpdateActivity;
import com.m3man.ui.mman9video.search.SearchPornyActivity;
import com.m3man.ui.mman9video.user.UserLoginActivity;
import com.m3man.ui.recommend.RecommendFeedActivity;
import com.m3man.ui.setting.SettingActivity;
import com.m3man.utils.ApkVersionUtils;
import com.m3man.utils.FragmentUtils;
import com.m3man.utils.NotificationChannelHelper;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.support.v4.content.ContextCompat;

import com.m3man.utils.SDCardUtils;
import com.m3man.utils.Tags;
import com.yanzhenjie.permission.AndPermission;
import com.yanzhenjie.permission.PermissionListener;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;

/**
 * @author flymegoc
 */
public class MainActivity extends MvpActivity<MainView, MainPresenter> implements MainView {

    private static final String TAG = MainActivity.class.getSimpleName();

    @BindView(R.id.bottom_navigation_bar)
    BottomNavigationBar bottomNavigationBar;
    @BindView(R.id.fab_search)
    FloatingActionButton fabSearch;
    @BindView(R.id.content)
    FrameLayout contentFrameLayout;

    private Fragment mCurrentFragment;
    private int permisionCode = 300;
    private int permisionReqCode = 400;
    private String[] permission = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ? new String[]{Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES}
            : PermissionConstants.getPermissions(PermissionConstants.STORAGE);

    /**
     * M40：用“真实写入能力”判断存储是否可用，避免旧版 AndPermission 在 Android 11+
     * 上对 READ/WRITE_EXTERNAL_STORAGE 的误判（targetSdk=28 走 legacy 存储，能写即可）。
     */
    private boolean hasStorageAccess() {
        if (SDCardUtils.isDownloadDirWritable(this)) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private Main9MmanVideoFragment mMain9MmanVideoFragment;
    private MineFragment mMineFragment;
    private FragmentManager fragmentManager;
    private int selectIndex;
    private String firstTabShow;
    private boolean isBackground = false;

    private List<String> firstTagsArray = new ArrayList<>();

    @Inject
    MainPresenter mainPresenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        NotificationChannelHelper.initChannel(this);
        ButterKnife.bind(this);
        firstTagsArray.add(Tags.TAG_SEARCH_PORN_VIDEO);
        firstTagsArray.add(Tags.TAG_MY_DOWNLOAD);
        firstTagsArray.add(Tags.TAG_PRON_9_VIDEO);

        fragmentManager = getSupportFragmentManager();
        selectIndex = getIntent().getIntExtra(Keys.KEY_SELECT_INDEX, 0);
        if (savedInstanceState != null) {
            selectIndex = savedInstanceState.getInt(Keys.KEY_SELECT_INDEX);
        }
        handlerContentMargin();
        initBottomNavigationBar(selectIndex);

        makeDirAndCheckPermission();

        fabSearch.setOnClickListener(v -> doOnFloatingActionButtonClick(selectIndex));
        firstTabShow = presenter.getMainFirstTabShow();
        doOnTabSelected(selectIndex);
        checkNeedToShowUpdateOrNoticeDialog();
    }

    /**
     * 取消开屏页后，更新与公告检查直接在主界面发起。
     * 结果通过 needUpdate / haveNewNotice 等回调返回。
     */
    private void checkNeedToShowUpdateOrNoticeDialog() {
        if (presenter.getNoticeVersionCode() == Notice.ABANDONED) {
            throw new IllegalStateException("The project has been abandoned.");
        }
        int versionCode = ApkVersionUtils.getVersionCode(this);
        if (versionCode == 0) {
            Logger.t(TAG).d("获取应用版本失败，跳过更新检查，直接查公告");
            presenter.checkNewNotice();
            return;
        }
        presenter.checkUpdate(versionCode);
    }

    @Override
    public void needUpdate(UpdateVersion updateVersion) {
        //用户已选择该版本不再提示
        if (presenter.getIgnoreUpdateVersionCode() == updateVersion.getVersionCode()) {
            return;
        }
        showUpdateDialog(updateVersion);
    }

    @Override
    public void noNeedUpdate() {
        Logger.t(TAG).d("当前已是最新版本");
        //没有更新再检查公告
        presenter.checkNewNotice();
    }

    @Override
    public void checkUpdateError(String message) {
        Logger.t(TAG).d("检查更新错误：" + message);
        presenter.checkNewNotice();
    }

    @Override
    public void haveNewNotice(Notice notice) {
        showNewNoticeDialog(notice);
    }

    @Override
    public void noNewNotice() {
        Logger.t(TAG).d("没有新公告");
    }

    @Override
    public void checkNewNoticeError(String message) {
        Logger.t(TAG).d("检查新公告：" + message);
    }

    private void doOnFloatingActionButtonClick(@IntRange(from = 0, to = 1) int position) {
        switch (position) {
            case 0:
                showVideoBottomSheet(firstTagsArray.indexOf(firstTabShow));
                break;
            default:
        }
    }

    private void showVideoBottomSheet(final int checkIndex) {
        new QMUIBottomSheet.BottomListSheetBuilder(this, true)
                .addItem(ResourceUtil.getDrawable(this, R.drawable.ic_search_black_24dp), Tags.TAG_SEARCH_PORN_VIDEO)
                .addItem(ResourceUtil.getDrawable(this, R.drawable.ic_my_download), Tags.TAG_MY_DOWNLOAD)
                .addItem(ResourceUtil.getDrawable(this, R.drawable.ic_video_library_black_24dp), Tags.TAG_PRON_9_VIDEO)
                .setCheckedIndex(checkIndex)
                .setOnSheetItemClickListener((dialog, itemView, position, tag) -> {
                    dialog.dismiss();
                    switch (tag) {
                        case Tags.TAG_SEARCH_PORN_VIDEO:
                            goToSearchVideo();
                            break;
                        case Tags.TAG_MY_DOWNLOAD:
                            Intent intent = new Intent(context, DownloadActivity.class);
                            startActivityWithAnimation(intent);
                            break;
                        default:
                            handlerFirstTabClickToShow(tag, selectIndex, true);
                    }
                })
                .build()
                .show();
    }

    private void initBottomNavigationBar(@IntRange(from = 0, to = 3) int position) {
        bottomNavigationBar.addItem(new BottomNavigationItem(ResourceUtil.getDrawable(this, R.drawable.ic_video_library_black_24dp), R.string.title_video));
        bottomNavigationBar.addItem(new BottomNavigationItem(ResourceUtil.getDrawable(this, R.drawable.ic_recommend_black_24dp), R.string.title_recommend));
        bottomNavigationBar.addItem(new BottomNavigationItem(ResourceUtil.getDrawable(this, R.drawable.ic_search_black_24dp), R.string.title_porny));
        bottomNavigationBar.addItem(new BottomNavigationItem(ResourceUtil.getDrawable(this, R.drawable.ic_menu_black_24dp), R.string.title_me));

        bottomNavigationBar.setMode(BottomNavigationBar.MODE_FIXED);
        // MODE_FIXED 下两个 Tab 会自动均分整条导航栏宽度（各占一半）
        bottomNavigationBar.setActiveColor(R.color.bottom_navigation_bar_active);
        bottomNavigationBar.setInActiveColor(R.color.bottom_navigation_bar_inactive);
        bottomNavigationBar.setBackgroundStyle(BottomNavigationBar.BACKGROUND_STYLE_STATIC);

        bottomNavigationBar.setFirstSelectedPosition(position);
        bottomNavigationBar.setTabSelectedListener(new BottomNavigationBar.SimpleOnTabSelectedListener() {
            @Override
            public void onTabSelected(int position) {
                doOnTabSelected(position);
            }
        });
        bottomNavigationBar.setBarBackgroundColor(R.color.bottom_navigation_bar_background);
        bottomNavigationBar.setFab(fabSearch);
        bottomNavigationBar.setAutoHideEnabled(!presenter.isFixMainNavigation());
        bottomNavigationBar.initialise();
    }

    private void handlerContentMargin() {
        if (contentFrameLayout == null || bottomNavigationBar == null || !presenter.isFixMainNavigation()) {
            return;
        }
        CoordinatorLayout.LayoutParams layoutParams = (CoordinatorLayout.LayoutParams) contentFrameLayout.getLayoutParams();
        layoutParams.bottomMargin = QMUIDisplayHelper.getActionBarHeight(this);
        contentFrameLayout.setLayoutParams(layoutParams);
    }

    private void doOnTabSelected(@IntRange(from = 0, to = 3) int position) {
        switch (position) {
            case 0:
                handlerFirstTabClickToShow(firstTabShow, position, false);
                showFloatingActionButton(fabSearch);
                break;
            case 1:
                // 推荐流（抖音式上下滑），独立全屏页
                Intent recommendIntent = new Intent(context, RecommendFeedActivity.class);
                startActivityWithAnimation(recommendIntent);
                hideFloatingActionButton(fabSearch);
                break;
            case 2:
                // 91porny 第二视频源（首版仅搜索接入）
                if (!presenter.isPornyEnabled()) {
                    showMessage("请在设置中启用 91porny 源", TastyToast.INFO);
                    break;
                }
                Intent pornyIntent = new Intent(context, SearchPornyActivity.class);
                startActivityWithAnimation(pornyIntent);
                hideFloatingActionButton(fabSearch);
                break;
            case 3:
                if (mMineFragment == null) {
                    mMineFragment = MineFragment.getInstance();
                }
                mCurrentFragment = FragmentUtils.switchContent(fragmentManager, mCurrentFragment, mMineFragment, contentFrameLayout.getId(), position, false);
                hideFloatingActionButton(fabSearch);
                break;
            default:
        }
        selectIndex = position;
        // M44：同步底部导航选中态，避免 fragment 切换失败后导航状态漂移导致点击无响应
        if (bottomNavigationBar != null
                && bottomNavigationBar.getCurrentSelectedPosition() != position) {
            bottomNavigationBar.selectTab(position);
        }
    }

    private void handlerFirstTabClickToShow(String tag, int itemId, boolean isInnerReplace) {
        switch (tag) {
            case Tags.TAG_PRON_9_VIDEO:
                // M44：地址未设置时不再 return 拦截（否则视频 tab 点击完全无响应），
                // 仍切换到对应 fragment（其加载失败有错误页/重试），保证 tab 点击总有反馈
                if (mMain9MmanVideoFragment == null) {
                    mMain9MmanVideoFragment = Main9MmanVideoFragment.getInstance();
                }
                mCurrentFragment = FragmentUtils.switchContent(fragmentManager, mCurrentFragment, mMain9MmanVideoFragment, contentFrameLayout.getId(), itemId, isInnerReplace);
                firstTabShow = Tags.TAG_PRON_9_VIDEO;
                presenter.setMainFirstTabShow(Tags.TAG_PRON_9_VIDEO);
                if (presenter.haveNotSetV9pronAddress()) {
                    showMessage("请先在设置中配置视频地址", TastyToast.INFO);
                }
                break;
            default:
                // M44：firstTabShow 异常/未知时回退默认视频分类，保证视频 tab 点击始终有响应
                presenter.setMainFirstTabShow(Tags.TAG_PRON_9_VIDEO);
                firstTabShow = Tags.TAG_PRON_9_VIDEO;
                handlerFirstTabClickToShow(Tags.TAG_PRON_9_VIDEO, itemId, isInnerReplace);
                break;
        }
    }

    private void showNeedSetAddressDialog() {
        QMUIDialog.MessageDialogBuilder builder = new QMUIDialog.MessageDialogBuilder(context);
        builder.setTitle("温馨提示");
        builder.setMessage("还未设置对应地址，现在去设置？");
        builder.addAction("去设置", (dialog, index) -> {
            dialog.dismiss();
            Intent intent = new Intent(context, SettingActivity.class);
            startActivityWithAnimation(intent);
        });
        builder.addAction("返回", (dialog, index) -> dialog.dismiss());
        builder.show();
    }

    private void hideFloatingActionButton(FloatingActionButton fabSearch) {
        ViewGroup.LayoutParams layoutParams = fabSearch.getLayoutParams();
        if (layoutParams instanceof CoordinatorLayout.LayoutParams) {
            CoordinatorLayout.LayoutParams coLayoutParams = (CoordinatorLayout.LayoutParams) layoutParams;
            FloatingActionButton.Behavior behavior = new FloatingActionButton.Behavior();
            coLayoutParams.setBehavior(behavior);
        }
        fabSearch.hide();
    }

    private void showFloatingActionButton(final FloatingActionButton fabSearch) {
        fabSearch.show(new FloatingActionButton.OnVisibilityChangedListener() {
            @Override
            public void onShown(FloatingActionButton fab) {
                fabSearch.requestLayout();
                bottomNavigationBar.setFab(fab);
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(Keys.KEY_SELECT_INDEX, selectIndex);
    }

    /**
     * 申请权限并创建下载目录
     */
    private void makeDirAndCheckPermission() {
        if (!hasStorageAccess()) {
            AndPermission.with(this)
                    .requestCode(permisionCode)
                    .permission(permission)
                    .rationale((requestCode, rationale) -> {
                        // 此对话框可以自定义，调用rationale.resume()就可以继续申请。
                        AndPermission.rationaleDialog(MainActivity.this, rationale).show();
                    })
                    .callback(listener)
                    .start();
        }
    }

    private PermissionListener listener = new PermissionListener() {
        File file = new File(SDCardUtils.DOWNLOAD_VIDEO_PATH);

        @Override
        public void onSucceed(int requestCode, @NonNull List<String> grantedPermissions) {
            // 权限申请成功回调。

            // 这里的requestCode就是申请时设置的requestCode。
            // 和onActivityResult()的requestCode一样，用来区分多个不同的请求。
            if (requestCode == permisionCode) {
                // TODO ...
                if (hasStorageAccess()) {
                    if (!file.exists()) {
                        if (!file.mkdirs()) {
                            showMessage("创建下载目录失败了", TastyToast.ERROR);
                        }
                    }
                } else {
                    AndPermission.defaultSettingDialog(MainActivity.this, permisionReqCode).show();
                }
            }
        }

        @Override
        public void onFailed(int requestCode, @NonNull List<String> deniedPermissions) {
            // 权限申请失败回调。
            if (requestCode == permisionCode) {
                // TODO ...
                if (!hasStorageAccess()) {
                    // 是否有不再提示并拒绝的权限。
                    if (AndPermission.hasAlwaysDeniedPermission(MainActivity.this, deniedPermissions)) {
                        // 第一种：用AndPermission默认的提示语。
                        AndPermission.defaultSettingDialog(MainActivity.this, permisionReqCode).show();
                    } else {
                        AndPermission.defaultSettingDialog(MainActivity.this, permisionReqCode).show();
                    }
                }
            }
        }
    };


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == permisionReqCode) {
            if (!hasStorageAccess()) {
                showMessage("你拒绝了读写存储卡权限，这将影响下载视频等功能！", TastyToast.WARNING);
            }
        }
        if (mCurrentFragment != null) {
            mCurrentFragment.onActivityResult(requestCode, resultCode, data);
        }
    }

    public static final int MIN_CLICK_DELAY_TIME = 2000;
    private long lastClickTime = 0;

    @Override
    public void onBackPressed() {
        if (mCurrentFragment instanceof BaseMainFragment && ((BaseMainFragment) mCurrentFragment).onBackPressed()) {
            return;
        }
        showMessage("再次点击退出程序", TastyToast.INFO);
        long currentTime = Calendar.getInstance().getTimeInMillis();
        if (currentTime - lastClickTime > MIN_CLICK_DELAY_TIME) {
            lastClickTime = currentTime;
        } else {
            FileDownloader.getImpl().pauseAll();
            FileDownloader.getImpl().unBindService();
            //没啥意义
            if (!existActivityWithAnimation && !isFinishing()) {
                super.onBackPressed();
            }
            finishAffinity();
            new Handler().postDelayed(() -> {
                int pid = android.os.Process.myPid();
                android.os.Process.killProcess(pid);
            }, 500);
        }
    }

    private void goToSearchVideo() {
        String[] items = {"搜视频地址视频"};
        new QMUIDialog.CheckableDialogBuilder(this)
                .setTitle("搜索啥呀")
                .addItems(items, (dialog, which) -> {
                    dialog.dismiss();
                    switch (which) {
                        case 0:
                            if (!presenter.isUserLogin()) {
                                showMessage("请先登录", TastyToast.INFO);
                                Intent intent = new Intent(MainActivity.this, UserLoginActivity.class);
                                intent.putExtra(Keys.KEY_INTENT_LOGIN_FOR_ACTION, KeysActivityRequestResultCode.LOGIN_ACTION_FOR_SEARCH_91PRON_VIDEO);
                                startActivityForResultWithAnimation(intent, Constants.USER_LOGIN_REQUEST_CODE);
                                return;
                            }
                            Intent intent = new Intent(MainActivity.this, SearchActivity.class);
                            startActivityWithAnimation(intent);
                            break;
                    }
                })
                .show();

    }

    private void showUpdateDialog(final UpdateVersion updateVersion) {
        QMUIDialog.MessageDialogBuilder builder = new QMUIDialog.MessageDialogBuilder(this);
        builder.setTitle("发现新版本--v" + updateVersion.getVersionName());
        builder.setMessage(updateVersion.getUpdateMessage());
        builder.addAction("立即更新", (dialog, index) -> {
            dialog.dismiss();
            Intent intent = new Intent(MainActivity.this, UpdateActivity.class);
            intent.putExtra("updateVersion", updateVersion);
            startActivityWithAnimation(intent);
        });
        builder.addAction("稍后更新", (dialog, index) -> dialog.dismiss());
        builder.addAction("该版本不再提示", (dialog, index) -> {
            //保存版本号，用户对于此版本选择了不在提示
            presenter.setIgnoreUpdateVersionCode(updateVersion.getVersionCode());
            dialog.dismiss();
        });
        builder.show();
    }

    private void showNewNoticeDialog(final Notice notice) {
        QMUIDialog.MessageDialogBuilder builder = new QMUIDialog.MessageDialogBuilder(this);
        builder.setTitle("新公告");
        builder.setMessage(notice.getNoticeMessage());
        builder.addAction("我知道了", (dialog, index) -> {
            dialog.dismiss();
            presenter.saveNoticeVersionCode(notice.getVersionCode());
        });
        builder.show();
    }

    @NonNull
    @Override
    public MainPresenter createPresenter() {
        return mainPresenter;
    }


    @Override
    public void showError(String message) {

    }

    @Override
    public void showLoading(boolean pullToRefresh) {

    }

    @Override
    public void showContent() {

    }

    @Override
    public void showMessage(String msg, int type) {
        super.showMessage(msg, type);
    }

    @Override
    protected void onResume() {
        super.onResume();
        isBackground = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        isBackground = true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onTryToReleaseMemory(LowMemoryEvent lowMemoryEvent) {
        if (contentFrameLayout == null || fragmentManager == null || !isBackground) {
            return;
        }
        if (!BuildConfig.DEBUG) {
            //Bugsnag.notify(new Throwable(TAG + ":LowMemory,try to release some memory now!"), Severity.INFO);
        }
        try {
            Logger.t(TAG).d("start try to release memory ....");
            FragmentTransaction bt = fragmentManager.beginTransaction();
            for (int i = 0; i < 4; i++) {
                //只移除当前未选中的
                if (i != selectIndex) {
                    String name = FragmentUtils.makeFragmentName(contentFrameLayout.getId(), i);
                    Fragment fragment = fragmentManager.findFragmentByTag(name);
                    if (fragment != null) {
                        bt.remove(fragment);
                        setNull(i);
                    }
                }
            }
            bt.commitAllowingStateLoss();
            //通知系统尝试释放内存
            System.gc();
            System.runFinalization();
            Logger.t(TAG).d("try to release memory success !!!");
        } catch (Exception e) {
            e.printStackTrace();
            if (!BuildConfig.DEBUG) {
                //Bugsnag.notify(new Throwable(TAG + " tryToReleaseMemory error::", e), Severity.WARNING);
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void urlRedirectEvent(final UrlRedirectEvent urlRedirectEvent) {
        if (isBackground) {
            return;
        }
        QMUIDialog.MessageDialogBuilder builder = new QMUIDialog.MessageDialogBuilder(this);
        builder.setTitle("温馨提示");
        builder.setMessage("服务器连接发生跳转，新地址为：\n" + urlRedirectEvent.getNewUrl() + "\n原地址：\n" + urlRedirectEvent.getOldUrl() + "\n是否保存为最新地址？");
        builder.addAction("保存", (dialog, index) -> {
            if (Api.PORN9_VIDEO_DOMAIN_NAME.equals(urlRedirectEvent.getHeader())) {
                presenter.setMman9VideoAddress(urlRedirectEvent.getNewUrl());
                showMessage("保存成功", TastyToast.SUCCESS);
            } else {
                showMessage("保存失败，信息错误", TastyToast.ERROR);
            }

            dialog.dismiss();
        });
        builder.addAction("取消", (dialog, index) -> dialog.dismiss());
        builder.show();
    }

    private void setNull(int position) {
        switch (position) {
            case 0:
                mMain9MmanVideoFragment = null;
                break;
            case 3:
                mMineFragment = null;
                break;
            default:
        }
    }
}
