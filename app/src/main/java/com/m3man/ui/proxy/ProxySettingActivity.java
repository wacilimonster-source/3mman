package com.m3man.ui.proxy;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.Toolbar;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.helper.loadviewhelper.help.OnLoadViewListener;
import com.helper.loadviewhelper.load.LoadViewHelper;
import com.orhanobut.logger.Logger;
import com.qmuiteam.qmui.util.QMUIKeyboardHelper;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.R;
import com.m3man.adapter.ProxyAdapter;
import com.m3man.data.model.ProxyModel;
import com.m3man.ui.MvpActivity;
import com.m3man.ui.setting.SettingActivity;
import com.m3man.utils.DialogUtils;
import com.m3man.widget.IpInputEditText;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;

/**
 * @author flymegoc
 */
public class ProxySettingActivity extends MvpActivity<ProxyView, ProxyPresenter> implements View.OnClickListener, ProxyView {
    private static final String TAG = ProxySettingActivity.class.getSimpleName();
    @BindView(R.id.toolbar)
    Toolbar toolbar;
    @BindView(R.id.et_dialog_proxy_setting_ip_address)
    IpInputEditText etDialogProxySettingIpAddress;
    @BindView(R.id.et_dialog_proxy_setting_port)
    AppCompatEditText etDialogProxySettingPort;
    @BindView(R.id.bt_proxy_setting_test)
    AppCompatButton btProxySettingTest;
    @BindView(R.id.recycler_view_proxy_setting)
    RecyclerView recyclerViewProxySetting;
    @BindView(R.id.bt_proxy_setting_reset)
    AppCompatButton btProxySettingReset;
    @BindView(R.id.swipe_layout)
    SwipeRefreshLayout swipeLayout;
    private AlertDialog testAlertDialog;
    private boolean isTestSuccess = false;
    /** M100：「完成」时若测试结果已失效，先自动补测一次，成功后再继续保存流程 */
    private boolean pendingSaveAfterTest = false;
    private ProxyAdapter proxyAdapter;
    private LoadViewHelper helper;

    @Inject
    protected ProxyPresenter proxyPresenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_proxy_setting);
        ButterKnife.bind(this);
        initToolBar(toolbar);
        init();
        initListener();
    }

    private void init() {
        testAlertDialog = DialogUtils.initLoadingDialog(this, getString(R.string.proxy_testing_loading));
        String proxyHost = presenter.getProxyIpAddress();
        int port = presenter.getProxyPort();
        etDialogProxySettingIpAddress.setIpAddressStr(proxyHost);
        etDialogProxySettingPort.setText(port == 0 ? "" : String.valueOf(port));

        List<ProxyModel> data = new ArrayList<>();
        proxyAdapter = new ProxyAdapter(R.layout.item_proxy, data);

        recyclerViewProxySetting.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewProxySetting.setHasFixedSize(true);
        recyclerViewProxySetting.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        View view = getLayoutInflater().inflate(R.layout.item_proxy, recyclerViewProxySetting, false);
        proxyAdapter.setHeaderView(view);
        proxyAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                presenter.parseXiCiDaiLi(false);
            }
        }, recyclerViewProxySetting);
        proxyAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                ProxyModel proxyModel = (ProxyModel) adapter.getItem(position);
                if (proxyModel == null) {
                    showMessage(getString(R.string.proxy_data_error), TastyToast.INFO);
                    return;
                }
                proxyAdapter.setClickPosition(position);
                if (proxyModel.getType() != ProxyModel.TYPE_SOCKS) {
                    etDialogProxySettingIpAddress.setIpAddressStr(proxyModel.getProxyIp());
                    etDialogProxySettingPort.setText(proxyModel.getProxyPort());
                } else {
                    showMessage(getString(R.string.proxy_no_socket), TastyToast.INFO);
                }
            }
        });
        recyclerViewProxySetting.setAdapter(proxyAdapter);

        helper = new LoadViewHelper(recyclerViewProxySetting);
        helper.setListener(new OnLoadViewListener() {
            @Override
            public void onRetryClick() {
                presenter.parseXiCiDaiLi(false);
            }
        });

        presenter.parseXiCiDaiLi(false);
    }

    private void initListener() {
        btProxySettingTest.setOnClickListener(this);
        btProxySettingReset.setOnClickListener(this);
        swipeLayout.setEnabled(false);
        swipeLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                swipeLayout.setRefreshing(true);
                presenter.parseXiCiDaiLi(true);
            }
        });
        // M100：任意编辑 IP/端口即令旧测试结果失效，防止「A 地址测试通过后残留到 B 地址」跨输入串用
        TextWatcher invalidateTester = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                isTestSuccess = false;
            }
        };
        hookProxyInputChanged(invalidateTester);
    }

    /** M100：IpInputEditText 是复合控件（内部 4 个子 EditText），需逐个挂钩才能监听 IP 编辑；端口框单独挂 */
    private void hookProxyInputChanged(TextWatcher watcher) {
        for (int i = 0; i < etDialogProxySettingIpAddress.getChildCount(); i++) {
            View child = etDialogProxySettingIpAddress.getChildAt(i);
            if (child instanceof EditText) {
                ((EditText) child).addTextChangedListener(watcher);
            }
        }
        etDialogProxySettingPort.addTextChangedListener(watcher);
    }

    @NonNull
    @Override
    public ProxyPresenter createPresenter() {
        return proxyPresenter;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.proxy_setting, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.menu_done_proxy_setting) {
            doSettingProxy();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * M14：安全解析端口。原实现只判 isDigitsOnly，"999999999999" 会让 Integer.parseInt 抛
     * NumberFormatException 直接崩溃；同时也未校验 65535 上界。
     *
     * @param portStr 端口字符串
     * @return 合法端口，非法时返回 -1
     */
    private int parsePort(String portStr) {
        if (TextUtils.isEmpty(portStr) || !TextUtils.isDigitsOnly(portStr)) {
            return -1;
        }
        try {
            int port = Integer.parseInt(portStr);
            return (port > 0 && port <= 65535) ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void doSettingProxy() {
        String proxyIpAddress = etDialogProxySettingIpAddress.getIpAddressStr();
        String proxyPortStr = etDialogProxySettingPort.getText().toString().trim();
        int proxyPort = parsePort(proxyPortStr);
        if (TextUtils.isEmpty(proxyIpAddress) || proxyPort < 0) {
            showMessage(getString(R.string.proxy_port_error), TastyToast.INFO);
            return;
        }
        if (!isTestSuccess) {
            // M100：保存即开启代理开关；输入被编辑后旧测试结果已失效——先自动补测一次，
            // 成功（testProxySuccess 回调）后继续本方法落盘，失败 Toast 并中止保存
            pendingSaveAfterTest = true;
            presenter.exitTest();
            presenter.testProxy(proxyIpAddress, proxyPort);
            return;
        }
        presenter.exitTest();
        //设置开启代理并存储地址和端口号
        presenter.setOpenHttpProxy(true);
        presenter.setProxyIpAddress(proxyIpAddress);
        presenter.setProxyPort(proxyPort);
        showMessage(getString(R.string.proxy_save_success), TastyToast.SUCCESS);
        onBackPressed();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.bt_proxy_setting_test:
                if (presenter.isSetMman91VideoAddress()) {
                    Logger.t(TAG).d("木有设置地址呀");
                    showNeedSetAddressFirstDialog();
                    return;
                }
                isTestSuccess = false;
                String proxyIpAddress = etDialogProxySettingIpAddress.getIpAddressStr();
                String portStr = etDialogProxySettingPort.getText().toString().trim();
                int proxyPort = parsePort(portStr);
                if (TextUtils.isEmpty(proxyIpAddress) || proxyPort < 0) {
                    showMessage(getString(R.string.proxy_address_invalid), TastyToast.WARNING);
                    return;
                }
                presenter.testProxy(proxyIpAddress, proxyPort);
                QMUIKeyboardHelper.hideKeyboard(v);
                break;
            case R.id.bt_proxy_setting_reset:
                etDialogProxySettingIpAddress.setIpAddressStr("");
                etDialogProxySettingPort.setText("");
                View view = getCurrentFocus();
                if (view instanceof AppCompatEditText || view instanceof EditText) {
                    QMUIKeyboardHelper.showKeyboard((EditText) view, QMUIKeyboardHelper.SHOW_KEYBOARD_DELAY_TIME);
                }
                break;
            default:
        }
    }

    private void showNeedSetAddressFirstDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.MyDialogTheme);
        builder.setTitle(getString(R.string.proxy_tip_title));
        builder.setMessage(getString(R.string.proxy_need_address_msg));
        builder.setPositiveButton(getString(R.string.proxy_go_set), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Intent intent = new Intent(context, SettingActivity.class);
                startActivityWithAnimation(intent);
                finish();
            }
        });
        builder.setNegativeButton(getString(R.string.back), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        builder.setCancelable(false);
        builder.show();
    }

    @Override
    public void testProxySuccess(String message) {
        isTestSuccess = true;
        showMessage(message, TastyToast.SUCCESS);
        // M100：自动补测成功→继续此前挂起的「完成」保存流程
        if (pendingSaveAfterTest) {
            pendingSaveAfterTest = false;
            doSettingProxy();
        }
    }

    @Override
    public void testProxyError(String message) {
        // M100：测试（含自动补测）失败→中止保存流程，仅提示
        pendingSaveAfterTest = false;
        dismissDialog();
        showMessage(message, TastyToast.ERROR);
    }

    @Override
    public void parseXiCiDaiLiSuccess(List<ProxyModel> proxyModelList) {
        swipeLayout.setEnabled(true);
        swipeLayout.setRefreshing(false);
        proxyAdapter.setNewData(proxyModelList);
    }

    @Override
    public void loadMoreDataComplete() {
        proxyAdapter.loadMoreComplete();
    }

    @Override
    public void loadMoreFailed() {
        proxyAdapter.loadMoreFail();
    }

    @Override
    public void noMoreData() {
        proxyAdapter.loadMoreEnd(true);
    }

    @Override
    public void setMoreData(List<ProxyModel> proxyModelList) {
        proxyAdapter.addData(proxyModelList);
    }

    @Override
    public void beginParseProxy() {
        helper.showLoading();
    }

    @Override
    public void showLoading(boolean pullToRefresh) {
        testAlertDialog.show();
    }

    @Override
    public void showContent() {
        swipeLayout.setRefreshing(false);
        helper.showContent();
        dismissDialog();
    }

    private void dismissDialog() {
        if (testAlertDialog != null && testAlertDialog.isShowing()) {
            testAlertDialog.dismiss();
        }
    }

    @Override
    public void showMessage(String msg, int type) {
        super.showMessage(msg, type);
    }

    @Override
    public void showError(String message) {
        dismissDialog();
        swipeLayout.setRefreshing(false);
        helper.showError();
        showMessage(message, TastyToast.ERROR);
    }

    @Override
    protected void onDestroy() {
        presenter.exitTest();
        super.onDestroy();
    }
}
