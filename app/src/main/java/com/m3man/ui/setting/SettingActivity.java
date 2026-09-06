package com.m3man.ui.setting;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.AppCompatAutoCompleteTextView;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.Toolbar;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;

import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.qmuiteam.qmui.widget.grouplist.QMUICommonListItemView;
import com.qmuiteam.qmui.widget.grouplist.QMUIGroupListView;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.R;
import com.m3man.utils.PlayUiPrefs;
import com.m3man.constants.Constants;
import com.m3man.data.DataManager;
import com.m3man.data.network.Api;
import com.m3man.data.db.entity.AutoCompleteEntity;
import com.m3man.data.prefs.AppPreferencesHelper;
import com.m3man.data.reco.RecoEngine;
import com.m3man.data.reco.RecoParams;
import com.m3man.ui.MvpActivity;
import com.m3man.ui.mman9video.user.UserLoginActivity;
import com.m3man.ui.proxy.ProxySettingActivity;
import com.m3man.ui.recommend.RecoSettingsDialog;
import com.m3man.utils.DialogUtils;
import com.m3man.utils.PlaybackEngine;
import com.m3man.utils.SDCardUtils;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import cn.qqtheme.framework.picker.FilePicker;
import cn.qqtheme.framework.util.StorageUtils;
import me.jessyan.retrofiturlmanager.RetrofitUrlManager;
import okhttp3.HttpUrl;

/**
 * @author flymegoc
 */
public class SettingActivity extends MvpActivity<SettingView, SettingPresenter> implements View.OnClickListener, SettingView {

    private static final String TAG = SettingActivity.class.getSimpleName();
    @BindView(R.id.toolbar)
    Toolbar toolbar;
    @BindView(R.id.mine_list)
    QMUIGroupListView qmuiGroupListView;
    @BindView(R.id.bt_setting_exit_account)
    Button btSettingExitAccount;

    @Inject
    SettingPresenter settingPresenter;

    private AlertDialog testAlertDialog;
    private AlertDialog moveOldDirDownloadVideoToNewDirDiaog;
    private boolean isTestSuccess = false;
    // M100：测试地址草稿按地址类型区分存储/预填——原单一 testBaseUrl 字段导致
    // mman 与 porny 两类地址输入互相串位（打开另一类弹窗时预填了上一类的地址）
    private String testBaseUrlMman;
    private String testBaseUrlPorny;
    private QMUICommonListItemView openProxyItemWithSwitch;

    /** M100：按地址 key 取对应类型的草稿地址 */
    private String getTestBaseUrlDraft(String key) {
        return AppPreferencesHelper.KEY_SP_PORN_91_VIDEO_ADDRESS.equals(key) ? testBaseUrlMman : testBaseUrlPorny;
    }

    /** M100：按地址 key 写入对应类型的草稿地址 */
    private void setTestBaseUrlDraft(String key, String address) {
        if (AppPreferencesHelper.KEY_SP_PORN_91_VIDEO_ADDRESS.equals(key)) {
            testBaseUrlMman = address;
        } else {
            testBaseUrlPorny = address;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setting);
        ButterKnife.bind(this);
        initToolBar(toolbar);
        initSettingSection();
        initListener();
        init();
    }

    @NonNull
    @Override
    public SettingPresenter createPresenter() {
        return settingPresenter;
    }

    private void init() {
        if (presenter.isUserLogin()) {
            btSettingExitAccount.setVisibility(View.VISIBLE);
        }
        testAlertDialog = DialogUtils.initLoadingDialog(context, getString(R.string.setting_testing_loading));
        moveOldDirDownloadVideoToNewDirDiaog = DialogUtils.initLoadingDialog(context, getString(R.string.setting_moving_file_loading));
    }

    private void initListener() {
        btSettingExitAccount.setOnClickListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从代理设置页返回后刷新开关与详情
        if (openProxyItemWithSwitch != null && presenter != null) {
            String host = presenter.getProxyIpAddress();
            int port = presenter.getProxyPort();
            if (!TextUtils.isEmpty(host) && port > 0) {
                openProxyItemWithSwitch.setDetailText(host + " : " + port);
            }
            openProxyItemWithSwitch.getSwitch().setChecked(presenter.isOpenHttpProxy());
        }
    }

    /** 主线程 Handler（M98：引擎后台初始化完成后回主线程弹窗用） */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 推荐调参弹窗（从「我的」迁入）。
     * <p>
     * M98：RecoEngine.get() 首次初始化含磁盘 I/O（assets JSON 词典 + 画像文件读盘），
     * 不能在主线程同步调用。参照 RecommendFeedFragment 的 M91 模式：后台线程初始化，
     * 完成后 post 回主线程再创建弹窗；期间的使用点（resetMemory 等）就地改为
     * 捕获初始化完成后的引擎引用。
     */
    private void showRecoTuneDialog() {
        showMessage(getString(R.string.setting_reco_engine_loading), TastyToast.INFO);
        final android.content.Context appContext = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final RecoEngine engine = RecoEngine.get(appContext);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            openRecoTuneDialog(engine);
                        }
                    });
                } catch (Throwable t) {
                    // 初始化失败仅上报，不阻塞设置页其它功能
                    android.util.Log.w(TAG, "RecoEngine init failed: " + t.getMessage());
                }
            }
        }, "reco-engine-init-setting").start();
    }

    /** M98：引擎就绪后回主线程打开调参弹窗（engine 引用由后台初始化结果捕获） */
    private void openRecoTuneDialog(final RecoEngine engine) {
        new RecoSettingsDialog(this, engine, presenter.getDataManager(), new RecoSettingsDialog.OnParamsChangedListener() {
            @Override
            public void onParamsChanged(RecoParams params) {
                showMessage(getString(R.string.setting_reco_params_saved), TastyToast.SUCCESS);
            }

            @Override
            public void onMemoryCleared() {
                engine.resetMemory();
                showMessage(getString(R.string.setting_reco_memory_cleared), TastyToast.SUCCESS);
            }
        }).show();
    }


    private void initSettingSection() {
        qmuiGroupListView.setSeparatorStyle(QMUIGroupListView.SEPARATOR_STYLE_NORMAL);
        QMUIGroupListView.Section tsec = QMUIGroupListView.newSection(this);
        //91pron地址
        QMUICommonListItemView addressItemWithChevron = qmuiGroupListView.createItemView(getString(R.string.address_mman));
        addressItemWithChevron.setId(R.id.setting_item_9_mman_address);
        addressItemWithChevron.setOrientation(QMUICommonListItemView.VERTICAL);
        String video91Address = presenter.getVideo9MmanAddress();
        addressItemWithChevron.setDetailText(TextUtils.isEmpty(video91Address) ? getString(R.string.setting_not_set) : video91Address);
        addressItemWithChevron.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_CHEVRON);


        tsec.addItemView(addressItemWithChevron, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddressSettingDialog((QMUICommonListItemView) v, AppPreferencesHelper.KEY_SP_PORN_91_VIDEO_ADDRESS);
            }
        });

        //91porny 地址
        QMUICommonListItemView pornyAddressItemWithChevron = qmuiGroupListView.createItemView(getString(R.string.address_porny));
        pornyAddressItemWithChevron.setOrientation(QMUICommonListItemView.VERTICAL);
        pornyAddressItemWithChevron.setId(R.id.setting_item_porny_address);
        String pornyAddress = presenter.getPornyAddress();
        pornyAddressItemWithChevron.setDetailText(TextUtils.isEmpty(pornyAddress) ? getString(R.string.setting_addr_not_set) : pornyAddress);
        pornyAddressItemWithChevron.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_CHEVRON);

        tsec.addItemView(pornyAddressItemWithChevron, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddressSettingDialog((QMUICommonListItemView) v, AppPreferencesHelper.KEY_SP_PORNY_ADDRESS);
            }
        });
        tsec.addTo(qmuiGroupListView);

        // HTTP 代理（从「我的」迁入：开关 + 长按进入详细设置页）
        openProxyItemWithSwitch = qmuiGroupListView.createItemView(getString(R.string.proxy_setting));
        openProxyItemWithSwitch.setOrientation(QMUICommonListItemView.VERTICAL);
        String proxyHost = presenter.getProxyIpAddress();
        int proxyPort = presenter.getProxyPort();
        if (TextUtils.isEmpty(proxyHost) || proxyPort == 0) {
            openProxyItemWithSwitch.setDetailText(getString(R.string.setting_long_press_set));
        } else {
            openProxyItemWithSwitch.setDetailText(proxyHost + " : " + proxyPort);
        }
        openProxyItemWithSwitch.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_SWITCH);
        openProxyItemWithSwitch.getSwitch().setChecked(presenter.isOpenHttpProxy());
        openProxyItemWithSwitch.getSwitch().setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                // 每次以当前配置为准，避免从代理设置页返回后仍使用创建列表项时的旧地址。
                String currentHost = presenter.getProxyIpAddress();
                int currentPort = presenter.getProxyPort();
                if (TextUtils.isEmpty(currentHost) || currentPort == 0) {
                    buttonView.setChecked(false);
                    presenter.setOpenHttpProxy(false);
                    return;
                }
                presenter.setOpenHttpProxy(isChecked);
            }
        });
        QMUIGroupListView.newSection(this)
                .addItemView(openProxyItemWithSwitch, null, new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        Intent intent = new Intent(SettingActivity.this, ProxySettingActivity.class);
                        startActivityWithAnimation(intent);
                        return false;
                    }
                })
                .addTo(qmuiGroupListView);

        // 推荐调参（从「我的」迁入）
        QMUICommonListItemView recoTuneItemWithChevron = qmuiGroupListView.createItemView(getString(R.string.reco_tune));
        recoTuneItemWithChevron.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_CHEVRON);
        QMUIGroupListView.newSection(this)
                .addItemView(recoTuneItemWithChevron, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showRecoTuneDialog();
                    }
                })
                .addTo(qmuiGroupListView);

        //播放引擎
        QMUICommonListItemView playEngineItemWithChevron = qmuiGroupListView.createItemView(getString(R.string.playback_engine));
        playEngineItemWithChevron.setId(R.id.setting_item_player_engine_choice);
        playEngineItemWithChevron.setOrientation(QMUICommonListItemView.VERTICAL);
        final int checkedIndex = presenter.getPlaybackEngine();
        playEngineItemWithChevron.setDetailText(PlaybackEngine.PLAY_ENGINE_ITEMS[checkedIndex]);
        playEngineItemWithChevron.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_CHEVRON);

        //自定义下载路径
        final QMUICommonListItemView customDownloadPathItemWithChevron = qmuiGroupListView.createItemView(getString(R.string.setting_custom_download_dir));
        customDownloadPathItemWithChevron.setOrientation(QMUICommonListItemView.VERTICAL);
        String customDirPath = presenter.getCustomDownloadVideoDirPath();
        if (SDCardUtils.DOWNLOAD_VIDEO_PATH.equalsIgnoreCase(customDirPath)) {
            customDownloadPathItemWithChevron.setDetailText(getString(R.string.setting_clear_unfinished_hint));
        } else {
            customDownloadPathItemWithChevron.setDetailText(customDirPath);
        }

        customDownloadPathItemWithChevron.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_CHEVRON);


        QMUIGroupListView.Section downloadDirSection = QMUIGroupListView.newSection(this)
                .addItemView(playEngineItemWithChevron, this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // V13：Scoped Storage 强制，公共目录不能作为自定义下载目录（fileDownloader 只能写真实路径），
            // 隐藏虚假选目录入口，改为展示固定说明，避免 Android 10+ 上选目录失效/被忽略。
            QMUICommonListItemView scopedNoteItem = qmuiGroupListView.createItemView(getString(R.string.setting_download_save_location));
            scopedNoteItem.setOrientation(QMUICommonListItemView.VERTICAL);
            scopedNoteItem.setDetailText(getString(R.string.setting_scoped_storage_hint));
            downloadDirSection.addItemView(scopedNoteItem, this);
        } else {
            // Android 9 及以下仍可写公共目录，保留自定义目录选择
            downloadDirSection.addItemView(customDownloadPathItemWithChevron, v ->
                    selectDownloadVideoDir(customDownloadPathItemWithChevron));
        }
        downloadDirSection.addTo(qmuiGroupListView);


        QMUIGroupListView.Section sec = QMUIGroupListView.newSection(this);

        // 夜间模式归入“更多设置”，避免在“我的”页面占用独立入口。
        // M112：由「硬开关」升级为三档选择（跟随系统 / 始终夜间 / 始终日间）——
        // minSdk 28，全量用户都在 Android 9+，系统深色模式不跟随是明显的体验缺口。
        QMUICommonListItemView nightModeItemWithSwitch = qmuiGroupListView.createItemView(getString(R.string.night_mode));
        nightModeItemWithSwitch.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_CHEVRON);
        nightModeItemWithSwitch.setDetailText(nightModeLabel(presenter.getNightMode()));
        nightModeItemWithSwitch.setOnClickListener(v -> showNightModePicker(nightModeItemWithSwitch));
        sec.addItemView(nightModeItemWithSwitch, null);
        // L-fix：「推荐页预加载」——控制推荐流本地缓存秒显 + 预取下一视频（元数据/封面/首包）
        QMUICommonListItemView recoPrefetchItemWithSwitch = qmuiGroupListView.createItemView(getString(R.string.setting_reco_prefetch));
        recoPrefetchItemWithSwitch.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_SWITCH);
        recoPrefetchItemWithSwitch.getSwitch().setChecked(PlayUiPrefs.isRecoPrefetchEnabled(this));
        recoPrefetchItemWithSwitch.getSwitch().setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                PlayUiPrefs.setRecoPrefetchEnabled(SettingActivity.this, isChecked);
            }
        });
        sec.addItemView(recoPrefetchItemWithSwitch, null);

        //禁用自动释放内存功能
        boolean isForbidden = presenter.isForbiddenAutoReleaseMemory();
        QMUICommonListItemView itemWithSwitchForbidden = qmuiGroupListView.createItemView(getString(R.string.setting_disable_auto_release_memory));
        itemWithSwitchForbidden.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_SWITCH);
        itemWithSwitchForbidden.getSwitch().setChecked(isForbidden);
        itemWithSwitchForbidden.getSwitch().setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                presenter.setForbiddenAutoReleaseMemory(isChecked);
                if (isChecked) {
                    showForbiddenReleaseMemoryTipInfoDialog();
                }
            }
        });

        //非Wi-Fi环境下下载视频
        boolean isDownloadNeedWifi = presenter.isDownloadVideoNeedWifi();
        QMUICommonListItemView itemWithSwitch = qmuiGroupListView.createItemView(getString(R.string.setting_download_without_wifi));
        itemWithSwitch.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_SWITCH);
        itemWithSwitch.getSwitch().setChecked(!isDownloadNeedWifi);
        itemWithSwitch.getSwitch().setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                presenter.setDownloadVideoNeedWifi(!isChecked);
            }
        });

        //启用 91porny 搜索源
        boolean isPornyEnabled = presenter.isPornyEnabled();
        QMUICommonListItemView pornyEnabledItemWithSwitch = qmuiGroupListView.createItemView(getString(R.string.enable_porny_source));
        pornyEnabledItemWithSwitch.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_SWITCH);
        pornyEnabledItemWithSwitch.getSwitch().setChecked(isPornyEnabled);
        pornyEnabledItemWithSwitch.getSwitch().setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                presenter.setPornyEnabled(isChecked);
            }
        });

        // M42：收藏方式——本地收藏（无需登录，与分分钟一致） / 服务器收藏
        boolean isLocalFavoriteMode = presenter.isLocalFavoriteMode();
        QMUICommonListItemView localFavoriteItemWithSwitch = qmuiGroupListView.createItemView(getString(R.string.setting_local_favorite));
        localFavoriteItemWithSwitch.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_SWITCH);
        localFavoriteItemWithSwitch.getSwitch().setChecked(isLocalFavoriteMode);
        localFavoriteItemWithSwitch.getSwitch().setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                presenter.setLocalFavoriteMode(isChecked);
                showMessage(isChecked ? getString(R.string.setting_switched_local_favorite) : getString(R.string.setting_switched_server_favorite), TastyToast.INFO);
            }
        });


        sec.addItemView(itemWithSwitch, null);
        sec.addItemView(itemWithSwitchForbidden, this);
        sec.addItemView(pornyEnabledItemWithSwitch, null);
        sec.addItemView(localFavoriteItemWithSwitch, null);

        // 推荐时长筛选：设置页里控制推荐流只保留 ≤ N 分钟的视频（0=不限）
        QMUICommonListItemView recoDurationItemWithChevron = qmuiGroupListView.createItemView(getString(R.string.setting_reco_max_duration));
        recoDurationItemWithChevron.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_CHEVRON);
        recoDurationItemWithChevron.setDetailText(recoDurationLabel(presenter.getRecoMaxDurationMinutes()));
        recoDurationItemWithChevron.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRecoDurationPicker(recoDurationItemWithChevron);
            }
        });
        sec.addItemView(recoDurationItemWithChevron, null);

        sec.addTo(qmuiGroupListView);

    }

    /**
     * 自定义视频下载地址
     * M100：未完成下载/已完成文件的检查均改走 Presenter 的 IO 线程异步版本，
     * 回调回到主线程后再禁用/放行入口（原主线程直查 DB/文件系统会卡顿）。
     */
    public void selectDownloadVideoDir(final QMUICommonListItemView qmuiCommonListItemView) {
        presenter.checkHaveUnFinishDownloadVideo(new SettingPresenter.DownloadCheckCallback() {
            @Override
            public void onResult(boolean hasUnfinished) {
                if (hasUnfinished) {
                    showMessage(getString(R.string.setting_unfinished_download_block), TastyToast.INFO);
                    return;
                }
                openDownloadDirPicker(qmuiCommonListItemView);
            }
        });
    }

    /** M100：从 selectDownloadVideoDir 拆出：通过未完成检查后的选目录流程 */
    private void openDownloadDirPicker(final QMUICommonListItemView qmuiCommonListItemView) {
        FilePicker picker = new FilePicker(this, FilePicker.DIRECTORY);
        picker.setRootPath(StorageUtils.getExternalRootPath());
        picker.setTitleText(getString(R.string.setting_pick_folder));
        picker.setItemHeight(40);
        picker.setOnFilePickListener(new FilePicker.OnFilePickListener() {
            @Override
            public void onFilePicked(String currentPath) {
                if (presenter.getCustomDownloadVideoDirPath().equalsIgnoreCase(currentPath + "/")) {
                    showMessage(getString(R.string.setting_cannot_pick_same_dir), TastyToast.WARNING);
                    return;
                }
                // M100：已完成文件扫描同样移到 IO 线程，回调中决定是否弹「移动文件」确认框
                presenter.checkHaveFinishDownloadVideoFile(new SettingPresenter.DownloadCheckCallback() {
                    @Override
                    public void onResult(boolean hasFinishedFiles) {
                        if (hasFinishedFiles) {
                            showIsMoveOldDirVideoFileToNewDirDialog(currentPath, qmuiCommonListItemView);
                        } else {
                            showMessage(getString(R.string.setting_save_success), TastyToast.SUCCESS);
                            qmuiCommonListItemView.setDetailText(currentPath);
                            presenter.setCustomDownloadVideoDirPath(currentPath);
                        }
                    }
                });
            }
        });
        picker.show();
    }

    private void showIsMoveOldDirVideoFileToNewDirDialog(final String newDirPath, final QMUICommonListItemView qmuiCommonListItemView) {
        QMUIDialog.MessageDialogBuilder builder = new QMUIDialog.MessageDialogBuilder(context);
        builder.setTitle(getString(R.string.setting_move_files_title));
        builder.setMessage(getString(R.string.setting_move_files_message, newDirPath));
        builder.addAction(getString(R.string.setting_move), new QMUIDialogAction.ActionListener() {
            @Override
            public void onClick(QMUIDialog dialog, int index) {
                dialog.dismiss();
                presenter.moveOldDownloadVideoToNewDir(newDirPath, qmuiCommonListItemView);
            }
        });
        builder.addAction(getString(R.string.setting_dont_move), new QMUIDialogAction.ActionListener() {
            @Override
            public void onClick(QMUIDialog dialog, int index) {
                dialog.dismiss();
                qmuiCommonListItemView.setDetailText(newDirPath);
                presenter.setCustomDownloadVideoDirPath(newDirPath);
            }
        });
        builder.addAction(getString(R.string.back), new QMUIDialogAction.ActionListener() {
            @Override
            public void onClick(QMUIDialog dialog, int index) {
                dialog.dismiss();
            }
        });
        builder.show();
    }

    private String getAddressSettingTitle(String key) {
        switch (key) {
            case AppPreferencesHelper.KEY_SP_PORN_91_VIDEO_ADDRESS:
                return getString(R.string.setting_addr_title_mman);
            case AppPreferencesHelper.KEY_SP_PORNY_ADDRESS:
                return getString(R.string.setting_addr_title_porny);
            default:
                return getString(R.string.setting_addr_title_default);
        }
    }

    private void showAddressSettingDialog(final QMUICommonListItemView qmuiCommonListItemView, final String key) {
        View view = getLayoutInflater().inflate(R.layout.dialog_setting_address, qmuiCommonListItemView, false);
        final AlertDialog alertDialog = new AlertDialog.Builder(this, R.style.MyDialogTheme)
                .setTitle(getAddressSettingTitle(key))
                .setView(view)
                .setCancelable(false)
                .show();
        AppCompatButton okAppCompatButton = view.findViewById(R.id.bt_dialog_address_setting_ok);
        AppCompatButton backAppCompatButton = view.findViewById(R.id.bt_dialog_address_setting_back);
        AppCompatButton testAppCompatButton = view.findViewById(R.id.bt_dialog_address_setting_test);
        final AppCompatAutoCompleteTextView autoCompleteTextView = view.findViewById(R.id.atv_dialog_address_setting_address);
        // M100：预填按地址类型区分，不再共享同一草稿字段
        String draftAddress = getTestBaseUrlDraft(key);
        autoCompleteTextView.setText(draftAddress);
        if (!TextUtils.isEmpty(draftAddress)) {
            autoCompleteTextView.setSelection(draftAddress.length());
        } else {
            switch (key) {
                case AppPreferencesHelper.KEY_SP_PORN_91_VIDEO_ADDRESS:
                    autoCompleteTextView.setText(presenter.getVideo9MmanAddress());
                    break;

                case AppPreferencesHelper.KEY_SP_PORNY_ADDRESS:
                    autoCompleteTextView.setText(presenter.getPornyAddress());
                    break;

                default:
            }
        }
        // M3：将历史保存过的地址与协议前缀合并为自动补全建议
        final String[] addressPrefix = {"http://", "https://", "http://www.", "https://www."};
        List<String> suggestions = new ArrayList<>();
        suggestions.addAll(Arrays.asList(addressPrefix));
        List<String> savedAddresses = presenter.getAutoCompleteNames(AutoCompleteEntity.TYPE_ADDRESS);
        if (savedAddresses != null) {
            for (String s : savedAddresses) {
                if (!suggestions.contains(s)) {
                    suggestions.add(s);
                }
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_auto_complete_textview, suggestions);
        autoCompleteTextView.setAdapter(adapter);

        okAppCompatButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String address = autoCompleteTextView.getText().toString().trim();
                if (TextUtils.isEmpty(address)) {
                    showMessage(getString(R.string.setting_address_empty_error), TastyToast.ERROR);
                    return;
                }
                //因为我们很多地方链接地址是拼接的，所以如果缺少了后面的“/”，就会拼接处错误的链接
                if (!address.endsWith("/")) {
                    address += "/";
                }
                if (!checkAddress(address)) {
                    return;
                }
                // M100：草稿按地址类型分别记录
                setTestBaseUrlDraft(key, address);
                alertDialog.dismiss();
                if (isTestSuccess) {
                    saveToSpAndUpdateQMUICommonListItemView(key, qmuiCommonListItemView, address);
                } else {
                    showConfirmDialog(qmuiCommonListItemView, address, key);
                }
            }
        });
        backAppCompatButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetOrUpdateAddress(key);
                alertDialog.dismiss();
            }
        });
        testAppCompatButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String address = autoCompleteTextView.getText().toString().trim();
                if (!checkAddress(address)) {
                    return;
                }
                // M100：草稿按地址类型分别记录
                setTestBaseUrlDraft(key, address);
                alertDialog.dismiss();
                beginTestAddress(address, qmuiCommonListItemView, key);
            }
        });
    }

    private void beginTestAddress(String address, QMUICommonListItemView qmuiCommonListItemView, String key) {
        switch (key) {
            case AppPreferencesHelper.KEY_SP_PORN_91_VIDEO_ADDRESS:
                presenter.test9MmanVideo(address, qmuiCommonListItemView, key);
                break;
                case AppPreferencesHelper.KEY_SP_PORNY_ADDRESS:
                    presenter.testPorny(address, qmuiCommonListItemView, key);
                    break;
                default:
        }
    }

    /**
     * 刷新为原地址或者最新地址
     *
     * @param key key
     */
    private void resetOrUpdateAddress(String key) {
        switch (key) {
            case AppPreferencesHelper.KEY_SP_PORN_91_VIDEO_ADDRESS:
                // 全局 BaseUrl 的优先级低于 Domain-Name header 中单独配置的,其他未配置的接口将受全局 BaseUrl 的影响
                if (!TextUtils.isEmpty(presenter.getVideo9MmanAddress())) {
                    RetrofitUrlManager.getInstance().putDomain(Api.PORN9_VIDEO_DOMAIN_NAME, presenter.getVideo9MmanAddress());
                }
                break;
            case AppPreferencesHelper.KEY_SP_PORNY_ADDRESS:
                if (!TextUtils.isEmpty(presenter.getPornyAddress())) {
                    RetrofitUrlManager.getInstance().putDomain(Api.PORNY_DOMAIN_NAME, presenter.getPornyAddress());
                }
                break;
            default:
        }
    }

    /**
     * 仅仅只需将新地址保存到sp中即可，下次会自动读取
     *
     * @param key                    key
     * @param qmuiCommonListItemView qc
     * @param address                address
     */
    private void saveToSpAndUpdateQMUICommonListItemView(String key, QMUICommonListItemView qmuiCommonListItemView, String address) {
        switch (key) {
            case AppPreferencesHelper.KEY_SP_PORN_91_VIDEO_ADDRESS:
                presenter.setMman9VideoAddress(address);
                break;
            case AppPreferencesHelper.KEY_SP_PORNY_ADDRESS:
                presenter.setPornyAddress(address);
                break;
            default:
        }
        // M3：保存地址的同时持久化到自动补全表，供下次输入建议
        presenter.saveAutoComplete(address, AutoCompleteEntity.TYPE_ADDRESS);
        qmuiCommonListItemView.setDetailText(address);
        showMessage(getString(R.string.setting_save_success), TastyToast.INFO);
        // M100：仅清空当前类型的草稿，不影响另一类地址
        setTestBaseUrlDraft(key, "");
    }

    private void showConfirmDialog(final QMUICommonListItemView qmuiCommonListItemView, final String address, final String key) {
        new AlertDialog.Builder(this, R.style.MyDialogTheme)
                .setTitle(getString(R.string.setting_tip_title))
                .setMessage(getString(R.string.setting_test_not_success_confirm))
                .setPositiveButton(getString(R.string.setting_set_button), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        saveToSpAndUpdateQMUICommonListItemView(key, qmuiCommonListItemView, address);
                        //强制设置，则刷新地址
                        resetOrUpdateAddress(key);
                    }
                })
                .setNegativeButton(getString(R.string.back), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showAddressSettingDialog(qmuiCommonListItemView, key);
                    }
                })
                .show();
    }

    private boolean checkAddress(String address) {
        HttpUrl httpUrl = HttpUrl.parse(address);
        if (httpUrl == null) {
            showMessage(getString(R.string.setting_address_format_error), TastyToast.ERROR);
            return false;
        }
        List<String> pathSegments = httpUrl.pathSegments();
        // 修复 AIOOBE：pathSegments 可能为空（如 http://example.com 无路径时）
        if (pathSegments.isEmpty() || !"".equals(pathSegments.get(pathSegments.size() - 1))) {
            showMessage(getString(R.string.setting_address_format_error), TastyToast.ERROR);
            return false;
        }
        return true;
    }

    private void showForbiddenReleaseMemoryTipInfoDialog() {
        QMUIDialog.MessageDialogBuilder builder = new QMUIDialog.MessageDialogBuilder(this);
        builder.setTitle(getString(R.string.setting_tip_title));
        builder.setMessage(getString(R.string.setting_forbidden_release_memory_tip));
        builder.addAction(getString(R.string.setting_got_it), new QMUIDialogAction.ActionListener() {
            @Override
            public void onClick(QMUIDialog dialog, int index) {
                dialog.dismiss();
            }
        });
        builder.show();
    }

    // ==================== M112：夜间模式三档（跟随系统 / 始终夜间 / 始终日间）====================

    /** 夜间模式存储码：与 AppPreferencesHelper#getNightMode 的迁移逻辑一致 */
    private static final int NIGHT_MODE_FOLLOW_SYSTEM = 0;
    private static final int NIGHT_MODE_ON = 1;
    private static final int NIGHT_MODE_OFF = 2;

    private String nightModeLabel(int nightMode) {
        switch (nightMode) {
            case NIGHT_MODE_ON:
                return getString(R.string.setting_night_on);
            case NIGHT_MODE_OFF:
                return getString(R.string.setting_night_off);
            default:
                return getString(R.string.setting_night_follow);
        }
    }

    private void showNightModePicker(final QMUICommonListItemView item) {
        final int current = presenter.getNightMode();
        final CharSequence[] labels = new CharSequence[]{getString(R.string.setting_night_follow), getString(R.string.setting_night_on), getString(R.string.setting_night_off)};
        final int[] codes = new int[]{NIGHT_MODE_FOLLOW_SYSTEM, NIGHT_MODE_ON, NIGHT_MODE_OFF};
        int checkedIndex = 0;
        for (int i = 0; i < codes.length; i++) {
            if (codes[i] == current) {
                checkedIndex = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.night_mode))
                .setSingleChoiceItems(labels, checkedIndex, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        int code = codes[which];
                        if (code != current) {
                            applyNightMode(code, item);
                        }
                    }
                })
                .show();
    }

    // ==================== 推荐时长筛选（设置项）：不限 / 1 / 2 / 3 / 5 / 10 分钟 ====================

    private static final int[] RECO_DURATION_VALUES = {0, 1, 2, 3, 5, 10};

    /** 把持久化的「分钟数」渲染成列表右侧的明细文案。0 显示「不限」。 */
    private String recoDurationLabel(int minutes) {
        if (minutes <= 0) {
            return getString(R.string.setting_reco_duration_unlimited);
        }
        return getString(R.string.setting_reco_duration_minutes, minutes);
    }

    /** 弹出单选对话框让用户挑时长上限，保存后更新右侧明细。 */
    private void showRecoDurationPicker(final QMUICommonListItemView item) {
        final int current = presenter.getRecoMaxDurationMinutes();
        final CharSequence[] labels = new CharSequence[RECO_DURATION_VALUES.length];
        for (int i = 0; i < RECO_DURATION_VALUES.length; i++) {
            labels[i] = recoDurationLabel(RECO_DURATION_VALUES[i]);
        }
        int checkedIndex = 0;
        for (int i = 0; i < RECO_DURATION_VALUES.length; i++) {
            if (RECO_DURATION_VALUES[i] == current) {
                checkedIndex = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.setting_reco_max_duration))
                .setSingleChoiceItems(labels, checkedIndex, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        int picked = RECO_DURATION_VALUES[which];
                        if (picked != current) {
                            presenter.setRecoMaxDurationMinutes(picked);
                            item.setDetailText(recoDurationLabel(picked));
                            showMessage(getString(R.string.setting_save_success), TastyToast.SUCCESS);
                        }
                    }
                })
                .show();
    }

    private void applyNightMode(int nightMode, QMUICommonListItemView item) {
        presenter.setNightMode(nightMode);
        if (item != null) {
            item.setDetailText(nightModeLabel(nightMode));
        }
        int mode;
        switch (nightMode) {
            case NIGHT_MODE_ON:
                mode = AppCompatDelegate.MODE_NIGHT_YES;
                break;
            case NIGHT_MODE_OFF:
                mode = AppCompatDelegate.MODE_NIGHT_NO;
                break;
            default:
                mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                break;
        }
        AppCompatDelegate.setDefaultNightMode(mode);
        recreate();
    }

    private void showPlaybackEngineChoiceDialog(final QMUICommonListItemView qmuiCommonListItemView) {
        final int checkedIndex = presenter.getPlaybackEngine();
        new QMUIDialog.CheckableDialogBuilder(this)
                .setTitle(getString(R.string.setting_playback_engine_title))
                .setCheckedIndex(checkedIndex)
                .addItems(PlaybackEngine.PLAY_ENGINE_ITEMS, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        presenter.setPlaybackEngine(which);
                        qmuiCommonListItemView.setDetailText(PlaybackEngine.PLAY_ENGINE_ITEMS[which]);
                        showMessage(getString(R.string.setting_save_success), TastyToast.SUCCESS);
                        dialog.dismiss();
                    }
                })
                .show();
    }

    private void showExitDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.MyDialogTheme);
        builder.setTitle(getString(R.string.exit_login_account));
        builder.setMessage(getString(R.string.setting_exit_account_confirm_msg));
        builder.setPositiveButton(getString(R.string.setting_exit), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                presenter.existLogin();
                Intent intent = new Intent(SettingActivity.this, UserLoginActivity.class);
                startActivityForResultWithAnimation(intent, Constants.USER_LOGIN_REQUEST_CODE);
                finish();
            }
        });
        builder.setNegativeButton(getString(R.string.common_cancel), null);
        builder.show();
    }

    @Override
    public void onClick(View v) {

        switch (v.getId()) {
            case R.id.bt_setting_exit_account:
                showExitDialog();
                break;
            case R.id.setting_item_player_engine_choice:
                showPlaybackEngineChoiceDialog((QMUICommonListItemView) v);
                break;
            default:
        }
    }


    @Override
    public void showTestingAddressDialog(boolean isTest) {
        isTestSuccess = false;
        testAlertDialog.show();
    }

    @Override
    public void testNewAddressSuccess(String message, QMUICommonListItemView qmuiCommonListItemView, String key) {
        isTestSuccess = true;
        dismissDialog();
        showMessage(message, TastyToast.SUCCESS);
        showAddressSettingDialog(qmuiCommonListItemView, key);
    }

    @Override
    public void testNewAddressFailure(String message, QMUICommonListItemView qmuiCommonListItemView, String key) {
        isTestSuccess = false;
        showMessage(message, TastyToast.ERROR);
        showAddressSettingDialog(qmuiCommonListItemView, key);
        dismissDialog();
    }

    @Override
    public void beginMoveOldDirDownloadVideoToNewDir() {
        moveOldDirDownloadVideoToNewDirDiaog.show();
    }

    @Override
    public void setNewDownloadVideoDirSuccess(String message) {
        dismissDialog();
        showMessage(message, TastyToast.SUCCESS);
    }

    @Override
    public void setNewDownloadVideoDirError(String message) {
        dismissDialog();
        showMessage(message, TastyToast.ERROR);
    }

    private void dismissDialog() {
        if (testAlertDialog.isShowing() && !isFinishing()) {
            testAlertDialog.dismiss();
        } else if (moveOldDirDownloadVideoToNewDirDiaog.isShowing() && !isFinishing()) {
            moveOldDirDownloadVideoToNewDirDiaog.dismiss();
        }
    }
}
