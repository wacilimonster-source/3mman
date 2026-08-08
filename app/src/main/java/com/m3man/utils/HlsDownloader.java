package com.m3man.utils;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.text.TextUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 91porny HLS (m3u8) 下载器。
 *
 * 流程：
 *  1. 请求 m3u8 索引，解析出 ts 分片列表
 *  2. 并发下载所有 ts 分片到临时目录
 *  3. 按顺序二进制拼接为一个 .ts 文件
 *  4. 用 MediaExtractor + MediaMuxer 将 .ts 重封装为 .mp4（不重编码，速度快，画质无损）
 *
 * 说明：
 *  - m3u8 分片通常无需 Referer（已实测 200 可访问）
 *  - 若 m3u8 含 #EXT-X-KEY（AES 加密）暂不支持，会回调 onError
 *  - 该下载器独立于 filedownloader，用于 91porny 的 m3u8 源
 */
public class HlsDownloader {

    public interface HlsDownloadListener {
        /** 分片下载进度：done 已下载数 / total 总数 */
        void onProgress(int done, int total);

        /** 下载并转换完成 */
        void onSuccess(File mp4File);

        /** 失败原因 */
        void onError(String message);
    }

    private static final Pattern EXTINF_SEG = Pattern.compile("#EXTINF:[^\\r\\n]*(?:\\r?\\n)\\s*(\\S+)");
    private static final Pattern KEY_LINE = Pattern.compile("#EXT-X-KEY");
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private final Context context;
    private final ExecutorService executor;
    private volatile boolean cancelled;

    public HlsDownloader(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(4);
    }

    /**
     * 开始下载 m3u8 并转为 mp4。
     *
     * @param m3u8Url   m3u8 索引地址
     * @param saveDir   保存目录（如 SDCardUtils.DOWNLOAD_VIDEO_PATH）
     * @param fileName  输出文件名（不带扩展名，最终为 fileName.mp4）
     */
    public void download(final String m3u8Url, final String saveDir, final String fileName,
                         final HlsDownloadListener listener) {
        executor.execute(() -> {
            File merged = null;
            try {
                // 1. 解析 m3u8
                String m3u8Content = fetchString(m3u8Url);
                if (TextUtils.isEmpty(m3u8Content)) {
                    notifyError(listener, "获取 m3u8 失败");
                    return;
                }
                if (KEY_LINE.matcher(m3u8Content).find()) {
                    notifyError(listener, "该视频为加密 HLS（AES），暂不支持下载");
                    return;
                }
                List<String> segments = parseSegments(m3u8Content);
                if (segments.isEmpty()) {
                    notifyError(listener, "m3u8 中没有可下载的分片");
                    return;
                }

                // 2. 拼接分片完整地址
                String baseDir = getBaseDir(m3u8Url);
                List<String> fullUrls = new ArrayList<>(segments.size());
                for (String seg : segments) {
                    fullUrls.add(resolveSegmentUrl(baseDir, seg));
                }

                // 3. 下载所有分片
                File tempDir = new File(context.getCacheDir(), "hls_" + System.currentTimeMillis());
                if (!tempDir.exists() && !tempDir.mkdirs()) {
                    notifyError(listener, "创建临时目录失败");
                    return;
                }
                List<File> tsFiles = new ArrayList<>(fullUrls.size());
                int ok = 0;
                for (int i = 0; i < fullUrls.size(); i++) {
                    if (cancelled) {
                        notifyError(listener, "下载已取消");
                        return;
                    }
                    File tsFile = new File(tempDir, String.format("seg_%05d.ts", i));
                    if (downloadToFile(fullUrls.get(i), tsFile)) {
                        tsFiles.add(tsFile);
                        ok++;
                    }
                    notifyProgress(listener, ok, fullUrls.size());
                }
                if (tsFiles.isEmpty()) {
                    notifyError(listener, "所有分片下载失败");
                    return;
                }

                // 4. 合并分片为单个 ts
                File tsMerged = new File(tempDir, "merged.ts");
                mergeFiles(tsFiles, tsMerged);

                // 5. ts -> mp4（MediaExtractor + MediaMuxer）
                File outDir = new File(saveDir);
                if (!outDir.exists() && !outDir.mkdirs()) {
                    notifyError(listener, "创建保存目录失败");
                    return;
                }
                File mp4File = new File(outDir, fileName + ".mp4");
                boolean remuxOk = remuxTsToMp4(tsMerged, mp4File);
                if (!remuxOk || !mp4File.exists() || mp4File.length() <= 0) {
                    notifyError(listener, "视频转码失败（该分片可能不支持转 mp4）");
                    return;
                }
                deleteQuietly(tempDir);
                notifySuccess(listener, mp4File);
            } catch (Exception e) {
                e.printStackTrace();
                notifyError(listener, "下载失败: " + e.getMessage());
            } finally {
                // M26：任务结束（成功 / 失败 / 取消）后关闭线程池，避免 4 个工作线程永久泄漏
                executor.shutdown();
            }
        });
    }

    public void cancel() {
        cancelled = true;
    }

    /** M26：关闭内部线程池，避免 4 个工作线程永久泄漏（下载器为单次使用） */
    public void shutdown() {
        try {
            executor.shutdown();
        } catch (Exception ignored) {
        }
    }

    // ---------- 私有方法 ----------

    private String fetchString(String urlStr) throws IOException {
        HttpURLConnection conn = open(urlStr);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                return null;
            }
            InputStream in = new BufferedInputStream(conn.getInputStream());
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            in.close();
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private boolean downloadToFile(String urlStr, File file) {
        // M41：网络抖动导致单个分片失败时重试 1 次，避免静默缺失分片产出损坏文件
        for (int attempt = 0; attempt < 2; attempt++) {
            if (tryDownloadToFile(urlStr, file)) {
                return true;
            }
        }
        return false;
    }

    private boolean tryDownloadToFile(String urlStr, File file) {
        HttpURLConnection conn = null;
        try {
            conn = open(urlStr);
            int code = conn.getResponseCode();
            if (code != 200) {
                return false;
            }
            InputStream in = new BufferedInputStream(conn.getInputStream());
            OutputStream out = new BufferedOutputStream(new FileOutputStream(file));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            out.close();
            in.close();
            return file.length() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private HttpURLConnection open(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "*/*");
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private static List<String> parseSegments(String m3u8) {
        List<String> list = new ArrayList<>();
        Matcher m = EXTINF_SEG.matcher(m3u8);
        while (m.find()) {
            String seg = m.group(1).trim();
            if (!TextUtils.isEmpty(seg) && !seg.startsWith("#")) {
                list.add(seg);
            }
        }
        // 兜底：直接匹配所有非注释行
        if (list.isEmpty()) {
            String[] lines = m3u8.split("\\r?\\n");
            for (String line : lines) {
                String t = line.trim();
                if (!TextUtils.isEmpty(t) && !t.startsWith("#") && !t.startsWith("http")) {
                    list.add(t);
                }
            }
        }
        return list;
    }

    private static String getBaseDir(String m3u8Url) {
        int idx = m3u8Url.lastIndexOf('/');
        return idx > 0 ? m3u8Url.substring(0, idx + 1) : m3u8Url;
    }

    private static String resolveSegmentUrl(String baseDir, String seg) {
        if (seg.startsWith("http://") || seg.startsWith("https://")) {
            return seg;
        }
        if (seg.startsWith("//")) {
            return "https:" + seg;
        }
        if (seg.startsWith("/")) {
            // 取域名部分
            try {
                URL url = new URL(baseDir);
                return url.getProtocol() + "://" + url.getHost() + seg;
            } catch (Exception e) {
                return seg;
            }
        }
        return baseDir + seg;
    }

    private static void mergeFiles(List<File> parts, File out) throws IOException {
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out));
        byte[] buf = new byte[8192];
        for (File part : parts) {
            BufferedInputStream bis = new BufferedInputStream(new FileInputStream(part));
            int n;
            while ((n = bis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            bis.close();
        }
        bos.flush();
        bos.close();
    }

    /**
     * 用 MediaExtractor 读 ts，MediaMuxer 写 mp4（remux，不重编码）。
     * API 21+ 的 MediaExtractor 支持 MPEG-TS。
     */
    private static boolean remuxTsToMp4(File tsFile, File mp4File) {
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(tsFile.getAbsolutePath());

            int videoTrackIndex = -1;
            int audioTrackIndex = -1;
            MediaFormat videoFormat = null;
            MediaFormat audioFormat = null;

            int trackCount = extractor.getTrackCount();
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/") && videoTrackIndex == -1) {
                    videoTrackIndex = i;
                    videoFormat = format;
                } else if (mime != null && mime.startsWith("audio/") && audioTrackIndex == -1) {
                    audioTrackIndex = i;
                    audioFormat = format;
                }
            }
            if (videoTrackIndex == -1) {
                return false;
            }

            muxer = new MediaMuxer(mp4File.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxVideoIndex = muxer.addTrack(videoFormat);
            int muxAudioIndex = -1;
            if (audioTrackIndex != -1 && audioFormat != null) {
                muxAudioIndex = muxer.addTrack(audioFormat);
            }
            muxer.start();

            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            // 视频轨
            extractor.selectTrack(videoTrackIndex);
            long videoSampleTime = 0;
            while (true) {
                info.offset = 0;
                info.size = extractor.readSampleData(buffer, 0);
                if (info.size < 0) {
                    break;
                }
                info.presentationTimeUs = extractor.getSampleTime();
                if (info.presentationTimeUs < 0) {
                    info.presentationTimeUs = videoSampleTime;
                }
                info.flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0;
                muxer.writeSampleData(muxVideoIndex, buffer, info);
                videoSampleTime = info.presentationTimeUs + 40000;
                extractor.advance();
            }

            // 音频轨（如有）
            if (muxAudioIndex != -1) {
                extractor.selectTrack(audioTrackIndex);
                while (true) {
                    info.offset = 0;
                    info.size = extractor.readSampleData(buffer, 0);
                    if (info.size < 0) {
                        break;
                    }
                    info.presentationTimeUs = extractor.getSampleTime();
                    if (info.presentationTimeUs < 0) {
                        info.presentationTimeUs = videoSampleTime;
                    }
                    info.flags = 0;
                    muxer.writeSampleData(muxAudioIndex, buffer, info);
                    extractor.advance();
                }
            }

            muxer.stop();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            try {
                if (extractor != null) {
                    extractor.release();
                }
            } catch (Exception ignored) {
            }
            try {
                if (muxer != null) {
                    muxer.release();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void deleteQuietly(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteQuietly(f);
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    private static void notifyProgress(HlsDownloadListener listener, int done, int total) {
        if (listener != null) {
            listener.onProgress(done, total);
        }
    }

    private static void notifySuccess(HlsDownloadListener listener, File file) {
        if (listener != null) {
            listener.onSuccess(file);
        }
    }

    private static void notifyError(HlsDownloadListener listener, String msg) {
        if (listener != null) {
            listener.onError(msg);
        }
    }
}
