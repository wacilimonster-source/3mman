package com.m3man.ui.main;

import android.text.TextUtils;

import com.hannesdorfmann.mosby3.mvp.MvpBasePresenter;
import com.m3man.data.DataManager;
import com.m3man.data.model.Notice;
import com.m3man.data.model.UpdateVersion;
import com.m3man.ui.notice.NoticePresenter;
import com.m3man.ui.notice.NoticeView;
import com.m3man.ui.update.UpdatePresenter;
import com.m3man.ui.update.UpdateView;

import javax.inject.Inject;

/**
 * @author flymegoc
 * @date 2017/12/23
 */
public class MainPresenter extends MvpBasePresenter<MainView> implements IMain {

    private UpdatePresenter updatePresenter;
    private NoticePresenter noticePresenter;
    private DataManager dataManager;

    @Inject
    public MainPresenter(DataManager dataManager, UpdatePresenter updatePresenter, NoticePresenter noticePresenter) {
        this.dataManager = dataManager;
        this.updatePresenter = updatePresenter;
        this.noticePresenter = noticePresenter;
    }


    @Override
    public void setIgnoreUpdateVersionCode(int versionCode) {
        dataManager.setIgnoreUpdateVersionCode(versionCode);
    }

    /**
     * 检查版本更新。原先由 SplashActivity 负责，取消开屏页后移到这里。
     */
    @Override
    public void checkUpdate(int versionCode) {
        updatePresenter.checkUpdate(versionCode, new UpdatePresenter.UpdateListener() {
            @Override
            public void needUpdate(final UpdateVersion updateVersion) {
                ifViewAttached(view -> view.needUpdate(updateVersion));
            }

            @Override
            public void noNeedUpdate() {
                ifViewAttached(UpdateView::noNeedUpdate);
            }

            @Override
            public void checkUpdateError(final String message) {
                ifViewAttached(view -> view.checkUpdateError(message));
            }
        });
    }

    /**
     * 检查新公告。原先由 SplashActivity 负责，取消开屏页后移到这里。
     */
    @Override
    public void checkNewNotice() {
        noticePresenter.checkNewNotice(new NoticePresenter.CheckNewNoticeListener() {
            @Override
            public void haveNewNotice(final Notice notice) {
                ifViewAttached(view -> view.haveNewNotice(notice));
            }

            @Override
            public void noNewNotice() {
                ifViewAttached(NoticeView::noNewNotice);
            }

            @Override
            public void checkNewNoticeError(final String message) {
                ifViewAttached(view -> view.checkNewNoticeError(message));
            }
        });
    }

    @Override
    public int getIgnoreUpdateVersionCode() {
        return dataManager.getIgnoreUpdateVersionCode();
    }

    @Override
    public void saveNoticeVersionCode(int versionCode) {
        dataManager.setNoticeVersionCode(versionCode);
    }

    @Override
    public int getNoticeVersionCode() {
        return  dataManager.getNoticeVersionCode();
    }

    @Override
    public void setMainSecondTabShow(String tabId) {
        dataManager.setMainSecondTabShow(tabId);
    }

    @Override
    public String getMainSecondTabShow() {
        return dataManager.getMainSecondTabShow();
    }

    @Override
    public void setMainFirstTabShow(String tabId) {
        dataManager.setMainFirstTabShow(tabId);
    }

    @Override
    public String getMainFirstTabShow() {
        return dataManager.getMainFirstTabShow();
    }

    @Override
    public boolean haveNotSetV9pronAddress() {
        return TextUtils.isEmpty(dataManager.getMman9VideoAddress());
    }

    @Override
    public boolean isPornyEnabled() {
        return dataManager.isPornyEnabled();
    }

    @Override
    public boolean isUserLogin() {
        return dataManager.isUserLogin();
    }

    @Override
    public void setMman9VideoAddress(String mman9VideoAddress) {
        dataManager.setMman9VideoAddress(mman9VideoAddress);
    }

}
