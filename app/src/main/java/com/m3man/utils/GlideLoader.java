package com.m3man.utils;

import android.net.Uri;
import android.support.annotation.DrawableRes;
import android.support.annotation.NonNull;
import android.widget.ImageView;

import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.m3man.R;

/**
 * H-11: 封装通用的 Glide 加载逻辑，消除多个 Adapter 中的重复代码。
 * <p>
 * 用法示例：
 * <pre>
 * GlideLoader.loadCover(context, imageView, coverUrl);
 * GlideLoader.loadCover(context, imageView, coverUrl, R.drawable.placeholder);
 * </pre>
 */
public final class GlideLoader {

    private static final int CROSS_FADE_DURATION = 300;

    private GlideLoader() {
        // 防止实例化
    }

    /**
     * 加载封面图（带 tag 防抖、占位图、crossFade、SmartCoverTransformation）
     *
     * @param imageView 目标 ImageView
     * @param coverUrl  封面 URL
     * @param placeholderRes 占位图资源 ID（可选，默认 R.drawable.placeholder）
     */
    public static void loadCover(@NonNull ImageView imageView, @NonNull String coverUrl,
                                 @DrawableRes int placeholderRes) {
        // M97：tag 防抖——RecyclerView 复用时相同 url 跳过重载，消除封面闪烁
        Object boundTag = imageView.getTag(R.id.tag_adapter_url);
        if (boundTag != null && boundTag.equals(coverUrl)) {
            return;
        }
        imageView.setTag(R.id.tag_adapter_url, coverUrl);

        Uri uri = Uri.parse(coverUrl);
        // M77：智能封面变换——近似 16:9 直接填满；竖版/异形封面画模糊底+原图居中，杜绝细条留白与拉伸
        GlideApp.with(imageView)
                .load(uri)
                .placeholder(placeholderRes)
                .transition(new DrawableTransitionOptions().crossFade(CROSS_FADE_DURATION))
                .transform(new SmartCoverTransformation())
                .into(imageView);
    }

    /**
     * 加载封面图（使用默认占位图 R.drawable.placeholder）
     */
    public static void loadCover(@NonNull ImageView imageView, @NonNull String coverUrl) {
        loadCover(imageView, coverUrl, R.drawable.placeholder);
    }
}