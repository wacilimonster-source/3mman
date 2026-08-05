package com.m3man.ui.mman9video.author;

import android.arch.lifecycle.Lifecycle;
import android.support.v7.app.AppCompatActivity;

import com.trello.lifecycle2.android.lifecycle.AndroidLifecycle;
import com.trello.rxlifecycle2.LifecycleProvider;
import com.m3man.ui.about.AboutActivity;

import dagger.Module;
import dagger.Provides;

@Module
public class AuthorActivityModule {
    @Provides
    AppCompatActivity provideAppCompatActivity(AuthorActivity authorActivity){
        return authorActivity;
    }

    @Provides
    static LifecycleProvider<Lifecycle.Event> providerLifecycleProvider(AppCompatActivity mAppCompatActivity) {
        return AndroidLifecycle.createLifecycleProvider(mAppCompatActivity);
    }
}
