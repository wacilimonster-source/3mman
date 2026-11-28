package com.m3man.ui.mman9video.user;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.m3man.R;
import com.m3man.cookie.SharedPrefsCookiePersistor;
import com.m3man.utils.AppLog;

import java.util.ArrayList;
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
    /** 网址提示只弹一次（页面会多次 onPageFinished）。 */
    private boolean emailFieldHintShown;

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
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String finishedUrl) {
                super.onPageFinished(view, finishedUrl);
                prepareEmailField(view);
                syncCookiesToApp();
            }
        });
        setContentView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        injectAppCookies(url);
        webView.loadUrl(url);
    }

    /**
     * 站点表单里的邮箱输入框预填的是掩码值（如 {@code 1441*****}），直接点 Submit 会被站点判为
     * “Please enter your email.”。这里把它清空并聚焦，用户直接输入完整邮箱即可；
     * 同时给一次提示，避免用户以为点一下就好。
     */
    private void prepareEmailField(WebView view) {
        if (view == null) {
            return;
        }
        view.evaluateJavascript(
                "(function(){var f=document.querySelector('input[name=email]');"
                        + "if(f){if(f.value&&f.value.indexOf('*')>=0){f.value='';}f.focus();"
                        + "return f.value;}return 'none';})()",
                value -> AppLog.i(TAG, "验证页邮箱输入框处理结果=" + value));
        if (!emailFieldHintShown) {
            emailFieldHintShown = true;
            Toast.makeText(this, R.string.play_email_verify_input_hint, Toast.LENGTH_LONG).show();
        }
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
        syncCookiesToApp();
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        syncCookiesToApp();
        super.onPause();
    }

    /**
     * 把 WebView 侧可能被站点更新过的 cookie 写回 App 的 OkHttp cookie 仓库。
     * <p>
     * 2026-11 实测：站点在验证页提交邮箱后，会把「已验证」状态绑在**当前会话**上（浏览器里提交完就能播）。
     * 但 WebView 与 OkHttp 是两套 cookie 存储，只做「App → WebView」单向注入时，原生播放器仍然带着
     * 未验证的会话；这里补齐反向同步，让返回播放页后的请求复用同一个会话。
     */
    private void syncCookiesToApp() {
        try {
            if (webView == null) {
                return;
            }
            String url = webView.getUrl();
            if (TextUtils.isEmpty(url)) {
                url = DEFAULT_URL;
            }
            String cookieHeader = CookieManager.getInstance().getCookie(url);
            if (TextUtils.isEmpty(cookieHeader)) {
                return;
            }
            String host = Uri.parse(url).getHost();
            if (TextUtils.isEmpty(host)) {
                return;
            }
            List<Cookie> parsed = new ArrayList<>();
            for (String pair : cookieHeader.split(";")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                if (name.isEmpty()) {
                    continue;
                }
                parsed.add(new Cookie.Builder().domain(host).path("/").name(name).value(value).build());
            }
            if (!parsed.isEmpty()) {
                new SharedPrefsCookiePersistor(getApplicationContext()).saveAll(parsed);
                AppLog.i(TAG, "回写 WebView cookie 数量=" + parsed.size() + " host=" + host);
            }
        } catch (Exception e) {
            AppLog.w(TAG, "回写 WebView cookie 失败 " + AppLog.cause(e));
        }
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
