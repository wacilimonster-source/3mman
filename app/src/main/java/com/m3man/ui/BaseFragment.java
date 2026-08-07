package com.m3man.ui;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;

import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.R;
import com.m3man.constants.Keys;
import com.m3man.data.db.entity.Category;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.download.DownloadActivity;
import com.m3man.ui.main.MainActivity;
import com.m3man.ui.mman9video.play.BasePlayVideo;
import com.m3man.utils.PlaybackEngine;

import dagger.android.support.DaggerFragment;

/**
 * @author flymegoc
 * @date 2017/11/20
 * @describe
 */

public abstract class BaseFragment extends DaggerFragment {
    private final String TAG = getClass().getSimpleName();
    private final String KEY_SAVE_DIN_STANCE_STATE_CATEGORY = "key_save_din_stance_state_category";

    protected Context context;
    protected Activity activity;
    protected Category category;
    protected boolean mIsLoadedData;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        this.context = getContext();
        activity = getActivity();
    }

    @Override
    public void onDetach() {
        super.onDetach();
        // L2：分离时清空 activity/context 引用，避免持有已销毁 Activity 导致泄漏
        context = null;
        activity = null;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            category = (Category) savedInstanceState.getSerializable(KEY_SAVE_DIN_STANCE_STATE_CATEGORY);
        }

    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable(KEY_SAVE_DIN_STANCE_STATE_CATEGORY, category);
    }


    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (isResumed()) {
            handleOnVisibilityChangedToUser(isVisibleToUser);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getUserVisibleHint()) {
            handleOnVisibilityChangedToUser(true);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getUserVisibleHint()) {
            handleOnVisibilityChangedToUser(false);
        }
    }

    /**
     * 处理对用户是否可见
     *
     * @param isVisibleToUser 可见
     */
    private void handleOnVisibilityChangedToUser(boolean isVisibleToUser) {
        if (isVisibleToUser) {
            // 对用户可见
            if (!mIsLoadedData) {
                mIsLoadedData = true;
                onLazyLoadOnce();
            }
            onVisibleToUser();
        } else {
            // 对用户不可见
            onInvisibleToUser();
        }
    }

    /**
     * 懒加载一次。如果只想在对用户可见时才加载数据，并且只加载一次数据，在子类中重写该方法
     */
    protected void onLazyLoadOnce() {
    }

    /**
     * 对用户可见时触发该方法。如果只想在对用户可见时才加载数据，在子类中重写该方法
     */
    protected void onVisibleToUser() {
    }

    /**
     * 对用户不可见时触发该方法
     */
    protected void onInvisibleToUser() {
    }

    public String getTitle() {
        return "";
    }

    /**
     * 带动画的启动activity
     */
    public void startActivityWithAnimation(Intent intent) {
        startActivity(intent);
        playAnimation();
    }

    /**
     * 带动画的启动activity
     */
    public void startActivityForResultWithAnimation(Intent intent, int requestCode) {
        startActivityForResult(intent, requestCode);
        playAnimation();
    }

    private void playAnimation() {
        if (activity != null) {
            activity.overridePendingTransition(R.anim.slide_in_right, R.anim.side_out_left);
        }
    }

    protected void goToPlayVideo(V9MmanItem v9MmanItem, int playBackEngine, int skipPage, int position) {
        Intent intent = PlaybackEngine.getPlaybackEngineIntent(getContext(), playBackEngine);
        intent.putExtra(Keys.KEY_INTENT_V9MMAN_ITEM, v9MmanItem);
        intent.putExtra(Keys.KEY_INTENT_CATEGORY_ITEM, category);
        intent.putExtra(Keys.KEY_INTENT_SKIP_PAGE, skipPage);
        intent.putExtra(Keys.KEY_INTENT_SCROLL_TO_POSITION, position);
        if (activity instanceof MainActivity || activity instanceof DownloadActivity) {
            startActivity(intent);
            activity.overridePendingTransition(R.anim.slide_in_right, R.anim.side_out_left);
        } else if (activity instanceof BasePlayVideo) {
            BasePlayVideo basePlayVideo = (BasePlayVideo) activity;
            basePlayVideo.setV9MmanItems(v9MmanItem);
            basePlayVideo.initData();
        } else {
            showMessage("无法获取宿主Activity", TastyToast.INFO);
        }
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }


    protected void showMessage(String msg, int type) {
        TastyToast.makeText(context.getApplicationContext(), msg, TastyToast.LENGTH_SHORT, type).show();
    }

    /**
     * 展示一个可以选择的dialog
     *
     * @param msg    title
     * @param checks 可以选择的条目
     * @param check  回调
     */
    protected void showDialog(String msg, String[] checks, @NonNull final DialogCheck check) {
        final QMUIDialog.CheckableDialogBuilder builder = new QMUIDialog.CheckableDialogBuilder(this.getContext());
        QMUIDialog dialog;
        builder.setTitle(msg);
        builder.addItems(checks, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                check.onCheck(builder.getCheckedIndex());
                dialog.dismiss();
            }
        });
        dialog = builder.create();
        dialog.show();
    }

    protected interface DialogCheck {
        void onCheck(int index);
    }
}
