package com.m3man.ui.setting;

import androidx.appcompat.app.AppCompatActivity;

import com.m3man.di.module.BaseActivityModule;

import dagger.Module;
import dagger.Provides;

@Module(includes = BaseActivityModule.class)
public class SettingActivityModule {

    @Provides
    AppCompatActivity provideAppCompatActivity(SettingActivity settingActivity) {
        return settingActivity;
    }
}
