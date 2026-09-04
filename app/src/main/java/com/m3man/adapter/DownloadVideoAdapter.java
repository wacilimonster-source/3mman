package com.m3man.adapter;

import android.net.Uri;
import androidx.annotation.Nullable;
import android.text.format.Formatter;
import android.widget.ImageView;

import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.liulishuo.filedownloader.FileDownloader;
import com.liulishuo.filedownloader.model.FileDownloadStatus;
import com.m3man.R;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.mman9video.play.PlayVideoPresenter;
import com.m3man.utils.GlideApp;
import com.m3man.utils.GlideLoader;
import com.m3man.utils.SDCardUtils;

import java.util.List;

/**
 * @author flymegoc
 * @date 2018/1/9
 */

public class DownloadVideoAdapter extends BaseQuickAdapter<V9MmanItem, BaseViewHolder> {

    /** payload 局部刷新标记：只更新进度条/百分比/大小/速率，不重绑封面与标题 */
    public static final Object PAYLOAD_PROGRESS = new Object();

    public DownloadVideoAdapter(int layoutResId, @Nullable List<V9MmanItem> data) {
        super(layoutResId, data);
    }

    @Override
    public void onBindViewHolder(BaseViewHolder holder, int position, List<Object> payloads) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads);
            return;
        }
        V9MmanItem item = getItem(position);
        if (item == null) {
            return;
        }
        // 进度类字段高频变化，局部刷新可避免封面 Glide 请求与标题重排
        holder.setProgress(R.id.progressBar_download, item.getProgress());
        holder.setText(R.id.tv_download_progress, String.valueOf(item.getProgress()) + "%");
        String sizeText;
        if (item.getTotalFarBytes() > 0) {
            sizeText = Formatter.formatFileSize(holder.itemView.getContext(), item.getSoFarBytes()).replace("MB", "") + "/ "
                    + Formatter.formatFileSize(holder.itemView.getContext(), item.getTotalFarBytes());
        } else if (item.getSoFarBytes() > 0) {
            sizeText = Formatter.formatFileSize(holder.itemView.getContext(), item.getSoFarBytes()).replace("MB", "") + "/ --";
        } else {
            sizeText = "--/--";
        }
        holder.setText(R.id.tv_download_filesize, sizeText);
        if (item.getStatus() == FileDownloadStatus.progress) {
            holder.setText(R.id.tv_download_speed, item.getSpeed() + " KB/s");
        }
    }

    @Override
    protected void convert(BaseViewHolder helper, V9MmanItem item) {
        helper.setText(R.id.tv_91mman_item_title, item.getTitleWithDuration());
        ImageView simpleDraweeView = helper.getView(R.id.iv_91mman_item_img);
        String coverUrl = item.getImgUrl();
        // H-11: 使用共享工具类加载封面
        GlideLoader.loadCover(simpleDraweeView, coverUrl);
        helper.setProgress(R.id.progressBar_download, item.getProgress());
        helper.setText(R.id.tv_download_progress, String.valueOf(item.getProgress()) + "%");
        // L-fix：HLS 分片阶段总量未知(total=0)，改显已下载字节 / "--"；直连任务维持原样式
        String sizeText;
        if (item.getTotalFarBytes() > 0) {
            sizeText = Formatter.formatFileSize(helper.itemView.getContext(), item.getSoFarBytes()).replace("MB", "") + "/ "
                    + Formatter.formatFileSize(helper.itemView.getContext(), item.getTotalFarBytes());
        } else if (item.getSoFarBytes() > 0) {
            sizeText = Formatter.formatFileSize(helper.itemView.getContext(), item.getSoFarBytes()).replace("MB", "") + "/ --";
        } else {
            sizeText = "--/--";
        }
        helper.setText(R.id.tv_download_filesize, sizeText);
        if (item.getStatus() == FileDownloadStatus.completed) {
            helper.setText(R.id.tv_download_speed, helper.itemView.getContext().getString(R.string.download_status_completed));
            helper.setVisible(R.id.iv_download_control, false);
        } else {
            //未下载完成，显示控制
            helper.setVisible(R.id.iv_download_control, true);
            if (FileDownloader.getImpl().isServiceConnected()) {
                helper.setImageResource(R.id.iv_download_control, R.drawable.pause_download);
                if (item.getStatus() == FileDownloadStatus.progress) {
                    // M43：分分钟(HLS)分片下载到 100% 后进入合并/转码阶段，明确提示“转换中”
                    if (PlayVideoPresenter.isPornySource(item) && item.getProgress() >= 100) {
                        helper.setText(R.id.tv_download_speed, helper.itemView.getContext().getString(R.string.download_status_converting));
                    } else {
                        helper.setText(R.id.tv_download_speed, item.getSpeed() + " KB/s");
                    }
                } else if (item.getStatus() == FileDownloadStatus.paused) {
                    helper.setText(R.id.tv_download_speed, helper.itemView.getContext().getString(R.string.download_status_paused));
                    helper.setImageResource(R.id.iv_download_control, R.drawable.start_download);
                } else if (item.getStatus() == FileDownloadStatus.pending) {
                    helper.setText(R.id.tv_download_speed, helper.itemView.getContext().getString(R.string.download_status_preparing));
                } else if (item.getStatus() == FileDownloadStatus.started) {
                    helper.setText(R.id.tv_download_speed, helper.itemView.getContext().getString(R.string.download_status_started));
                } else if (item.getStatus() == FileDownloadStatus.connected) {
                    helper.setText(R.id.tv_download_speed, helper.itemView.getContext().getString(R.string.download_status_connecting));
                } else if (item.getStatus() == FileDownloadStatus.error) {
                    helper.setText(R.id.tv_download_speed, helper.itemView.getContext().getString(R.string.download_status_error));
                    helper.setImageResource(R.id.iv_download_control, R.drawable.start_download);
                } else if (item.getStatus() == FileDownloadStatus.retry) {
                    helper.setText(R.id.tv_download_speed, helper.itemView.getContext().getString(R.string.download_status_retry));
                } else if (item.getStatus() == FileDownloadStatus.warn) {
                    helper.setText(R.id.tv_download_speed, helper.itemView.getContext().getString(R.string.download_status_warn));
                    helper.setImageResource(R.id.iv_download_control, R.drawable.start_download);
                }

            } else {
                helper.setText(R.id.tv_download_speed, helper.itemView.getContext().getString(R.string.download_status_paused));
                helper.setImageResource(R.id.iv_download_control, R.drawable.start_download);
            }
        }
        helper.addOnClickListener(R.id.iv_download_control);
        helper.addOnClickListener(R.id.right_menu_delete);
    }

    /** M112：回收时取消挂起的 Glide 请求（同 V91MmanAdapter） */
    @Override
    public void onViewRecycled(BaseViewHolder holder) {
        super.onViewRecycled(holder);
        ImageView cover = holder.getView(R.id.iv_91mman_item_img);
        if (cover != null) {
            GlideApp.with(cover).clear(cover);
        }
    }
}
