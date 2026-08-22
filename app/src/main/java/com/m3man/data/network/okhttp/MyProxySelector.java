package com.m3man.data.network.okhttp;

import android.text.TextUtils;

import com.orhanobut.logger.Logger;
import com.m3man.data.prefs.PreferencesHelper;
import com.m3man.utils.AppLog;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * @author flymegoc
 * @date 2018/2/10
 */
@Singleton
public class MyProxySelector extends ProxySelector {
    private static final String TAG = MyProxySelector.class.getSimpleName();

    /**
     * 显式“不走代理”标记（用于本地回环地址，如视频缓存代理、代理自身），避免代理回环。
     */
    private static final List<Proxy> DIRECT_NO_PROXY = Collections.singletonList(Proxy.NO_PROXY);

    /**
     * M15：测试代理列表用 volatile 持有不可变快照，避免跨线程共享可变 ArrayList 造成撕裂。
     */
    private volatile List<Proxy> testProxyList;
    private volatile boolean isTest = false;
    private final PreferencesHelper preferencesHelper;

    /**
     * M-sys：保存被本选择器替换前的系统默认选择器（反映设备全局代理/VPN）。
     * 不可在 select() 内直接调用 ProxySelector.getDefault()——本实例已被 setDefault 注册为默认，
     * 那样会递归调用自身导致 StackOverflow。
     */
    private ProxySelector systemDefaultSelector;

    @Inject
    public MyProxySelector(List<Proxy> proxyList, PreferencesHelper preferencesHelper) {
        //注入的 proxyList 仅作占位，实际选路每次构造不可变快照，避免并发修改
        this.preferencesHelper = preferencesHelper;
    }

    public void setTest(boolean test, String proxyHost, int port) {
        if (test) {
            if (TextUtils.isEmpty(proxyHost) || !isValidPort(port)) {
                Logger.t(TAG).d("测试代理参数非法，忽略");
                clearTest();
                return;
            }
            Logger.t(TAG).d("开始代理测试了");
            testProxyList = Collections.singletonList(
                    new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, port)));
            isTest = true;
        } else {
            clearTest();
        }
    }

    /**
     * M15：清理测试态。测试崩溃/被杀后若不清理，所有请求（含检查更新）都会被强制导向未验证代理。
     */
    public void clearTest() {
        isTest = false;
        testProxyList = null;
    }

    /**
     * 注入被本选择器替换前的系统默认 ProxySelector（须在 MyApplication.setDefault 之前捕获后传入）。
     */
    public void setSystemDefaultSelector(ProxySelector selector) {
        this.systemDefaultSelector = selector;
    }

    /**
     * 端口合法性校验（M14：原代码只判 &gt;0，未判上界）
     *
     * @param port 端口
     * @return 是否合法
     */
    public static boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }

    @Override
    public List<Proxy> select(URI uri) {
        //M14：ProxySelector 契约要求 uri 为 null 时抛 IllegalArgumentException，且不得返回 null
        if (uri == null) {
            throw new IllegalArgumentException("uri must not be null");
        }
        String host = uri.getHost();
        // 本地回环地址（视频缓存代理 127.0.0.1:PORT、以及代理自身 127.0.0.1:7897）不走代理，
        // 否则会产生代理回环，导致本地视频缓存与代理请求全部失败。
        if (host != null && (host.equals("127.0.0.1") || host.equals("localhost")
                || host.equals("::1") || host.equals("[::1]"))) {
            Logger.t(TAG).d("本地地址，直连：" + uri.toString());
            return DIRECT_NO_PROXY;
        }

        // 暂时只支持91mman视频，但下载/CDN 等其它外部地址同样需要走代理才能连通
        boolean isOpenProxy = preferencesHelper.isOpenHttpProxy();

        if (isTest) {
            // 代理连通性测试：对所有外部地址返回测试代理
            List<Proxy> snapshot = testProxyList;
            if (snapshot != null) {
                Logger.t(TAG).d("本次为代理测试了");
                return snapshot;
            }
        }

        if (!isOpenProxy) {
            // 应用内代理关闭时，回退到系统默认 ProxySelector（设备全局代理/VPN 对本应用生效）。
            // 注意：必须用注入的 systemDefaultSelector，不能调 ProxySelector.getDefault()——
            // 本实例已被注册为默认，调它会递归自身导致 StackOverflow。
            ProxySelector def = systemDefaultSelector;
            if (def != null) {
                try {
                    List<Proxy> sys = def.select(uri);
                    if (sys != null && !sys.isEmpty()) {
                        Logger.t(TAG).d("未开应用内代理，走系统默认代理：" + sys);
                        return sys;
                    }
                } catch (IllegalArgumentException ignore) {
                    // 某些 URI 方案默认选择器可能抛异常，忽略后回退直连
                }
            }
            Logger.t(TAG).d("未有任何代理或测试，直连");
            //M14：不得返回 null，否则部分 HttpURLConnection 栈会 NPE
            return DIRECT_NO_PROXY;
        }

        // 正式代理：所有外部地址都通过 Http 代理中转（视频列表、播放、下载 CDN 统一走代理）
        String proxyHost = preferencesHelper.getProxyIpAddress();
        int port = preferencesHelper.getProxyPort();
        if (TextUtils.isEmpty(proxyHost) || !isValidPort(port)) {
            Logger.t(TAG).d("代理地址为空或端口非法，直连");
            return DIRECT_NO_PROXY;
        }
        //每次构造不可变快照，避免共享列表被并发清空/写入
        AppLog.i(TAG, "代理选择=HTTP " + proxyHost + ":" + port + " target=" + host);
        return Collections.singletonList(
                new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, port)));
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        Logger.t(TAG).d("connectFailed(URI uri, SocketAddress sa-----------------:::" + uri.toString());
    }
}
