package com.m3man.data.network.okhttp;

import android.text.TextUtils;

import com.orhanobut.logger.Logger;
import com.m3man.data.prefs.PreferencesHelper;

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
    private List<Proxy> proxyList;

    /**
     * 显式“不走代理”标记（用于本地回环地址，如视频缓存代理、代理自身），避免代理回环。
     */
    private static final List<Proxy> DIRECT_NO_PROXY = Collections.singletonList(Proxy.NO_PROXY);

    private boolean isTest = false;
    private PreferencesHelper preferencesHelper;

    @Inject
    public MyProxySelector(List<Proxy> proxyList, PreferencesHelper preferencesHelper) {
        this.proxyList = proxyList;
        this.preferencesHelper = preferencesHelper;
    }

    public void setTest(boolean test, String proxyHost, int port) {
        isTest = test;
        if (test) {
            Logger.t(TAG).d("开始代理测试了");
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, port));
            proxyList.clear();
            proxyList.add(proxy);
        }
    }

    @Override
    public List<Proxy> select(URI uri) {
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
            Logger.t(TAG).d("本次为代理测试了");
            return proxyList;
        }

        if (!isOpenProxy) {
            Logger.t(TAG).d("select(URI uri)-----------------------------::::" + uri.toString());
            Logger.t(TAG).d("未有任何代理或测试，直连");
            return null;
        }

        // 正式代理：所有外部地址都通过 Http 代理中转（视频列表、播放、下载 CDN 统一走代理）
        Logger.t(TAG).d("select(URI uri)-----------------------------::::是相等的，可以启用了");
        String proxyHost = preferencesHelper.getProxyIpAddress();
        int port = preferencesHelper.getProxyPort();
        if (TextUtils.isEmpty(proxyHost) || port <= 0) {
            Logger.t(TAG).d("代理地址为空，直连");
            return null;
        }
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, port));
        proxyList.clear();
        proxyList.add(proxy);
        return proxyList;
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        Logger.t(TAG).d("connectFailed(URI uri, SocketAddress sa-----------------:::" + uri.toString());
    }
}
