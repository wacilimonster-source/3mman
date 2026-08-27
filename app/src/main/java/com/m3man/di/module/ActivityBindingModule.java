package com.m3man.di.module;

import com.m3man.di.PerActivity;
import com.m3man.ui.about.AboutActivity;
import com.m3man.ui.about.AboutActivityModule;
import com.m3man.ui.download.DownloadActivity;
import com.m3man.ui.download.DownloadActivityModule;
import com.m3man.ui.main.MainActivity;
import com.m3man.ui.main.MainActivityModule;
import com.m3man.ui.mman9video.author.AuthorActivity;
import com.m3man.ui.mman9video.author.AuthorActivityModule;
import com.m3man.ui.mman9video.author.AuthorFavoriteActivity;
import com.m3man.ui.mman9video.favorite.FavoriteActivity;
import com.m3man.ui.mman9video.favorite.FavoriteActivityModule;
import com.m3man.ui.mman9video.favorite.PornyFavoriteActivity;
import com.m3man.ui.mman9video.favorite.PornyFavoriteActivityModule;
import com.m3man.ui.mman9video.history.HistoryActivity;
import com.m3man.ui.mman9video.history.HistoryActivityModule;
import com.m3man.ui.mman9video.play.ExoMediaPlayerActivity;
import com.m3man.ui.mman9video.play.ExoPlayerVideoModule;
import com.m3man.ui.mman9video.play.JiaoZiVideoPlayerActivity;
import com.m3man.ui.mman9video.play.JiaoZiVideoPlayerModule;
import com.m3man.ui.mman9video.search.SearchActivity;
import com.m3man.ui.mman9video.search.SearchActivityModule;
import com.m3man.ui.mman9video.user.UserLoginActivity;
import com.m3man.ui.mman9video.user.UserLoginActivityModule;
import com.m3man.ui.mman9video.user.UserRegisterActivity;
import com.m3man.ui.mman9video.user.UserRegisterActivityModule;
import com.m3man.ui.proxy.ProxySettingActivity;
import com.m3man.ui.proxy.ProxySettingActivityModule;
import com.m3man.ui.setting.SettingActivity;
import com.m3man.ui.setting.SettingActivityModule;
import com.m3man.ui.update.UpdateActivity;

import dagger.Module;
import dagger.android.ContributesAndroidInjector;

/**
 * @author megoc
 */
@Module
public abstract class ActivityBindingModule {

    @PerActivity
    @ContributesAndroidInjector(modules = MainActivityModule.class)
    abstract MainActivity mainActivity();

    @PerActivity
    @ContributesAndroidInjector(modules = DownloadActivityModule.class)
    abstract DownloadActivity downloadActivity();

    @PerActivity
    @ContributesAndroidInjector(modules = SettingActivityModule.class)
    abstract SettingActivity settingActivity();

    @PerActivity
    @ContributesAndroidInjector(modules = AboutActivityModule.class)
    abstract AboutActivity aboutActivity();

    @PerActivity
    @ContributesAndroidInjector(modules = FavoriteActivityModule.class)
    abstract FavoriteActivity favoriteActivity();

    @PerActivity
    @ContributesAndroidInjector(modules = PornyFavoriteActivityModule.class)
    abstract PornyFavoriteActivity pornyFavoriteActivity();

    @PerActivity
    @ContributesAndroidInjector(modules = SearchActivityModule.class)
    abstract SearchActivity searchActivity();

    @PerActivity
    @ContributesAndroidInjector(modules = ExoPlayerVideoModule.class)
    abstract ExoMediaPlayerActivity exoMediaPlayerActivity();

    @PerActivity
    @ContributesAndroidInjector(modules = JiaoZiVideoPlayerModule.class)
    abstract JiaoZiVideoPlayerActivity jiaoZiVideoPlayerActivity();

    @PerActivity
    @ContributesAndroidInjector(modules = UserLoginActivityModule.class)
    abstract UserLoginActivity userLoginActivity();

    @PerActivity
    @ContributesAndroidInjector(modules = UserRegisterActivityModule.class)
    abstract UserRegisterActivity userRegisterActivity();

    @PerActivity
    @ContributesAndroidInjector(modules = AuthorActivityModule.class)
    abstract AuthorActivity authorActivity();

    @PerActivity
    @ContributesAndroidInjector
    abstract AuthorFavoriteActivity authorFavoriteActivity();

    @PerActivity
    @ContributesAndroidInjector(modules = ProxySettingActivityModule.class)
    abstract ProxySettingActivity proxySettingActivity();

    @PerActivity
    @ContributesAndroidInjector(modules = HistoryActivityModule.class)
    abstract HistoryActivity historyActivity();

    // M98：RecommendFeedActivity 已删除，其 @ContributesAndroidInjector 绑定一并移除

    @PerActivity
    @ContributesAndroidInjector
    abstract UpdateActivity updateActivity();
}
