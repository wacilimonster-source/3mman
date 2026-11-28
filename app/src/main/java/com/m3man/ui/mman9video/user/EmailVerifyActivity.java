package com.m3man.ui.mman9video.user;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.m3man.cookie.SharedPrefsCookiePersistor;
import com.m3man.utils.AppLog;

import java.util.List;

import okhttp3.Cookie;

/**
 * 站内邮箱验证页（WebView）。
 * <p>
 * 2026-11 实测：播放页提示「需要先验证邮箱」后，旧实现用 ACTION_VIEW 把
 * {@code email_verify.php} 丢给系统浏览器——浏览器没有 App 的会话 cookie，
 * 用户还要在网页里重新登录一次才能验证。这里改成应用内 WebView，并先把
 * App 的持久化 cookie 注入到 WebView，用户打开就是已登录状态。
 */
public class EmailVerifyActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "extra_url";
    private static final String DEFAULT_URL = "https://www.91porn.com/email_verify.php";
    private static final String TAG = "EmailVerifyActivity";

    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String url = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_URL);
        if (TextUtils.isEmpty(url)) {
            url = DEFAULT_URL;
        }
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        webView.setWebViewClient(new WebViewClient());
        setContentView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        injectAppCookies(url);
        webView.loadUrl(url);
    }

    /**
     * 把 OkHttp 侧的持久化 cookie 注入系统 WebView，使验证页直接处于登录态。
     * 注入失败不影响页面打开（用户仍可在页面里手动登录）。
     */
    private void injectAppCookies(String url) {
        try {
            CookieManager webCookieManager = CookieManager.getInstance();
            webCookieManager.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                webCookieManager.setAcceptThirdPartyCookies(webView, true);
            }
            List<Cookie> cookies = new SharedPrefsCookiePersistor(getApplicationContext()).loadAll();
            int injected = 0;
            for (Cookie cookie : cookies) {
                String domain = cookie.domain();
                if (TextUtils.isEmpty(domain)) {
                    continue;
                }
                String value = cookie.name() + "=" + cookie.value()
                        + "; path=" + (TextUtils.isEmpty(cookie.path()) ? "/" : cookie.path());
                webCookieManager.setCookie(domain, value);
                injected++;
            }
            // 目标 host 再显式写一遍，避免 domain 写法（.91porn.com / www.91porn.com）不一致导致漏发
            for (Cookie cookie : cookies) {
                webCookieManager.setCookie(url, cookie.name() + "=" + cookie.value() + "; path=/");
            }
            webCookieManager.flush();
            AppLog.i(TAG, "注入 cookie 数量=" + injected + " url=" + url);
        } catch (Exception e) {
            AppLog.w(TAG, "注入 cookie 失败 " + AppLog.cause(e));
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
