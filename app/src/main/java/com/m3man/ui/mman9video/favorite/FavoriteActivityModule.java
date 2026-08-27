package com.m3man.ui.mman9video.favorite;

import android.support.v7.app.AppCompatActivity;

import com.m3man.di.module.BaseActivityModule;

import dagger.Module;
import dagger.Provides;

@Module(includes = BaseActivityModule.class)
public class FavoriteActivityModule {

    @Provides
    AppCompatActivity provideAppCompatActivity(FavoriteActivity favoriteActivity) {
        return favoriteActivity;
    }
}
