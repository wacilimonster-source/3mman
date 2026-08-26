package com.m3man.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 基于 tess-two 的离线验证码识别工具（无需 GMS / ML Kit，适用于无 Play 服务的模拟器）。
 * <p>
 * 训练数据(eng.traineddata, ~22MB)不再打包进 APK，改为首次需要识别验证码时按需下载，
 * 下载后缓存到应用私有目录，后续直接使用。这样既大幅缩减安装包体积，
 * 又保证登录验证码自动识别功能可用；下载失败则优雅降级为手动输入。
 */
public class CaptchaOcr {

    private static final String TAG = "CaptchaOcr";

    private static final String TESS_DIR = "tessdata";
    private static final String LANG = "eng";
    // 训练数据托管地址（与 App 更新同源：GitHub raw）
    private static final String TRAINEDDATA_URL =
            "https://raw.githubusercontent.com/wacilimonster-source/3mman/master/tessdata/eng.traineddata";

    /**
     * M94：训练数据完整性指纹——2026-08 版本指纹（对仓库 tessdata/eng.traineddata
     * 实测 SHA-256，23466654 字节）。下载完成后除大小校验外必须比对，
     * 防止 CDN 劫持/截断/错误页内容被当作模型文件。
     */
    private static final String EXPECTED_SHA256 =
            "daa0c97d651c19fba3b25e81317cd697e9908c8208090c94c3905381c23fc047";

    private final Context context;
    private TessBaseAPI tessBaseAPI;
    private boolean engineReady = false;

    public interface PrepareCallback {
        /** 下载进度 0~100；非下载阶段(如初始化)不会回调 */
        void onProgress(int percent);

        /** 准备完成：success=true 表示引擎可用 */
        void onPrepared(boolean success);
    }

    public CaptchaOcr(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 本地是否已缓存训练数据 */
    public boolean isDataReady() {
        File data = trainedDataFile();
        return data.exists() && data.length() > 0;
    }

    private File tessDir() {
        return new File(context.getFilesDir(), TESS_DIR);
    }

    private File trainedDataFile() {
        return new File(tessDir(), LANG + ".traineddata");
    }

    /**
     * 准备 OCR 引擎：若本地缺训练数据则先下载(建议子线程调用)，随后初始化引擎。
     * 通过 callback 上报进度与结果。
     * M94：加 synchronized 防止并发调用导致重复下载/双初始化。
     */
    public synchronized void prepare(final PrepareCallback callback) {
        if (engineReady) {
            callback.onPrepared(true);
            return;
        }
        // 1) 确保训练数据存在（缺失则下载）
        if (!isDataReady()) {
            boolean downloaded = downloadTrainedData(callback);
            if (!downloaded) {
                callback.onPrepared(false);
                return;
            }
        }
        // 2) 初始化引擎
        callback.onPrepared(initEngine());
    }

    private boolean downloadTrainedData(final PrepareCallback callback) {
        File dir = tessDir();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File tmp = new File(dir, LANG + ".traineddata.tmp");
        // M94：成功(已 rename 走)才跳过 finally 里的 .tmp 清理，任何失败路径都清残留
        boolean success = false;
        HttpURLConnection conn = null;
        InputStream is = null;
        OutputStream os = null;
        try {
            URL url = new URL(TRAINEDDATA_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                AppLog.w(TAG, "训练数据下载HTTP异常 code=" + code);
                return false;
            }
            int total = conn.getContentLength();
            is = conn.getInputStream();
            os = new FileOutputStream(tmp);
            byte[] buffer = new byte[8192];
            int read;
            long downloaded = 0;
            final long totalF = total > 0 ? total : -1;
            while ((read = is.read(buffer)) != -1) {
                os.write(buffer, 0, read);
                downloaded += read;
                if (totalF > 0) {
                    final int percent = (int) (downloaded * 100 / totalF);
                    postProgress(callback, percent);
                }
            }
            os.flush();
            os.close();
            os = null;
            // 校验大小合理（至少几百 KB，防止下载到错误页）
            if (tmp.length() < 1024 * 200) {
                AppLog.w(TAG, "训练数据过小(" + tmp.length() + "B)，疑似错误内容，丢弃");
                return false;
            }
            // M94：SHA-256 指纹校验（见 EXPECTED_SHA256 注释），不匹配删除 .tmp 并报错
            String actualSha256 = sha256Hex(tmp);
            if (!EXPECTED_SHA256.equals(actualSha256)) {
                AppLog.e(TAG, "训练数据SHA-256不匹配 actual=" + actualSha256
                        + " expected=" + EXPECTED_SHA256);
                return false;
            }
            // 原子替换
            File finalFile = trainedDataFile();
            if (finalFile.exists()) {
                finalFile.delete();
            }
            success = tmp.renameTo(finalFile);
            if (!success) {
                AppLog.e(TAG, ".tmp 原子替换失败 path=" + tmp.getPath());
            }
            return success;
        } catch (Exception e) {
            AppLog.e(TAG, "训练数据下载失败 " + AppLog.cause(e));
            return false;
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (os != null) {
                    os.close();
                }
            } catch (Exception ignored) {
            }
            if (conn != null) {
                conn.disconnect();
            }
            if (!success && tmp.exists() && !tmp.delete()) {
                AppLog.w(TAG, ".tmp 残留清理失败 path=" + tmp.getPath());
            }
        }
    }

    /** M94：流式计算文件 SHA-256（十六进制小写），供下载完整性校验 */
    private static String sha256Hex(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new FileInputStream(file);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
        } finally {
            in.close();
        }
        StringBuilder sb = new StringBuilder(md.getDigestLength() * 2);
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void postProgress(final PrepareCallback callback, final int percent) {
        if (callback == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                callback.onProgress(percent);
            }
        });
    }

    /**
     * 初始化 OCR 引擎（耗时，请在子线程调用）。
     */
    public synchronized boolean initEngine() {
        if (engineReady) {
            return true;
        }
        try {
            tessBaseAPI = new TessBaseAPI();
            if (!tessBaseAPI.init(context.getFilesDir().getAbsolutePath(), LANG)) {
                tessBaseAPI = null;
                return false;
            }
            // 仅识别字母与数字，提升验证码识别率
            tessBaseAPI.setVariable("tessedit_char_whitelist",
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789");
            engineReady = true;
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean isReady() {
        return engineReady;
    }

    /**
     * 识别验证码（请确保已 initEngine，建议在子线程调用）。
     */
    public synchronized String recognize(Bitmap bitmap) {
        if (!engineReady || tessBaseAPI == null || bitmap == null) {
            return "";
        }
        try {
            tessBaseAPI.setImage(bitmap);
            String text = tessBaseAPI.getUTF8Text();
            tessBaseAPI.clear();
            if (text == null) {
                return "";
            }
            // 验证码一般不含空格/换行
            return text.replaceAll("\\s+", "");
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public synchronized void recycle() {
        if (tessBaseAPI != null) {
            tessBaseAPI.end();
            tessBaseAPI = null;
        }
        engineReady = false;
    }
}
