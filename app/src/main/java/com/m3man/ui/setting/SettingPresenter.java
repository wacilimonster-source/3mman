package com.m3man.ui.setting;

import android.arch.lifecycle.Lifecycle;
import android.support.annotation.NonNull;
import android.text.TextUtils;

import com.orhanobut.logger.Logger;
import com.qmuiteam.qmui.widget.grouplist.QMUICommonListItemView;
import com.trello.rxlifecycle2.LifecycleProvider;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.AutoCompleteEntity;
import java.util.List;
import com.m3man.data.network.Api;
import com.m3man.rxjava.CallBackWrapper;
import com.m3man.rxjava.RxSchedulersHelper;
import com.m3man.ui.MvpBasePresenter;
import com.m3man.ui.mman9video.search.SearchPresenter;
import com.m3man.utils.SDCardUtils;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import cn.qqtheme.framework.util.FileUtils;
import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import io.reactivex.functions.Predicate;
import me.jessyan.retrofiturlmanager.RetrofitUrlManager;

/**
 * @author flymegoc
 * @date 2018/2/6
 */

public class SettingPresenter extends MvpBasePresenter<SettingView> implements ISetting {

    private static final String TAG = SearchPresenter.class.getSimpleName();
    private DataManager dataManager;

    @Inject
    public SettingPresenter(LifecycleProvider<Lifecycle.Event> provider, DataManager dataManager) {
        super(provider);
        this.dataManager = dataManager;
    }

    // M3：自动补全（地址）建议的读写，委托给 DataManager / DbHelper
    @Override
    public List<String> getAutoCompleteNames(int type) {
        return dataManager.getAutoCompleteNames(type);
    }

    @Override
    public void saveAutoComplete(String name, int type) {
        dataManager.saveAutoComplete(name, type);
    }

    @Override
    public void test9MmanVideo(String baseUrl, final QMUICommonListItemView qmuiCommonListItemView, final String key) {
        // 全局 BaseUrl 的优先级低于 Domain-Name header 中单独配置的,其他未配置的接口将受全局 BaseUrl 的影响
        // M62：记住旧地址，测试失败时回滚域名映射，避免主站请求继续打向坏地址直到进程重启
        final String oldAddress = dataManager.getMman9VideoAddress();
        RetrofitUrlManager.getInstance().putDomain(Api.PORN9_VIDEO_DOMAIN_NAME, baseUrl);
        dataManager.testMman9VideoAddress()
                .compose(RxSchedulersHelper.<Boolean>ioMainThread())
                .compose(provider.<Boolean>bindToLifecycle())
                .subscribe(new CallBackWrapper<Boolean>() {

                    @Override
                    public void onBegin(Disposable d) {
                        ifViewAttached(new ViewAction<SettingView>() {
                            @Override
                            public void run(@NonNull SettingView view) {
                                view.showTestingAddressDialog(true);
                            }
                        });
                    }

                    @Override
                    public void onSuccess(final Boolean s) {
                        ifViewAttached(new ViewAction<SettingView>() {
                            @Override
                            public void run(@NonNull SettingView view) {
                                if (s) {
                                    view.testNewAddressSuccess("测试成功", qmuiCommonListItemView, key);
                                } else {
                                    view.testNewAddressSuccess("测试失败，可以访问，但无法获取正确的数据", qmuiCommonListItemView, key);
                                }

                            }
                        });
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        // M62：测试失败回滚域名映射
                        if (!TextUtils.isEmpty(oldAddress)) {
                            RetrofitUrlManager.getInstance().putDomain(Api.PORN9_VIDEO_DOMAIN_NAME, oldAddress);
                        }
                        ifViewAttached(new ViewAction<SettingView>() {
                            @Override
                            public void run(@NonNull SettingView view) {
                                view.testNewAddressFailure(msg, qmuiCommonListItemView, key);
                            }
                        });
                    }
                });
    }

    @Override
    public void testPorny(String baseUrl, final QMUICommonListItemView qmuiCommonListItemView, final String key) {
        // M62：记住旧地址，测试失败时回滚域名映射（testPornyAddress 内部会 putDomain 新地址）
        final String oldPornyAddress = dataManager.getPornyAddress();
        dataManager.testPornyAddress(baseUrl)
                .compose(RxSchedulersHelper.<Boolean>ioMainThread())
                .compose(provider.<Boolean>bindToLifecycle())
                .subscribe(new CallBackWrapper<Boolean>() {

                    @Override
                    public void onBegin(Disposable d) {
                        ifViewAttached(new ViewAction<SettingView>() {
                            @Override
                            public void run(@NonNull SettingView view) {
                                view.showTestingAddressDialog(true);
                            }
                        });
                    }

                    @Override
                    public void onSuccess(final Boolean s) {
                        // M73：测试返回 false（能访问但数据不对）同样视为失败，回滚域名映射——
                        // 此前只有异常分支回滚，"测试失败但可访问"时毒化的映射会残留整个会话
                        if (!s && !TextUtils.isEmpty(oldPornyAddress)) {
                            RetrofitUrlManager.getInstance().putDomain(Api.PORNY_DOMAIN_NAME, oldPornyAddress);
                        }
                        ifViewAttached(new ViewAction<SettingView>() {
                            @Override
                            public void run(@NonNull SettingView view) {
                                if (s) {
                                    view.testNewAddressSuccess("测试成功", qmuiCommonListItemView, key);
                                } else {
                                    view.testNewAddressSuccess("测试失败，可以访问，但无法获取正确的数据", qmuiCommonListItemView, key);
                                }
                            }
                        });
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        // M62：测试失败回滚域名映射
                        if (!TextUtils.isEmpty(oldPornyAddress)) {
                            RetrofitUrlManager.getInstance().putDomain(Api.PORNY_DOMAIN_NAME, oldPornyAddress);
                        }
                        ifViewAttached(new ViewAction<SettingView>() {
                            @Override
                            public void run(@NonNull SettingView view) {
                                view.testNewAddressFailure(msg, qmuiCommonListItemView, key);
                            }
                        });
                    }
                });
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
    public boolean isHaveUnFinishDownloadVideo() {
        return dataManager.loadDownloadingData().size() != 0;
    }

    @Override
    public boolean isHaveFinishDownloadVideoFile() {
        if (!TextUtils.isEmpty(dataManager.getCustomDownloadVideoDirPath())) {
            File file = new File(dataManager.getCustomDownloadVideoDirPath());
            return file.listFiles() != null && file.listFiles().length != 0;
        }
        File file = new File(SDCardUtils.DOWNLOAD_VIDEO_PATH);
        //检查是否有MP4文件
        File[] children = file.listFiles();
        if (children == null) {
            return false;
        }
        for (File file1 : children) {
            if (file1.getName().endsWith(".mp4")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void moveOldDownloadVideoToNewDir(final String newDirPath, final QMUICommonListItemView qmuiCommonListItemView) {
        Observable.fromCallable(new Callable<File[]>() {
            @Override
            public File[] call() throws Exception {
                File file = new File(dataManager.getCustomDownloadVideoDirPath());
                return file.listFiles();
            }
        }).flatMap(new Function<File[], ObservableSource<File>>() {
            @Override
            public ObservableSource<File> apply(File[] files) throws Exception {
                return Observable.fromArray(files);
            }
        }).filter(new Predicate<File>() {
            @Override
            public boolean test(File file) throws Exception {
                return file.getName().endsWith(".mp4");
            }
        }).map(new Function<File, String>() {
            @Override
            public String apply(File file) throws Exception {
                FileUtils.move(file, new File(newDirPath, file.getName()));
                return file.getAbsolutePath();
            }
        }).delay(1, TimeUnit.SECONDS)
                .compose(RxSchedulersHelper.<String>ioMainThread())
                .compose(provider.<String>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<String>() {

                    @Override
                    public void onBegin(Disposable d) {
                        ifViewAttached(new ViewAction<SettingView>() {
                            @Override
                            public void run(@NonNull SettingView view) {
                                view.beginMoveOldDirDownloadVideoToNewDir();
                            }
                        });
                    }

                    @Override
                    public void onSuccess(final String s) {
                        Logger.t(TAG).d("正在移动到：" + s);
                    }

                    @Override
                    public void onError(String msg, int code) {
                        ifViewAttached(new ViewAction<SettingView>() {
                            @Override
                            public void run(@NonNull SettingView view) {
                                view.setNewDownloadVideoDirError("移动文件失败，无法设置新目录");
                            }
                        });
                    }

                    @Override
                    public void onComplete() {
                        super.onComplete();
                        ifViewAttached(new ViewAction<SettingView>() {
                            @Override
                            public void run(@NonNull SettingView view) {
                                dataManager.setCustomDownloadVideoDirPath(newDirPath);
                                qmuiCommonListItemView.setDetailText(newDirPath);
                                view.setNewDownloadVideoDirSuccess("移动文件完成,设置新目录成功");
                            }
                        });
                    }
                });
    }

    @Override
    public boolean isUserLogin() {
        return dataManager.isUserLogin();
    }

    @Override
    public void existLogin() {
        dataManager.existLogin();
    }

    @Override
    public int getPlaybackEngine() {
        return dataManager.getPlaybackEngine();
    }

    @Override
    public void setPlaybackEngine(int playbackEngine) {
        dataManager.setPlaybackEngine(playbackEngine);
    }

    @Override
    public void setMman9VideoAddress(String mman9VideoAddress) {
        dataManager.setMman9VideoAddress(mman9VideoAddress);
    }

    @Override
    public void setCustomDownloadVideoDirPath(String newDirPath) {
        dataManager.setCustomDownloadVideoDirPath(newDirPath);
    }

    @Override
    public String getCustomDownloadVideoDirPath() {
        return dataManager.getCustomDownloadVideoDirPath();
    }

    @Override
    public boolean isForbiddenAutoReleaseMemory() {
        return dataManager.isForbiddenAutoReleaseMemory();
    }

    @Override
    public void setForbiddenAutoReleaseMemory(boolean forbiddenAutoReleaseMemory) {
        dataManager.setForbiddenAutoReleaseMemory(forbiddenAutoReleaseMemory);
    }

    @Override
    public boolean isDownloadVideoNeedWifi() {
        return dataManager.isDownloadVideoNeedWifi();
    }

    @Override
    public void setDownloadVideoNeedWifi(boolean downloadVideoNeedWifi) {
        dataManager.setDownloadVideoNeedWifi(downloadVideoNeedWifi);
    }

    @Override
    public String getVideo9MmanAddress() {
        return dataManager.getMman9VideoAddress();
    }

    @Override
    public boolean isPornyEnabled() {
        return dataManager.isPornyEnabled();
    }

    @Override
    public void setPornyEnabled(boolean enabled) {
        dataManager.setPornyEnabled(enabled);
    }

    @Override
    public boolean isLocalFavoriteMode() {
        return dataManager.isLocalFavoriteMode();
    }

    @Override
    public void setLocalFavoriteMode(boolean localFavoriteMode) {
        dataManager.setLocalFavoriteMode(localFavoriteMode);
    }

    @Override
    public String getPornyAddress() {
        return dataManager.getPornyAddress();
    }

    @Override
    public void setPornyAddress(String address) {
        dataManager.setPornyAddress(address);
    }

}
