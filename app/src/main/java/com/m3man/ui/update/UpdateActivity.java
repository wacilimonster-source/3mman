package com.m3man.ui.update;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.support.annotation.Nullable;
import android.support.v4.content.FileProvider;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.liulishuo.filedownloader.BaseDownloadTask;
import com.liulishuo.filedownloader.FileDownloadListener;
import com.liulishuo.filedownloader.FileDownloader;
import com.orhanobut.logger.Logger;
import com.m3man.BuildConfig;
import com.m3man.R;
import com.m3man.data.model.UpdateVersion;
import com.m3man.ui.BaseAppCompatActivity;
import com.m3man.utils.AppLog;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * 前台更新界面：下载进度、速度、成功/失败提示与安装权限引导都在此可见，
 * 解决原 UpdateDownloadService 在 Android 8.0+ 因「安装未知应用」权限未开启而静默不安装的问题。
 */
public class UpdateActivity extends BaseAppCompatActivity implements View.OnClickListener {

    private static final String TAG = "UpdateActivity";
    private static final int REQ_INSTALL_PERMISSION = 1001;

    private enum State { PREPARE, DOWNLOADING, VERIFYING, INSTALLING, SUCCESS, ERROR, NEED_PERMISSION }

    private UpdateVersion updateVersion;
    private TextView tvTitle;
    private TextView tvMessage;
    private ProgressBar pb;
    private TextView tvPercent;
    private TextView tvStatus;
    private Button btnPermission;
    private Button btnRetry;
    private Button btnLater;

    private String apkPath;
    private int downloadId = 0;
    private volatile boolean released = false;
    private State state = State.PREPARE;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update);

        updateVersion = (UpdateVersion) getIntent().getSerializableExtra("updateVersion");
        if (updateVersion == null || TextUtils.isEmpty(updateVersion.getApkDownloadUrl())) {
            showMessage("更新信息无效", TastyToast_ERROR());
            finish();
            return;
        }

        tvTitle = findViewById(R.id.tv_update_title);
        tvMessage = findViewById(R.id.tv_update_message);
        pb = findViewById(R.id.pb_update);
        tvPercent = findViewById(R.id.tv_update_percent);
        tvStatus = findViewById(R.id.tv_update_status);
        btnPermission = findViewById(R.id.btn_update_permission);
        btnRetry = findViewById(R.id.btn_update_retry);
        btnLater = findViewById(R.id.btn_update_later);

        tvTitle.setText("正在更新到 v" + updateVersion.getVersionName());
        tvMessage.setText(TextUtils.isEmpty(updateVersion.getUpdateMessage())
                ? "正在为你下载最新安装包…" : updateVersion.getUpdateMessage());

        btnPermission.setOnClickListener(this);
        btnRetry.setOnClickListener(this);
        btnLater.setOnClickListener(this);

        // 应用私有下载目录（与 filepaths.xml 中的 external-files-path 对应）
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            dir = getFilesDir();
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        apkPath = dir.getAbsolutePath() + "/3mman_" + updateVersion.getVersionName() + ".apk";
        AppLog.i(TAG, "更新下载开始 url=" + updateVersion.getApkDownloadUrl()
                + " 版本=v" + updateVersion.getVersionName());

        // 进入界面先检查安装权限（Android 8.0+ 默认关闭），未开启则引导，不再静默失败
        if (needInstallPermission() && !canInstall()) {
            setState(State.NEED_PERMISSION);
        } else {
            startDownload();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从「设置-安装未知应用」返回后重新判断：若已授权则继续下载/安装
        if (state == State.NEED_PERMISSION && canInstall()) {
            startDownload();
        }
    }

    @Override
    protected void onDestroy() {
        released = true;
        // 只暂停本页发起的更新下载（downloadId>0 时才暂停），
        // 不能用 pauseAll()——那会把用户正在下载的视频也一并暂停
        if (downloadId > 0) {
            try {
                FileDownloader.getImpl().pause(downloadId);
            } catch (Throwable ignore) {
            }
        }
        super.onDestroy();
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_update_later) {
            finish();
        } else if (id == R.id.btn_update_retry) {
            startDownload();
        } else if (id == R.id.btn_update_permission) {
            openInstallPermissionSettings();
        }
    }

    // ===================== 下载 =====================

    private void startDownload() {
        if (released) {
            return;
        }
        // 若已有文件（上次未完成），删除重下，避免脏数据
        File f = new File(apkPath);
        if (f.exists() && f.length() == 0) {
            f.delete();
        }
        setState(State.DOWNLOADING);
        pb.setProgress(0);
        tvPercent.setText("0%");
        tvStatus.setText("准备下载…");
        btnRetry.setVisibility(View.GONE);
        btnPermission.setVisibility(View.GONE);

        downloadId = FileDownloader.getImpl().create(updateVersion.getApkDownloadUrl())
                .setPath(apkPath)
                .setListener(new FileDownloadListener() {
                    @Override
                    protected void pending(BaseDownloadTask task, int soFarBytes, int totalBytes) {
                        updateProgress(task, soFarBytes, totalBytes);
                    }

                    @Override
                    protected void progress(BaseDownloadTask task, int soFarBytes, int totalBytes) {
                        updateProgress(task, soFarBytes, totalBytes);
                    }

                    @Override
                    protected void completed(BaseDownloadTask task) {
                        if (released) {
                            return;
                        }
                        AppLog.i(TAG, "更新包下载完成 size=" + task.getSmallFileSoFarBytes() + " 路径=" + apkPath);
                        runOnUiThread(() -> onDownloadCompleted());
                    }

                    @Override
                    protected void paused(BaseDownloadTask task, int soFarBytes, int totalBytes) {
                        if (released) {
                            return;
                        }
                        runOnUiThread(() -> {
                            tvStatus.setText("已暂停");
                        });
                    }

                    @Override
                    protected void error(BaseDownloadTask task, Throwable e) {
                        if (released) {
                            return;
                        }
                        final String msg = e != null ? e.getMessage() : "未知错误";
                        Logger.t(TAG).e("download error: " + msg);
                        AppLog.e(TAG, "更新包下载失败 url=" + updateVersion.getApkDownloadUrl()
                                + " err=" + AppLog.cause(e));
                        runOnUiThread(() -> onDownloadError("下载失败：" + msg));
                    }

                    @Override
                    protected void warn(BaseDownloadTask task) {
                        // 文件已存在/可复用等告警，忽略
                    }
                })
                .setWifiRequired(false)
                .start();
    }

    private void updateProgress(BaseDownloadTask task, int soFarBytes, int totalBytes) {
        if (released || state != State.DOWNLOADING) {
            return;
        }
        final int percent = totalBytes > 0 ? (int) ((float) soFarBytes / totalBytes * 100) : 0;
        final String speed = Formatter.formatFileSize(this, task.getSpeed()) + "/s";
        final String size = Formatter.formatFileSize(this, soFarBytes) + "/" + Formatter.formatFileSize(this, totalBytes);
        runOnUiThread(() -> {
            pb.setProgress(percent);
            tvPercent.setText(percent + "%");
            tvStatus.setText("下载中 " + speed + "  " + size);
        });
    }

    private void onDownloadCompleted() {
        setState(State.VERIFYING);
        tvStatus.setText("校验安装包…");
        pb.setProgress(100);
        tvPercent.setText("100%");

        // 完整性校验（version.txt 提供 sha256 才校验）；7MB 文件计算耗时，放后台线程避免主线程卡顿
        final String expected = updateVersion.getSha256();
        final File apkFile = new File(apkPath);
        if (TextUtils.isEmpty(expected)) {
            proceedInstall(apkFile);
            return;
        }
        new Thread(() -> {
            final boolean ok = verifySha256(apkFile, expected);
            runOnUiThread(() -> {
                if (released) {
                    return;
                }
                if (!ok) {
                    onDownloadError("安装包校验失败，可能已被篡改，请重试");
                    return;
                }
                proceedInstall(apkFile);
            });
        }).start();
    }

    /** 校验通过后：再次确认安装权限并拉起安装 */
    private void proceedInstall(File apkFile) {
        if (needInstallPermission() && !canInstall()) {
            setState(State.NEED_PERMISSION);
            return;
        }
        installApk(apkFile);
    }

    private void onDownloadError(String msg) {
        setState(State.ERROR);
        tvStatus.setText(msg);
        pb.setProgress(0);
        tvPercent.setText("失败");
        btnRetry.setVisibility(View.VISIBLE);
        AppLog.e(TAG, "更新失败: " + msg);
        showMessage(msg, TastyToast_ERROR());
    }

    // ===================== 安装 =====================

    private void installApk(File file) {
        if (file == null || !file.exists()) {
            onDownloadError("安装包不存在，请重试");
            return;
        }
        setState(State.INSTALLING);
        tvStatus.setText("正在启动安装…");

        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(getApplicationContext(),
                    BuildConfig.APPLICATION_ID + ".fileprovider", file);
        } else {
            uri = Uri.fromFile(file);
        }

        // 优先用 ACTION_INSTALL_PACKAGE（Android 专用安装意图）
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        try {
            startActivity(intent);
            showMessage("已启动安装，请按系统提示完成", TastyToast_SUCCESS());
            // 安装界面会接管前台，稍后关闭本页
            finish();
        } catch (Throwable e1) {
            // 回退：通用 ACTION_VIEW
            try {
                Intent v = new Intent(Intent.ACTION_VIEW);
                v.setDataAndType(uri, "application/vnd.android.package-archive");
                v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                v.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(v);
                showMessage("已启动安装，请按系统提示完成", TastyToast_SUCCESS());
                finish();
            } catch (Throwable e2) {
                onDownloadError("无法启动安装程序：" + e2.getMessage());
            }
        }
    }

    // ===================== 权限 =====================

    private boolean needInstallPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    private boolean canInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    private void openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                // 必须携带 package: 数据，否则 API 26+ 会抛 ActivityNotFoundException
                intent.setData(Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivityForResult(intent, REQ_INSTALL_PERMISSION);
            } catch (Throwable e) {
                // 极少数机型无此页面，兜底打开应用设置
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQ_INSTALL_PERMISSION);
                } catch (Throwable e2) {
                    showMessage("请手动在系统设置中开启「安装未知应用」", TastyToast_ERROR());
                }
            }
        }
    }

    // ===================== 工具 =====================

    private void setState(State s) {
        this.state = s;
        runOnUiThread(() -> {
            switch (s) {
                case NEED_PERMISSION:
                    tvStatus.setText("需要开启「安装未知应用」权限后才能安装");
                    btnPermission.setVisibility(View.VISIBLE);
                    btnRetry.setVisibility(View.GONE);
                    break;
                case ERROR:
                    // 由 onDownloadError 处理
                    break;
                default:
                    btnPermission.setVisibility(View.GONE);
                    break;
            }
        });
    }

    private static boolean verifySha256(File file, String expectedHex) {
        if (file == null || !file.exists()) {
            return false;
        }
        // 非标准 64 位十六进制（缺失/占位符/异常字段）视为无校验，避免更新被字段问题卡死
        if (expectedHex == null || !expectedHex.matches("[0-9a-fA-F]{64}")) {
            return true;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1) {
                    digest.update(buf, 0, len);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().equalsIgnoreCase(expectedHex);
        } catch (Exception e) {
            return false;
        }
    }

    // TastyToast 类型常量（避免额外 import 顺序问题）
    private static int TastyToast_ERROR() {
        return com.sdsmdg.tastytoast.TastyToast.ERROR;
    }

    private static int TastyToast_SUCCESS() {
        return com.sdsmdg.tastytoast.TastyToast.SUCCESS;
    }
}
