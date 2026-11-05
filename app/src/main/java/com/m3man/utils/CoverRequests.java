package com.m3man.utils;

import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.m3man.R;
import com.m3man.perf.Perf;
import com.m3man.perf.PerfFlags;

/**
 * T03：封面展示与预取共享请求规格（方案 §5-T03）。
 *
 * <p>核心修复（E04）：展示侧 {@code load(Uri)} 与预取侧 {@code load(String)} 的 model
 * 类型不一致，Glide 缓存键包含 model——预取的解码结果永远无法命中展示请求，
 * 等于白下白解。现统一 model 为 String url，并统一 override 尺寸与变换，
 * 预取后展示可命中 resource cache，不重复相同规格解码。
 *
 * <p>契约：
 * <ul>
 *   <li>卡片与推荐全屏是不同 spec，尺寸不互用；横竖屏变化生成新 spec。</li>
 *   <li>尺寸来源：布局实测 / 容器宽高与卡片比例；未完成布局时延迟到 doOnLayout 加载。</li>
 *   <li>禁止 SIZE_ORIGINAL 预取；未定尺寸的预取不发起。</li>
 *   <li>方向登记是 transform 副作用：resource disk hit 不会重跑 transform，
 *       方向未知时保持未知语义，不用 16:9 结果图反推。</li>
 *   <li>磁盘策略维持当前等价策略（默认 Glide 策略）；后续以 DataSource 统计决定是否调整。</li>
 * </ul>
 *
 * @author 3mman
 */
public final class CoverRequests {

    /** 变换算法版本：SmartCoverTransformation 算法变更时递增（进缓存键） */
    public static final int TRANSFORM_VERSION = 2;

    private static final int CROSS_FADE_DURATION = 300;

    /** 卡片默认规格（16:9 由适配器/宿主按实际卡片尺寸注册覆盖） */
    private static volatile int defaultCardWidth = 0;
    private static volatile int defaultCardHeight = 0;

    private CoverRequests() {
    }

    /** 宿主（推荐 Fragment/适配器）在拿到真实卡片尺寸后注册；预取用相同容器规格 */
    public static void registerCardSize(int widthPx, int heightPx) {
        if (widthPx > 0 && heightPx > 0) {
            defaultCardWidth = widthPx;
            defaultCardHeight = heightPx;
        }
    }

    private static int cardWidth() {
        if (defaultCardWidth > 0) {
            return defaultCardWidth;
        }
        // 兜底：屏幕宽 × 16:9（与列表卡片框一致）；仅未注册时的保守估计
        android.content.res.Resources res = android.content.res.Resources.getSystem();
        int w = res.getDisplayMetrics().widthPixels;
        return Math.max(1, w);
    }

    private static int cardHeight() {
        if (defaultCardHeight > 0) {
            return defaultCardHeight;
        }
        return Math.max(1, Math.round(cardWidth() * 9f / 16f));
    }

    /** 不可变封面请求规格 */
    public static final class Spec {
        public final String url;
        public final int widthPx;
        public final int heightPx;
        public final int transformVersion;
        @DrawableRes
        public final int placeholderRes;

        Spec(String url, int widthPx, int heightPx, int transformVersion, @DrawableRes int placeholderRes) {
            this.url = url;
            this.widthPx = widthPx;
            this.heightPx = heightPx;
            this.transformVersion = transformVersion;
            this.placeholderRes = placeholderRes;
        }

        /** 卡片规格（列表/预取共用同一容器尺寸） */
        public static Spec card(String url) {
            return new Spec(url, cardWidth(), cardHeight(), TRANSFORM_VERSION, R.drawable.placeholder);
        }

        /** 全屏规格（推荐沉浸页按屏幕实际尺寸，调用方传实测值） */
        public static Spec fullscreen(String url, int widthPx, int heightPx) {
            return new Spec(url, widthPx <= 0 ? cardWidth() : widthPx,
                    heightPx <= 0 ? cardHeight() : heightPx,
                    TRANSFORM_VERSION, R.drawable.placeholder);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Spec)) {
                return false;
            }
            Spec s = (Spec) o;
            return widthPx == s.widthPx && heightPx == s.heightPx
                    && transformVersion == s.transformVersion
                    && placeholderRes == s.placeholderRes
                    && (url == null ? s.url == null : url.equals(s.url));
        }

        @Override
        public int hashCode() {
            int r = url == null ? 0 : url.hashCode();
            r = 31 * r + widthPx;
            r = 31 * r + heightPx;
            r = 31 * r + transformVersion;
            return r;
        }

        @Override
        public String toString() {
            // 不输出完整签名 URL；仅尺寸与版本
            return "Spec(" + widthPx + "x" + heightPx + ",v" + transformVersion + ")";
        }
    }

    /** 方向回调：直接复用 {@link GlideLoader.OrientationCallback}（同包，避免重复接口漂移） */
    public static void load(@NonNull final ImageView imageView, @NonNull final Spec spec,
                            @Nullable final GlideLoader.OrientationCallback orientationCallback) {
        // T03：视图已实测尺寸时以实测为准（与旧 override(w,h) 行为一致）；
        // 未布局时用注册/回退规格（预取与展示同键命中），Spec 不可变、横竖屏变化自然生成新 spec
        Spec effective = spec;
        int vw = imageView.getWidth();
        int vh = imageView.getHeight();
        if (vw > 0 && vh > 0 && (vw != spec.widthPx || vh != spec.heightPx)) {
            effective = new Spec(spec.url, vw, vh, spec.transformVersion, spec.placeholderRes);
        }
        final Spec es = effective;
        // M119：已登记方向先同步回调（tag 防抖跳过重载时也能立刻拿到方向）
        if (orientationCallback != null) {
            Integer known = SmartCoverTransformation.orientationOf(es.url);
            if (known != null) {
                orientationCallback.onOrientation(known);
            }
        }
        Object boundTag = imageView.getTag(R.id.tag_adapter_url);
        if (boundTag != null && boundTag.equals(es)) {
            return; // 相同规格已绑定，跳过重载（防闪烁）
        }
        imageView.setTag(R.id.tag_adapter_url, es);

        RequestBuilder<Drawable> build = GlideApp.with(imageView)
                .load(es.url) // T03：统一 String model（旧 Uri model 与预取缓存键不一致）
                .apply(new com.bumptech.glide.request.RequestOptions()
                        .placeholder(es.placeholderRes)
                        .error(es.placeholderRes)
                        .override(es.widthPx, es.heightPx)
                        .transform(new SmartCoverTransformation(es.url)))
                .transition(new DrawableTransitionOptions().crossFade(CROSS_FADE_DURATION));
        if (orientationCallback != null) {
            final String boundUrl = es.url;
            build.addListener(new RequestListener<Drawable>() {
                @Override
                public boolean onLoadFailed(@Nullable com.bumptech.glide.load.engine.GlideException e,
                                            Object model, Target<Drawable> target,
                                            boolean isFirstResource) {
                    // 失败清 tag：允许重试（复用/重绑定时不会因同 tag 被跳过）
                    if (es.equals(imageView.getTag(R.id.tag_adapter_url))) {
                        imageView.setTag(R.id.tag_adapter_url, null);
                    }
                    Perf.recorder().counter("cover.load_fail", 1);
                    if (boundUrl.equals(currentBoundUrl(imageView))) {
                        orientationCallback.onOrientation(0);
                    }
                    return false;
                }

                @Override
                public boolean onResourceReady(Drawable resource, Object model,
                                               Target<Drawable> target, DataSource dataSource,
                                               boolean isFirstResource) {
                    Perf.recorder().counter("cover.load_" + dataSource.name(), 1);
                    // 异步回调必须校验当前绑定 key，避免复用错位
                    if (boundUrl.equals(currentBoundUrl(imageView))) {
                        Integer orientation = SmartCoverTransformation.orientationOf(boundUrl);
                        orientationCallback.onOrientation(orientation == null ? 0 : orientation);
                    }
                    return false;
                }
            });
        }
        build.into(imageView);
    }

    /** 读取当前绑定 url（tag 为 Spec 时取其 url；否则 null） */
    private static String currentBoundUrl(ImageView imageView) {
        Object tag = imageView.getTag(R.id.tag_adapter_url);
        return tag instanceof Spec ? ((Spec) tag).url : null;
    }

    /** onViewRecycled/清除时调用：一并清 tag，允许下次重绑重新加载 */
    public static void clearBoundSpec(ImageView imageView) {
        imageView.setTag(R.id.tag_adapter_url, null);
    }

    /**
     * 预取：与展示完全相同的 spec（String model + override + transform）。
     * 规格未定（无尺寸）不预取——禁止 SIZE_ORIGINAL 未定尺寸解码大图。
     */
    public static void preload(@NonNull RequestManager rm, @NonNull Spec spec) {
        if (spec.widthPx <= 0 || spec.heightPx <= 0 || spec.url == null) {
            return;
        }
        long begin = Perf.recorder().begin(Perf.ST_COVER_LOAD, Perf.nextRequestId());
        try {
            rm.load(spec.url)
                    .apply(new com.bumptech.glide.request.RequestOptions()
                            .override(spec.widthPx, spec.heightPx)
                            .transform(new SmartCoverTransformation(spec.url)))
                    .preload();
            Perf.recorder().end(Perf.ST_COVER_LOAD, 0L, begin, Perf.OUTCOME_OK);
        } catch (Exception e) {
            Perf.recorder().end(Perf.ST_COVER_LOAD, 0L, begin, Perf.OUTCOME_FAIL);
        }
    }

    /** 预取卡片规格封面（RecommendPrefetcher 用） */
    public static void preloadCard(RequestManager rm, String url) {
        preload(rm, Spec.card(url));
    }

    /** 兼容旧入口：GlideLoader.loadCover 无 callback 重载（flag 关闭时走旧实现） */
    static void legacyLoad(ImageView imageView, String coverUrl, int placeholderRes) {
        Uri uri = Uri.parse(coverUrl);
        com.bumptech.glide.request.RequestOptions options = new com.bumptech.glide.request.RequestOptions()
                .placeholder(placeholderRes)
                .error(placeholderRes)
                .transform(new SmartCoverTransformation(coverUrl));
        int w = imageView.getWidth();
        int h = imageView.getHeight();
        if (w > 0 && h > 0) {
            options = options.override(w, h);
        }
        GlideApp.with(imageView)
                .load(uri)
                .apply(options)
                .transition(new DrawableTransitionOptions().crossFade(CROSS_FADE_DURATION))
                .into(imageView);
    }
}
