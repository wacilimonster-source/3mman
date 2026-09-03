package com.m3man.ui.mman9video.user;

import androidx.appcompat.app.AppCompatActivity;

import com.m3man.di.module.BaseActivityModule;

import dagger.Module;
import dagger.Provides;

@Module(includes = BaseActivityModule.class)
public class UserLoginActivityModule {

    @Provides
    static AppCompatActivity provideAppCompatActivity(UserLoginActivity userLoginActivity) {
        return userLoginActivity;
    }
}
