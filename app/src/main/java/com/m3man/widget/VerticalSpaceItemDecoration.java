package com.m3man.widget;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 单列大卡片行间距（spec §3：视频列表行间距 18dp）。
 * 只加底部间距，首尾由 RecyclerView 的 padding 控制，避免双倍间距。
 */
public class VerticalSpaceItemDecoration extends RecyclerView.ItemDecoration {

    private final int verticalSpace;

    public VerticalSpaceItemDecoration(int verticalSpace) {
        this.verticalSpace = Math.max(0, verticalSpace);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        outRect.bottom = verticalSpace;
    }
}
