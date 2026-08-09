package com.m3man.ui.recommend;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.AlertDialog;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.m3man.R;
import com.m3man.data.DataManager;
import com.m3man.data.reco.RecoEngine;
import com.m3man.data.reco.RecoParams;
import com.m3man.data.reco.RecoProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 推荐算法调参面板。
 * <p>
 * 交互说明：早期版本每个参数是一条 SeekBar，十几条挤在一起密密麻麻、
 * 手指一碰就跳好几档，根本没法微调。现在改成「减 / 数值 / 加」步进器：
 * <ul>
 *   <li>点一下走一个固定步长，长按连续调整，数值精确可控；</li>
 *   <li>只有「常用设置」默认展开，权重系数一类的进阶项收进
 *       {@code 高级设置} 里，需要时再点开；</li>
 *   <li>顶部提供「查看学习记录」入口，可以看到算法到底学到了哪些标签 / 作者 / 行为。</li>
 * </ul>
 * 面板仍然用代码搭而不是写 XML：全是同构的重复行，一个 {@link #addStepper} 生成一条，
 * 增删参数只要加一行。
 *
 * @author 3mman
 */
public class RecoSettingsDialog {

    /** 长按连续调整：首次延迟 / 重复间隔 */
    private static final long REPEAT_DELAY = 380L;
    private static final long REPEAT_INTERVAL = 70L;

    private static final int COLOR_SECTION = 0xFFE53935;
    private static final int COLOR_LINE = 0xFFE0E0E0;

    public interface OnParamsChangedListener {
        void onParamsChanged(RecoParams params);

        void onMemoryCleared();
    }

    /** 参数读写器：把「某个字段」抽象成可读可写可格式化的三件套 */
    private interface Accessor {
        float get(RecoParams p);

        void set(RecoParams p, float v);

        String format(float v);
    }

    /** 小数展示 */
    private abstract static class FloatAccessor implements Accessor {
        @Override
        public String format(float v) {
            return String.format(Locale.US, "%.2f", v);
        }
    }

    /** 百分比展示 */
    private abstract static class RatioAccessor implements Accessor {
        @Override
        public String format(float v) {
            return String.format(Locale.US, "%d%%", Math.round(v * 100));
        }
    }

    /** 整数展示 */
    private abstract static class IntAccessor implements Accessor {
        @Override
        public String format(float v) {
            return String.valueOf(Math.round(v));
        }
    }

    private final Context context;
    private final RecoEngine engine;
    private final DataManager dataManager;
    private final OnParamsChangedListener listener;
    /** 恢复默认时用来把所有数值刷回新值 */
    private final List<Runnable> syncFromParams = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private RecoParams working;

    public RecoSettingsDialog(Context context, RecoEngine engine, DataManager dataManager,
                              OnParamsChangedListener listener) {
        this.context = context;
        this.engine = engine;
        this.dataManager = dataManager;
        this.listener = listener;
    }

    public void show() {
        working = RecoParams.load(context);

        ScrollView scrollView = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, dp(6), pad, dp(8));
        scrollView.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addProfileSummary(root);
        addRecordsEntry(root);

        // ---------- 常用设置 ----------
        addSectionTitle(root, context.getString(R.string.reco_param_basic), null);
        buildBasicGroup(root);

        // ---------- 高级设置（默认收起） ----------
        final LinearLayout advanced = new LinearLayout(context);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setVisibility(View.GONE);
        addCollapsibleHeader(root, advanced);
        buildAdvancedGroup(advanced);
        root.addView(advanced);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.reco_settings_title)
                .setView(scrollView)
                .setPositiveButton(R.string.sure, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        engine.updateParams(working);
                        if (listener != null) {
                            listener.onParamsChanged(working);
                        }
                    }
                })
                .setNeutralButton(R.string.reco_param_reset, null)
                .setNegativeButton(R.string.reco_param_clear_memory, null)
                .create();

        // 中性 / 否定按钮点完不关窗，所以要在 onShow 之后接管点击事件
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                AlertDialog ad = (AlertDialog) d;
                ad.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        working.resetToDefault();
                        for (Runnable r : syncFromParams) {
                            r.run();
                        }
                    }
                });
                ad.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (listener != null) {
                            listener.onMemoryCleared();
                        }
                    }
                });
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                handler.removeCallbacksAndMessages(null);
            }
        });
        dialog.show();
    }

    // ==================== 分组内容 ====================

    private void buildBasicGroup(LinearLayout parent) {
        addStepper(parent, R.string.reco_param_exploration, 0f, 0.8f, 0.05f,
                new RatioAccessor() {
                    @Override
                    public float get(RecoParams p) {
                        return p.explorationRate;
                    }

                    @Override
                    public void set(RecoParams p, float v) {
                        p.explorationRate = v;
                    }
                });
        addStepper(parent, R.string.reco_param_recency, 0f, 5f, 0.1f,
                new FloatAccessor() {
                    @Override
                    public float get(RecoParams p) {
                        return p.recencyBias;
                    }

                    @Override
                    public void set(RecoParams p, float v) {
                        p.recencyBias = v;
                    }
                });
        addStepper(parent, R.string.reco_param_max_age_years, 1f, 20f, 1f,
                new IntAccessor() {
                    @Override
                    public float get(RecoParams p) {
                        return p.maxAgeYears;
                    }

                    @Override
                    public void set(RecoParams p, float v) {
                        p.maxAgeYears = Math.round(v);
                    }
                });
        addStepper(parent, R.string.reco_param_author_ratio, 0f, 1f, 0.05f,
                new RatioAccessor() {
                    @Override
                    public float get(RecoParams p) {
                        return p.authorRecallRatio;
                    }

                    @Override
                    public void set(RecoParams p, float v) {
                        p.authorRecallRatio = v;
                    }
                });
        addStepper(parent, R.string.reco_param_prefetch, 0f, 8f, 1f,
                new IntAccessor() {
                    @Override
                    public float get(RecoParams p) {
                        return p.prefetchAhead;
                    }

                    @Override
                    public void set(RecoParams p, float v) {
                        p.prefetchAhead = Math.round(v);
                    }
                });
        addDislikeFilterBox(parent);
    }

    private void buildAdvancedGroup(LinearLayout parent) {
        TextView hint = new TextView(context);
        hint.setText(R.string.reco_param_advanced_hint);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(Color.GRAY);
        hint.setPadding(0, 0, 0, dp(6));
        parent.addView(hint);

        addStepper(parent, R.string.reco_param_like_boost, 0f, 20f, 0.5f,
                new FloatAccessor() {
                    @Override
                    public float get(RecoParams p) {
                        return p.likeBoost;
                    }

                    @Override
                    public void set(RecoParams p, float v) {
                        p.likeBoost = v;
                    }
                });
        addStepper(parent, R.string.reco_param_dislike_penalty, 0f, 20f, 0.5f,
                new FloatAccessor() {
                    @Override
                    public float get(RecoParams p) {
                        return p.dislikePenalty;
                    }

                    @Override
                    public void set(RecoParams p, float v) {
                        p.dislikePenalty = v;
                    }
                });
        addStepper(parent, R.string.reco_param_favorite_boost, 0f, 20f, 0.5f,
                new FloatAccessor() {
                    @Override
                    public float get(RecoParams p) {
                        return p.favoriteBoost;
                    }

                    @Override
                    public void set(RecoParams p, float v) {
                        p.favoriteBoost = v;
                    }
                });
        addStepper(parent, R.string.reco_param_finish_boost, 0f, 10f, 0.5f,
                new FloatAccessor() {
                    @Override
                    public float get(RecoParams p) {
                        return p.finishBoost;
                    }

                    @Override
                    public void set(RecoParams p, float v) {
                        p.finishBoost = v;
                    }
                });
        addStepper(parent, R.string.reco_param_skip_penalty, 0f, 10f, 0.5f,
                new FloatAccessor() {
                    @Override
                    public float get(RecoParams p) {
                        return p.skipPenalty;
                    }

                    @Override
                    public void set(RecoParams p, float v) {
                        p.skipPenalty = v;
                    }
                });
        addStepper(parent, R.string.reco_param_tag_coef, 0f, 5f, 0.1f,
                new FloatAccessor() {
                    @Override
                    public float get(RecoParams p) {
                        return p.tagCoef;
                    }

                    @Override
                    public void set(RecoParams p, float v) {
                        p.tagCoef = v;
                    }
                });
        addStepper(parent, R.string.reco_param_category_coef, 0f, 5f, 0.1f,
                new FloatAccessor() {
                    @Override
                    public float get(RecoParams p) {
                        return p.categoryCoef;
                    }

                    @Override
                    public void set(RecoParams p, float v) {
                        p.categoryCoef = v;
                    }
                });
        addStepper(parent, R.string.reco_param_author_coef, 0f, 5f, 0.1f,
                new FloatAccessor() {
                    @Override
                    public float get(RecoParams p) {
                        return p.authorCoef;
                    }

                    @Override
                    public void set(RecoParams p, float v) {
                        p.authorCoef = v;
                    }
                });
        addStepper(parent, R.string.reco_param_decay_days, 1f, 365f, 5f,
                new IntAccessor() {
                    @Override
                    public float get(RecoParams p) {
                        return p.decayDays;
                    }

                    @Override
                    public void set(RecoParams p, float v) {
                        p.decayDays = Math.round(v);
                    }
                });
    }

    private void addDislikeFilterBox(LinearLayout parent) {
        final CheckBox filterBox = new CheckBox(context);
        filterBox.setText(R.string.reco_param_dislike_filter);
        filterBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        filterBox.setChecked(working.enableDislikeFilter);
        filterBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                working.enableDislikeFilter = isChecked;
            }
        });
        parent.addView(filterBox);
        syncFromParams.add(new Runnable() {
            @Override
            public void run() {
                filterBox.setChecked(working.enableDislikeFilter);
            }
        });
    }

    // ==================== 头部 ====================

    private void addProfileSummary(LinearLayout root) {
        RecoProfile profile = engine.getProfile();
        int actions = profile.likeCount + profile.dislikeCount
                + profile.favoriteCount + profile.watchCount;
        TextView tv = new TextView(context);
        tv.setText(context.getString(R.string.reco_profile_summary,
                profile.tagWeights.size(), profile.authorWeights.size(), actions));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(Color.GRAY);
        tv.setPadding(0, 0, 0, dp(6));
        root.addView(tv);
    }

    /** 「查看学习记录」入口 */
    private void addRecordsEntry(LinearLayout root) {
        TextView tv = new TextView(context);
        tv.setText(R.string.reco_records);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(COLOR_SECTION);
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        tv.setBackground(outlineBackground(COLOR_SECTION));
        tv.setGravity(Gravity.CENTER);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new RecoRecordsDialog(context, engine, dataManager).show();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        root.addView(tv, lp);
    }

    private void addSectionTitle(LinearLayout root, String title, View.OnClickListener click) {
        TextView tv = new TextView(context);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(COLOR_SECTION);
        tv.setPadding(0, dp(12), 0, dp(4));
        if (click != null) {
            tv.setOnClickListener(click);
        }
        root.addView(tv);

        View line = new View(context);
        line.setBackgroundColor(COLOR_LINE);
        root.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
    }

    /** 可折叠的「高级设置」标题栏，点一下切换展开 / 收起 */
    private void addCollapsibleHeader(LinearLayout root, final LinearLayout target) {
        final TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(COLOR_SECTION);
        tv.setPadding(0, dp(14), 0, dp(6));
        tv.setText("▸  " + context.getString(R.string.reco_param_advanced));
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean expand = target.getVisibility() != View.VISIBLE;
                target.setVisibility(expand ? View.VISIBLE : View.GONE);
                tv.setText((expand ? "▾  " : "▸  ")
                        + context.getString(R.string.reco_param_advanced));
            }
        });
        root.addView(tv);

        View line = new View(context);
        line.setBackgroundColor(COLOR_LINE);
        root.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
    }

    // ==================== 步进器 ====================

    /**
     * 添加一行「标签 ─ [－] 数值 [＋]」。
     *
     * @param min      下限
     * @param max      上限
     * @param step     单次步长
     * @param accessor 字段读写器
     */
    private void addStepper(LinearLayout root, int labelRes, final float min, final float max,
                            final float step, final Accessor accessor) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));

        TextView label = new TextView(context);
        label.setText(labelRes);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView minus = makeStepButton("−");
        row.addView(minus, new LinearLayout.LayoutParams(dp(38), dp(30)));

        final TextView value = new TextView(context);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        value.setGravity(Gravity.CENTER);
        row.addView(value, new LinearLayout.LayoutParams(dp(56),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView plus = makeStepButton("+");
        row.addView(plus, new LinearLayout.LayoutParams(dp(38), dp(30)));

        root.addView(row);

        final Runnable sync = new Runnable() {
            @Override
            public void run() {
                value.setText(accessor.format(accessor.get(working)));
            }
        };
        sync.run();
        syncFromParams.add(sync);

        attachRepeat(minus, new Runnable() {
            @Override
            public void run() {
                applyStep(accessor, -step, min, max);
                sync.run();
            }
        });
        attachRepeat(plus, new Runnable() {
            @Override
            public void run() {
                applyStep(accessor, step, min, max);
                sync.run();
            }
        });
    }

    private void applyStep(Accessor accessor, float delta, float min, float max) {
        float next = accessor.get(working) + delta;
        // 步长是小数时会累积浮点误差，按步长对齐一下
        if (delta != 0f) {
            float unit = Math.abs(delta);
            next = Math.round(next / unit) * unit;
        }
        if (next < min) {
            next = min;
        } else if (next > max) {
            next = max;
        }
        accessor.set(working, next);
    }

    /** 点一下走一步；按住不放连续走 */
    private void attachRepeat(View view, final Runnable action) {
        view.setOnTouchListener(new View.OnTouchListener() {
            private Runnable repeater;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        action.run();
                        repeater = new Runnable() {
                            @Override
                            public void run() {
                                action.run();
                                handler.postDelayed(this, REPEAT_INTERVAL);
                            }
                        };
                        handler.postDelayed(repeater, REPEAT_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        if (repeater != null) {
                            handler.removeCallbacks(repeater);
                            repeater = null;
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private TextView makeStepButton(String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(0xFF444444);
        tv.setBackground(outlineBackground(0xFFBDBDBD));
        tv.setClickable(true);
        return tv;
    }

    private GradientDrawable outlineBackground(int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(4));
        d.setStroke(Math.max(1, dp(1)), strokeColor);
        d.setColor(Color.TRANSPARENT);
        return d;
    }

    private int dp(int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
