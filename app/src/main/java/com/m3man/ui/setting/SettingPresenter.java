package com.m3man.ui.setting;

import androidx.lifecycle.Lifecycle;
import android.os.Looper;
import androidx.annotation.NonNull;
import android.text.TextUtils;

import com.orhanobut.logger.Logger;
import com.qmuiteam.qmui.widget.grouplist.QMUICommonListItemView;
import com.trello.rxlifecycle2.LifecycleProvider;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.AutoCompleteEntity;
import java.util.ArrayList;
import java.util.List;
import com.m3man.data.network.Api;
import com.m3man.rxjava.CallBackWrapper;
import com.m3man.rxjava.RxSchedulersHelper;
import com.m3man.ui.MvpBasePresenter;
import com.m3man.utils.SDCardUtils;

import java.io.File;
import java.util.concurrent.Callable;

import javax.inject.Inject;

import cn.qqtheme.framework.util.FileUtils;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;
import me.jessyan.retrofiturlmanager.RetrofitUrlManager;

/**
 * @author flymegoc
 * @date 2018/2/6
 */

public class SettingPresenter extends MvpBasePresenter<SettingView> implements ISetting {

    // M100：TAG 原误用 SearchPresenter 类名，导致日志分组错乱，改回本类名
    private static final String TAG = SettingPresenter.class.getSimpleName();
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

    /** 供 SettingsActivity 构造 RecoSettingsDialog 使用。 */
    public DataManager getDataManager() {
        return dataManager;
    }

    /** M100：下载项检查结果回调（查询已移出主线程，结果回主线程交付） */
    public interface DownloadCheckCallback {
        void onResult(boolean has);
    }

    /**
     * M100：原实现主线程直查 DB（loadDownloadingData），改为 IO 线程查询后回调，
     * 调用方在 onResult 里再禁用/放行入口。
     */
    public void checkHaveUnFinishDownloadVideo(final DownloadCheckCallback callback) {
        Observable.fromCallable(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                return dataManager.loadDownloadingData().size() != 0;
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(provider.<Boolean>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new Consumer<Boolean>() {
                    @Override
                    public void accept(Boolean has) throws Exception {
                        if (callback != null) {
                            callback.onResult(has);
                        }
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) throws Exception {
                        Logger.t(TAG).e(TAG + ": 查询未完成下载失败 " + throwable.getMessage());
                        // M100：查询异常时保守按「有未完成下载」处理，拦截入口防误操作
                        if (callback != null) {
                            callback.onResult(true);
                        }
                    }
                });
    }

    /**
     * M100：原实现主线程扫描文件系统，改为 IO 线程查询后回调。
     */
    public void checkHaveFinishDownloadVideoFile(final DownloadCheckCallback callback) {
        Observable.fromCallable(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                if (!TextUtils.isEmpty(dataManager.getCustomDownloadVideoDirPath())) {
                    File file = new File(dataManager.getCustomDownloadVideoDirPath());
                    File[] files = file.listFiles();
                    return files != null && files.length != 0;
                }
                File file = new File(SDCardUtils.DOWNLOAD_VIDEO_PATH);
                //检查是否有MP4文件
                File[] children = file.listFiles();
                if (children == null) {
                    return false;
                }
                for (File childFile : children) {
                    if (childFile.getName().endsWith(".mp4")) {
                        return true;
                    }
                }
                return false;
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(provider.<Boolean>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new Consumer<Boolean>() {
                    @Override
                    public void accept(Boolean has) throws Exception {
                        if (callback != null) {
                            callback.onResult(has);
                        }
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) throws Exception {
                        Logger.t(TAG).e(TAG + ": 扫描已下载文件失败 " + throwable.getMessage());
                        // M100：扫描异常按「无可移动文件」处理，放行走直接设置目录的分支
                        if (callback != null) {
                            callback.onResult(false);
                        }
                    }
                });
    }

    /**
     * M100：保留接口同步实现仅作兼容（界面入口已改走 checkHaveUnFinishDownloadVideo），
     * 禁止在主线程调用。
     */
    @Override
    public boolean isHaveUnFinishDownloadVideo() {
        // M-07: 运行时强制检查，防止误在主线程调用
        if (Looper.getMainLooper() == Looper.myLooper()) {
            throw new IllegalStateException("isHaveUnFinishDownloadVideo() 禁止在主线程调用，请使用 checkHaveUnFinishDownloadVideo()");
        }
        return dataManager.loadDownloadingData().size() != 0;
    }

    /**
     * M100：保留接口同步实现仅作兼容（界面入口已改走 checkHaveFinishDownloadVideoFile），
     * 禁止在主线程调用。
     */
    @Override
    public boolean isHaveFinishDownloadVideoFile() {
        // M-07: 运行时强制检查，防止误在主线程调用
        if (Looper.getMainLooper() == Looper.myLooper()) {
            throw new IllegalStateException("isHaveFinishDownloadVideoFile() 禁止在主线程调用，请使用 checkHaveFinishDownloadVideoFile()");
        }
        if (!TextUtils.isEmpty(dataManager.getCustomDownloadVideoDirPath())) {
            File file = new File(dataManager.getCustomDownloadVideoDirPath());
            // 复查修正（M-02 残留）：原为两次 listFiles()，存在 TOCTOU 竞态，收敛为单次调用
            File[] files = file.listFiles();
            return files != null && files.length != 0;
        }
        File file = new File(SDCardUtils.DOWNLOAD_VIDEO_PATH);
        //检查是否有MP4文件
        File[] children = file.listFiles();
        if (children == null) {
            return false;
        }
        for (File childFile : children) {
            if (childFile.getName().endsWith(".mp4")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void moveOldDownloadVideoToNewDir(final String newDirPath, final QMUICommonListItemView qmuiCommonListItemView) {
        Observable.fromCallable(new Callable<List<String>>() {
            @Override
            public List<String> call() throws Exception {
                File oldDir = new File(dataManager.getCustomDownloadVideoDirPath());
                File[] files = oldDir.listFiles();
                List<String> movedSrcPaths = new ArrayList<>();
                if (files == null) {
                    return movedSrcPaths;
                }
                try {
                    for (File file : files) {
                        if (!file.getName().endsWith(".mp4")) {
                            continue;
                        }
                        FileUtils.move(file, new File(newDirPath, file.getName()));
                        movedSrcPaths.add(file.getAbsolutePath());
                    }
                } catch (Exception e) {
                    // M100：任一移动失败→把已成功移动的文件移回原位（回滚），保证不出现半迁移状态；
                    // 失败即整单失败，不写 SP、不放行新目录
                    for (int i = movedSrcPaths.size() - 1; i >= 0; i--) {
                        File src = new File(movedSrcPaths.get(i));
                        try {
                            FileUtils.move(new File(newDirPath, src.getName()), src);
                        } catch (Exception rollbackErr) {
                            Logger.t(TAG).e(TAG + ": 回滚文件失败 " + src.getName() + " " + rollbackErr.getMessage());
                        }
                    }
                    throw new IllegalStateException("移动文件失败：" + e.getMessage(), e);
                }
                return movedSrcPaths;
            }
        })
                .compose(RxSchedulersHelper.<List<String>>ioMainThread())
                .compose(provider.<List<String>>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<List<String>>() {

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
                    public void onSuccess(final List<String> movedPaths) {
                        Logger.t(TAG).d("已全部移动 " + movedPaths.size() + " 个文件到：" + newDirPath);
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
                                // M100：全部成功才写 SP 放行新目录
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
