package com.m3man.ui.recommend;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.m3man.R;
import com.m3man.utils.PlayUiPrefs;

/**
 * 推荐筛选面板（spec EX-03）。
 * <p>
 * 打开时把「已应用值」复制到草稿；面板内每次选择只改草稿；
 * Back / 遮罩 / 关闭丢弃草稿；「应用」一次把草稿交给宿主提交；
 * 「重置草稿」只把草稿恢复为全部方向 / 时长不限 / 循环当前，不提交。
 */
final class RecoFilterSheet {

    interface Listener {
        void onApply(int orientation, int maxDurationMinutes, boolean autoplay);
    }

    /** 与设置页/DataManager 保持一致的时长枚举（不新增 20/30）。 */
    private static final int[] DURATION_VALUES = {0, 1, 2, 3, 5, 10};
    private static final int[] DURATION_CHIP_IDS = {
            R.id.chip_reco_duration_0, R.id.chip_reco_duration_1,
            R.id.chip_reco_duration_2, R.id.chip_reco_duration_3,
            R.id.chip_reco_duration_5, R.id.chip_reco_duration_10
    };

    private RecoFilterSheet() {
    }

    static void show(Context context, int appliedOrientation, int appliedMinutes,
                     boolean appliedAutoplay, final Listener listener) {
        if (context == null) {
            return;
        }
        final BottomSheetDialog dialog = new BottomSheetDialog(context, R.style.RecoFilterSheetTheme);
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_reco_filter, null);
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);

        final RecoFilterState[] draft = {
                new RecoFilterState(appliedOrientation, appliedMinutes, appliedAutoplay)
        };

        final TextView orientAll = content.findViewById(R.id.chip_reco_orientation_all);
        final TextView orientPortrait = content.findViewById(R.id.chip_reco_orientation_portrait);
        final TextView orientLandscape = content.findViewById(R.id.chip_reco_orientation_landscape);
        final TextView autoOff = content.findViewById(R.id.chip_reco_autoplay_off);
        final TextView autoOn = content.findViewById(R.id.chip_reco_autoplay_on);

        final Runnable render = () -> {
            orientAll.setSelected(draft[0].orientation == PlayUiPrefs.FILTER_ALL);
            orientPortrait.setSelected(draft[0].orientation == PlayUiPrefs.FILTER_PORTRAIT);
            orientLandscape.setSelected(draft[0].orientation == PlayUiPrefs.FILTER_LANDSCAPE);
            for (int i = 0; i < DURATION_VALUES.length; i++) {
                TextView chip = content.findViewById(DURATION_CHIP_IDS[i]);
                if (chip != null) {
                    chip.setSelected(draft[0].maxDurationMinutes == DURATION_VALUES[i]);
                }
            }
            autoOff.setSelected(!draft[0].autoplay);
            autoOn.setSelected(draft[0].autoplay);
        };
        render.run();

        orientAll.setOnClickListener(v -> {
            draft[0] = draft[0].withOrientation(PlayUiPrefs.FILTER_ALL);
            render.run();
        });
        orientPortrait.setOnClickListener(v -> {
            draft[0] = draft[0].withOrientation(PlayUiPrefs.FILTER_PORTRAIT);
            render.run();
        });
        orientLandscape.setOnClickListener(v -> {
            draft[0] = draft[0].withOrientation(PlayUiPrefs.FILTER_LANDSCAPE);
            render.run();
        });
        for (int i = 0; i < DURATION_VALUES.length; i++) {
            final int minutes = DURATION_VALUES[i];
            TextView chip = content.findViewById(DURATION_CHIP_IDS[i]);
            if (chip != null) {
                chip.setOnClickListener(v -> {
                    draft[0] = draft[0].withMaxDurationMinutes(minutes);
                    render.run();
                });
            }
        }
        autoOff.setOnClickListener(v -> {
            draft[0] = draft[0].withAutoplay(false);
            render.run();
        });
        autoOn.setOnClickListener(v -> {
            draft[0] = draft[0].withAutoplay(true);
            render.run();
        });

        ImageView close = content.findViewById(R.id.iv_reco_filter_close);
        close.setOnClickListener(v -> dialog.dismiss());

        content.findViewById(R.id.btn_reco_filter_reset).setOnClickListener(v -> {
            // 只重置草稿，不提交、不持久化。
            draft[0] = draft[0].resetDraft();
            render.run();
        });

        content.findViewById(R.id.btn_reco_filter_apply).setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) {
                listener.onApply(draft[0].orientation, draft[0].maxDurationMinutes,
                        draft[0].autoplay);
            }
        });

        dialog.setOnShowListener(d -> {
            FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) {
                return;
            }
            sheet.setBackgroundColor(Color.TRANSPARENT);
            ViewGroup.LayoutParams lp = sheet.getLayoutParams();
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            sheet.setLayoutParams(lp);
            BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
            behavior.setHideable(true);
        });
        dialog.show();
    }
}
