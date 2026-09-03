package com.m3man.ui.about;

import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import android.view.View;
import android.widget.TextView;

import com.orhanobut.logger.Logger;
import com.qmuiteam.qmui.util.QMUIPackageHelper;
import com.qmuiteam.qmui.widget.QMUILoadingView;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.qmuiteam.qmui.widget.grouplist.QMUICommonListItemView;
import com.qmuiteam.qmui.widget.grouplist.QMUIGroupListView;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.R;
import com.m3man.data.model.UpdateVersion;
import com.m3man.ui.MvpActivity;
import com.m3man.ui.update.UpdateActivity;
import com.m3man.utils.ApkVersionUtils;
import com.m3man.utils.AppCacheUtils;
import com.m3man.utils.AppLog;
import com.m3man.utils.DialogUtils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import ru.noties.markwon.Markwon;

/**
 * @author flymegoc
 */
public class AboutActivity extends MvpActivity<AboutView, AboutPresenter> implements AboutView {

    private static final String TAG = AboutActivity.class.getSimpleName();
    @BindView(R.id.toolbar)
    Toolbar toolbar;

    @BindView(R.id.version)
    TextView mVersionTextView;
    @BindView(R.id.about_list)
    QMUIGroupListView mAboutGroupListView;
    @BindView(R.id.copyright)
    TextView mCopyrightTextView;

    private AlertDialog alertDialog;
    private AlertDialog cleanCacheDialog;

    private QMUICommonListItemView cleanCacheQMUICommonListItemView;

    @Inject
    protected AboutPresenter aboutPresenter;
    private TextView commonQuestionTextView;

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        ButterKnife.bind(this);

        initToolBar(toolbar);

        initAboutSection();

        mVersionTextView.setText("v" + QMUIPackageHelper.getAppVersion(this));

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy", Locale.CHINA);
        String currentYear = dateFormat.format(new java.util.Date());
        mCopyrightTextView.setText(String.format(getResources().getString(R.string.about_copyright), currentYear));

        alertDialog = DialogUtils.initLoadingDialog(this, getString(R.string.about_checking_update_loading));
        presenter.countCacheFileSize(getString(R.string.about_item_clean_cache));
    }

    private void initAboutSection() {
        mAboutGroupListView.setSeparatorStyle(QMUIGroupListView.SEPARATOR_STYLE_NORMAL);
        cleanCacheQMUICommonListItemView = mAboutGroupListView.createItemView(getString(R.string.about_item_clean_cache));
        cleanCacheQMUICommonListItemView.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_CUSTOM);
        QMUILoadingView loadingView = new QMUILoadingView(this);
        cleanCacheQMUICommonListItemView.addAccessoryCustomView(loadingView);

        QMUIGroupListView.newSection(this)

                .addItemView(cleanCacheQMUICommonListItemView, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showChoiceCacheCleanDialog();
                    }
                })
                .addItemView(mAboutGroupListView.createItemView(getString(R.string.about_common_questions)), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showCommonQuestionsDialog();
                    }
                })
                .addItemView(mAboutGroupListView.createItemView(getResources().getString(R.string.about_check_update)), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        int versionCode = ApkVersionUtils.getVersionCode(AboutActivity.this);
                        if (versionCode == 0) {
                            showMessage(getString(R.string.about_get_version_failed), TastyToast.ERROR);
                            return;
                        }
                        alertDialog.show();
                        presenter.checkUpdate(versionCode);
                    }
                })
                .addTo(mAboutGroupListView);

    }

    private void showCommonQuestionsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.MyDialogTheme);
        builder.setTitle(getString(R.string.about_common_questions));
        View view = View.inflate(this, R.layout.layout_common_questions, null);
        commonQuestionTextView = view.findViewById(R.id.tv_common_question);
        Markwon.setMarkdown(commonQuestionTextView, getString(R.string.about_loading_md));
        builder.setView(view);
        builder.setCancelable(false);
        builder.setPositiveButton(getString(R.string.about_got_it), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                commonQuestionTextView=null;
                dialog.dismiss();
            }
        });
        builder.show();
        presenter.commonQuestions();
    }

    private void showChoiceCacheCleanDialog() {
        final String[] items = new String[]{
                getString(R.string.about_cache_web, AppCacheUtils.getRxcacheFileSizeStr(this)),
                getString(R.string.about_cache_video, AppCacheUtils.getVideoCacheFileSizeStr(this)),
                getString(R.string.about_cache_image, AppCacheUtils.getGlidecacheFileSizeStr(this))
        };
        final QMUIDialog.MultiCheckableDialogBuilder builder = new QMUIDialog.MultiCheckableDialogBuilder(this)
                .setCheckedItems(new int[]{1})
                .addItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {

                    }
                });
        builder.setTitle(getString(R.string.about_choose_cache_title));
        builder.addAction(getString(R.string.common_cancel), new QMUIDialogAction.ActionListener() {
            @Override
            public void onClick(QMUIDialog dialog, int index) {
                dialog.dismiss();
            }
        });
        builder.addAction(getString(R.string.about_clear), new QMUIDialogAction.ActionListener() {
            @Override
            public void onClick(QMUIDialog dialog, int index) {
                actionCleanFile(builder);
                dialog.dismiss();
            }
        });
        builder.show();
    }

    private void actionCleanFile(QMUIDialog.MultiCheckableDialogBuilder builder) {
        int selectIndexLength = builder.getCheckedItemIndexes().length;
        List<File> fileDirList = new ArrayList<>();
        for (int i = 0; i < selectIndexLength; i++) {
            int indexCheck = builder.getCheckedItemIndexes()[i];
            switch (indexCheck) {
                case 0:
                    fileDirList.add(AppCacheUtils.getRxCacheDir(AboutActivity.this));
                    break;
                case 1:
                    fileDirList.add(AppCacheUtils.getVideoCacheDir(AboutActivity.this));
                    break;
                case 2:
                    fileDirList.add(AppCacheUtils.getGlideDiskCacheDir(AboutActivity.this));
                default:
            }
        }
        if (fileDirList.size() == 0) {
            showMessage(getString(R.string.about_no_cache_selected), TastyToast.INFO);
            return;
        }
        presenter.cleanCacheFile(fileDirList);
    }

    private String getCleanCacheTitle() {
        String zeroFileSize = "0 B";
        String fileSizeStr = AppCacheUtils.getAllCacheFileSizeStr(this);
        if (zeroFileSize.equals(fileSizeStr)) {
            return getResources().getString(R.string.about_item_clean_cache);
        }
        return getResources().getString(R.string.about_item_clean_cache) + "(" + fileSizeStr + ")";
    }

    @NonNull
    @Override
    public AboutPresenter createPresenter() {
        return aboutPresenter;
    }

    private void showUpdateDialog(final UpdateVersion updateVersion) {
        new QMUIDialog.MessageDialogBuilder(this)
                .setTitle(getString(R.string.about_found_new_version, updateVersion.getVersionName()))
                .setMessage(updateVersion.getUpdateMessage())
                .addAction(getString(R.string.about_update_now), new QMUIDialogAction.ActionListener() {
                    @Override
                    public void onClick(QMUIDialog dialog, int index) {
                        dialog.dismiss();
                        Intent intent = new Intent(AboutActivity.this, UpdateActivity.class);
                        intent.putExtra("updateVersion", updateVersion);
                        startActivityWithAnimation(intent);
                    }
                })
                .addAction(getString(R.string.about_update_later), new QMUIDialogAction.ActionListener() {
                    @Override
                    public void onClick(QMUIDialog dialog, int index) {
                        dialog.dismiss();
                    }
                })
                .show();
    }

    @Override
    public void needUpdate(UpdateVersion updateVersion) {
        AppLog.i("UpdateCheck", "[关于页]发现新版本 v" + updateVersion.getVersionName()
                + " url=" + updateVersion.getApkDownloadUrl());
        showUpdateDialog(updateVersion);
    }

    @Override
    public void noNeedUpdate() {
        AppLog.i("UpdateCheck", "[关于页]当前已是最新版本");
        showMessage(getString(R.string.about_already_latest), TastyToast.SUCCESS);
    }

    @Override
    public void checkUpdateError(String message) {
        AppLog.e("UpdateCheck", "[关于页]检查更新失败: " + message);
        showMessage(message, TastyToast.ERROR);
    }

    @Override
    public void showLoading(boolean pullToRefresh) {
        alertDialog.show();
    }

    @Override
    public void showContent() {
        dismissDialog();
    }

    @Override
    public void showMessage(String msg, int type) {
        super.showMessage(msg, type);
    }

    @Override
    public void showError(String message) {
        showMessage(message, TastyToast.ERROR);
    }

    @Override
    public void showCleanDialog(String message) {
        cleanCacheDialog = DialogUtils.initLoadingDialog(this, message);
        cleanCacheDialog.show();
    }

    @Override
    public void cleanCacheSuccess(String message) {
        dismissDialog();
        cleanCacheQMUICommonListItemView.setText(getCleanCacheTitle());
        showMessage(message, TastyToast.SUCCESS);
    }

    @Override
    public void cleanCacheFailure(String message) {
        dismissDialog();
        showMessage(message, TastyToast.ERROR);
    }

    @Override
    public void finishCountCacheFileSize(String message) {
        cleanCacheQMUICommonListItemView.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_NONE);
        cleanCacheQMUICommonListItemView.setText(message);
    }

    @Override
    public void countCacheFileSizeError(String message) {
        showMessage(message, TastyToast.ERROR);
    }

    @Override
    public void loadCommonQuestionsSuccess(String mdString) {
        Logger.t(TAG).d(mdString);
        if (commonQuestionTextView != null) {
            Markwon.setMarkdown(commonQuestionTextView, mdString);
        }
    }

    @Override
    public void loadCommonQuestionsFailure(String errorMessage, int code) {
        showError(errorMessage);
    }

    private void dismissDialog() {
        // M97：两个 loading dialog 各自独立判断关闭——旧写法用 else-if，
        // alertDialog 在展示时 cleanCacheDialog 即使在展示也永远关不掉
        if (alertDialog != null && alertDialog.isShowing() && !isFinishing()) {
            alertDialog.dismiss();
        }
        if (cleanCacheDialog != null && cleanCacheDialog.isShowing() && !isFinishing()) {
            cleanCacheDialog.dismiss();
        }
    }
}
