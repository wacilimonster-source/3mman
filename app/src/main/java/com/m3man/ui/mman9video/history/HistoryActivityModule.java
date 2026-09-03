package com.m3man.ui.mman9video.history;

import androidx.appcompat.app.AppCompatActivity;

import com.m3man.di.module.BaseActivityModule;

import dagger.Module;
import dagger.Provides;

@Module(includes = BaseActivityModule.class)
public class HistoryActivityModule {

    @Provides
    AppCompatActivity provideAppCompatActivity(HistoryActivity historyActivity) {
        return historyActivity;
    }
}
