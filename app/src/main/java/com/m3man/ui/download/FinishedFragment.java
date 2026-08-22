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
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.aitsuki.swipe.SwipeItemLayout;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.liulishuo.filedownloader.BaseDownloadTask;
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
                    File file = SDCardUtils.resolveExistingDownloadFile(getContext(),
                            v9MmanItem.getDownLoadPath(presenter.getCustomDownloadVideoDirPath()));
                    if (file.exists()) {
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
        builder.setTitle("提示");
        builder.setMessage("是否连同删除本地文件？");
        builder.setNegativeButton("否", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                presenter.deleteDownloadedTask(v9MmanItem, false);
                presenter.loadFinishedData();
            }
        });
        builder.setPositiveButton("是", new DialogInterface.OnClickListener() {
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
        File file = SDCardUtils.resolveExistingDownloadFile(getContext(),
                v9MmanItem.getDownLoadPath(presenter.getCustomDownloadVideoDirPath()));
        if (file.exists()) {
            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(context, "com.m3man.fileprovider", file);
            } else {
                uri = Uri.fromFile(file);
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "video/mp4");
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            PackageManager pm = context.getPackageManager();
            ComponentName cn = intent.resolveActivity(pm);
            if (cn == null) {
                showMessage("你手机上未安装任何可以播放此视频的播放器！", TastyToast.INFO);
                return;
            }
            startActivity(intent);
        } else {
            showReDownloadFileDialog(v9MmanItem);
        }
    }


    private void showReDownloadFileDialog(final V9MmanItem v9MmanItem) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("提示");
        builder.setMessage("文件不存在，可能已经被删除，要重新下载？");
        builder.setNegativeButton("取消", null);
        builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
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
                presenter.updateV9MmanItem(v9MmanItem);
                presenter.downloadVideo(v9MmanItem, true);
                isFocusRefresh = true;
                Intent intent = new Intent(getContext(), DownloadVideoService.class);
                context.startService(intent);
            }
        });
        builder.show();
    }

    private void startHlsReDownload(V9MmanItem item) {
        if (item.getVideoResult() == null || TextUtils.isEmpty(item.getVideoResult().getVideoUrl())) {
            showMessage("未解析到视频地址，无法重新下载", TastyToast.INFO);
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
        showMessage("已加入后台下载", TastyToast.SUCCESS);
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
        return "下载完成";
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
        LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(hlsReceiver);
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
        mDownloadAdapter.setNewData(v9MmanItems);
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
