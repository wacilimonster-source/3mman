package com.m3man.cookie;

import com.franmontiel.persistentcookiejar.PersistentCookieJar;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class AppCookieManager implements CookieManager {
    private final PersistentCookieJar cookieJar;

    @Inject
    public AppCookieManager(PersistentCookieJar cookieJar) {
        this.cookieJar = cookieJar;
    }

    @Override
    public void cleanAllCookies() {
        cookieJar.clear();
    }
}
