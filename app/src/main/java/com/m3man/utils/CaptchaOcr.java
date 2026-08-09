package com.m3man.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 基于 tess-two 的离线验证码识别工具（无需 GMS / ML Kit，适用于无 Play 服务的模拟器）。
 * <p>
 * 训练数据(eng.traineddata, ~22MB)不再打包进 APK，改为首次需要识别验证码时按需下载，
 * 下载后缓存到应用私有目录，后续直接使用。这样既大幅缩减安装包体积，
 * 又保证登录验证码自动识别功能可用；下载失败则优雅降级为手动输入。
 */
public class CaptchaOcr {

    private static final String TESS_DIR = "tessdata";
    private static final String LANG = "eng";
    // 训练数据托管地址（与 App 更新同源：GitHub raw）
    private static final String TRAINEDDATA_URL =
            "https://raw.githubusercontent.com/wacilimonster-source/3mman/master/tessdata/eng.traineddata";

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
     */
    public void prepare(final PrepareCallback callback) {
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
            // 校验大小合理（至少几百 KB，防止下载到错误页）
            if (tmp.length() < 1024 * 200) {
                return false;
            }
            // 原子替换
            File finalFile = trainedDataFile();
            if (finalFile.exists()) {
                finalFile.delete();
            }
            return tmp.renameTo(finalFile);
        } catch (Exception e) {
            e.printStackTrace();
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
        }
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
