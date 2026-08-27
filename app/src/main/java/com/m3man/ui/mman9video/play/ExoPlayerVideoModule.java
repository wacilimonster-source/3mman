package com.m3man.ui.mman9video.play;

import android.arch.lifecycle.Lifecycle;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatActivity;

import com.m3man.di.module.BaseActivityModule;
import com.m3man.di.PerFragment;
import com.m3man.ui.mman9video.author.AuthorFragment;
import com.m3man.ui.mman9video.comment.CommentFragment;
import com.m3man.ui.mman9video.favorite.FavoriteFragment;
import com.m3man.ui.mman9video.index.IndexFragment;
import com.m3man.ui.mman9video.videolist.VideoListFragment;

import dagger.Module;
import dagger.Provides;
import dagger.android.ContributesAndroidInjector;

@Module(includes = BaseActivityModule.class)
public abstract class ExoPlayerVideoModule {
    @PerFragment
    @ContributesAndroidInjector
    abstract AuthorFragment authorFragment();

    @PerFragment
    @ContributesAndroidInjector
    abstract FavoriteFragment favoriteFragment();

    @PerFragment
    @ContributesAndroidInjector
    abstract CommentFragment commentFragment();

    @PerFragment
    @ContributesAndroidInjector
    abstract IndexFragment indexFragment();

    @PerFragment
    @ContributesAndroidInjector
    abstract VideoListFragment videoListFragment();

    @Provides
    static AppCompatActivity provideAppCompatActivity(ExoMediaPlayerActivity exoMediaPlayerActivity) {
        return exoMediaPlayerActivity;
    }

    @Provides
    static FragmentManager providesSupportFragmentManager(AppCompatActivity mAppCompatActivity) {
        return mAppCompatActivity.getSupportFragmentManager();
    }
}
