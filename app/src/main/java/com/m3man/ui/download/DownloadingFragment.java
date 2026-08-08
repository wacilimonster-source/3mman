package com.m3man.ui.download;


import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.aitsuki.swipe.SwipeItemLayout;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.liulishuo.filedownloader.BaseDownloadTask;
import com.liulishuo.filedownloader.FileDownloadConnectListener;
import com.liulishuo.filedownloader.FileDownloader;
import com.liulishuo.filedownloader.model.FileDownloadStatus;
import com.orhanobut.logger.Logger;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.R;
import com.m3man.adapter.DownloadVideoAdapter;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.service.DownloadVideoService;
import com.m3man.ui.MvpFragment;
import com.m3man.utils.DownloadManager;
import com.m3man.utils.SDCardUtils;
import com.m3man.service.HlsDownloadService;
import com.m3man.ui.mman9video.play.PlayVideoPresenter;

import android.content.BroadcastReceiver;
import android.support.v4.content.LocalBroadcastManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;

/**
 * A simple {@link Fragment} subclass.
 *
 * @author flymegoc
 */
public class DownloadingFragment extends MvpFragment<DownloadView, DownloadPresenter> implements DownloadManager.DownloadStatusUpdater, DownloadView {

    private static final String TAG = DownloadingFragment.class.getSimpleName();
    @BindView(R.id.recyclerView_download)
    RecyclerView recyclerView;
    Unbinder unbinder;
    private DownloadVideoAdapter mDownloadAdapter;
    private ArrayList<V9MmanItem> mV9MmanItemList;

    private BroadcastReceiver hlsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            if (HlsDownloadService.ACTION_HLS_PROGRESS.equals(intent.getAction())) {
                String vk = intent.getStringExtra(HlsDownloadService.EXTRA_VIEW_KEY);
                int p = intent.getIntExtra(HlsDownloadService.EXTRA_PROGRESS, 0);
                updateHlsItemProgress(vk, p);
            } else if (HlsDownloadService.ACTION_HLS_DONE.equals(intent.getAction())) {
                // HLS 下载完成会从「正在下载」列表移出
                presenter.loadDownloadingData();
            }
        }
    };

    @Inject
    protected DownloadPresenter downloadPresenter;

    @Inject
    public DownloadingFragment() {
        // Required empty public constructor
    }

    private FileDownloadConnectListener fileDownloadConnectListener = new FileDownloadConnectListener() {
        @Override
        public void connected() {
            Logger.t(TAG).d("连接上下载服务");
            List<V9MmanItem> v9MmanItems = presenter.loadDownloadingDatas();
            for (V9MmanItem v9MmanItem : v9MmanItems) {
                if (v9MmanItem == null || v9MmanItem.getVideoResult() == null) {
                    continue;
                }
                String path = v9MmanItem.getDownLoadPath(presenter.getCustomDownloadVideoDirPath());
                int status = FileDownloader.getImpl().getStatus(v9MmanItem.getVideoResult().getVideoUrl(), path);
                Logger.t(TAG).d("fix status:::" + status);
                if (status != v9MmanItem.getStatus()) {
                    // M41：若该条已按“完成”处理（文件实际已完整，无论因自动纠错还是正常完成），
                    // 不要被 FileDownloader 残留的 error/旧状态回写覆盖，否则会重新显示“下载错误”。
                    if (v9MmanItem.getStatus() == FileDownloadStatus.completed
                            || SDCardUtils.isDownloadFileComplete(new File(path), v9MmanItem.getTotalFarBytes())) {
                        continue;
                    }
                    v9MmanItem.setStatus(status);
                    presenter.updateV9MmanItem(v9MmanItem);
                }
            }
            presenter.loadDownloadingData();
        }

        @Override
        public void disconnected() {
            Logger.t(TAG).d("下载服务连接断开了");
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DownloadManager.getImpl().addUpdater(this);
        FileDownloader.getImpl().addServiceConnectListener(fileDownloadConnectListener);
        IntentFilter filter = new IntentFilter();
        filter.addAction(HlsDownloadService.ACTION_HLS_PROGRESS);
        filter.addAction(HlsDownloadService.ACTION_HLS_DONE);
        LocalBroadcastManager.getInstance(getContext()).registerReceiver(hlsReceiver, filter);
    }

    @NonNull
    @Override
    public DownloadPresenter createPresenter() {
        return downloadPresenter;

    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        unbinder = ButterKnife.bind(this, view);
        mV9MmanItemList = new ArrayList<>();
        mDownloadAdapter = new DownloadVideoAdapter(R.layout.item_right_menu_delete_download, mV9MmanItemList);

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.getItemAnimator().setChangeDuration(0);
        recyclerView.setAdapter(mDownloadAdapter);
        mDownloadAdapter.setEmptyView(R.layout.empty_view, recyclerView);

        mDownloadAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                goToPlayVideo((V9MmanItem) adapter.getItem(position), presenter.getPlaybackEngine(), 0, 0);
            }
        });

        mDownloadAdapter.setOnItemChildClickListener(new BaseQuickAdapter.OnItemChildClickListener() {
            @Override
            public void onItemChildClick(BaseQuickAdapter adapter, final View view, int position) {
                final V9MmanItem v9MmanItem = (V9MmanItem) adapter.getItem(position);
                if (v9MmanItem == null) {
                    return;
                }
                Logger.t(TAG).d("当前状态：" + v9MmanItem.getStatus());
                // HLS（分分钟）下载走独立通道，删除/取消需分流处理
                if (PlayVideoPresenter.isPornySource(v9MmanItem)) {
                    if (view.getId() == R.id.right_menu_delete || view.getId() == R.id.iv_download_control) {
                        SwipeItemLayout swipeItemLayout = (SwipeItemLayout) view.getParent();
                        swipeItemLayout.close();
                        cancelHlsDownload(v9MmanItem);
                        presenter.loadDownloadingData();
                    }
                    return;
                }
                if (view.getId() == R.id.right_menu_delete) {
                    SwipeItemLayout swipeItemLayout = (SwipeItemLayout) view.getParent();
                    swipeItemLayout.close();
                    presenter.deleteDownloadingTask(v9MmanItem);
                    presenter.loadDownloadingData();
                } else if (view.getId() == R.id.iv_download_control) {
                    if (FileDownloader.getImpl().isServiceConnected()) {
                        if (v9MmanItem.getStatus() == FileDownloadStatus.progress) {
                            FileDownloader.getImpl().pause(v9MmanItem.getDownloadId());
                            ((ImageView) view).setImageResource(R.drawable.start_download);
                        } else {
                            showDownloadCheck(v9MmanItem, view);
                        }
                    }
                }
            }
        });
    }

    /**
     * 让使用者自己选择是重新下载还是继续下载
     *
     * @param v9MmanItem 需要下载的视频信息
     * @param view       需要更新的view
     */
    private void showDownloadCheck(final V9MmanItem v9MmanItem, final View view) {
        showDialog("请选择下载方式", new String[]{"继续下载", "重新下载"}, new DialogCheck() {
            @Override
            public void onCheck(int index) {
                startDownload(v9MmanItem, view, index != 0);
            }
        });
    }

    @Override
    protected void onLazyLoadOnce() {
        super.onLazyLoadOnce();
        if (!FileDownloader.getImpl().isServiceConnected()) {
            FileDownloader.getImpl().bindService();
            Logger.t(TAG).d("启动下载服务");
        } else {
            presenter.loadDownloadingData();
            Logger.t(TAG).d("下载服务已经连接");
        }
    }

    private void startDownload(V9MmanItem v9MmanItem, View view, boolean isForceReDownload) {
        presenter.downloadVideo(v9MmanItem, isForceReDownload);
        ((ImageView) view).setImageResource(R.drawable.pause_download);
        Intent intent = new Intent(getContext(), DownloadVideoService.class);
        context.startService(intent);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment

        super.onCreateView(inflater, container, savedInstanceState);
        return inflater.inflate(R.layout.fragment_download, container, false);
    }

    @Override
    public void complete(BaseDownloadTask task) {
        if (mV9MmanItemList == null || mV9MmanItemList.size() == 0) {
            return;
        }
        Logger.t(TAG).d("已经下载完成了");
        V9MmanItem v9MmanItem = presenter.findUnLimit91MmanItemByDownloadId(task.getId());
        if (v9MmanItem != null) {
            int position = mV9MmanItemList.indexOf(v9MmanItem);
            if (position >= 0 && position < mV9MmanItemList.size()) {
                mV9MmanItemList.remove(position);
                mDownloadAdapter.notifyItemRemoved(position);
            } else {
                presenter.loadDownloadingData();
            }
        } else {
            presenter.loadDownloadingData();
        }
    }

    @Override
    public void update(BaseDownloadTask task) {
        Logger.t(TAG).d("updateV9MmanItem(BaseDownloadTask task)");
        if (mV9MmanItemList == null || mV9MmanItemList.size() == 0) {
            return;
        }
        V9MmanItem v9MmanItem = presenter.findUnLimit91MmanItemByDownloadId(task.getId());
        if (v9MmanItem != null) {
            int position = mV9MmanItemList.indexOf(v9MmanItem);
            Logger.t(TAG).d("position" + position);
            if (position >= 0 && position < mV9MmanItemList.size()) {
                mV9MmanItemList.set(position, v9MmanItem);
                mDownloadAdapter.notifyItemChanged(position);
            } else {
                mV9MmanItemList.add(v9MmanItem);
                mDownloadAdapter.notifyItemInserted(mV9MmanItemList.size());
            }
        } else {
            presenter.loadDownloadingData();
        }
    }

    @Override
    public String getTitle() {
        return "正在下载";
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        FileDownloader.getImpl().removeServiceConnectListener(fileDownloadConnectListener);
        DownloadManager.getImpl().removeUpdater(this);
        LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(hlsReceiver);
    }

    private void cancelHlsDownload(V9MmanItem item) {
        Intent cancel = new Intent(getContext(), HlsDownloadService.class).setAction(HlsDownloadService.ACTION_CANCEL);
        getContext().startService(cancel);
        if (item != null) {
            item.setDownloadId(0);
            presenter.updateV9MmanItem(item);
        }
    }

    private void updateHlsItemProgress(String viewKey, int progress) {
        if (mV9MmanItemList == null || viewKey == null) {
            return;
        }
        for (int i = 0; i < mV9MmanItemList.size(); i++) {
            V9MmanItem it = mV9MmanItemList.get(i);
            if (it != null && viewKey.equals(it.getViewKey())) {
                it.setProgress(progress);
                mDownloadAdapter.notifyItemChanged(i);
                break;
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unbinder.unbind();
    }

    @Override
    public void setDownloadingData(List<V9MmanItem> v9MmanItems) {
        mV9MmanItemList.clear();
        mV9MmanItemList.addAll(v9MmanItems);
        mDownloadAdapter.notifyDataSetChanged();
        if (v9MmanItems.size() == 0) {
            try {
                Intent intent = new Intent(getContext(), DownloadVideoService.class);
                context.stopService(intent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void setFinishedData(List<V9MmanItem> v9MmanItems) {

    }

    @Override
    public void showLoading(boolean pullToRefresh) {

    }

    @Override
    public void showContent() {

    }

    @Override
    public void showMessage(String msg, int type) {
        super.showMessage(msg, type);
    }

    @Override
    public void showError(String message) {
        showMessage(message, TastyToast.ERROR);
    }
}
