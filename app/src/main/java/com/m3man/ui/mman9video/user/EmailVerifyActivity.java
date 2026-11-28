package com.m3man.ui.mman9video.user;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Cookie;

/**
 * 站内邮箱验证（应用内 WebView + 全自动）。
 * <p>
 * 站点现状（2026-11 实测）：登录后内容页会 302 到 {@code confirm_email.php?cv=1}，需要在
 * {@code email_verify.php} 里填一次邮箱并提交，站点随后跳一次广告页（几秒后出现「跳过广告」），
 * 跳过之后该会话才允许播放。浏览器里手动做一遍就能看片。
 * <p>
 * 这里把整条链路自动化：
 * <ol>
 *   <li>打开前把 App 的持久化 cookie 注入 WebView（已登录态）；</li>
 *   <li>用 App 会话抓 {@code my_profile.php} 里的 {@code prevemail} 拿到账号邮箱（无需用户输入）；</li>
 *   <li>自动填表并提交（站点预填的是 {@code 1441*****} 这类掩码，直接提交会被判无效）；</li>
 *   <li>自动点掉广告页的「跳过/Skip」；</li>
 *   <li>进入播放页后自动关闭本页返回原生播放器，并把 WebView 更新后的 cookie 回写 App。</li>
 * </ol>
 */
public class EmailVerifyActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "extra_url";
    /** 验证完成后从网页里抓到的媒体地址，回传给原生播放器。 */
    public static final String EXTRA_RESULT_MEDIA_URL = "extra_media_url";
    /** 需要播放的视频页地址：验证通过后由 WebView 打开它并抓媒体地址。 */
    public static final String EXTRA_VIDEO_URL = "extra_video_url";
    private static final String DEFAULT_URL = "https://www.91porn.com/email_verify.php";
    private static final String PROFILE_URL = "https://www.91porn.com/my_profile.php";
    private static final String TAG = "EmailVerifyActivity";

    /** 自动流程的轮询间隔与最大轮数（2.5s × 24 ≈ 60s 上限）。 */
    private static final long AUTO_STEP_INTERVAL_MS = 2500L;
    private static final int AUTO_STEP_MAX = 24;

    private static final Pattern PREV_EMAIL_PATTERN =
            Pattern.compile("name=\"prevemail\"\\s+value=\"([^\"]+@[^\"]+)\"");

    private WebView webView;
    private String targetUrl = DEFAULT_URL;
    private String accountEmail;
    private String videoPageUrl;
    private boolean navigatedToVideo;
    private boolean autoSubmitted;
    /** 提交邮箱后经过的轮数：给广告页留出跳过时间，再自动关闭并交回播放器。 */
    private int stepsAfterSubmit;
    private boolean emailFieldHintShown;
    private int autoStep;
    private final Handler autoHandler = new Handler(Looper.getMainLooper());

    private final Runnable autoDriver = new Runnable() {
        @Override
        public void run() {
            if (webView == null || isFinishing()) {
                return;
            }
            autoStep++;
            if (autoStep > AUTO_STEP_MAX) {
                AppLog.i(TAG, "自动流程超时，交由用户手动完成");
                return;
            }
            String url = webView.getUrl();
            if (!TextUtils.isEmpty(url) && isPlayerPage(url)) {
                // 站点验证后网页里已经能播，抓视频地址交回原生播放器
                grabMediaUrlAndFinish();
                return;
            }
            if (!autoSubmitted && isVerifyPage(url) && !TextUtils.isEmpty(accountEmail)) {
                fillEmailAndSubmit();
                autoSubmitted = true;
                stepsAfterSubmit = 0;
            } else if (autoSubmitted) {
                clickSkipAd();
                stepsAfterSubmit++;
                if (!navigatedToVideo && !TextUtils.isEmpty(videoPageUrl)) {
                    // 站点提交后会先跳广告页再回首页；这里主动打开目标视频页，验证后的会话即可加载它，
                    // 再由网页播放器里抓出真实媒体地址交回原生播放器。
                    AppLog.i(TAG, "验证已提交，打开目标视频页 " + videoPageUrl);
                    webView.loadUrl(videoPageUrl);
                    navigatedToVideo = true;
                    autoHandler.postDelayed(this, AUTO_STEP_INTERVAL_MS);
                    return;
                }
                // 抓不到播放页就留在应用内网页播放：站点把播放能力绑在网页会话上，
                // 用户在网页里验证 + 跳过广告后即可正常观看。
                if (stepsAfterSubmit >= 3) {
                    AppLog.i(TAG, "验证流程结束，保留应用内网页播放 url=" + url);
                    return;
                }
            } else {
                clickSkipAd();
            }
            autoHandler.postDelayed(this, AUTO_STEP_INTERVAL_MS);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent() != null) {
            if (!TextUtils.isEmpty(getIntent().getStringExtra(EXTRA_URL))) {
                targetUrl = getIntent().getStringExtra(EXTRA_URL);
            }
            videoPageUrl = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        }
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        // 用桌面 UA：站点对移动 UA 的模板会额外套验证/广告逻辑，桌面模板与用户浏览器行为一致
        settings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        settings.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String finishedUrl) {
                super.onPageFinished(view, finishedUrl);
                syncCookiesToApp();
            }
        });
        setContentView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        injectAppCookies(targetUrl);
        webView.loadUrl(targetUrl);
        fetchAccountEmailAsync();
        autoHandler.postDelayed(autoDriver, AUTO_STEP_INTERVAL_MS);
    }

    private boolean isVerifyPage(String url) {
        return !TextUtils.isEmpty(url) && (url.contains("email_verify") || url.contains("confirm_email"));
    }

    /**
     * 在网页播放器里抓媒体地址（video.src / source.src / 页面里的 mp4、m3u8），抓到就回传给原生播放器。
     * 抓不到就正常关闭，由播放页再走一次解析。
     */
    private void grabMediaUrlAndFinish() {
        if (webView == null) {
            finish();
            return;
        }
        String js = "(function(){"
                + "var v=document.querySelector('video');"
                + "if(v&&v.src&&v.src.indexOf('blob:')!==0){return v.src;}"
                + "var s=document.querySelector('video source');"
                + "if(s&&s.src){return s.src;}"
                + "var h=document.documentElement.innerHTML;"
                + "var m=h.match(/https?:[^\"'\\\\s]+\\.(mp4|m3u8)[^\"'\\\\s]*/);"
                + "if(m){return m[0];}"
                + "return '';})()";
        webView.evaluateJavascript(js, value -> {
            String cleaned = value == null ? "" : value.replace("\"", "").replace("\\/", "/").trim();
            if (!TextUtils.isEmpty(cleaned) && cleaned.startsWith("http") && !"null".equals(cleaned)) {
                AppLog.i(TAG, "抓到网页媒体地址=" + cleaned);
                Intent result = new Intent();
                result.putExtra(EXTRA_RESULT_MEDIA_URL, cleaned);
                setResult(RESULT_OK, result);
                finish();
                return;
            }
            // 抓不到媒体地址就留在应用内网页播放（站点把播放能力绑在网页会话上，网页里能正常播）
            AppLog.i(TAG, "网页未抓到媒体地址，保留应用内网页播放 value=" + value);
        });
    }

    private boolean isPlayerPage(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        return url.contains("view_video") || url.contains("watch_video") || url.contains("v.php?next=watch");
    }

    /** 用 App 会话抓账号邮箱：站点在 my_profile.php 里用隐藏域 prevemail 存的是完整邮箱。 */
    private void fetchAccountEmailAsync() {
        new Thread(() -> {
            String email = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(PROFILE_URL).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36");
                connection.setRequestProperty("Cookie", buildCookieHeader());
                connection.setInstanceFollowRedirects(true);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }
                Matcher matcher = PREV_EMAIL_PATTERN.matcher(sb.toString());
                if (matcher.find()) {
                    email = matcher.group(1).trim();
                }
                AppLog.i(TAG, "抓取账号邮箱结果=" + (email == null ? "未取到" : email));
            } catch (Exception e) {
                AppLog.w(TAG, "抓取账号邮箱失败 " + AppLog.cause(e));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            final String resolved = email;
            runOnUiThread(() -> accountEmail = resolved);
        }, "email-verify-profile").start();
    }

    private String buildCookieHeader() {
        StringBuilder sb = new StringBuilder();
        try {
            List<Cookie> cookies = new SharedPrefsCookiePersistor(getApplicationContext()).loadAll();
            for (Cookie cookie : cookies) {
                if (cookie.domain() == null || !cookie.domain().contains("91porn")) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(cookie.name()).append('=').append(cookie.value());
            }
        } catch (Exception e) {
            AppLog.w(TAG, "拼装 cookie 失败 " + AppLog.cause(e));
        }
        return sb.toString();
    }

    /** 自动填表并提交（站点预填是掩码，必须覆盖成完整邮箱）。 */
    private void fillEmailAndSubmit() {
        if (webView == null) {
            return;
        }
        String js = "(function(){var f=document.querySelector('input[name=email]');"
                + "if(!f){return 'no-input';}"
                + "f.value='" + accountEmail.replace("'", "") + "';"
                + "var s=document.querySelector('input[name=submit]')||document.querySelector('input[type=submit]');"
                + "if(s){s.click();return 'submitted';}"
                + "var fm=f.form;if(fm){fm.submit();return 'form-submit';}"
                + "return 'no-submit';})()";
        webView.evaluateJavascript(js, value -> AppLog.i(TAG, "自动提交邮箱结果=" + value));
    }

    /** 广告页：找「跳过/Skip/关闭/×」并点掉。 */
    private void clickSkipAd() {
        if (webView == null) {
            return;
        }
        String js = "(function(){var kw=['\u8df3\u8fc7','\u8df3\u904e','Skip','SKIP','skip','\u5173\u95ed','\u00d7','\u2715'];" // 跳过/跳過/Skip/关闭/×/✕
                + "var els=document.querySelectorAll('a,button,div,span,img,i');"
                + "for(var i=0;i<els.length;i++){var el=els[i];"
                + "var t=((el.innerText||el.value||el.alt||'')+'').trim();"
                + "if(!t||t.length>16){continue;}"
                + "for(var k=0;k<kw.length;k++){if(t.indexOf(kw[k])>=0){try{el.click();}catch(e){};return 'clicked:'+t;}}}"
                + "return 'none';})()";
        webView.evaluateJavascript(js, value -> {
            if (value != null && !"\"none\"".equals(value) && !"null".equals(value)) {
                AppLog.i(TAG, "广告跳过点击结果=" + value);
            }
        });
    }

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

    /**
     * 把 WebView 侧可能被站点更新过的 cookie 写回 App 的 OkHttp cookie 仓库。
     * <p>
     * 站点把「已验证」绑在当前会话上，而 WebView 与 OkHttp 是两套 cookie 存储；
     * 只做单向注入时，原生播放器仍带着未验证的会话，这里补齐反向同步。
     */
    private void syncCookiesToApp() {
        try {
            if (webView == null) {
                return;
            }
            String url = webView.getUrl();
            if (TextUtils.isEmpty(url)) {
                url = targetUrl;
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

    @Override
    protected void onDestroy() {
        autoHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            syncCookiesToApp();
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
