package com.m3man.ui.mman9video.author;

import android.support.v7.app.AppCompatActivity;

import com.m3man.di.module.BaseActivityModule;

import dagger.Module;
import dagger.Provides;

@Module(includes = BaseActivityModule.class)
public class AuthorActivityModule {

    @Provides
    AppCompatActivity provideAppCompatActivity(AuthorActivity authorActivity) {
        return authorActivity;
    }
}
