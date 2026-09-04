package com.m3man.ui.download;


import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.core.content.FileProvider;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.aitsuki.swipe.SwipeItemLayout;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.liulishuo.filedownloader.BaseDownloadTask;
import com.liulishuo.filedownloader.model.FileDownloadStatus;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.R;
import com.m3man.adapter.DownloadVideoAdapter;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.service.DownloadVideoService;
import com.m3man.ui.MvpFragment;
import com.m3man.utils.AdapterDiffUtil;
import com.m3man.utils.DownloadManager;
import com.m3man.utils.SDCardUtils;
import com.m3man.service.HlsDownloadService;
import com.m3man.ui.mman9video.play.PlayVideoPresenter;

import android.content.BroadcastReceiver;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.text.TextUtils;

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
public class FinishedFragment extends MvpFragment<DownloadView, DownloadPresenter> implements DownloadManager.DownloadStatusUpdater, DownloadView {

    @BindView(R.id.recyclerView_download_finish)
    RecyclerView recyclerView;
    Unbinder unbinder;

    private DownloadVideoAdapter mDownloadAdapter;
    private boolean isFocusRefresh = false;
    // M97：缓存应用级 Context，onDestroy 反注销 LocalBroadcastManager 时 getContext() 可能为 null
    private Context appContext;

    private BroadcastReceiver hlsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            if (HlsDownloadService.ACTION_HLS_DONE.equals(intent.getAction())) {
                presenter.loadFinishedData();
            }
        }
    };

    @Inject
    protected DownloadPresenter downloadPresenter;

    @Inject
    public FinishedFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // M97：提前缓存 ApplicationContext，供 onDestroy 判空反注销使用
        appContext = getActivity() != null ? getActivity().getApplicationContext() : null;
        DownloadManager.getImpl().addUpdater(this);
        // 注册 HLS 下载完成广播，让「下载完成」列表实时刷新
        IntentFilter hlsFilter = new IntentFilter();
        hlsFilter.addAction(HlsDownloadService.ACTION_HLS_DONE);
        LocalBroadcastManager.getInstance(getContext()).registerReceiver(hlsReceiver, hlsFilter);
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

        List<V9MmanItem> mV9MmanItemList = new ArrayList<>();
        mDownloadAdapter = new DownloadVideoAdapter(R.layout.item_right_menu_delete_download, mV9MmanItemList);

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setHasFixedSize(true);
        recyclerView.setAdapter(mDownloadAdapter);
        mDownloadAdapter.setEmptyView(R.layout.empty_view, recyclerView);
        mDownloadAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                V9MmanItem v9MmanItem = (V9MmanItem) adapter.getItem(position);
                if (v9MmanItem == null) {
                    return;
                }
                openMp4File(v9MmanItem);
            }
        });
        mDownloadAdapter.setOnItemChildClickListener(new BaseQuickAdapter.OnItemChildClickListener() {
            @Override
            public void onItemChildClick(BaseQuickAdapter adapter, View view, int position) {
                V9MmanItem v9MmanItem = (V9MmanItem) adapter.getItem(position);
                if (view.getId() == R.id.right_menu_delete && v9MmanItem != null) {
                    SwipeItemLayout swipeItemLayout = (SwipeItemLayout) view.getParent();
                    swipeItemLayout.close();
                    // M61：兼容 ensureDownloadDir 回退目录，文件可能不在原路径
                    // V13：优先 MediaStore 归档路径（Scoped Storage 下载完成成品）
                    String localPath = SDCardUtils.resolvePlayablePath(getContext(),
                            v9MmanItem.getLocalFilePath(),
                            v9MmanItem.getDownLoadPath(presenter.getCustomDownloadVideoDirPath()));
                    File file = localPath != null ? new File(localPath) : null;
                    if (file != null && file.exists()) {
                        showDeleteFileDialog(v9MmanItem);
                    } else {
                        presenter.deleteDownloadedTask(v9MmanItem, false);
                        presenter.loadFinishedData();
                    }
                }
            }
        });
        presenter.loadFinishedData();
    }

    private void showDeleteFileDialog(final V9MmanItem v9MmanItem) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.finished_tip_title));
        builder.setMessage(getString(R.string.finished_delete_local_file_msg));
        builder.setNegativeButton(getString(R.string.finished_no), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                presenter.deleteDownloadedTask(v9MmanItem, false);
                presenter.loadFinishedData();
            }
        });
        builder.setPositiveButton(getString(R.string.finished_yes), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                presenter.deleteDownloadedTask(v9MmanItem, true);
                presenter.loadFinishedData();
            }
        });
        builder.show();
    }

    /**
     * 调用系统播放器播放本地视频
     *
     * @param v9MmanItem item
     */
    private void openMp4File(V9MmanItem v9MmanItem) {
        // M61：下载目录不可写时文件被写进应用专属回退目录，按原路径找会误报“文件不存在”；
        // 这里按「原路径 → 回退目录」解析真实文件再播放。
        // V13：优先 MediaStore 归档路径（Scoped Storage 下载完成成品直读公共路径）。
        String path = SDCardUtils.resolvePlayablePath(getContext(),
                v9MmanItem.getLocalFilePath(),
                v9MmanItem.getDownLoadPath(presenter.getCustomDownloadVideoDirPath()));
        File file = path != null ? new File(path) : null;
        if (file != null && file.exists()) {
            // 统一走 App 内播放引擎，直接传入本地文件，不再请求远程视频地址。
            goToPlayLocalVideo(v9MmanItem, presenter.getPlaybackEngine(), file.getAbsolutePath());
        } else {
            showReDownloadFileDialog(v9MmanItem);
        }
    }


    private void showReDownloadFileDialog(final V9MmanItem v9MmanItem) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.finished_tip_title));
        builder.setMessage(getString(R.string.finished_file_missing_redownload_msg));
        builder.setNegativeButton(getString(R.string.common_cancel), null);
        builder.setPositiveButton(getString(R.string.sure), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (PlayVideoPresenter.isPornySource(v9MmanItem)) {
                    // HLS（分分钟）重新下载走专用通道
                    v9MmanItem.setDownloadId(0);
                    v9MmanItem.setSoFarBytes(0);
                    presenter.updateV9MmanItem(v9MmanItem);
                    startHlsReDownload(v9MmanItem);
                    return;
                }
                v9MmanItem.setDownloadId(0);
                v9MmanItem.setSoFarBytes(0);
                v9MmanItem.setProgress(0);
                v9MmanItem.setStatus(FileDownloadStatus.pending);
                presenter.updateV9MmanItem(v9MmanItem);
                // 直接用已有地址入队，确认后立即显示为下载中，不再经过一次延迟解析。
                presenter.downloadVideoImmediately(v9MmanItem);
                isFocusRefresh = true;
                Intent intent = new Intent(getContext(), DownloadVideoService.class);
                context.startService(intent);
            }
        });
        builder.show();
    }

    private void startHlsReDownload(V9MmanItem item) {
        if (item.getVideoResult() == null || TextUtils.isEmpty(item.getVideoResult().getVideoUrl())) {
            showMessage(getString(R.string.finished_no_video_url), TastyToast.INFO);
            return;
        }
        String customDir = presenter.getCustomDownloadVideoDirPath();
        String savePath = item.getDownLoadPath(customDir);
        Intent serviceIntent = new Intent(getContext(), HlsDownloadService.class);
        serviceIntent.setAction(HlsDownloadService.ACTION_START);
        serviceIntent.putExtra(HlsDownloadService.EXTRA_VIDEO_URL, item.getVideoResult().getVideoUrl());
        serviceIntent.putExtra(HlsDownloadService.EXTRA_TITLE, item.getTitle());
        serviceIntent.putExtra(HlsDownloadService.EXTRA_FILE_NAME, item.getTitle());
        serviceIntent.putExtra(HlsDownloadService.EXTRA_VIEW_KEY, item.getViewKey());
        serviceIntent.putExtra(HlsDownloadService.EXTRA_SAVE_PATH, savePath);
        getContext().startService(serviceIntent);
        showMessage(getString(R.string.finished_added_to_background), TastyToast.SUCCESS);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment

        super.onCreateView(inflater, container, savedInstanceState);
        return inflater.inflate(R.layout.fragment_finish, container, false);
    }

    @Override
    public String getTitle() {
        // M113：setupWithViewPager 在 Fragment attach 前调 getPageTitle() → getString() → 崩溃
        if (!isAdded()) return "";
        return getString(R.string.finished_title);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unbinder.unbind();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        DownloadManager.getImpl().removeUpdater(this);
        // M97：用缓存的 appContext 判空反注销，修复 onDestroy 时 getContext()==null 的 NPE
        if (appContext != null) {
            LocalBroadcastManager.getInstance(appContext).unregisterReceiver(hlsReceiver);
            appContext = null;
        }
    }

    @Override
    public void complete(BaseDownloadTask task) {
        presenter.loadFinishedData();
    }

    @Override
    public void update(BaseDownloadTask task) {
        if (isFocusRefresh) {
            isFocusRefresh = false;
            presenter.loadFinishedData();
        }
    }

    @Override
    public void setDownloadingData(List<V9MmanItem> v9MmanItems) {

    }

    @Override
    public void setFinishedData(List<V9MmanItem> v9MmanItems) {
        AdapterDiffUtil.apply(mDownloadAdapter, v9MmanItems, AdapterDiffUtil.v9MmanItem());
    }


    @Override
    public void showError(String message) {
        showMessage(message, TastyToast.ERROR);
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
}
