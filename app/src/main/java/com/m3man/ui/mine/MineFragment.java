package com.m3man.ui.mine;


import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.app.AppCompatDelegate;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import android.support.v7.app.AlertDialog;

import com.qmuiteam.qmui.util.QMUIDisplayHelper;
import com.qmuiteam.qmui.widget.grouplist.QMUICommonListItemView;
import com.qmuiteam.qmui.widget.grouplist.QMUIGroupListView;
import com.m3man.R;
import com.m3man.constants.Constants;
import com.m3man.constants.Keys;
import com.m3man.constants.KeysActivityRequestResultCode;
import com.m3man.data.model.User;
import com.m3man.ui.MvpFragment;
import com.m3man.ui.about.AboutActivity;
import com.m3man.ui.download.DownloadActivity;
import com.m3man.ui.main.MainActivity;
import com.m3man.ui.mman9video.favorite.FavoriteActivity;
import com.m3man.ui.mman9video.author.AuthorFavoriteActivity;
import com.m3man.ui.mman9video.history.HistoryActivity;
import com.m3man.ui.mman9video.user.UserLoginActivity;
import com.m3man.ui.proxy.ProxySettingActivity;
import com.m3man.ui.setting.SettingActivity;
import com.m3man.utils.UserHelper;
import com.m3man.widget.ObservableScrollView;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;

import static android.app.Activity.RESULT_OK;

/**
 * A simple {@link Fragment} subclass.
 *
 * @author flymegoc
 */
public class MineFragment extends MvpFragment<MineView, MinePresenter> implements View.OnClickListener {

    private static final String TAG = MineFragment.class.getSimpleName();
    @BindView(R.id.tv_nav_username)
    TextView tvNavUsername;
    @BindView(R.id.tv_nav_last_login_time)
    TextView tvNavLastLoginTime;
    @BindView(R.id.tv_nav_last_login_ip)
    TextView tvNavLastLoginIp;
    @BindView(R.id.mine_list)
    QMUIGroupListView mineList;
    Unbinder unbinder;
    @BindView(R.id.imageView)
    ImageView imageView;
    @BindView(R.id.ov_setting_wrapper)
    ObservableScrollView observableScrollView;
    @BindView(R.id.root_layout)
    RelativeLayout rootRelativeLayout;

    private String myFavoriteStr;
    private String proxyStr;
    public String myDownloadStr;
    private String viewHistoryStr;
    private String nightModeStr;
    private String aboutMeStr;
    private String moreSettingStr;

    private QMUICommonListItemView logoutItemView;

    private int scrollYPosition = 0;
    private QMUICommonListItemView openProxyItemWithSwitch;

    /**
     * 恢复滚动位置的延迟任务，需要在onDestroyView中移除，避免Fragment销毁后回调造成内存泄漏/空指针
     */
    private Runnable restoreScrollRunnable;

    @Inject
    protected MinePresenter minePresenter;

    public MineFragment() {

        // Required empty public constructor
    }

    public static MineFragment getInstance() {
        return new MineFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        super.onCreateView(inflater, container, savedInstanceState);
        return inflater.inflate(R.layout.fragment_mine, container, false);
    }

    @NonNull
    @Override
    public MinePresenter createPresenter() {
        return minePresenter;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        unbinder = ButterKnife.bind(this, view);
        imageView.setOnClickListener(this);
        observableScrollView.setOnScollChangedListener(new ObservableScrollView.OnScollChangedListener() {
            @Override
            public void onScrollChanged(ObservableScrollView scrollView, int x, int y, int oldx, int oldy) {
                scrollYPosition = y;
            }
        });
        restoreScrollRunnable = new Runnable() {
            @Override
            public void run() {
                //Fragment可能已经销毁，做安全校验
                if (!isAdded() || observableScrollView == null || presenter == null) {
                    return;
                }
                int savedScrollY = presenter.getSettingScrollViewScrollPosition();
                if (savedScrollY > 0) {
                    presenter.setSettingScrollViewScrollPosition(0);
                    observableScrollView.scrollTo(0, savedScrollY);
                }
            }
        };
        observableScrollView.postDelayed(restoreScrollRunnable, 200);
        initMineSection();
        handlerMargin();
    }

    private void handlerMargin() {
        if (presenter.isFixMainNavigation()) {
            return;
        }
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) rootRelativeLayout.getLayoutParams();
        layoutParams.bottomMargin = QMUIDisplayHelper.getActionBarHeight(context);
        rootRelativeLayout.setLayoutParams(layoutParams);
    }

    @Override
    public void onResume() {
        super.onResume();
        setUpUserInfo(presenter.getLoginUser());
        if (logoutItemView != null) {
            logoutItemView.setVisibility(presenter.isUserLogin() ? View.VISIBLE : View.GONE);
        }
        String proxyStr = presenter.getProxyIpAddress();
        int proxyPort = presenter.getProxyPort();
        updateProxySetUI(proxyStr, proxyPort);
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        initStr();
    }

    private void initStr() {
        myFavoriteStr = getString(R.string.my_collect);
        proxyStr = getString(R.string.proxy_setting);
        myDownloadStr = getString(R.string.my_download);
        viewHistoryStr = getString(R.string.history_views);
        nightModeStr = getString(R.string.night_mode);
        aboutMeStr = getString(R.string.about_me);
        moreSettingStr = getString(R.string.more_setting);
    }

    private void initMineSection() {

        boolean openNightMode = presenter.isOpenNightMode();
        QMUICommonListItemView openNightModeItemWithSwitch = mineList.createItemView(nightModeStr);
        openNightModeItemWithSwitch.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_SWITCH);
        openNightModeItemWithSwitch.getSwitch().setChecked(openNightMode);
        openNightModeItemWithSwitch.getSwitch().setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                presenter.setOpenNightMode(isChecked);
                presenter.setSettingScrollViewScrollPosition(scrollYPosition);
                AppCompatDelegate.setDefaultNightMode(isChecked ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
                Intent intent = new Intent(context, MainActivity.class);
                // 夜间模式切换后重建主界面，回到「我的」这个 Tab（底部导航第 4 个，下标 3）
                intent.putExtra(Keys.KEY_SELECT_INDEX, 3);
                startActivity(intent);
                activity.finish();
                activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            }
        });

        boolean openProxy = presenter.isOpenHttpProxy();
        openProxyItemWithSwitch = mineList.createItemView(proxyStr);
        openProxyItemWithSwitch.setOrientation(QMUICommonListItemView.VERTICAL);
        final String proxyHost = presenter.getProxyIpAddress();
        final int port = presenter.getProxyPort();
        if (TextUtils.isEmpty(proxyHost) || port == 0) {
            openProxyItemWithSwitch.setDetailText("长按设置");
        } else {
            openProxyItemWithSwitch.setDetailText(proxyHost + " : " + port);
        }

        openProxyItemWithSwitch.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_SWITCH);
        openProxyItemWithSwitch.getSwitch().setChecked(openProxy);
        openProxyItemWithSwitch.getSwitch().setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (TextUtils.isEmpty(proxyHost) || port == 0) {
                    buttonView.setChecked(false);
                    presenter.setOpenHttpProxy(false);
                    return;
                }
                presenter.setOpenHttpProxy(isChecked);
            }
        });

        QMUICommonListItemView favoriteItemWithChevron = mineList.createItemView(myFavoriteStr);
        favoriteItemWithChevron.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_CHEVRON);

        // M42：本地收藏模式下分分钟收藏与“我的收藏”合并展示，隐藏独立入口
        boolean localFavoriteMode = presenter.isLocalFavoriteMode();
        QMUICommonListItemView pornyFavoriteItemWithChevron = null;
        if (!localFavoriteMode) {
            pornyFavoriteItemWithChevron = mineList.createItemView(getString(R.string.porny_local_favorite));
            pornyFavoriteItemWithChevron.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_CHEVRON);
        }

        QMUICommonListItemView authorFavoriteItemWithChevron = mineList.createItemView(getString(R.string.author_favorite));
        authorFavoriteItemWithChevron.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_CHEVRON);

        QMUICommonListItemView downloadItemWithChevron = mineList.createItemView(myDownloadStr);
        downloadItemWithChevron.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_CHEVRON);

        QMUICommonListItemView viewHistoryItemWithChevron = mineList.createItemView(viewHistoryStr);
        viewHistoryItemWithChevron.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_CHEVRON);

        mineList.setSeparatorStyle(QMUIGroupListView.SEPARATOR_STYLE_NORMAL);

        QMUIGroupListView.Section mineSection = QMUIGroupListView.newSection(context);
        mineSection.addItemView(favoriteItemWithChevron, this);
        if (pornyFavoriteItemWithChevron != null) {
            mineSection.addItemView(pornyFavoriteItemWithChevron, this);
        }
        mineSection.addItemView(authorFavoriteItemWithChevron, this)
                .addItemView(downloadItemWithChevron, this)
                .addItemView(viewHistoryItemWithChevron, this)
                .addItemView(openNightModeItemWithSwitch, null)
                .addTo(mineList);

        QMUIGroupListView.newSection(context)
                .addItemView(openProxyItemWithSwitch, null, new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        Intent intent = new Intent(context, ProxySettingActivity.class);
                        startActivityWithAnimation(intent);
                        return false;
                    }
                })
                .addTo(mineList);

        QMUICommonListItemView moreSettingItemWithChevron = mineList.createItemView(moreSettingStr);
        moreSettingItemWithChevron.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_CHEVRON);

        QMUIGroupListView.newSection(context)
                .addItemView(moreSettingItemWithChevron, this)
                .addTo(mineList);

        QMUICommonListItemView aboutItemWithChevron = mineList.createItemView(aboutMeStr);
        aboutItemWithChevron.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_CHEVRON);

        QMUIGroupListView.newSection(context)
                .addItemView(aboutItemWithChevron, this)
                .addTo(mineList);

        if (presenter.isUserLogin()) {
            logoutItemView = mineList.createItemView(getString(R.string.exit_login_account));
            logoutItemView.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_CHEVRON);
            QMUIGroupListView.newSection(context)
                    .addItemView(logoutItemView, v -> logout())
                    .addTo(mineList);
        }
    }

    public void updateProxySetUI(String proxyStr, int proxyPort) {
        //视图可能还未创建（Activity提前调用），做空校验
        if (openProxyItemWithSwitch == null || presenter == null) {
            return;
        }
        if (!TextUtils.isEmpty(proxyStr) && proxyPort > 0) {
            openProxyItemWithSwitch.setDetailText(proxyStr + " : " + proxyPort);
        }
        boolean openProxy = presenter.isOpenHttpProxy();
        openProxyItemWithSwitch.getSwitch().setChecked(openProxy);
    }

    private void userImageViewClick() {
        if (presenter.isUserLogin()) {
            return;
        }
        Intent intent = new Intent(context, UserLoginActivity.class);
        startActivityForResultWithAnimation(intent, Constants.USER_LOGIN_REQUEST_CODE);
    }

    @SuppressLint("SetTextI18n")
    private void setUpUserInfo(User user) {

        if (!UserHelper.isUserInfoComplete(user)) {
            tvNavUsername.setText("请登录");
            tvNavLastLoginTime.setText("---");
            tvNavLastLoginIp.setText("---");
            return;
        }

        if (!TextUtils.isEmpty(user.getStatus())) {
            String status = user.getStatus().contains("正常") ? "正常" : "异常";
            tvNavUsername.setText(user.getUserName() + "(" + status + ")");
        }
        if (!TextUtils.isEmpty(user.getLastLoginTime())) {
            tvNavLastLoginTime.setText(user.getLastLoginTime().replace("(如果你觉得时间不对,可能帐号被盗)", ""));
        }
        tvNavLastLoginIp.setText(user.getLastLoginIP());
    }

    private void logout() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context, R.style.MyDialogTheme);
        builder.setTitle(getString(R.string.exit_login_account));
        builder.setMessage("退出当前帐号？");
        builder.setPositiveButton("退出", (dialog, which) -> {
            presenter.existLogin();
            setUpUserInfo(presenter.getLoginUser());
            if (logoutItemView != null) {
                logoutItemView.setVisibility(View.GONE);
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Constants.USER_LOGIN_REQUEST_CODE && resultCode == RESULT_OK) {
            setUpUserInfo(presenter.getLoginUser());
            if (logoutItemView != null) {
                logoutItemView.setVisibility(presenter.isUserLogin() ? View.VISIBLE : View.GONE);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        //移除未执行的延迟任务，防止内存泄漏
        if (observableScrollView != null && restoreScrollRunnable != null) {
            observableScrollView.removeCallbacks(restoreScrollRunnable);
        }
        restoreScrollRunnable = null;
        if (unbinder != null) {
            unbinder.unbind();
            unbinder = null;
        }
    }

    @Override
    public void onClick(View v) {
        if (v instanceof QMUICommonListItemView) {
            String string = String.valueOf(((QMUICommonListItemView) v).getText());
            actionClickList(string);
        } else {
            switch (v.getId()) {
                case R.id.imageView:
                    userImageViewClick();
                    break;
                default:
            }
        }
    }

    private void actionClickList(String content) {
        if (TextUtils.isEmpty(content)) {
            return;
        }

        if (content.equals(myFavoriteStr)) {
            // M42：本地收藏模式无需登录直接进入（与分分钟合并展示）
            if (!presenter.isLocalFavoriteMode() && !presenter.isUserLogin()) {
                Intent intent = new Intent(context, UserLoginActivity.class);
                intent.putExtra(Keys.KEY_INTENT_LOGIN_FOR_ACTION, KeysActivityRequestResultCode.LOGIN_ACTION_FOR_LOOK_MY_FAVORITE);
                startActivityForResultWithAnimation(intent, Constants.USER_LOGIN_REQUEST_CODE);
                return;
            }
            Intent intent = new Intent(context, FavoriteActivity.class);
            startActivityWithAnimation(intent);
        } else if (content.equals(getString(R.string.porny_local_favorite))) {
            Intent intent = new Intent(context, com.m3man.ui.mman9video.favorite.PornyFavoriteActivity.class);
            startActivityWithAnimation(intent);
        } else if (content.equals(getString(R.string.author_favorite))) {
            Intent intent = new Intent(context, AuthorFavoriteActivity.class);
            startActivityWithAnimation(intent);
        } else if (content.equals(myDownloadStr)) {
            Intent intent = new Intent(context, DownloadActivity.class);
            startActivityWithAnimation(intent);
        } else if (content.equals(viewHistoryStr)) {
            Intent intent = new Intent(context, HistoryActivity.class);
            startActivityWithAnimation(intent);
        } else if (content.equals(aboutMeStr)) {
            Intent intent = new Intent(context, AboutActivity.class);
            startActivityWithAnimation(intent);
        } else if (content.equals(moreSettingStr)) {
            Intent intent = new Intent(context, SettingActivity.class);
            startActivityWithAnimation(intent);
        }
    }
}
