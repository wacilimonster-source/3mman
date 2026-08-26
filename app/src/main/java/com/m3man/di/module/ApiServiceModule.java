package com.m3man.di.module;

import android.content.Context;
import android.support.annotation.NonNull;
import android.text.TextUtils;

import com.franmontiel.persistentcookiejar.PersistentCookieJar;
import com.orhanobut.logger.Logger;
import com.m3man.BuildConfig;
import com.m3man.cookie.RulerCookie;
import com.m3man.cookie.SetCookieCache;
import com.m3man.cookie.SharedPrefsCookiePersistor;
import com.m3man.data.network.Api;
import com.m3man.data.network.apiservice.GitHubServiceApi;
import com.m3man.data.network.apiservice.PornyServiceApi;
import com.m3man.data.network.apiservice.ProxyServiceApi;
import com.m3man.data.network.apiservice.V9MmanServiceApi;
import com.m3man.data.network.okhttp.CommonHeaderInterceptor;
import com.m3man.data.network.okhttp.MyProxySelector;
import com.m3man.di.ApplicationContext;
import com.m3man.utils.AddressHelper;

import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import me.jessyan.retrofiturlmanager.RetrofitUrlManager;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;

/**
 * @author flymegoc
 * @date 2018/2/10
 */
@Module
public class ApiServiceModule {

    private static final String TAG = ApiServiceModule.class.getSimpleName();

    @Singleton
    @Provides
    SharedPrefsCookiePersistor providesSharedPrefsCookiePersistor(@ApplicationContext Context context) {
        return new SharedPrefsCookiePersistor(context);
    }

    @Singleton
    @Provides
    SetCookieCache providesSetCookieCache() {
        return new SetCookieCache();
    }

    @Singleton
    @Provides
    PersistentCookieJar providesPersistentCookieJar(SharedPrefsCookiePersistor sharedPrefsCookiePersistor, SetCookieCache setCookieCache) {
        return new PersistentCookieJar(setCookieCache, sharedPrefsCookiePersistor);
    }

    @Singleton
    @Provides
    RulerCookie providesRuler(SharedPrefsCookiePersistor sharedPrefsCookiePersistor, SetCookieCache setCookieCache){
        return new RulerCookie(setCookieCache,sharedPrefsCookiePersistor);
    }

    @Singleton
    @Provides
    HttpLoggingInterceptor providesHttpLoggingInterceptor() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor(new HttpLoggingInterceptor.Logger() {
            @Override
            public void log(@NonNull String message) {
                Logger.t("OkHttp").d(message);
            }
        });
        // M13：release 包不打印请求/响应头（含 Cookie），避免敏感信息泄露；仅 debug 包输出 BODY
        logging.setLevel(BuildConfig.DEBUG ? HttpLoggingInterceptor.Level.BODY : HttpLoggingInterceptor.Level.NONE);
        return logging;
    }

    @Singleton
    @Provides
    List<Proxy> providesListProxy() {
        return new ArrayList<>();
    }

    @Singleton
    @Provides
    OkHttpClient providesOkHttpClient(CommonHeaderInterceptor commonHeaderInterceptor, HttpLoggingInterceptor httpLoggingInterceptor, RulerCookie rulerCookie, MyProxySelector myProxySelector, AddressHelper addressHelper) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        // M95：评审结论——超时必须显式声明，不能依赖 OkHttp 默认值（默认 connect/read/write 均为 10s，
        // 且散落在隐式行为里，弱网/慢站点场景下排障困难）。显式设置：
        //   连接 10s：目标站多为境外/镜像，快速失败便于上层走地址测试与重试；
        //   读 30s：视频播放页 HTML 较大且站点响应慢，读超时需放宽；
        //   写 15s：评论/收藏等 POST 体量小，15s 足够。
        // 不改 retryOnConnectionFailure（保持 OkHttp 默认 true）：该开关影响所有请求的失败重连路径，
        // 与本次超时治理正交；改动会扩大行为面（可能掩盖连接类故障、增加重复请求），如需收紧另行评审。
        builder.connectTimeout(10, TimeUnit.SECONDS);
        builder.readTimeout(30, TimeUnit.SECONDS);
        builder.writeTimeout(15, TimeUnit.SECONDS);
        builder.addInterceptor(commonHeaderInterceptor);
        builder.addInterceptor(httpLoggingInterceptor);
        builder.cookieJar(rulerCookie);
        builder.proxySelector(myProxySelector);
        //动态baseUrl
        RetrofitUrlManager.getInstance().putDomain(Api.GITHUB_DOMAIN_NAME, Api.APP_GITHUB_DOMAIN);
        RetrofitUrlManager.getInstance().putDomain(Api.XICI_DAILI_DOMAIN_NAME, Api.APP_PROXY_XICI_DAILI_DOMAIN);
        if (!TextUtils.isEmpty(addressHelper.getVideo9MmanAddress())) {
            RetrofitUrlManager.getInstance().putDomain(Api.PORN9_VIDEO_DOMAIN_NAME, addressHelper.getVideo9MmanAddress());
        }
        if (!TextUtils.isEmpty(addressHelper.getPornyAddress())) {
            RetrofitUrlManager.getInstance().putDomain(Api.PORNY_DOMAIN_NAME, addressHelper.getPornyAddress());
        }
        return RetrofitUrlManager.getInstance().with(builder).build();
    }

    @Singleton
    @Provides
    Retrofit providesRetrofit(OkHttpClient okHttpClient) {
        return new Retrofit.Builder()
                .client(okHttpClient)
                .baseUrl(Api.APP_GITHUB_DOMAIN)
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .addConverterFactory(ScalarsConverterFactory.create())
                .build();
    }

    @Singleton
    @Provides
    GitHubServiceApi providesGitHubServiceApi(Retrofit retrofit) {
        return retrofit.create(GitHubServiceApi.class);
    }

    @Singleton
    @Provides
    V9MmanServiceApi provides91MmanVideoServiceApi(Retrofit retrofit) {
        return retrofit.create(V9MmanServiceApi.class);
    }

    @Singleton
    @Provides
    ProxyServiceApi providesProxyServiceApi(Retrofit retrofit) {
        return retrofit.create(ProxyServiceApi.class);
    }

    @Singleton
    @Provides
    PornyServiceApi providesPornyServiceApi(Retrofit retrofit) {
        return retrofit.create(PornyServiceApi.class);
    }
}
