package com.m3man.utils;

import android.view.View;

import com.google.android.material.snackbar.Snackbar;

/**
 * 可撤销的破坏性操作确认条。
 * <p>
 * 解决的问题：删除下载、删除记录这类操作一旦执行无法挽回，
 * 过去直接 Toast 一句就落库，用户手滑没有任何反悔机会。
 * <p>
 * 使用方式：把原来「立即执行删除」的代码包进 {@link Action} 传进来。
 * 真正的删除只在 Snackbar <b>自然超时</b>（用户既没点撤销、也没手动关掉它）后执行：
 * <ul>
 *   <li>用户点了「撤销」→ 取消，什么都不发生；</li>
 *   <li>用户把它滑走 / 屏幕上出现新 Snackbar 顶掉它 → 取消（保守策略，宁可不删）；</li>
 *   <li>超时自动消失 → 执行。</li>
 * </ul>
 * 被取消时列表数据未动，用户可以再次操作 —— 方向永远是「往安全的一边出错」。
 *
 * @author 3mman
 */
public final class UndoSnackbar {

    private UndoSnackbar() {
    }

    /** Snackbar 超时后才执行的动作（即用户没有撤销） */
    public interface Action {
        void run();
    }

    /**
     * 展示「已删除，可撤销」确认条。
     *
     * @param anchor    依附的视图（一般为列表 RecyclerView）
     * @param text      提示文案，如「已删除下载任务」
     * @param undoLabel 撤销按钮文案，如「撤销」
     * @param action    超时未撤销时才真正执行的动作（原删除逻辑）
     */
    public static void confirmWithUndo(View anchor, CharSequence text,
                                       CharSequence undoLabel, final Action action) {
        if (anchor == null) {
            // 没有可依附的视图时退化为直接执行：宁可恢复旧行为，也不丢操作
            action.run();
            return;
        }
        Snackbar snackbar = Snackbar.make(anchor, text, Snackbar.LENGTH_LONG);
        snackbar.setAction(undoLabel, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 撤销：不做任何事，数据保持原样
            }
        });
        snackbar.addCallback(new Snackbar.Callback() {
            @Override
            public void onDismissed(Snackbar sb, int event) {
                // 只有自然超时（用户未做任何干预）才执行删除；
                // 点击撤销 / 手动滑走 / 被新 Snackbar 顶掉，一律视为取消。
                if (event == DISMISS_EVENT_TIMEOUT) {
                    action.run();
                }
            }
        });
        snackbar.show();
    }
}
