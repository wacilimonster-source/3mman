package com.m3man.utils;

import com.orhanobut.logger.Logger;

import okhttp3.OkHttpClient;

/**
 * M95：静态解析器专用 OkHttpClient 持有器。
 *
 * 背景：ParseV9MmanVideo 等纯静态解析类无法参与 Dagger 注入，旧代码兜底请求直接
 * Jsoup.connect()，会绕过全局 Cookie（RulerCookie）/代理（MyProxySelector）配置，
 * 且每次新建连接池造成资源浪费。
 *
 * 用法：MyApplication 在主进程 onCreate 时组装一个与主链路同构的客户端并 set(...)；
 * 解析器侧统一通过 get() 取用。未初始化（如单测/非常规入口）时回退默认实例，
 * 保证兜底请求不崩。volatile 保证跨线程可见。
 */
public final class NetworkClientHolder {

    private static final String TAG = NetworkClientHolder.class.getSimpleName();

    private static volatile OkHttpClient sClient;

    private NetworkClientHolder() {
    }

    /** 由 MyApplication 主进程初始化时注入，重复调用以最后一次为准 */
    public static void set(OkHttpClient client) {
        sClient = client;
    }

    /** 未初始化时回退 new OkHttpClient 默认实例，绝不返回 null */
    public static OkHttpClient get() {
        OkHttpClient client = sClient;
        if (client == null) {
            Logger.t(TAG).w("NetworkClientHolder 未初始化，回退默认 OkHttpClient");
            client = new OkHttpClient();
        }
        return client;
    }
}
