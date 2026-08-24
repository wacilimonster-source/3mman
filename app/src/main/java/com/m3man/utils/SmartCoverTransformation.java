package com.m3man.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.support.annotation.NonNull;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.security.MessageDigest;

/**
 * M77：列表封面智能适配变换（方案 B）。
 * <p>
 * 背景：站点封面比例混杂——实测约 2/3 为 16:9 横版，约 1/6 是竖版（手机竖拍视频），
 * 另有少量 4:3。列表卡片图框锁死 16:9，竖版图用 fitCenter 会缩成中间细条 + 大片留白，
 * 用 centerCrop 又会把主体裁得只剩中间一截。
 * <p>
 * 策略：
 * <ul>
 *   <li>源图与目标框比例接近（相对偏差 ≤{@link #SIMILAR_RATIO_TOLERANCE}，覆盖
 *       400x224/230 这类近似 16:9 图）→ 直接等比裁剪填满（等效 centerCrop）；
 *   <li>比例差异大（竖版 / 4:3 等）→ 先画一层「放大填满 + 缩放模糊」的底图，
 *       再把原图等比居中叠上去（类似抖音分享卡）：不拉伸、不裁主体、卡片整齐。
 * </ul>
 * 模糊采用「缩小再放大」的廉价实现（双线性过滤天然糊化），不依赖 RenderScript；
 * 背景只是装饰层，糊即可。
 */
public class SmartCoverTransformation extends BitmapTransformation {

    private static final String ID = "com.m3man.utils.SmartCoverTransformation.v1";
    private static final byte[] ID_BYTES = ID.getBytes(CHARSET);

    /** 源图与目标框比例相对偏差 ≤ 该值时视为“接近”，直接填满 */
    private static final float SIMILAR_RATIO_TOLERANCE = 0.20f;
    /** 模糊强度：背景层源图的降采样倍数（越大越糊、越省内存） */
    private static final int BLUR_DOWNSCALE = 14;

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool,
                               @NonNull Bitmap source, int outWidth, int outHeight) {
        if (outWidth <= 0 || outHeight <= 0 || source.isRecycled()
                || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return source;
        }
        float srcRatio = source.getWidth() / (float) source.getHeight();
        float dstRatio = outWidth / (float) outHeight;
        boolean similar =
                Math.abs(srcRatio - dstRatio) / dstRatio <= SIMILAR_RATIO_TOLERANCE;

        Bitmap out = pool.get(outWidth, outHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        // 背景层：等比放大到完全盖住目标框（cover）。接近 16:9 时该层即最终结果
        RectF coverRect = coverRect(source.getWidth(), source.getHeight(), outWidth, outHeight);
        if (similar) {
            canvas.drawBitmap(source, null, coverRect, paint);
            return out;
        }

        // 差异大：背景先降采样再拉伸 → 双线性过滤天然模糊
        int sw = Math.max(1, source.getWidth() / BLUR_DOWNSCALE);
        int sh = Math.max(1, source.getHeight() / BLUR_DOWNSCALE);
        Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas smallCanvas = new Canvas(small);
        smallCanvas.drawBitmap(source, null, new Rect(0, 0, sw, sh), paint);
        canvas.drawBitmap(small, null, coverRect, paint);
        small.recycle();

        // 前景层：原图完整居中（fitCenter 语义），主体不裁不变形
        RectF foreground = fitCenterRect(source.getWidth(), source.getHeight(),
                outWidth, outHeight);
        canvas.drawBitmap(source, null, foreground, paint);
        return out;
    }

    /** 等比缩放并居中，完全覆盖目标区域（cover/crop 语义） */
    private static RectF coverRect(int srcW, int srcH, int dstW, int dstH) {
        float scale = Math.max(dstW / (float) srcW, dstH / (float) srcH);
        float w = srcW * scale;
        float h = srcH * scale;
        float left = (dstW - w) / 2f;
        float top = (dstH - h) / 2f;
        return new RectF(left, top, left + w, top + h);
    }

    /** 等比缩小并居中，完整放入目标区域（fitCenter 语义） */
    private static RectF fitCenterRect(int srcW, int srcH, int dstW, int dstH) {
        float scale = Math.min(dstW / (float) srcW, dstH / (float) srcH);
        float w = srcW * scale;
        float h = srcH * scale;
        float left = (dstW - w) / 2f;
        float top = (dstH - h) / 2f;
        return new RectF(left, top, left + w, top + h);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SmartCoverTransformation;
    }

    @Override
    public int hashCode() {
        return ID.hashCode();
    }

    @Override
    public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
        messageDigest.update(ID_BYTES);
    }
}
