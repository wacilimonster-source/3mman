package com.m3man.di.module;

import com.m3man.di.PerService;
import com.m3man.service.DownloadVideoModule;
import com.m3man.service.DownloadVideoService;

import dagger.Module;
import dagger.android.ContributesAndroidInjector;

@Module
public abstract class ServiceBindingModule {
    @PerService
    @ContributesAndroidInjector(modules = DownloadVideoModule.class)
    abstract DownloadVideoService downloadVideoService();
}
