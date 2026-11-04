package com.m3man.widget;

import android.content.Context;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;

import androidx.appcompat.widget.AppCompatImageView;

import com.m3man.R;

/**
 * 封面圆角视图（spec EX-02 / §3：16:9 封面圆角 10dp）。
 * <p>
 * 用 {@link ViewOutlineProvider} + clipToOutline 做硬件加速裁剪，
 * 不改动 Glide 的下载/缓存管线，也不额外增加一层 View。
 */
public class RoundedCornerImageView extends AppCompatImageView {

    private final float cornerRadius;

    public RoundedCornerImageView(Context context) {
        this(context, null);
    }

    public RoundedCornerImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RoundedCornerImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        cornerRadius = context.getResources().getDimension(R.dimen.video_cover_radius);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int width = view.getWidth();
                int height = view.getHeight();
                if (width <= 0 || height <= 0) {
                    return;
                }
                float radius = Math.min(cornerRadius, Math.min(width, height) / 2f);
                outline.setRoundRect(0, 0, width, height, radius);
            }
        });
    }
}
