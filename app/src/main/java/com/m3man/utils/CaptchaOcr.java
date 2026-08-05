package com.m3man.utils;

import android.content.Context;
import android.graphics.Bitmap;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 基于 tess-two 的离线验证码识别工具（无需 GMS / ML Kit，适用于无 Play 服务的模拟器）。
 * 使用 assets/tessdata/eng.traineddata，首次使用时拷贝到应用私有目录。
 */
public class CaptchaOcr {

    private static final String TESS_DIR = "tessdata";
    private static final String LANG = "eng";

    private final Context context;
    private TessBaseAPI tessBaseAPI;
    private boolean engineReady = false;

    public CaptchaOcr(Context context) {
        this.context = context.getApplicationContext();
        copyTrainedDataIfNeeded();
    }

    private void copyTrainedDataIfNeeded() {
        File tessDir = new File(context.getFilesDir(), TESS_DIR);
        if (!tessDir.exists()) {
            tessDir.mkdirs();
        }
        File data = new File(tessDir, LANG + ".traineddata");
        if (!data.exists()) {
            try {
                InputStream is = context.getAssets().open(TESS_DIR + "/" + LANG + ".traineddata");
                OutputStream os = new FileOutputStream(data);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                is.close();
                os.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
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
