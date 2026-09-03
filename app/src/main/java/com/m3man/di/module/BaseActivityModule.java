package com.m3man.di.module;

import androidx.lifecycle.Lifecycle;
import androidx.appcompat.app.AppCompatActivity;

import com.trello.lifecycle2.android.lifecycle.AndroidLifecycle;
import com.trello.rxlifecycle2.LifecycleProvider;

import dagger.Module;
import dagger.Provides;

/**
 * H-12: 基础 Activity Module，提供通用的 LifecycleProvider 绑定。
 * 子类只需提供具体的 Activity 实例绑定。
 */
@Module
public abstract class BaseActivityModule {

    @Provides
    static LifecycleProvider<Lifecycle.Event> provideLifecycleProvider(AppCompatActivity activity) {
        return AndroidLifecycle.createLifecycleProvider(activity);
    }
}