package com.m3man;

import android.content.Context;
import android.support.multidex.MultiDex;
import android.support.v7.app.AppCompatDelegate;

import com.helper.loadviewhelper.load.LoadViewHelper;
import com.liulishuo.filedownloader.FileDownloader;
import com.squareup.leakcanary.LeakCanary;
import com.tencent.bugly.crashreport.CrashReport;
import com.m3man.data.DataManager;
import com.m3man.data.network.okhttp.MyProxySelector;
import com.m3man.di.component.DaggerAppComponent;
import com.m3man.eventbus.LowMemoryEvent;
import com.m3man.utils.AppLogger;

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
        initLeakCanary();
        initLoadingHelper();
        initFileDownload();
        if (!BuildConfig.DEBUG) {
            //初始化bug收集
          //  Bugsnag.init(this);
        }
        CrashReport.initCrashReport(getApplicationContext(), "e426041d83", BuildConfig.DEBUG);
        BGASwipeBackHelper.init(this, null);
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

    /**
     * 初始化内存分析工具
     */
    private void initLeakCanary() {
        if (LeakCanary.isInAnalyzerProcess(this)) {
            // This process is dedicated to LeakCanary for heap analysis.
            // You should not init your app in this process.
            return;
        }
        LeakCanary.install(this);
        // Normal app init code...
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
