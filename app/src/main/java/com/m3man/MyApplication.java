package com.m3man;

import android.content.Context;
import android.support.multidex.MultiDex;
import android.support.v7.app.AppCompatDelegate;

import com.helper.loadviewhelper.load.LoadViewHelper;
import com.liulishuo.filedownloader.FileDownloader;
import com.tencent.bugly.crashreport.CrashReport;
import com.m3man.data.DataManager;
import com.m3man.data.network.okhttp.MyProxySelector;
import com.m3man.di.component.DaggerAppComponent;
import com.m3man.eventbus.LowMemoryEvent;
import com.m3man.utils.AppLog;
import com.m3man.utils.AppLogger;
import com.m3man.utils.NotificationChannelHelper;

import org.greenrobot.eventbus.EventBus;

import java.net.ProxySelector;

import javax.inject.Inject;

import cn.bingoogolapple.swipebacklayout.BGASwipeBackHelper;
import dagger.android.AndroidInjector;
import dagger.android.support.DaggerApplication;

/**
 * 应用入口
 *
 * @author flymegoc
 * @date 2017/11/14
 */

public class MyApplication extends DaggerApplication {

    private static final String TAG = MyApplication.class.getSimpleName();

    @Inject
    DataManager dataManager;

    @Inject
    MyProxySelector myProxySelector;

    private static MyApplication myApplication;

    @Override
    public void onCreate() {
        super.onCreate();
        myApplication = this;
        // 将应用代理选择器注册为全局默认，使 filedownloader 等直接使用 HttpURLConnection 的模块（如下载）
        // 也能统一走 Http 代理，解决“下载一直卡住”的问题。
        ProxySelector.setDefault(myProxySelector);
        initNightMode();
        AppLogger.initLogger();
        logStartupEnvironment();
        initLoadingHelper();
        initFileDownload();
        //C4：通知渠道必须在进程启动时创建，否则Service被系统单独拉起时
        //startForeground会因渠道不存在抛"Bad notification for startForeground"
        NotificationChannelHelper.initChannel(this);
        if (!BuildConfig.DEBUG) {
            //初始化bug收集
          //  Bugsnag.init(this);
        }
        CrashReport.initCrashReport(getApplicationContext(), "e426041d83", BuildConfig.DEBUG);
        BGASwipeBackHelper.init(this, null);
    }

    /**
     * 启动时记录环境信息（版本/源地址/代理状态），供「复制日志」排查用。
     */
    private void logStartupEnvironment() {
        try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            AppLog.i(TAG, "启动 v" + ver);
        } catch (Exception ignored) {
        }
        try {
            String mman9 = dataManager.getMman9VideoAddress();
            String porny = dataManager.getPornyAddress();
            AppLog.i(TAG, "视频源地址: 9mman=" + (mman9 == null ? "null" : mman9)
                    + " porny=" + (porny == null ? "null" : porny));
        } catch (Exception e) {
            AppLog.e(TAG, "读取源地址失败: " + AppLog.cause(e));
        }
        try {
            boolean proxyOn = dataManager.isOpenHttpProxy();
            String ip = dataManager.getProxyIpAddress();
            int port = dataManager.getProxyPort();
            AppLog.i(TAG, "HTTP代理: " + (proxyOn ? "开 " + ip + ":" + port : "关"));
        } catch (Exception e) {
            AppLog.e(TAG, "读取代理状态失败: " + AppLog.cause(e));
        }
    }

    public static MyApplication getInstance() {
        return myApplication;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    private void initNightMode() {
        boolean isNightMode = dataManager.isOpenNightMode();
        AppCompatDelegate.setDefaultNightMode(isNightMode ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
    }

    private void initFileDownload() {
        FileDownloader.setup(this);
    }

    /**
     * 初始化加载界面，空界面等
     */
    private void initLoadingHelper() {
        LoadViewHelper.getBuilder()
                .setLoadEmpty(R.layout.empty_view)
                .setLoadError(R.layout.error_view)
                .setLoadIng(R.layout.loading_view);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        boolean canReleaseMemory = dataManager.isForbiddenAutoReleaseMemory();
        if (!canReleaseMemory) {
            EventBus.getDefault().post(new LowMemoryEvent(TAG));
        }
    }

    public DataManager getDataManager() {
        return dataManager;
    }

    @Override
    protected AndroidInjector<? extends DaggerApplication> applicationInjector() {
        return DaggerAppComponent.builder().application(this).build();
    }
}
