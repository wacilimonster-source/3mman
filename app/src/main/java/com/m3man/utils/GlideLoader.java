package com.m3man.utils;

import android.graphics.drawable.Drawable;
import android.net.Uri;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.widget.ImageView;

import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
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

    /** M119：源图方向回调（1=竖屏 / -1=横屏 / 0=未知），供列表方向角标使用 */
    public interface OrientationCallback {
        void onOrientation(int orientation);
    }

    /**
     * 加载封面图（带 tag 防抖、占位图、错误图、crossFade、SmartCoverTransformation）
     *
     * @param imageView 目标 ImageView
     * @param coverUrl  封面 URL
     * @param placeholderRes 占位图资源 ID（可选，默认 R.drawable.placeholder）
     * @param orientationCallback 源图方向回调（可空；已登记方向时同步触发，否则在加载完成时触发）
     */
    public static void loadCover(@NonNull ImageView imageView, @NonNull String coverUrl,
                                 @DrawableRes int placeholderRes,
                                 @Nullable final OrientationCallback orientationCallback) {
        // M119：已登记方向先同步回调（tag 防抖跳过重载时也能立刻拿到方向）
        if (orientationCallback != null) {
            Integer known = SmartCoverTransformation.ORIENTATION_MAP.get(coverUrl);
            if (known != null) {
                orientationCallback.onOrientation(known);
            }
        }
        // M97：tag 防抖——RecyclerView 复用时相同 url 跳过重载，消除封面闪烁
        Object boundTag = imageView.getTag(R.id.tag_adapter_url);
        if (boundTag != null && boundTag.equals(coverUrl)) {
            return;
        }
        imageView.setTag(R.id.tag_adapter_url, coverUrl);

        Uri uri = Uri.parse(coverUrl);
        // M77：智能封面变换——近似 16:9 直接填满；竖版/异形封面画模糊底+原图居中，杜绝细条留白与拉伸
        // M112：补 error 占位 + 按目标 View 尺寸 override——
        //   1) 此前无 error 图，封面 404/超时后 ImageView 会一直停留在复用残留的旧图上，
        //      造成「张冠李戴」的封面错位；
        //   2) 此前无 override，Glide 按原图尺寸解码（部分封面原图远大于屏幕），
        //      大图造成单 item 解码卡顿与内存峰值。
        RequestOptions options = new RequestOptions()
                .placeholder(placeholderRes)
                .error(placeholderRes)
                .transform(new SmartCoverTransformation(coverUrl));
        int w = imageView.getWidth();
        int h = imageView.getHeight();
        if (w > 0 && h > 0) {
            options = options.override(w, h);
        }
        com.bumptech.glide.RequestBuilder<Drawable> build = GlideApp.with(imageView)
                .load(uri)
                .apply(options)
                .transition(new DrawableTransitionOptions().crossFade(CROSS_FADE_DURATION));
        if (orientationCallback != null) {
            // M119：变换输出被统一成 16:9，方向从 SmartCoverTransformation 的登记表回读
            build.addListener(new RequestListener<Drawable>() {
                @Override
                public boolean onLoadFailed(@Nullable com.bumptech.glide.load.engine.GlideException e,
                                            Object model, Target<Drawable> target,
                                            boolean isFirstResource) {
                    orientationCallback.onOrientation(0);
                    return false;
                }

                @Override
                public boolean onResourceReady(Drawable resource, Object model,
                                               Target<Drawable> target, DataSource dataSource,
                                               boolean isFirstResource) {
                    Integer orientation = SmartCoverTransformation.ORIENTATION_MAP.get(coverUrl);
                    orientationCallback.onOrientation(orientation == null ? 0 : orientation);
                    return false;
                }
            });
        }
        build.into(imageView);
    }

    /**
     * 加载封面图（使用默认占位图 R.drawable.placeholder）
     */
    public static void loadCover(@NonNull ImageView imageView, @NonNull String coverUrl) {
        loadCover(imageView, coverUrl, R.drawable.placeholder);
    }

    public static void loadCover(@NonNull ImageView imageView, @NonNull String coverUrl,
                                 @DrawableRes int placeholderRes) {
        loadCover(imageView, coverUrl, placeholderRes, null);
    }

    /** M119：带方向回调的便捷重载 */
    public static void loadCover(@NonNull ImageView imageView, @NonNull String coverUrl,
                                 @Nullable OrientationCallback orientationCallback) {
        loadCover(imageView, coverUrl, R.drawable.placeholder, orientationCallback);
    }
}
