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
import com.m3man.data.network.apiservice.AxgleServiceApi;
import com.m3man.data.network.apiservice.GitHubServiceApi;
import com.m3man.data.network.apiservice.KeDouServiceApi;
import com.m3man.data.network.apiservice.PavServiceApi;
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
        if (!TextUtils.isEmpty(addressHelper.getPavAddress())) {
            RetrofitUrlManager.getInstance().putDomain(Api.PA_DOMAIN_NAME, addressHelper.getPavAddress());
        }
        if (!TextUtils.isEmpty(addressHelper.getAxgleAddress())) {
            RetrofitUrlManager.getInstance().putDomain(Api.AXGLE_DOMAIN_NAME, addressHelper.getAxgleAddress());
        }
        if (!TextUtils.isEmpty(addressHelper.getKeDouWoAddress())) {
            RetrofitUrlManager.getInstance().putDomain(Api.KE_DOU_WO_DOMAIN_NAME, addressHelper.getKeDouWoAddress());
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
    PavServiceApi providesPigAvServiceApi(Retrofit retrofit) {
        return retrofit.create(PavServiceApi.class);
    }

    @Singleton
    @Provides
    ProxyServiceApi providesProxyServiceApi(Retrofit retrofit) {
        return retrofit.create(ProxyServiceApi.class);
    }

    @Singleton
    @Provides
    AxgleServiceApi providesAxgleServiceApi(Retrofit retrofit) {
        return retrofit.create(AxgleServiceApi.class);
    }

    @Singleton
    @Provides
    KeDouServiceApi providesKeDouWOServiceApi(Retrofit retrofit) {
        return retrofit.create(KeDouServiceApi.class);
    }

    @Singleton
    @Provides
    PornyServiceApi providesPornyServiceApi(Retrofit retrofit) {
        return retrofit.create(PornyServiceApi.class);
    }
}
