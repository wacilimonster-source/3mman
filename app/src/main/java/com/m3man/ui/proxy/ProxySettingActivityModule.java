package com.m3man.ui.proxy;

import android.support.v7.app.AppCompatActivity;

import com.m3man.di.module.BaseActivityModule;

import dagger.Module;
import dagger.Provides;

@Module(includes = BaseActivityModule.class)
public class ProxySettingActivityModule {

    @Provides
    static AppCompatActivity provideAppCompatActivity(ProxySettingActivity proxySettingActivity) {
        return proxySettingActivity;
    }
}
