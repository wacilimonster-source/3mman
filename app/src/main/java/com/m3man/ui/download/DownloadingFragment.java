package com.m3man.ui.download;


import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
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
import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;

/**
 * 正在下载任务列表 Fragment：展示下载进度并响应下载状态广播。
 *
 * @author flymegoc
 */
public class DownloadingFragment extends MvpFragment<DownloadView, DownloadPresenter> implements DownloadManager.DownloadStatusUpdater, DownloadView {

    private static final String TAG = DownloadingFragment.class.getSimpleName();
    // M97：缓存应用级 Context，onDestroy 反注销 LocalBroadcastManager 时 getContext() 可能为 null
    private Context appContext;
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
                // M102：running 缺省视为未知（不动状态），兼容旧发送方
                Boolean running = intent.hasExtra(HlsDownloadService.EXTRA_RUNNING)
                        ? intent.getBooleanExtra(HlsDownloadService.EXTRA_RUNNING, false)
                        : null;
                // L-fix：携带已落盘字节与实时速度，供列表显示大小/速率
                long soFar = intent.hasExtra(HlsDownloadService.EXTRA_SO_FAR_BYTES)
                        ? intent.getLongExtra(HlsDownloadService.EXTRA_SO_FAR_BYTES, -1L) : -1L;
                long speedBps = intent.hasExtra(HlsDownloadService.EXTRA_SPEED_BPS)
                        ? intent.getLongExtra(HlsDownloadService.EXTRA_SPEED_BPS, -1L) : -1L;
                updateHlsItemProgress(vk, p, running, soFar, speedBps);
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
                // M62：HLS(m3u8) 任务从未在 FileDownloader 登记（getStatus 恒返回 INVALID，
                // 文件存在时又误判 completed），其状态由 HlsDownloadService 全权管理，必须跳过，
                // 否则会覆写 HLS 记录制造/冻结幽灵行
                if (v9MmanItem.getVideoResult().getVideoUrl() != null
                        && DownloadManager.isHlsUrl(v9MmanItem.getVideoResult().getVideoUrl())) {
                    continue;
                }
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
        // M97：提前缓存 ApplicationContext，供 onDestroy 判空反注销使用
        appContext = getActivity() != null ? getActivity().getApplicationContext() : null;
        // L-fix：关闭 RecyclerView 默认 item 变更动画——高频进度刷新时整行反复淡出淡入，
// 视觉上即“缩略图反复缩放”。下载列表以数值刷新为主，不需要动画。
recyclerView.setItemAnimator(null);
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
                V9MmanItem item = (V9MmanItem) adapter.getItem(position);
                if (item == null) {
                    return;
                }
                // 下载未完成时点击整行不再进入播放，避免播放半成品文件或重新解析远程地址。
                if (item.getStatus() != FileDownloadStatus.completed) {
                    showMessage("视频尚未下载完成，请使用右侧控制按钮暂停、继续或重试", TastyToast.INFO);
                }
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
                // HLS（分分钟）下载走独立通道，删除/取消/重试需分流处理
                if (PlayVideoPresenter.isPornySource(v9MmanItem)) {
                    SwipeItemLayout swipeItemLayout = (SwipeItemLayout) view.getParent();
                    swipeItemLayout.close();
                    if (view.getId() == R.id.right_menu_delete) {
                    cancelHlsDownload(v9MmanItem);
                } else if (view.getId() == R.id.iv_download_control) {
                    if (v9MmanItem.getStatus() == FileDownloadStatus.error
                            || v9MmanItem.getStatus() == FileDownloadStatus.paused) {
                        // 失败/暂停记录点控制按钮 → 重新下载
                        startHlsReDownload(v9MmanItem);
                    } else {
                        // HLS 下载中点控制按钮 → 暂停并保留记录
                        pauseHlsDownload(v9MmanItem);
                    }
                }
                    presenter.loadDownloadingData();
                    return;
                }
                if (view.getId() == R.id.right_menu_delete) {
                    SwipeItemLayout swipeItemLayout = (SwipeItemLayout) view.getParent();
                    swipeItemLayout.close();
                    presenter.deleteDownloadingTask(v9MmanItem);
                    presenter.loadDownloadingData();
                } else if (view.getId() == R.id.iv_download_control) {
                    if (FileDownloader.getImpl().isServiceConnected()) {
                        if (v9MmanItem.getStatus() == FileDownloadStatus.progress
                                || v9MmanItem.getStatus() == FileDownloadStatus.started
                                || v9MmanItem.getStatus() == FileDownloadStatus.connected
                                || v9MmanItem.getStatus() == FileDownloadStatus.pending) {
                            FileDownloader.getImpl().pause(v9MmanItem.getDownloadId());
                            v9MmanItem.setStatus(FileDownloadStatus.paused);
                            presenter.updateV9MmanItem(v9MmanItem);
                            ((ImageView) view).setImageResource(R.drawable.start_download);
                        } else {
                            // 暂停、失败、警告状态统一支持继续/重试。
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
        // M97：用缓存的 appContext 判空反注销，修复 onDestroy 时 getContext()==null 的 NPE
        if (appContext != null) {
            LocalBroadcastManager.getInstance(appContext).unregisterReceiver(hlsReceiver);
            appContext = null;
        }
    }

    private void pauseHlsDownload(V9MmanItem item) {
        Intent pause = new Intent(getContext(), HlsDownloadService.class).setAction(HlsDownloadService.ACTION_PAUSE);
        if (item != null && !TextUtils.isEmpty(item.getViewKey())) {
            pause.putExtra(HlsDownloadService.EXTRA_VIEW_KEY, item.getViewKey());
        }
        getContext().startService(pause);
        if (item != null) {
            item.setStatus(FileDownloadStatus.paused);
            presenter.updateV9MmanItem(item);
        }
        showMessage("已暂停下载", TastyToast.INFO);
    }

    private void cancelHlsDownload(V9MmanItem item) {
        Intent cancel = new Intent(getContext(), HlsDownloadService.class).setAction(HlsDownloadService.ACTION_CANCEL);
        // M62：带上目标 viewKey，服务端校验匹配后才取消，防止删无关行误杀当前下载
        if (item != null && !TextUtils.isEmpty(item.getViewKey())) {
            cancel.putExtra(HlsDownloadService.EXTRA_VIEW_KEY, item.getViewKey());
        }
        getContext().startService(cancel);
        if (item != null) {
            item.setDownloadId(0);
            presenter.updateV9MmanItem(item);
        }
    }

    /** M41：失败的分分钟(HLS)记录重新下载（与「下载完成」页重新下载走同一通道） */
    private void startHlsReDownload(V9MmanItem item) {
        if (item == null || item.getVideoResult() == null || TextUtils.isEmpty(item.getVideoResult().getVideoUrl())) {
            showMessage("未解析到视频地址，无法重新下载", TastyToast.INFO);
            return;
        }
        String savePath = item.getDownLoadPath(presenter.getCustomDownloadVideoDirPath());
        Intent serviceIntent = new Intent(getContext(), HlsDownloadService.class);
        serviceIntent.setAction(HlsDownloadService.ACTION_START);
        serviceIntent.putExtra(HlsDownloadService.EXTRA_VIDEO_URL, item.getVideoResult().getVideoUrl());
        serviceIntent.putExtra(HlsDownloadService.EXTRA_TITLE, item.getTitle());
        serviceIntent.putExtra(HlsDownloadService.EXTRA_FILE_NAME, item.getTitle());
        serviceIntent.putExtra(HlsDownloadService.EXTRA_VIEW_KEY, item.getViewKey());
        serviceIntent.putExtra(HlsDownloadService.EXTRA_SAVE_PATH, savePath);
        getContext().startService(serviceIntent);
        showMessage("已加入后台下载", TastyToast.SUCCESS);
    }

    private void updateHlsItemProgress(String viewKey, int progress) {
        updateHlsItemProgress(viewKey, progress, null, -1L, -1L);
    }

    /**
     * M102：running 非空时一并同步行状态（true=下载中 / false=已暂停）。
     * 修复：直链受限兜底转 HLS 后，DB 已是 progress、进度正常刷新，
     * 但暂停时写入内存行的 paused 状态从未被更新，列表一直显示“暂停中”。
     */
    private void updateHlsItemProgress(String viewKey, int progress, Boolean running,
                                       long soFarBytes, long speedBps) {
        if (mV9MmanItemList == null || viewKey == null) {
            return;
        }
        for (int i = 0; i < mV9MmanItemList.size(); i++) {
            V9MmanItem it = mV9MmanItemList.get(i);
            if (it != null && viewKey.equals(it.getViewKey())) {
                it.setProgress(progress);
                if (running != null && it.getStatus() != FileDownloadStatus.completed) {
                    it.setStatus(running ? FileDownloadStatus.progress : FileDownloadStatus.paused);
                }
                if (soFarBytes >= 0) {
                    it.setSoFarBytes(soFarBytes);
                }
                if (speedBps >= 0) {
                    it.setSpeed(speedBps / 1024L); // 列表按 KB/s 展示
                }
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
