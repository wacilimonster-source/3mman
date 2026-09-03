package com.m3man.ui.mman9video.favorite;

import androidx.appcompat.app.AppCompatActivity;

import com.m3man.di.module.BaseActivityModule;

import dagger.Module;
import dagger.Provides;

@Module(includes = BaseActivityModule.class)
public class PornyFavoriteActivityModule {

    @Provides
    AppCompatActivity provideAppCompatActivity(PornyFavoriteActivity pornyFavoriteActivity) {
        return pornyFavoriteActivity;
    }
}
