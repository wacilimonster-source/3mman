package com.m3man.ui.mman9video.user;

import android.arch.lifecycle.Lifecycle;
import android.graphics.Bitmap;
import android.text.TextUtils;

import com.hannesdorfmann.mosby3.mvp.MvpBasePresenter;
import com.trello.rxlifecycle2.LifecycleProvider;
import com.m3man.data.DataManager;
import com.m3man.data.model.User;
import com.m3man.rxjava.CallBackWrapper;
import com.m3man.rxjava.RxSchedulersHelper;

import javax.inject.Inject;

import io.reactivex.disposables.Disposable;

/**
 * 用户登录
 *
 * @author flymegoc
 * @date 2017/12/10
 */

public class UserPresenter extends MvpBasePresenter<UserView> implements IUser {

    private LifecycleProvider<Lifecycle.Event> provider;
    private DataManager dataManager;

    @Inject
    public UserPresenter(LifecycleProvider<Lifecycle.Event> provider, DataManager dataManager) {
        this.provider = provider;
        this.dataManager = dataManager;
    }

    @Override
    public void login(String username, String password, String captcha) {
        login(username, password, captcha, null);
    }

    public void login(String username, String password, String captcha, final LoginListener loginListener) {
        dataManager.userLoginMman9Video(username, password, captcha)
                .compose(RxSchedulersHelper.ioMainThread())
                .compose(provider.bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<User>() {
                    @Override
                    public void onBegin(Disposable d) {
                        ifViewAttached(view -> {
                            if (loginListener == null) {
                                view.showLoading(true);
                            }
                        });
                    }

                    @Override
                    public void onSuccess(final User user) {
                        User stored = dataManager.getUser();
                        user.copyProperties(stored);
                        // parseUserInfo 可能因站点改版/响应不含用户信息段而解析不到用户名，
                        // 这里回退使用登录表单中的账号，确保“我的”页能正常展示登录信息
                        if (TextUtils.isEmpty(stored.getUserName())) {
                            stored.setUserName(username);
                        }
                        stored.setLogin(true);
                        if (loginListener != null) {
                            loginListener.loginSuccess(stored);
                        } else {
                            ifViewAttached(view -> {
                                view.showContent();
                                view.loginSuccess(stored);
                            });
                        }

                    }

                    @Override
                    public void onError(final String msg, int code) {
                        if (loginListener != null) {
                            loginListener.loginFailure(msg);
                        } else {
                            ifViewAttached(view -> {
                                view.showContent();
                                view.loginError(msg);
                            });
                        }
                    }
                });
    }

    @Override
    public void register(String username, String password1, String password2, String email, String captchaInput) {
        dataManager.userRegisterMman9Video(username, password1, password2, email, captchaInput)
                .compose(RxSchedulersHelper.ioMainThread())
                .compose(provider.bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<User>() {
                    @Override
                    public void onBegin(Disposable d) {
                        ifViewAttached(view -> view.showLoading(true));
                    }

                    @Override
                    public void onSuccess(final User user) {
                        ifViewAttached(view -> {
                            user.copyProperties(dataManager.getUser());
                            view.showContent();
                            view.registerSuccess(user);
                        });
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        ifViewAttached(view -> {
                            view.showContent();
                            view.registerFailure(msg);
                        });
                    }
                });
    }

    /**
     * 注册成功，默认保存用户名和密码
     */
    @Override
    public void saveUserInfoPrf(String username, String password) {
        dataManager.setMman9VideoLoginUserName(username);
        //记住密码
        dataManager.setMman9VideoLoginUserPassWord(password);
    }

    @Override
    public void saveUserInfoPrf(String username, String password, boolean isRememberPassword, boolean isAutoLogin) {
        dataManager.setMman9VideoLoginUserName(username);
        //记住密码
        if (isRememberPassword) {
            dataManager.setMman9VideoLoginUserPassWord(password);
        } else {
            dataManager.setMman9VideoLoginUserPassWord("");
        }
        //自动登录
        if (isAutoLogin) {
            dataManager.setMman9VideoUserAutoLogin(true);
        } else {
            dataManager.setMman9VideoUserAutoLogin(false);
        }
    }

    @Override
    public void loadCaptcha() {
        dataManager.mman9VideoLoginCaptcha()
                .compose(RxSchedulersHelper.ioMainThread())
                .compose(provider.bindUntilEvent(Lifecycle.Event.ON_STOP))
                .subscribe(new CallBackWrapper<Bitmap>() {
                    @Override
                    public void onSuccess(final Bitmap bitmap) {
                        ifViewAttached(view -> view.loadCaptchaSuccess(bitmap));
                    }

                    @Override
                    public void onError(final String msg, final int code) {
                        ifViewAttached(view -> view.loadCaptchaFailure(msg, code));
                    }
                });
    }

    @Override
    public String getUserName() {
        return dataManager.getMman9VideoLoginUserName();
    }

    @Override
    public String getPassword() {
        return dataManager.getMman9VideoLoginUserPassword();
    }

    @Override
    public boolean isAutoLogin() {
        return dataManager.isMman9VideoUserAutoLogin();
    }

    @Override
    public String getVideo9MmanAddress() {
        return dataManager.getMman9VideoAddress();
    }

    @Override
    public void existLogin() {
        dataManager.existLogin();
    }

    public interface LoginListener {
        void loginSuccess(User user);

        void loginFailure(String message);
    }
}
