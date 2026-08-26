package com.m3man;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.multidex.MultiDex;
import android.support.v7.app.AppCompatDelegate;

import com.helper.loadviewhelper.load.LoadViewHelper;
import com.liulishuo.filedownloader.FileDownloader;
import com.tencent.bugly.crashreport.CrashReport;
import com.m3man.cookie.RulerCookie;
import com.m3man.cookie.SetCookieCache;
import com.m3man.cookie.SharedPrefsCookiePersistor;
import com.m3man.data.DataManager;
import com.m3man.data.network.okhttp.MyProxySelector;
import com.m3man.di.component.DaggerAppComponent;
import com.m3man.eventbus.LowMemoryEvent;
import com.m3man.utils.AppLog;
import com.m3man.utils.AppLogger;
import com.m3man.utils.NetworkClientHolder;
import com.m3man.utils.NotificationChannelHelper;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;
import java.net.ProxySelector;

import javax.inject.Inject;

import cn.bingoogolapple.swipebacklayout.BGASwipeBackHelper;
import dagger.android.AndroidInjector;
import dagger.android.support.DaggerApplication;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 应用入口
 *
 * @author flymegoc
 * @date 2017/11/14
 */

public class MyApplication extends DaggerApplication {

    private static final String TAG = MyApplication.class.getSimpleName();
    /** M95：通用 Chrome UA（与 CommonHeaderInterceptor 保持一致），供解析兜底客户端使用 */
    private static final String FALLBACK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/68.0.3440.84 Safari/537.36";

    @Inject
    DataManager dataManager;

    @Inject
    MyProxySelector myProxySelector;

    private static MyApplication myApplication;

    @Override
    public void onCreate() {
        // M99：DaggerApplication 的 super.onCreate() 内部会完成整张 DI 图的构建与注入
        // （applicationInjector→DataManager/AppDbHelper 等单例），框架约束下无法把注入
        // 挪到进程判断之后——该构造成本为已知限制。
        super.onCreate();
        myApplication = this;
        // M73/M99：进程隔离——FileDownloader 会派生 :filedownloader 进程，该进程中重复执行
        // ProxySelector 注入/Bugly/夜间模式等主进程初始化会引发难复现的线上问题。
        // 非主进程只保留最基础的初始化后直接返回；所有重初始化均已在 gate 之后（见下方顺序）。
        if (!isMainProcess()) {
            AppLogger.initLogger();
            NotificationChannelHelper.initChannel(this);
            return;
        }
        // 将应用代理选择器注册为全局默认，使 filedownloader 等直接使用 HttpURLConnection 的模块（如下载）
        // 也能统一走 Http 代理，解决“下载一直卡住”的问题。
        // 先捕获系统原始默认选择器（含设备全局代理/VPN），交给 MyProxySelector 作为回退，
        // 否则其内部再调 ProxySelector.getDefault() 会拿到自己，陷入无限递归。
        ProxySelector systemDefaultSelector = ProxySelector.getDefault();
        ProxySelector.setDefault(myProxySelector);
        myProxySelector.setSystemDefaultSelector(systemDefaultSelector);
        // M95：为静态解析器（ParseV9MmanVideo 分享链接兜底）注入统一网络客户端
        initNetworkClientHolder();
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

    /** M73：当前进程是否为主进程（无冒号后缀） */
    private boolean isMainProcess() {
        String processName = getProcessNameSafely();
        return processName == null || getPackageName().equals(processName);
    }

    /**
     * M95：为静态解析器的兜底请求组装统一 OkHttpClient 并注入 NetworkClientHolder。
     * 组装参照 ApiServiceModule.providesOkHttpClient 的关键项：
     *   RulerCookie（视频页请求剥离登录态 cookie）+ MyProxySelector（全局 HTTP 代理）
     *   + 统一 Chrome UA 头。ApiServiceModule 未直接暴露 OkHttpClient，
     *   且解析器无法参与 DI，故此处按相同关键件独立组装（持久层与主链路共用同一 SharedPreferences，
     *   cookie 视图一致）。失败仅记日志，NetworkClientHolder 会回退默认实例。
     */
    private void initNetworkClientHolder() {
        try {
            RulerCookie rulerCookie = new RulerCookie(new SetCookieCache(), new SharedPrefsCookiePersistor(this));
            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            builder.cookieJar(rulerCookie);
            builder.proxySelector(myProxySelector);
            builder.addInterceptor(new Interceptor() {
                @Override
                public Response intercept(@NonNull Chain chain) throws IOException {
                    Request original = chain.request();
                    Request withUa = original.newBuilder()
                            .header("User-Agent", FALLBACK_UA)
                            .build();
                    return chain.proceed(withUa);
                }
            });
            NetworkClientHolder.set(builder.build());
        } catch (Exception e) {
            AppLog.e(TAG, "初始化解析兜底网络客户端失败: " + AppLog.cause(e));
        }
    }

    private String getProcessNameSafely() {
        try {
            int pid = android.os.Process.myPid();
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) {
                return null;
            }
            java.util.List<android.app.ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list != null) {
                for (android.app.ActivityManager.RunningAppProcessInfo info : list) {
                    if (info.pid == pid && info.processName != null) {
                        return info.processName;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
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
