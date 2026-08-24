package com.m3man.utils;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 全局内存日志（诊断用）。
 * <p>
 * 与 orhanobut Logger 不同，本条日志不依赖 BuildConfig.DEBUG，release 包同样可用：
 * 进程内保留最近 {@link #MAX_ENTRIES} 条带时间戳的日志，可通过
 * {@link #dump(Context)} 一键导出（拼接成纯文本），供用户复制发给开发者排查问题。
 * <p>
 * 线程安全：所有写操作走同一把锁，读操作返回不可变副本。
 *
 * @author 3mman
 */
public class AppLog {

    /** 内存保留的最大条数（先进先出，防泄漏） */
    public static final int MAX_ENTRIES = 500;

    private static final String TAG = "AppLog";

    private static final List<String> ENTRIES = Collections.synchronizedList(new ArrayList<String>(MAX_ENTRIES));
    // M73：SimpleDateFormat 非线程安全，静态共享实例在多线程 format 时会产生错误时间戳
    // 甚至 ArrayIndexOutOfBoundsException。改用 ThreadLocal 隔离。
    private static final ThreadLocal<SimpleDateFormat> TIME_FMT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault());
        }
    };

    private AppLog() {
    }

    /** 记录 info 级日志 */
    public static void i(String tag, String msg) {
        append("I", tag, msg);
    }

    /** 记录 warn 级日志 */
    public static void w(String tag, String msg) {
        append("W", tag, msg);
    }

    /** 记录 error 级日志 */
    public static void e(String tag, String msg) {
        append("E", tag, msg);
    }

    private static void append(String level, String tag, String msg) {
        if (msg == null) {
            return;
        }
        String line = TIME_FMT.get().format(new Date()) + " " + level + "/" + tag + ": " + msg;
        synchronized (ENTRIES) {
            ENTRIES.add(line);
            while (ENTRIES.size() > MAX_ENTRIES) {
                ENTRIES.remove(0);
            }
        }
    }

    /** 清空日志（调试用） */
    public static void clear() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
    }

    /**
     * 导出全部日志（含设备/版本/代理等环境头信息），供一键复制。
     */
    public static String dump(Context context) {
        StringBuilder sb = new StringBuilder(4096);
        appendHeader(sb, context);
        sb.append("---- 日志 ----").append('\n');
        synchronized (ENTRIES) {
            for (String line : ENTRIES) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static void appendHeader(StringBuilder sb, Context context) {
        sb.append("3mman 诊断日志").append('\n');
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (Android ").append(Build.VERSION.RELEASE)
                .append(", SDK ").append(Build.VERSION.SDK_INT).append(')').append('\n');
        try {
            String ver = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            int code = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;
            sb.append("App: v").append(ver).append(" (").append(code).append(')').append('\n');
        } catch (Exception ignored) {
        }
        sb.append("时间: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()))
                .append('\n');
        if (context instanceof android.app.Application) {
            // 代理状态与源地址由各埋点处写入，这里不依赖 DataManager
        }
    }

    /**
     * 从 Throwable 提取简短原因（类名 + message，去空行），避免复制大段堆栈。
     */
    public static String cause(Throwable t) {
        if (t == null) {
            return "null";
        }
        String msg = t.getMessage();
        if (!TextUtils.isEmpty(msg)) {
            msg = msg.replace('\n', ' ').trim();
            if (msg.length() > 200) {
                msg = msg.substring(0, 200);
            }
            return t.getClass().getSimpleName() + ": " + msg;
        }
        return t.getClass().getSimpleName();
    }

    /**
     * 从 URL 提取 host（脱敏，日志里只记域名不记完整直链）。
     */
    public static String hostOf(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return "";
        }
    }
}