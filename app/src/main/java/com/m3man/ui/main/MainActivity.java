package com.m3man.ui.main;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
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
import com.m3man.constants.Keys;
import com.m3man.constants.PermissionConstants;
import com.m3man.data.model.Notice;
import com.m3man.data.model.UpdateVersion;
import com.m3man.data.network.Api;
import com.m3man.eventbus.LowMemoryEvent;
import com.m3man.ui.MvpActivity;
import com.m3man.ui.basemain.BaseMainFragment;
import com.m3man.ui.download.DownloadActivity;
import com.m3man.ui.mine.MineFragment;
import com.m3man.ui.mman9video.Main9MmanVideoFragment;
import com.m3man.ui.update.UpdateActivity;
import com.m3man.ui.mman9video.search.SearchPornyFragment;
import com.m3man.ui.recommend.RecommendFeedFragment;
import com.m3man.ui.setting.SettingActivity;
import com.m3man.utils.ApkVersionUtils;
import com.m3man.utils.AppLog;
import com.m3man.utils.FragmentUtils;
import com.m3man.utils.NotificationChannelHelper;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.content.ContextCompat;

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
    private int notifyPermCode = 500;
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
    private RecommendFeedFragment mRecommendFeedFragment;
    private SearchPornyFragment mSearchPornyFragment;
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
        // 9mman 站内搜索入口已移除，仅保留主界面底部「搜索」Tab（91porny 搜索）
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
        requestNotificationPermission();

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
        AppLog.i("UpdateCheck", "发现新版本 v" + updateVersion.getVersionName()
                + " (code=" + updateVersion.getVersionCode() + ") url=" + updateVersion.getApkDownloadUrl());
        showUpdateDialog(updateVersion);
    }

    @Override
    public void noNeedUpdate() {
        Logger.t(TAG).d("当前已是最新版本");
        AppLog.i("UpdateCheck", "当前已是最新版本 (code=" + ApkVersionUtils.getVersionCode(this) + ")");
        //没有更新再检查公告
        presenter.checkNewNotice();
    }

    @Override
    public void checkUpdateError(String message) {
        Logger.t(TAG).d("检查更新错误：" + message);
        AppLog.e("UpdateCheck", "检查更新失败: " + message);
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
                .addItem(ResourceUtil.getDrawable(this, R.drawable.ic_my_download), Tags.TAG_MY_DOWNLOAD)
                .addItem(ResourceUtil.getDrawable(this, R.drawable.ic_video_library_black_24dp), Tags.TAG_PRON_9_VIDEO)
                .setCheckedIndex(checkIndex)
                .setOnSheetItemClickListener((dialog, itemView, position, tag) -> {
                    dialog.dismiss();
                    switch (tag) {
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
        // 首页底部导航栏固定展示，不再提供自动隐藏开关。
        bottomNavigationBar.setAutoHideEnabled(false);
        bottomNavigationBar.initialise();
    }

    private void handlerContentMargin() {
        if (contentFrameLayout == null || bottomNavigationBar == null) {
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
                // 推荐流固定嵌入主界面，不再启动新 Activity
                if (mRecommendFeedFragment == null) {
                    mRecommendFeedFragment = RecommendFeedFragment.getInstance();
                }
                mCurrentFragment = FragmentUtils.switchContent(fragmentManager, mCurrentFragment,
                        mRecommendFeedFragment, contentFrameLayout.getId(), position, false);
                // M61：采纳真正被显示的实例，防止字段指向未挂载的“幽灵”对象
                if (mCurrentFragment instanceof RecommendFeedFragment) {
                    mRecommendFeedFragment = (RecommendFeedFragment) mCurrentFragment;
                }
                hideFloatingActionButton(fabSearch);
                break;
            case 2:
                // 分分钟固定嵌入主界面，不再启动新 Activity
                if (!presenter.isPornyEnabled()) {
                    showMessage(getString(R.string.main_enable_porny_source), TastyToast.INFO);
                }
                if (mSearchPornyFragment == null) {
                    mSearchPornyFragment = SearchPornyFragment.getInstance();
                }
                mCurrentFragment = FragmentUtils.switchContent(fragmentManager, mCurrentFragment,
                        mSearchPornyFragment, contentFrameLayout.getId(), position, false);
                if (mCurrentFragment instanceof SearchPornyFragment) {
                    mSearchPornyFragment = (SearchPornyFragment) mCurrentFragment;
                }
                hideFloatingActionButton(fabSearch);
                break;
            case 3:
                if (mMineFragment == null) {
                    mMineFragment = MineFragment.getInstance();
                }
                mCurrentFragment = FragmentUtils.switchContent(fragmentManager, mCurrentFragment, mMineFragment, contentFrameLayout.getId(), position, false);
                if (mCurrentFragment instanceof MineFragment) {
                    mMineFragment = (MineFragment) mCurrentFragment;
                }
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
                if (mCurrentFragment instanceof Main9MmanVideoFragment) {
                    mMain9MmanVideoFragment = (Main9MmanVideoFragment) mCurrentFragment;
                }
                firstTabShow = Tags.TAG_PRON_9_VIDEO;
                presenter.setMainFirstTabShow(Tags.TAG_PRON_9_VIDEO);
                if (presenter.haveNotSetV9pronAddress()) {
                    showMessage(getString(R.string.main_config_video_address), TastyToast.INFO);
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
        builder.setTitle(getString(R.string.main_tip_title));
        builder.setMessage(getString(R.string.main_need_address_msg));
        builder.addAction(getString(R.string.main_go_set), (dialog, index) -> {
            dialog.dismiss();
            Intent intent = new Intent(context, SettingActivity.class);
            startActivityWithAnimation(intent);
        });
        builder.addAction(getString(R.string.back), (dialog, index) -> dialog.dismiss());
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
     * M23/targetSdk34：API 33+ 通知必须运行时授权，否则下载/升级进度通知静默不显示。
     * 申请失败不阻塞，后续相关通知仅在授权后可见。
     */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            AndPermission.with(this)
                    .requestCode(notifyPermCode)
                    .permission(Manifest.permission.POST_NOTIFICATIONS)
                    .callback(new PermissionListener() {
                        @Override
                        public void onSucceed(int requestCode, @NonNull List<String> grantedPermissions) {
                            // 已授权，后续通知正常显示
                        }

                        @Override
                        public void onFailed(int requestCode, @NonNull List<String> deniedPermissions) {
                            // 未授权，请求头不会弹系统通知
                        }
                    })
                    .start();
        }
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
                // M-13: 权限授予成功后，检查存储权限并创建下载目录
                if (hasStorageAccess()) {
                    if (!file.exists()) {
                        if (!file.mkdirs()) {
                            showMessage(getString(R.string.main_create_dir_failed), TastyToast.ERROR);
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
                // M-13: 权限被拒绝，检查存储权限并引导用户去设置
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
                showMessage(getString(R.string.main_storage_perm_denied), TastyToast.WARNING);
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
        // M112：非首页 Tab 时，返回键先回到「视频」首页，而不是直接进入「再按一次退出」流程——
        // 推荐流/搜索这类沉浸式页面里双击退出极易误触，且用户预期的「返回」是回到主导航。
        if (selectIndex > 0) {
            doOnTabSelected(0);
            return;
        }
        showMessage(getString(R.string.main_press_again_exit), TastyToast.INFO);
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
            // M97：删除 postDelayed killProcess 强杀——HLS 分片合并/转码进行中进程被强杀
            // 会产生无 moov 的脏文件与残留 hls_* 临时目录；finishAffinity 后交由系统正常回收。
        }
    }

    private void showUpdateDialog(final UpdateVersion updateVersion) {
        QMUIDialog.MessageDialogBuilder builder = new QMUIDialog.MessageDialogBuilder(this);
        builder.setTitle(getString(R.string.main_found_new_version, updateVersion.getVersionName()));
        builder.setMessage(updateVersion.getUpdateMessage());
        builder.addAction(getString(R.string.main_update_now), (dialog, index) -> {
            dialog.dismiss();
            Intent intent = new Intent(MainActivity.this, UpdateActivity.class);
            intent.putExtra("updateVersion", updateVersion);
            startActivityWithAnimation(intent);
        });
        builder.addAction(getString(R.string.main_update_later), (dialog, index) -> dialog.dismiss());
        builder.addAction(getString(R.string.main_ignore_version), (dialog, index) -> {
            //保存版本号，用户对于此版本选择了不在提示
            presenter.setIgnoreUpdateVersionCode(updateVersion.getVersionCode());
            dialog.dismiss();
        });
        builder.show();
    }

    private void showNewNoticeDialog(final Notice notice) {
        QMUIDialog.MessageDialogBuilder builder = new QMUIDialog.MessageDialogBuilder(this);
        builder.setTitle(getString(R.string.main_new_notice_title));
        builder.setMessage(notice.getNoticeMessage());
        builder.addAction(getString(R.string.main_got_it), (dialog, index) -> {
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

    private void setNull(int position) {
        switch (position) {
            case 0:
                mMain9MmanVideoFragment = null;
                break;
            case 1:
                mRecommendFeedFragment = null;
                break;
            case 2:
                mSearchPornyFragment = null;
                break;
            case 3:
                mMineFragment = null;
                break;
            default:
        }
    }
}
