package com.m3man.di.component;

import android.app.Application;

import com.m3man.MyApplication;
import com.m3man.di.module.ActivityBindingModule;
import com.m3man.di.module.ApiServiceModule;
import com.m3man.di.module.ApplicationModule;
import com.m3man.di.module.ServiceBindingModule;

import javax.inject.Singleton;

import dagger.BindsInstance;
import dagger.Component;
import dagger.android.AndroidInjector;
import dagger.android.support.AndroidSupportInjectionModule;

@Singleton
@Component( modules = {ApplicationModule.class, ApiServiceModule.class, ActivityBindingModule.class,ServiceBindingModule.class, AndroidSupportInjectionModule.class})
public interface AppComponent extends AndroidInjector<MyApplication> {

    @Override
    void inject(MyApplication instance);

    @Component.Builder
    interface Builder {

        @BindsInstance
        AppComponent.Builder application(Application application);

        AppComponent build();
    }
}
