package com.m3man.ui.main;

import android.arch.lifecycle.Lifecycle;
import android.support.v7.app.AppCompatActivity;

import com.trello.lifecycle2.android.lifecycle.AndroidLifecycle;
import com.trello.rxlifecycle2.LifecycleProvider;
import com.m3man.di.PerFragment;
import com.m3man.ui.axgle.AxgleFragment;
import com.m3man.ui.axgle.MainAxgleFragment;
import com.m3man.ui.kedouwo.KeDouFragment;
import com.m3man.ui.kedouwo.MainKeDouFragment;
import com.m3man.ui.mine.MineFragment;
import com.m3man.ui.mman9video.Main9MmanVideoFragment;
import com.m3man.ui.mman9video.comment.CommentFragment;
import com.m3man.ui.mman9video.index.IndexFragment;
import com.m3man.ui.mman9video.videolist.VideoListFragment;
import com.m3man.ui.pxgav.MainPxgavFragment;
import com.m3man.ui.pxgav.PxgavFragment;

import dagger.Module;
import dagger.Provides;
import dagger.android.ContributesAndroidInjector;

@Module
public abstract class MainActivityModule {
    @PerFragment
    @ContributesAndroidInjector
    abstract VideoListFragment videoListFragment();

    @PerFragment
    @ContributesAndroidInjector
    abstract PxgavFragment pavFragment();

    @PerFragment
    @ContributesAndroidInjector
    abstract MainPxgavFragment mainPavFragment();

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
    abstract MainAxgleFragment mainAxgleFragment();

    @PerFragment
    @ContributesAndroidInjector
    abstract AxgleFragment axgleFragment();

    @PerFragment
    @ContributesAndroidInjector
    abstract MainKeDouFragment mainKeDouFragment();

    @PerFragment
    @ContributesAndroidInjector
    abstract KeDouFragment KeDouFragment();

    @Provides
    static AppCompatActivity provideAppCompatActivity(MainActivity mainActivity){
        return mainActivity;
    }

    @Provides
    static LifecycleProvider<Lifecycle.Event> providerLifecycleProvider(AppCompatActivity mAppCompatActivity) {
        return AndroidLifecycle.createLifecycleProvider(mAppCompatActivity);
    }
}
