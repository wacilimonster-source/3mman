package com.m3man.ui.recommend;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.m3man.R;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.reco.RecoEngine;
import com.m3man.data.reco.RecoProfile;
import com.m3man.data.reco.RecoStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「学习到了什么」明细面板。
 * <p>
 * 推荐算法平时是黑盒，这里把它学到的四类东西摊开给用户看：
 * <ul>
 *   <li><b>标签</b>：从标题分词得到的兴趣词及其权重</li>
 *   <li><b>作者</b>：作者权重（id 会翻译成昵称）</li>
 *   <li><b>分类</b>：分类权重</li>
 *   <li><b>行为</b>：逐条的赞 / 不喜欢 / 收藏记录，异步补上视频标题</li>
 * </ul>
 * 权重为正表示「更想看」，为负表示「少推一点」。
 *
 * @author 3mman
 */
public class RecoRecordsDialog {

    private static final int TAB_TAG = 0;
    private static final int TAB_AUTHOR = 1;
    private static final int TAB_CATEGORY = 2;
    private static final int TAB_ACTION = 3;

    /** 行为记录最多展示条数（再多列表会很卡） */
    private static final int MAX_ACTION_ROWS = 300;

    private static final int COLOR_POSITIVE = 0xFFE53935;
    private static final int COLOR_NEGATIVE = 0xFF00897B;
    private static final int COLOR_TAB_ON = 0xFFE53935;
    private static final int COLOR_TAB_OFF = 0xFF888888;

    private final Context context;
    private final RecoEngine engine;
    private final DataManager dataManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** viewKey -> 标题，后台查库补齐 */
    private final Map<String, String> titleCache = new HashMap<>();

    private LinearLayout listContainer;
    private final TextView[] tabViews = new TextView[4];
    private int currentTab = TAB_TAG;
    private volatile boolean dismissed = false;

    public RecoRecordsDialog(Context context, RecoEngine engine, DataManager dataManager) {
        this.context = context;
        this.engine = engine;
        this.dataManager = dataManager;
    }

    public void show() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        root.addView(buildTabBar());
        root.addView(buildSummary());

        ScrollView scroll = new ScrollView(context);
        listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        listContainer.setPadding(pad, dp(4), pad, dp(12));
        scroll.addView(listContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 固定高度，避免记录很多时对话框顶满屏幕
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.reco_records_title)
                .setView(root)
                .setPositiveButton(R.string.reco_records_close, null)
                .create();
        dialog.setOnDismissListener(d -> dismissed = true);
        dialog.show();

        selectTab(TAB_TAG);
    }

    // ==================== 头部 ====================

    private View buildTabBar() {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(8), dp(8), dp(8), 0);
        int[] labels = {R.string.reco_records_tab_tag, R.string.reco_records_tab_author,
                R.string.reco_records_tab_category, R.string.reco_records_tab_action};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView tab = new TextView(context);
            tab.setText(labels[i]);
            tab.setGravity(Gravity.CENTER);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tab.setPadding(0, dp(8), 0, dp(8));
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(index);
                }
            });
            tabViews[i] = tab;
            bar.addView(tab, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return bar;
    }

    private View buildSummary() {
        RecoProfile profile = engine.getProfile();
        TextView tv = new TextView(context);
        tv.setText(String.format(Locale.getDefault(),
                "赞 %d · 不喜欢 %d · 收藏 %d · 观看 %d",
                profile.likeCount, profile.dislikeCount,
                profile.favoriteCount, profile.watchCount));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(Color.GRAY);
        tv.setPadding(dp(16), dp(4), dp(16), dp(6));
        return tv;
    }

    private void selectTab(int tab) {
        currentTab = tab;
        for (int i = 0; i < tabViews.length; i++) {
            boolean on = i == tab;
            tabViews[i].setTextColor(on ? COLOR_TAB_ON : COLOR_TAB_OFF);
            tabViews[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
        renderCurrentTab();
    }

    // ==================== 列表 ====================

    private void renderCurrentTab() {
        listContainer.removeAllViews();
        switch (currentTab) {
            case TAB_TAG:
                renderWeights(engine.getProfile().tagWeights, false);
                break;
            case TAB_AUTHOR:
                renderWeights(engine.getProfile().authorWeights, true);
                break;
            case TAB_CATEGORY:
                renderWeights(engine.getProfile().categoryWeights, false);
                break;
            case TAB_ACTION:
                renderActions();
                break;
            default:
        }
    }

    /**
     * 渲染一张权重表，按权重绝对值从大到小排（越"有主见"的排越前）。
     *
     * @param translateAuthor true 时把作者 id 翻译成昵称
     */
    private void renderWeights(Map<String, Double> weights, boolean translateAuthor) {
        if (weights == null || weights.isEmpty()) {
            addEmpty();
            return;
        }
        List<Map.Entry<String, Double>> entries = new ArrayList<>(weights.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Double>>() {
            @Override
            public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
                return Double.compare(Math.abs(b.getValue()), Math.abs(a.getValue()));
            }
        });
        RecoStore store = engine.getStore();
        for (Map.Entry<String, Double> e : entries) {
            String name = e.getKey();
            if (translateAuthor) {
                String display = store.authorName(name);
                if (!TextUtils.isEmpty(display)) {
                    name = display;
                }
            }
            double w = e.getValue() == null ? 0d : e.getValue();
            addRow(name, String.format(Locale.US, "%+.2f", w),
                    w >= 0 ? COLOR_POSITIVE : COLOR_NEGATIVE);
        }
    }

    private void renderActions() {
        LinkedHashMap<String, Integer> actions = engine.getStore().snapshotActions();
        if (actions.isEmpty()) {
            addEmpty();
            return;
        }
        // LinkedHashMap 是「越新越靠后」，展示时倒过来
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(actions.entrySet());
        Collections.reverse(entries);
        if (entries.size() > MAX_ACTION_ROWS) {
            entries = entries.subList(0, MAX_ACTION_ROWS);
        }
        final List<String> pending = new ArrayList<>();
        for (Map.Entry<String, Integer> e : entries) {
            String key = e.getKey();
            String title = titleCache.get(key);
            if (title == null) {
                pending.add(key);
                title = shortKey(key);
            }
            addRow(title, actionLabel(e.getValue()), actionColor(e.getValue()));
        }
        if (!pending.isEmpty()) {
            resolveTitlesAsync(pending);
        }
    }

    /** 后台查库把 viewKey 换成视频标题，回来后整页重绘一次 */
    private void resolveTitlesAsync(final List<String> keys) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Map<String, String> resolved = new HashMap<>();
                for (String key : keys) {
                    if (dismissed) {
                        return;
                    }
                    String title = null;
                    try {
                        V9MmanItem item = dataManager.findV9MmanItemByViewKey(key);
                        if (item != null) {
                            title = item.getTitle();
                        }
                    } catch (Exception ignored) {
                    }
                    resolved.put(key, TextUtils.isEmpty(title) ? shortKey(key) : title);
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (dismissed || listContainer == null) {
                            return;
                        }
                        titleCache.putAll(resolved);
                        if (currentTab == TAB_ACTION) {
                            renderCurrentTab();
                        }
                    }
                });
            }
        }, "reco-records").start();
    }

    private static String shortKey(String viewKey) {
        if (TextUtils.isEmpty(viewKey)) {
            return "(未知)";
        }
        int idx = viewKey.indexOf('=');
        return idx >= 0 && idx + 1 < viewKey.length() ? viewKey.substring(idx + 1) : viewKey;
    }

    private static String actionLabel(Integer action) {
        if (action == null) {
            return "";
        }
        switch (action) {
            case RecoStore.ACTION_LIKE:
                return "赞";
            case RecoStore.ACTION_DISLIKE:
                return "不喜欢";
            case RecoStore.ACTION_FAVORITE:
                return "收藏";
            default:
                return "";
        }
    }

    private static int actionColor(Integer action) {
        return action != null && action == RecoStore.ACTION_DISLIKE
                ? COLOR_NEGATIVE : COLOR_POSITIVE;
    }

    private void addRow(String name, String value, int valueColor) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView left = new TextView(context);
        left.setText(name);
        left.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        left.setMaxLines(2);
        left.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView right = new TextView(context);
        right.setText(value);
        right.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        right.setTextColor(valueColor);
        right.setGravity(Gravity.END);
        row.addView(right, new LinearLayout.LayoutParams(dp(64),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        listContainer.addView(row);
    }

    private void addEmpty() {
        TextView tv = new TextView(context);
        tv.setText(R.string.reco_records_empty);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(Color.GRAY);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(40), 0, dp(40));
        listContainer.addView(tv);
    }

    private int dp(int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
