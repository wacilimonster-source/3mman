package com.m3man.ui.mman9video.play;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.m3man.R;
import com.m3man.utils.AppLog;

import okhttp3.HttpUrl;

/** Plays main-site pages that load their media through the website's player. */
public class MainSitePlaybackActivity extends AppCompatActivity {
    private FrameLayout container;
    private WebView webView;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private AlertDialog loadError;

    @Override
    @SuppressLint("SetJavaScriptEnabled") // 源站播放器依赖 JavaScript，网页没有原生接口。
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String pageUrl = getIntent().getDataString();
        HttpUrl page = pageUrl == null ? null : HttpUrl.parse(pageUrl);
        if (page == null) {
            finish();
            return;
        }

        container = new FrameLayout(this);
        webView = new WebView(this);
        container.addView(webView, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setContentView(container);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        // 用户点播后，源站会异步切换到实际视频。允许播放器在切流完成后继续播放。
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                if (!"http".equals(target.getScheme()) && !"https".equals(target.getScheme())) {
                    return true;
                }
                return request.isForMainFrame() && !page.host().equalsIgnoreCase(target.getHost());
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showLoadError("WebView " + error.getErrorCode());
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse error) {
                if (request.isForMainFrame()) showLoadError("HTTP " + error.getStatusCode());
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreenView = view;
                fullscreenCallback = callback;
                webView.setVisibility(View.INVISIBLE);
                container.addView(view, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });
        webView.loadUrl(page.newBuilder().fragment("player_one").build().toString());
    }

    private void showLoadError(String reason) {
        if (isFinishing() || isDestroyed() || (loadError != null && loadError.isShowing())) return;
        AppLog.w("MainSitePlayback", "主站播放页加载失败 " + reason);
        loadError = new AlertDialog.Builder(this)
                .setMessage(R.string.play_main_site_web_error)
                .setPositiveButton(R.string.play_main_site_web_retry, (dialog, which) -> webView.reload())
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
                .show();
    }

    private void exitFullscreen() {
        if (fullscreenView == null) return;
        container.removeView(fullscreenView);
        fullscreenView = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        webView.setVisibility(View.VISIBLE);
        WebChromeClient.CustomViewCallback callback = fullscreenCallback;
        fullscreenCallback = null;
        callback.onCustomViewHidden();
    }

    @Override
    public void onBackPressed() {
        if (fullscreenView != null) {
            exitFullscreen();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (loadError != null) loadError.dismiss();
        if (webView != null) {
            exitFullscreen();
            container.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
