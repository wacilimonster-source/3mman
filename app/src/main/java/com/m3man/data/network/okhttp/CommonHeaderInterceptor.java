package com.m3man.data.network.okhttp;

import androidx.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

import com.orhanobut.logger.Logger;
import com.m3man.BuildConfig;
import com.m3man.data.network.Api;
import com.m3man.utils.AppLog;
import com.m3man.data.prefs.PreferencesHelper;
import java.io.IOException;

import javax.inject.Inject;
import javax.inject.Singleton;

import me.jessyan.retrofiturlmanager.RetrofitUrlManager;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * @author flymegoc
 * @date 2018/1/17
 */

@Singleton
public class CommonHeaderInterceptor implements Interceptor {

    private static final String TAG = CommonHeaderInterceptor.class.getSimpleName();
    private PreferencesHelper preferencesHelper;

    @Inject
    public CommonHeaderInterceptor(PreferencesHelper preferencesHelper) {
        this.preferencesHelper = preferencesHelper;
    }

    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        //统一设置请求头
        Request original = chain.request();
        String header = original.header("Domain-Name");

        Request.Builder requestBuilder = original.newBuilder();
        requestBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/68.0.3440.84 Safari/537.36");
        requestBuilder.header("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5");
        requestBuilder.header("Proxy-Connection", "keep-alive");
        requestBuilder.header("Cache-Control", "max-age=0");

        requestBuilder.method(original.method(), original.body());

        // Retrofit 的默认 BaseUrl 是 GitHub。若 URL 管理器尚未完成域名替换，
        // 先把带视频域名标记的请求直接改到当前配置，避免视频页面先访问 GitHub 再被重定向。
        // M65b：覆盖 9mman 与 porny 两个源（此前只处理 9mman，porny 播放页仍经 GitHub 重定向 400）。
        if (Api.PORN9_VIDEO_DOMAIN_NAME.equals(header) || Api.PORNY_DOMAIN_NAME.equals(header)) {
            String configuredAddress;
            if (Api.PORNY_DOMAIN_NAME.equals(header)) {
                configuredAddress = preferencesHelper.getPornyAddress();
            } else {
                configuredAddress = preferencesHelper.getMman9VideoAddress();
            }
            HttpUrl configuredUrl = HttpUrl.parse(configuredAddress);
            HttpUrl originalUrl = original.url();
            HttpUrl githubUrl = HttpUrl.parse(Api.APP_GITHUB_DOMAIN);
            if (configuredUrl != null
                    && githubUrl != null
                    && githubUrl.host().equals(originalUrl.host())
                    && !configuredUrl.host().equals(originalUrl.host())) {
                HttpUrl.Builder urlBuilder = originalUrl.newBuilder()
                        .scheme(configuredUrl.scheme())
                        .host(configuredUrl.host())
                        .port(configuredUrl.port());
                requestBuilder.url(urlBuilder.build());
            }
        }

        Request request = requestBuilder.build();
        // M62：只 proceed 一次。旧实现先 proceed 一次探测重定向、再 proceed 一次返回，
        // 导致所有主站请求被真实执行两次（POST 双提交/配额双耗），且首个 Response 从不关闭造成连接泄漏。
        // === M63 诊断：打印完整请求 URL + 最终 URL + HTTP 状态码，用于定位推荐/视频/详情 404 ===
        // M95：[DIAG] 常规诊断（info 级、含完整 URL）改为仅 debug 包输出——release 下逐请求
        // 三通道打日志既拖慢请求线程，也会把带 Cookie 上下文的 URL 泄露到日志文件。
        // HTTP 失败分支（code>=400，error 级）保持不门控：release 下仍需「复制日志」定位失败请求。
        String reqUrl = request.url().toString();
        if (BuildConfig.DEBUG) {
            String diagStart = "[DIAG] --> " + request.method() + " " + reqUrl;
            Logger.t(TAG).d(diagStart);
            AppLog.i(TAG, diagStart);
            Log.i(TAG, diagStart);
        }
        Response response = chain.proceed(request);
        int code = response.code();
        if (BuildConfig.DEBUG) {
            String finalUrl = response.request().url().toString();
            String diagEnd = "[DIAG] <-- HTTP " + code + "  "
                    + (reqUrl.equals(finalUrl) ? "" : ("(重定向->" + finalUrl + ") "))
                    + reqUrl;
            Logger.t(TAG).d(diagEnd);
            AppLog.i(TAG, diagEnd);
            Log.i(TAG, diagEnd);
        }
        // release 下保留错误级别日志能力：失败请求诊断不门控
        if (code >= 400) {
            String finalUrl = response.request().url().toString();
            String diagError = "[DIAG-ERR] 失败请求: " + request.method() + " " + reqUrl
                    + "  HTTP=" + code + "  final=" + finalUrl;
            Logger.t(TAG).e(diagError);
            AppLog.e(TAG, diagError);
            Log.e(TAG, diagError);
        }
        // === 诊断结束 ===

        //如果是可能被重定向的header，从最终响应读取实际 host 探测重定向
        if (!TextUtils.isEmpty(header) && header.equals(Api.PORN9_VIDEO_DOMAIN_NAME)) {
            HttpUrl httpUrl = response.request().url();
            //读取本地地址
            String url = preferencesHelper.getMman9VideoAddress();
            HttpUrl oldHttpUrl = HttpUrl.parse(url);
            //如果不相等则可能被重定向了
            if (oldHttpUrl != null && !oldHttpUrl.host().equals(httpUrl.host())) {
                // M73：域名毒化防护——只有成功响应（2xx/3xx）才更新全局域名映射。
                // CDN 错误页/302 到验证页（HTTP 4xx）时若照搬最终 host，会把整个会话的
                // 视频域名映射污染到错误地址，后续所有请求连锁失败。
                if (code < 400) {
                    HttpUrl newHttpUrl = new HttpUrl.Builder().scheme(httpUrl.scheme()).host(httpUrl.host()).build();
                    String urlStr = newHttpUrl.toString();
                    Logger.t(TAG).e("连接被重定向为:" + urlStr);
                    //更新为最新地址
                    RetrofitUrlManager.getInstance().putDomain(Api.PORN9_VIDEO_DOMAIN_NAME, urlStr);
                } else {
                    Logger.t(TAG).e("重定向目标返回 HTTP " + code + "，不更新域名映射(防毒化) host=" + httpUrl.host());
                }
            }
        }

        return response;
    }
}
