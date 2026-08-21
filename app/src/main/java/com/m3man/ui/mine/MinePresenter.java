package com.m3man.ui.mine;


import com.hannesdorfmann.mosby3.mvp.MvpBasePresenter;
import com.m3man.data.DataManager;
import com.m3man.data.model.User;

import javax.inject.Inject;

/**
 * @author megoc
 */
public class MinePresenter extends MvpBasePresenter<MineView> implements IMine {

    private DataManager dataManager;

    @Inject
    public MinePresenter(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Override
    public boolean isUserLogin() {
        return dataManager.isUserLogin();
    }

    @Override
    public User getLoginUser() {
        return dataManager.getUser();
    }

    @Override
    public void existLogin() {
        // 清除会话 cookie 与内存中的用户信息，并清空已保存的登录用户名，
        // 避免下次启动时被 AppDataManager 构造函数自动恢复登录态
        dataManager.existLogin();
        dataManager.setMman9VideoLoginUserName("");
    }

    @Override
    public boolean isOpenHttpProxy() {
        return dataManager.isOpenHttpProxy();
    }

    @Override
    public void setOpenHttpProxy(boolean openHttpProxy) {
        dataManager.setOpenHttpProxy(openHttpProxy);
    }

    @Override
    public String getProxyIpAddress() {
        return dataManager.getProxyIpAddress();
    }

    @Override
    public int getProxyPort() {
        return dataManager.getProxyPort();
    }

    @Override
    public boolean isOpenNightMode() {
        return dataManager.isOpenNightMode();
    }

    @Override
    public void setOpenNightMode(boolean openNightMode) {
        dataManager.setOpenNightMode(openNightMode);
    }

    @Override
    public void setSettingScrollViewScrollPosition(int settingScrollViewScrollPosition) {
        dataManager.setSettingScrollViewScrollPosition(settingScrollViewScrollPosition);
    }

    @Override
    public int getSettingScrollViewScrollPosition() {
        return dataManager.getSettingScrollViewScrollPosition();
    }

    @Override
    public boolean isLocalFavoriteMode() {
        return dataManager.isLocalFavoriteMode();
    }
}
