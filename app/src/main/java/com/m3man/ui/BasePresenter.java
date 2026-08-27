package com.m3man.ui;

import android.arch.lifecycle.Lifecycle;

import com.trello.rxlifecycle2.LifecycleProvider;

/**
 * @author flymegoc
 * @date 2018/2/4
 */

public class BasePresenter {
    // L-07：6 个构造重载中全工程实际只走到 provider 这一条链，其余重载与
    // cacheProviders/appDataManager 字段一并移除；provider 由子类（SettingPresenter）使用。
    protected LifecycleProvider<Lifecycle.Event> provider;

    public BasePresenter(LifecycleProvider<Lifecycle.Event> provider) {
        this.provider = provider;
    }
}
