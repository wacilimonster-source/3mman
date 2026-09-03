package com.m3man.ui.mman9video.index;

import androidx.lifecycle.Lifecycle;
import androidx.annotation.NonNull;

import com.hannesdorfmann.mosby3.mvp.MvpBasePresenter;
import com.trello.rxlifecycle2.LifecycleProvider;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.rxjava.CallBackWrapper;
import com.m3man.rxjava.RetryWhenProcess;
import com.m3man.rxjava.RxSchedulersHelper;

import java.util.List;

import javax.inject.Inject;

import io.reactivex.disposables.Disposable;

/**
 * @author flymegoc
 * @date 2017/11/15
 * @describe
 */

public class IndexPresenter extends MvpBasePresenter<IndexView> implements IIndex {
    private static final String TAG = IndexPresenter.class.getSimpleName();
    private LifecycleProvider<Lifecycle.Event> provider;
    private DataManager dataManager;

    @Inject
    public IndexPresenter(LifecycleProvider<Lifecycle.Event> provider, DataManager dataManager) {
        this.provider = provider;
        this.dataManager = dataManager;
    }

    /**
     * 加载首页视频数据
     *
     * @param pullToRefresh 是否刷新
     */
    @Override
    public void loadIndexData(final boolean pullToRefresh, boolean cleanCache) {

        dataManager.loadMman9VideoIndex(cleanCache)
                .retryWhen(new RetryWhenProcess(RetryWhenProcess.PROCESS_TIME))
                .compose(RxSchedulersHelper.<List<V9MmanItem>>ioMainThread())
                .compose(provider.<List<V9MmanItem>>bindUntilEvent(Lifecycle.Event.ON_DESTROY))
                .subscribe(new CallBackWrapper<List<V9MmanItem>>() {
                    @Override
                    public void onBegin(Disposable d) {
                        ifViewAttached(new ViewAction<IndexView>() {
                            @Override
                            public void run(@NonNull IndexView view) {
                                if (!pullToRefresh) {
                                    view.showLoading(pullToRefresh);
                                }
                            }
                        });
                    }

                    @Override
                    public void onSuccess(final List<V9MmanItem> v9MmanItems) {
                        ifViewAttached(new ViewAction<IndexView>() {
                            @Override
                            public void run(@NonNull IndexView view) {
                                view.setData(v9MmanItems);
                                view.showContent();
                            }
                        });
                    }

                    @Override
                    public void onError(final String msg, int code) {
                        ifViewAttached(new ViewAction<IndexView>() {
                            @Override
                            public void run(@NonNull IndexView view) {
                                view.showError(msg);
                            }
                        });
                    }
                });
    }

    @Override
    public int getPlayBackEngine() {
        return dataManager.getPlaybackEngine();
    }
}
