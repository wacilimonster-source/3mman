package com.m3man.cookie;

import android.text.TextUtils;

import com.franmontiel.persistentcookiejar.PersistentCookieJar;
import com.franmontiel.persistentcookiejar.cache.CookieCache;
import com.franmontiel.persistentcookiejar.persistence.CookiePersistor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

public class RulerCookie extends PersistentCookieJar {
    private static final String VIDEO_PATH = "/view_video.php";
    //登录信息cookie
    private List<String> BLACK_COOKIE = Arrays.asList("USERNAME","user_level","level","EMAILVERIFIED","DUID");

    public RulerCookie(CookieCache cache, CookiePersistor persistor) {
        super(cache, persistor);
    }

    @Override
    public synchronized List<Cookie> loadForRequest(HttpUrl url) {
        String host = url.host();
        String path = url.encodedPath();
        if (!TextUtils.equals(VIDEO_PATH, path)) {
            return super.loadForRequest(url);
        }else {
            //请求视频信息的时候去除登录信息
            List<Cookie> requestCookies = new ArrayList<>();
            List<Cookie> cookies = super.loadForRequest(url);
            for (Cookie cookie : cookies) {
                // M95：黑名单匹配改为 cookie 名精确（忽略大小写）比较。
                // 旧实现 cookie.toString().contains(blackName) 会把 VALUE 值中恰好含
                // 黑名单子串的无关 cookie 误杀（如值里带 "level" 的普通 cookie 被误删）。
                boolean useful = true;
                for (String blackName: BLACK_COOKIE) {
                    if (cookie.name().equalsIgnoreCase(blackName)){
                        useful = false;
                        break;
                    }
                }
                if (useful){
                    requestCookies.add(cookie);
                }
            }
            return requestCookies;
        }
    }
}
