package com.m3man.utils;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.orhanobut.logger.Logger;

import java.util.List;

/**
 * @author flymegoc
 * @date 2018/1/22
 */

public class FragmentUtils {
    private static final String TAG = FragmentUtils.class.getSimpleName();

    /**
     * 切换
     *
     * @param fragmentManager fm管理器
     * @param currentFragment 当前
     * @param toShowFragment  将要显示
     * @param viewId          容器id
     * @param itemId          position标识id
     * @param isInnerReplace  是否是同一位置替换
     * @return 当前显示（M61：始终返回真正被显示的实例——旧实现在复用已存在实例的分支里
     *         返回的是传入的新对象，导致调用方的 mCurrentFragment 变成“幽灵引用”，
     *         下次切换 hide 不中，旧页面叠在新页面顶部）
     */
    public static Fragment switchContent(FragmentManager fragmentManager, Fragment currentFragment, Fragment toShowFragment, int viewId, long itemId, boolean isInnerReplace) {
        if (fragmentManager == null || toShowFragment == null) {
            return currentFragment;
        }
        String name = FragmentUtils.makeFragmentName(viewId, itemId);
        // M61：同位置已有存活实例时优先复用（Activity 恢复后 getInstance() 新建的对象
        // 与恢复出来的旧实例同 tag 并存，会互相叠加且只隐藏其中一个）
        Fragment tagged = fragmentManager.findFragmentByTag(name);
        if (tagged != null && tagged != toShowFragment
                && !tagged.isDetached() && !tagged.isRemoving()) {
            toShowFragment = tagged;
        }
        FragmentTransaction transaction = fragmentManager.beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out);
        //同一位置替换（例如：从91视频切换到朱古力视频）：移除旧的再add新的
        if (isInnerReplace && tagged != null) {
            transaction.remove(tagged);
            transaction.add(viewId, toShowFragment, name).commit();
            Logger.t(TAG).d("isInnerReplace is true ,remove old and add new");
            return toShowFragment;
        }
        // M61：先隐藏容器内其它全部可见 fragment（含历史残留/重复实例），再显示目标，
        // 从机制上杜绝“上一个页面盖在顶部”
        hideAllOtherVisible(fragmentManager, transaction, toShowFragment);
        if (!toShowFragment.isAdded()) {
            transaction.add(viewId, toShowFragment, name);
            Logger.t(TAG).d("add new fragment: " + name);
        } else {
            transaction.show(toShowFragment);
            Logger.t(TAG).d("show existing fragment: " + name);
        }
        transaction.commit();
        return toShowFragment;
    }

    /** 隐藏容器内除 keep 外所有已添加且可见的 fragment */
    private static void hideAllOtherVisible(FragmentManager fragmentManager, FragmentTransaction transaction, Fragment keep) {
        try {
            List<Fragment> added = fragmentManager.getFragments();
            if (added == null) {
                return;
            }
            for (Fragment f : added) {
                if (f != null && f != keep && f.isAdded() && !f.isHidden() && !f.isDetached() && !f.isRemoving()) {
                    transaction.hide(f);
                }
            }
        } catch (Exception e) {
            Logger.t(TAG).d("hide others failed: " + e.getMessage());
        }
    }

    /**
     * FragmentPagerAdapter 内部生成tag的方法
     *
     * @param viewId viewpager id
     * @param id     getItemId 获取到的id
     * @return tag
     */
    public static String makeFragmentName(int viewId, long id) {
        return "android:switcher:" + viewId + ":" + id;
    }
}
