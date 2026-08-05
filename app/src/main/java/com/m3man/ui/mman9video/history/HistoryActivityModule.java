package com.m3man.ui.mman9video.history;

import android.arch.lifecycle.Lifecycle;
import android.support.v7.app.AppCompatActivity;

import com.trello.lifecycle2.android.lifecycle.AndroidLifecycle;
import com.trello.rxlifecycle2.LifecycleProvider;
import com.m3man.ui.about.AboutActivity;

import dagger.Module;
import dagger.Provides;

@Module
public class HistoryActivityModule {
    @Provides
    AppCompatActivity provideAppCompatActivity(HistoryActivity historyActivity){
        return historyActivity;
    }

    @Provides
    static LifecycleProvider<Lifecycle.Event> providerLifecycleProvider(AppCompatActivity mAppCompatActivity) {
        return AndroidLifecycle.createLifecycleProvider(mAppCompatActivity);
    }
}
