package com.m3man.utils;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃兜底。
 * <p>
 * 背景：Bugly 移除后工程里没有任何崩溃捕获，崩溃直接交系统处理 ——
 * 用户只看到「已停止运行」，开发者零感知，无法统计崩溃率、也无法复现问题。
 * <p>
 * 职责非常克制：
 * <ol>
 *   <li>把崩溃堆栈写到应用私有目录 {@code files/crash/} 下（保留最近若干份，自动滚动）；</li>
 *   <li>随后<b>交回系统默认处理器</b>，保持系统原有的崩溃对话框 / 进程退出行为，</li>
 *       不吞崩溃、不阻止进程退出、不做任何「假装没崩」的事。</li>
 * </ol>
 * 写文件失败也不抛异常（此时进程已经不健康，任何二次异常都会让日志丢失）。
 *
 * @author 3mman
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    /** 崩溃日志目录名（位于应用私有 files 目录下，外部不可见） */
    private static final String DIR_NAME = "crash";
    /** 最多保留的崩溃日志份数，超过时删除最旧的 */
    private static final int MAX_LOG_FILES = 10;

    private final Context appContext;
    private final Thread.UncaughtExceptionHandler systemHandler;

    private CrashHandler(Context appContext, Thread.UncaughtExceptionHandler systemHandler) {
        this.appContext = appContext;
        this.systemHandler = systemHandler;
    }

    /** 在 Application.onCreate 里尽早安装；重复调用无副作用。 */
    public static void install(Context context) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof CrashHandler) {
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context.getApplicationContext(), current));
    }

    /** 最近一次崩溃的日志文件；没有则返回 null。供「设置-关于」或后续上报通道读取。 */
    public static File latestCrashFile(Context context) {
        File[] files = crashDir(context).listFiles();
        if (files == null || files.length == 0) {
            return null;
        }
        File latest = null;
        for (File f : files) {
            if (latest == null || f.lastModified() > latest.lastModified()) {
                latest = f;
            }
        }
        return latest;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            writeCrashLog(thread, throwable);
        } catch (Throwable ignored) {
            // 进程已在崩溃路径上，任何二次异常都不能再抛
        }
        if (systemHandler != null) {
            // 交回系统：保持崩溃对话框 / 进程退出等默认行为
            systemHandler.uncaughtException(thread, throwable);
        } else {
            Runtime.getRuntime().exit(2);
        }
    }

    private void writeCrashLog(Thread thread, Throwable throwable) {
        File dir = crashDir(appContext);
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        String time = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        File out = new File(dir, "crash_" + time + ".txt");
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8);
            writer.write("time=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\n");
            writer.write("thread=" + thread.getName() + "\n");
            writer.write("version=" + ApkVersionUtils.getVersionName(appContext) + "\n\n");
            writer.write(stackTraceOf(throwable));
            writer.flush();
            trimOldLogs(dir);
        } catch (Throwable ignored) {
            // 同上：崩溃路径上保持沉默
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String stackTraceOf(Throwable throwable) {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static void trimOldLogs(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_LOG_FILES) {
            return;
        }
        // 按修改时间从旧到新删，直到数量回到上限
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - MAX_LOG_FILES; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    private static File crashDir(Context context) {
        return new File(context.getFilesDir(), DIR_NAME);
    }
}
