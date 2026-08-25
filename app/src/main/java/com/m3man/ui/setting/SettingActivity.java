package com.m3man.ui.setting;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatDelegate;
import android.support.v7.widget.AppCompatAutoCompleteTextView;
import android.support.v7.widget.AppCompatButton;
import android.support.v7.widget.Toolbar;
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
    private String testBaseUrl;
    private QMUICommonListItemView openProxyItemWithSwitch;

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
        testAlertDialog = DialogUtils.initLoadingDialog(context, "测试中，请稍后...");
        moveOldDirDownloadVideoToNewDirDiaog = DialogUtils.initLoadingDialog(context, "移动文件中，请稍后...");
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

    /** 推荐调参弹窗（从「我的」迁入）。 */
    private void showRecoTuneDialog() {
        final RecoEngine engine = RecoEngine.get(this);
        new RecoSettingsDialog(this, engine, presenter.getDataManager(), new RecoSettingsDialog.OnParamsChangedListener() {
            @Override
            public void onParamsChanged(RecoParams params) {
                showMessage("推荐参数已保存", TastyToast.SUCCESS);
            }

            @Override
            public void onMemoryCleared() {
                engine.resetMemory();
                showMessage("推荐记忆已清除", TastyToast.SUCCESS);
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
        addressItemWithChevron.setDetailText(TextUtils.isEmpty(video91Address) ? "未设置" : video91Address);
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
        pornyAddressItemWithChevron.setDetailText(TextUtils.isEmpty(pornyAddress) ? "未设置" : pornyAddress);
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
            openProxyItemWithSwitch.setDetailText("长按设置");
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
        final QMUICommonListItemView customDownloadPathItemWithChevron = qmuiGroupListView.createItemView("自定义视频下载文件夹");
        customDownloadPathItemWithChevron.setOrientation(QMUICommonListItemView.VERTICAL);
        String customDirPath = presenter.getCustomDownloadVideoDirPath();
        if (SDCardUtils.DOWNLOAD_VIDEO_PATH.equalsIgnoreCase(customDirPath)) {
            customDownloadPathItemWithChevron.setDetailText("需先清空所有未完成下载，建议使用默认");
        } else {
            customDownloadPathItemWithChevron.setDetailText(customDirPath);
        }

        customDownloadPathItemWithChevron.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_CHEVRON);


        QMUIGroupListView.newSection(this)
                .addItemView(playEngineItemWithChevron, this)
                .addItemView(customDownloadPathItemWithChevron, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        selectDownloadVideoDir(customDownloadPathItemWithChevron);
                    }
                })
                .addTo(qmuiGroupListView);


        QMUIGroupListView.Section sec = QMUIGroupListView.newSection(this);

        // 夜间模式归入“更多设置”，避免在“我的”页面占用独立入口。
        QMUICommonListItemView nightModeItemWithSwitch = qmuiGroupListView.createItemView(getString(R.string.night_mode));
        nightModeItemWithSwitch.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_SWITCH);
        nightModeItemWithSwitch.getSwitch().setChecked(presenter.isOpenNightMode());
        nightModeItemWithSwitch.getSwitch().setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                presenter.setOpenNightMode(isChecked);
                AppCompatDelegate.setDefaultNightMode(isChecked
                        ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
                recreate();
            }
        });
        sec.addItemView(nightModeItemWithSwitch, null);

        //禁用自动释放内存功能
        boolean isForbidden = presenter.isForbiddenAutoReleaseMemory();
        QMUICommonListItemView itemWithSwitchForbidden = qmuiGroupListView.createItemView("禁用自动释放内存功能");
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
        QMUICommonListItemView itemWithSwitch = qmuiGroupListView.createItemView("非Wi-Fi环境下下载视频");
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
        QMUICommonListItemView localFavoriteItemWithSwitch = qmuiGroupListView.createItemView("本地收藏（无需登录）");
        localFavoriteItemWithSwitch.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_SWITCH);
        localFavoriteItemWithSwitch.getSwitch().setChecked(isLocalFavoriteMode);
        localFavoriteItemWithSwitch.getSwitch().setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                presenter.setLocalFavoriteMode(isChecked);
                showMessage(isChecked ? "已切换为本地收藏" : "已切换为服务器收藏", TastyToast.INFO);
            }
        });


        sec.addItemView(itemWithSwitch, null);
        sec.addItemView(itemWithSwitchForbidden, this);
        sec.addItemView(pornyEnabledItemWithSwitch, null);
        sec.addItemView(localFavoriteItemWithSwitch, null);
        sec.addTo(qmuiGroupListView);

    }

    /**
     * 自定义视频下载地址
     */
    public void selectDownloadVideoDir(final QMUICommonListItemView qmuiCommonListItemView) {
        if (presenter.isHaveUnFinishDownloadVideo()) {
            showMessage("当前有未下载完成视频，无法更改", TastyToast.INFO);
            return;
        }
        FilePicker picker = new FilePicker(this, FilePicker.DIRECTORY);
        picker.setRootPath(StorageUtils.getExternalRootPath());
        picker.setTitleText("选择文件夹");
        picker.setItemHeight(40);
        picker.setOnFilePickListener(new FilePicker.OnFilePickListener() {
            @Override
            public void onFilePicked(String currentPath) {
                if (presenter.getCustomDownloadVideoDirPath().equalsIgnoreCase(currentPath + "/")) {
                    showMessage("不能选择原目录哦", TastyToast.WARNING);
                    return;
                }
                if (presenter.isHaveFinishDownloadVideoFile()) {
                    showIsMoveOldDirVideoFileToNewDirDialog(currentPath, qmuiCommonListItemView);
                } else {
                    showMessage("设置成功", TastyToast.SUCCESS);
                    qmuiCommonListItemView.setDetailText(currentPath);
                    presenter.setCustomDownloadVideoDirPath(currentPath);
                }
            }
        });
        picker.show();
    }

    private void showIsMoveOldDirVideoFileToNewDirDialog(final String newDirPath, final QMUICommonListItemView qmuiCommonListItemView) {
        QMUIDialog.MessageDialogBuilder builder = new QMUIDialog.MessageDialogBuilder(context);
        builder.setTitle("移动文件");
        builder.setMessage("当前已选择新文件夹路径：" + newDirPath + "\n发现原下载文件夹有已下载完成视频，是否移动到新文件夹？\n PS:不移动则无法在下载完成界面打开文件");
        builder.addAction("移动", new QMUIDialogAction.ActionListener() {
            @Override
            public void onClick(QMUIDialog dialog, int index) {
                dialog.dismiss();
                presenter.moveOldDownloadVideoToNewDir(newDirPath, qmuiCommonListItemView);
            }
        });
        builder.addAction("不移动", new QMUIDialogAction.ActionListener() {
            @Override
            public void onClick(QMUIDialog dialog, int index) {
                dialog.dismiss();
                qmuiCommonListItemView.setDetailText(newDirPath);
                presenter.setCustomDownloadVideoDirPath(newDirPath);
            }
        });
        builder.addAction("返回", new QMUIDialogAction.ActionListener() {
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
                return "9*mman视频地址设置";
            case AppPreferencesHelper.KEY_SP_PORNY_ADDRESS:
                return "91porny地址设置";
            default:
                return "地址设置";
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
        autoCompleteTextView.setText(testBaseUrl);
        if (!TextUtils.isEmpty(testBaseUrl)) {
            autoCompleteTextView.setSelection(testBaseUrl.length());
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
                    showMessage("地址不能为空哟！", TastyToast.ERROR);
                    return;
                }
                //因为我们很多地方链接地址是拼接的，所以如果缺少了后面的“/”，就会拼接处错误的链接
                if (!address.endsWith("/")) {
                    address += "/";
                }
                if (!checkAddress(address)) {
                    return;
                }
                testBaseUrl = address;
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
                testBaseUrl = address;
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
        showMessage("设置成功", TastyToast.INFO);
        testBaseUrl = "";
    }

    private void showConfirmDialog(final QMUICommonListItemView qmuiCommonListItemView, final String address, final String key) {
        new AlertDialog.Builder(this, R.style.MyDialogTheme)
                .setTitle("温馨提示")
                .setMessage("地址还未测试成功，确认设置吗？")
                .setPositiveButton("设置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        saveToSpAndUpdateQMUICommonListItemView(key, qmuiCommonListItemView, address);
                        //强制设置，则刷新地址
                        resetOrUpdateAddress(key);
                    }
                })
                .setNegativeButton("返回", new DialogInterface.OnClickListener() {
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
            showMessage("设置失败，输入地址格式不正确，(不要忘了最后面的“/”)", TastyToast.ERROR);
            return false;
        }
        List<String> pathSegments = httpUrl.pathSegments();
        if (!"".equals(pathSegments.get(pathSegments.size() - 1))) {
            showMessage("设置失败，输入地址格式不正确，(不要忘了最后面的“/”)", TastyToast.ERROR);
            return false;
        }
        return true;
    }

    private void showForbiddenReleaseMemoryTipInfoDialog() {
        QMUIDialog.MessageDialogBuilder builder = new QMUIDialog.MessageDialogBuilder(this);
        builder.setTitle("温馨提示");
        builder.setMessage("为了获得较好的体验，新版本程序占用内存较高，这可能导致后台运行而系统内存不足时成为系统回收内存的优先对象（尤其在低内存手机上），因此我做了自动释放内存功能，但这同时也会使体验有所下降，你可以强制关闭次功能，建议开启");
        builder.addAction("知道了", new QMUIDialogAction.ActionListener() {
            @Override
            public void onClick(QMUIDialog dialog, int index) {
                dialog.dismiss();
            }
        });
        builder.show();
    }

    private void showPlaybackEngineChoiceDialog(final QMUICommonListItemView qmuiCommonListItemView) {
        final int checkedIndex = presenter.getPlaybackEngine();
        new QMUIDialog.CheckableDialogBuilder(this)
                .setTitle("播放引擎选择")
                .setCheckedIndex(checkedIndex)
                .addItems(PlaybackEngine.PLAY_ENGINE_ITEMS, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        presenter.setPlaybackEngine(which);
                        qmuiCommonListItemView.setDetailText(PlaybackEngine.PLAY_ENGINE_ITEMS[which]);
                        showMessage("设置成功", TastyToast.SUCCESS);
                        dialog.dismiss();
                    }
                })
                .show();
    }

    private void showExitDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.MyDialogTheme);
        builder.setTitle("退出登录");
        builder.setMessage("退出当前帐号？");
        builder.setPositiveButton("退出", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                presenter.existLogin();
                Intent intent = new Intent(SettingActivity.this, UserLoginActivity.class);
                startActivityForResultWithAnimation(intent, Constants.USER_LOGIN_REQUEST_CODE);
                finish();
            }
        });
        builder.setNegativeButton("取消", null);
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
