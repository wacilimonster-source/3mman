package com.m3man.ui.main;

import android.arch.lifecycle.Lifecycle;
import android.support.v7.app.AppCompatActivity;

import com.m3man.di.module.BaseActivityModule;
import com.m3man.di.PerFragment;
import com.m3man.ui.mine.MineFragment;
import com.m3man.ui.mman9video.Main9MmanVideoFragment;
import com.m3man.ui.mman9video.search.SearchPornyFragment;
import com.m3man.ui.recommend.RecommendFeedFragment;
import com.m3man.ui.mman9video.comment.CommentFragment;
import com.m3man.ui.mman9video.index.IndexFragment;
import com.m3man.ui.mman9video.videolist.VideoListFragment;

import dagger.Module;
import dagger.Provides;
import dagger.android.ContributesAndroidInjector;

@Module(includes = BaseActivityModule.class)
public abstract class MainActivityModule {
    @PerFragment
    @ContributesAndroidInjector
    abstract VideoListFragment videoListFragment();

    @PerFragment
    @ContributesAndroidInjector
    abstract IndexFragment indexFragment();

    @PerFragment
    @ContributesAndroidInjector
    abstract MineFragment mineFragment();

    @PerFragment
    @ContributesAndroidInjector
    abstract CommentFragment commentFragment();

    @PerFragment
    @ContributesAndroidInjector
    abstract Main9MmanVideoFragment main9MmanVideoFragment();

    @PerFragment
    @ContributesAndroidInjector
    abstract SearchPornyFragment searchPornyFragment();

    @PerFragment
    @ContributesAndroidInjector
    abstract RecommendFeedFragment recommendFeedFragment();

    @Provides
    static AppCompatActivity provideAppCompatActivity(MainActivity mainActivity) {
        return mainActivity;
    }
}
