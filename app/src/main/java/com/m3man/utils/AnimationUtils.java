package com.m3man.utils;

import android.view.View;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;

/**
 * @author flymegoc
 * @date 2018/1/19
 */

public class AnimationUtils {

    public static void rotateUp(View view) {
        rotate(view, 0f, 180f);
    }

    public static void rotateDown(View view) {
        rotate(view, 180f, 0f);
    }

    /**
     * L-04：rotateUp/rotateDown 仅起始角度不同，动画主体统一收敛到本方法。
     */
    private static void rotate(View view, float fromDegrees, float toDegrees) {
        RotateAnimation rotate = new RotateAnimation(fromDegrees, toDegrees,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        LinearInterpolator lin = new LinearInterpolator();
        rotate.setInterpolator(lin);
        rotate.setDuration(200);
        rotate.setRepeatCount(0);
        rotate.setFillAfter(true);
        rotate.setStartOffset(10);
        view.startAnimation(rotate);
    }
}
