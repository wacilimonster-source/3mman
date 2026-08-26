package com.m3man.utils;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.m3man.data.DataManager;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.VideoResult;
import com.m3man.data.model.BaseResult;
import com.m3man.parser.Parse91PornyVideo;
import com.m3man.service.HlsDownloadService;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 91porny 备用源解析：91mman（91porn 官方）视频直链 CDN 对部分网络封锁（表现为
 * 下载 error / 0% 无速度），此时用视频标题去 91porny 反查同一视频，改走 91porny 的
 * HLS 分片下载（91porny CDN 通常可达，且 m3u8 分片无签名可直接下）。
 * <p>
 * 调用方需保证在 IO 线程调用 {@link #resolve(DataManager, String)}（内部阻塞等待网络）。
 */
public class PornyFallbackResolver {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/68.0.3440.84 Safari/537.36";

    /** 命中相似度阈值（91porny 标题通常带 [原创] 等前缀，0.5 以上基本可确认同一视频） */
    private static final double MIN_SIMILARITY = 0.5d;
    /** 探活只取 1 字节 */
    private static final String RANGE = "bytes=0-0";
    private static final Pattern BRACKET_PREFIX = Pattern.compile("^\\s*\\[[^\\]]*\\]\\s*");

    private PornyFallbackResolver() {
    }

    /**
     * 标题反查 91porny：搜索 → 相似度匹配 → 解析播放页拿 m3u8。
     *
     * @return 命中且解析成功返回 VideoResult（videoUrl 为 m3u8）；否则 null
     */
    public static VideoResult resolve(DataManager dm, String title) {
        if (dm == null || TextUtils.isEmpty(title)) {
            return null;
        }
        String kw = searchKeyword(title);
        if (TextUtils.isEmpty(kw)) {
            return null;
        }
        try {
            BaseResult<List<V9MmanItem>> r = dm.searchPornyVideos(kw, 1).blockingFirst();
            if (r == null || r.getData() == null || r.getData().isEmpty()) {
                return null;
            }
            String bestKey = null;
            double bestSim = 0d;
            for (V9MmanItem it : r.getData()) {
                if (it == null || TextUtils.isEmpty(it.getTitle())) {
                    continue;
                }
                double s = similarity(title, it.getTitle());
                if (s > bestSim) {
                    bestSim = s;
                    bestKey = it.getViewKey();
                }
            }
            if (bestKey == null || bestSim < MIN_SIMILARITY) {
                return null;
            }
            VideoResult vr = dm.loadPornyVideoUrl(bestKey).blockingFirst();
            if (vr == null || TextUtils.isEmpty(vr.getVideoUrl())) {
                return null;
            }
            return vr;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 探活：向 mp4 直链发 Range 1 字节请求。
     * 返回 true = CDN 放行（可正常下载）；false = 连接被拒/超时/非 2xx（多半被封锁，走备用源）。
     */
    public static boolean isAlive(OkHttpClient client, String mp4Url) {
        if (client == null || TextUtils.isEmpty(mp4Url)) {
            return false;
        }
        try {
            Request req = new Request.Builder()
                    .url(mp4Url)
                    .header("Range", RANGE)
                    .header("User-Agent", UA)
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                int code = resp.code();
                return code == 200 || code == 206;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** 启动 91porny HLS 下载（复用分分钟的 HlsDownloadService 通道） */
    public static void enqueueHlsDownload(Context ctx, V9MmanItem item, String m3u8Url, String savePath) {
        if (ctx == null || item == null || TextUtils.isEmpty(m3u8Url)) {
            return;
        }
        Intent i = new Intent(ctx, HlsDownloadService.class);
        i.setAction(HlsDownloadService.ACTION_START);
        i.putExtra(HlsDownloadService.EXTRA_VIDEO_URL, m3u8Url);
        i.putExtra(HlsDownloadService.EXTRA_TITLE, item.getTitle());
        // M99：文件名必须经 sanitizeFileName 清洗，防止标题含非法字符导致建目录/写文件失败
        i.putExtra(HlsDownloadService.EXTRA_FILE_NAME, SDCardUtils.sanitizeFileName(item.getTitle()));
        i.putExtra(HlsDownloadService.EXTRA_VIEW_KEY, item.getViewKey());
        i.putExtra(HlsDownloadService.EXTRA_SAVE_PATH, savePath);
        try {
            // M73：Android 8+ 后台 startService 静默失败（IllegalStateException 被吞），
            // 应用退后台后 HLS 兜底下载不会启动。HlsDownloadService onStartCommand 会
            // startForeground，用 startForegroundService 保证后台也能拉起。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception ignored) {
        }
    }

    /** 把已命中的 91porny m3u8 写回 DB 并标记 source（后续列表/播放按分分钟源分流） */
    public static boolean applyPornyResult(DataManager dm, V9MmanItem item, VideoResult pornyResult) {
        if (dm == null || item == null || pornyResult == null) {
            return false;
        }
        try {
            dm.saveVideoResult(pornyResult);
            item.setVideoResult(pornyResult);
            item.setSource(Parse91PornyVideo.SOURCE);
            item.setSourceName(Parse91PornyVideo.SOURCE);
            dm.updateV9MmanItem(item);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 内部工具 ====================

    /** 提取搜索关键词：去 [xxx] 前缀，取前 12 字符（91porny 搜索对长标题截断更稳） */
    static String searchKeyword(String title) {
        String t = stripPrefix(title);
        if (t.length() <= 12) {
            return t;
        }
        return t.substring(0, 12);
    }

    /** 去标题开头 [原创]/[中文] 之类的方括号前缀 */
    static String stripPrefix(String title) {
        if (TextUtils.isEmpty(title)) {
            return "";
        }
        String t = title.trim();
        Matcher m = BRACKET_PREFIX.matcher(t);
        return m.find() ? m.replaceFirst("") : t;
    }

    /** 相似度：一方包含另一方视为 0.9（高置信）；否则按字符集合重合率 */
    static double similarity(String a, String b) {
        String x = stripPrefix(a);
        String y = stripPrefix(b);
        if (TextUtils.isEmpty(x) || TextUtils.isEmpty(y)) {
            return 0d;
        }
        if (x.contains(y) || y.contains(x)) {
            return 0.9d;
        }
        Set<Character> sx = chars(x);
        Set<Character> sy = chars(y);
        if (sx.isEmpty() || sy.isEmpty()) {
            return 0d;
        }
        int inter = 0;
        for (char c : sx) {
            if (sy.contains(c)) {
                inter++;
            }
        }
        return 2.0d * inter / (sx.size() + sy.size());
    }

    private static Set<Character> chars(String s) {
        Set<Character> set = new HashSet<>();
        for (int i = 0; i < s.length(); i++) {
            set.add(s.charAt(i));
        }
        return set;
    }
}
