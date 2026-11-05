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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 91porny HLS (m3u8) 下载器。
 *
 * 流程：
 *  1. 协调线程请求 m3u8 索引，解析出 ts 分片列表
 *  2. 3 个长活 worker 从 AtomicInteger 领取下一片并发下载（T05 有界模型，
 *     不再为每个分片提交一个 Runnable——10,000 片也不会线性堆积队列）
 *  3. 按顺序二进制拼接为一个 .ts 文件（合并前先做空间预估，不足则清晰失败且保留可续传片）
 *  4. 用 MediaExtractor + MediaMuxer 将 .ts 重封装为 .mp4（不重编码，速度快，画质无损）
 *
 * T05 可靠性契约：
 *  - 分片先写 {@code .part}，完整下载并校验后 rename 为正式片——
 *    续传时非空正式片可信；不会把半截文件当完整片复用
 *  - 任务目录写 {@code started.json}（分片 URL 集合摘要）；摘要不匹配（签名变化/换源）
 *    保守清掉旧片，不跨任务误复用
 *  - 活动 HttpURLConnection 登记注册表，暂停/取消立即 disconnect，1 秒内停止新增读取
 *  - {@code stop(true)} 由协调线程在 worker 全部退出后再清理临时目录，不边写边删
 *
 * 说明：
 *  - m3u8 分片通常无需 Referer（已实测 200 可访问）
 *  - 若 m3u8 含 #EXT-X-KEY（AES 加密）暂不支持，会回调 onError
 *  - 该下载器独立于 filedownloader，用于 91porny 的 m3u8 源
 */
public class HlsDownloader {

    public interface HlsDownloadListener {
        /**
         * 分片下载进度：done 已下载数 / total 总数；
         * bytesDone 为已落盘字节累计（含断点复用与缓存命中分片的 length），转码阶段为最终值。
         */
        void onProgress(int done, int total, long bytesDone);

        /** 下载并转换完成 */
        void onSuccess(File mp4File);

        /** 失败原因 */
        void onError(String message);

        /**
         * T05：阶段切换（segment→merging→remuxing；归档阶段由 Service 侧发布）。
         * 阶段事件低频，接收方可立即刷新通知（不受普通进度节流约束）。
         */
        default void onPhase(String phase) {
        }
    }

    /** 阶段常量（与 Perf hls.* 埋点对应） */
    public static final String PHASE_MERGING = "merging";
    public static final String PHASE_REMUXING = "remuxing";

    /** 分片 worker 数（T05：与既有通常有效并发一致；在途分片 ≤3） */
    private static final int WORKER_COUNT = 3;
    private static final Pattern EXTINF_SEG = Pattern.compile("#EXTINF:[^\\r\\n]*(?:\\r?\\n)\\s*(\\S+)");
    private static final Pattern KEY_LINE = Pattern.compile("#EXT-X-KEY");
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    /** 空间安全余量系数：估算 片段+merged.ts+mp4 + 10% 余量 */
    private static final double SPACE_MARGIN = 1.1d;

    private final Context context;
    /** 协调线程（解析/合并/转封装/清理都在此线程执行，不占 worker 名额） */
    private final ExecutorService coordinator;
    /** 活动连接注册表：stop 时 disconnect，阻塞中的 read 立刻抛错退出 */
    private final Set<HttpURLConnection> activeConnections = new HashSet<>();
    private final Object connLock = new Object();

    private volatile boolean cancelled;
    private volatile File workingTempDir;
    private volatile CountDownLatch workersLatch;

    public HlsDownloader(Context context) {
        this.context = context.getApplicationContext();
        this.coordinator = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hls-coordinator");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    /**
     * 开始下载 m3u8 并转为 mp4。
     *
     * @param m3u8Url      m3u8 索引地址
     * @param saveDir      保存目录（如 SDCardUtils.DOWNLOAD_VIDEO_PATH）
     * @param fileName     输出文件名（不带扩展名，最终为 fileName.mp4）
     * @param videoCacheDir 播放缓存目录（AppCacheUtils.getVideoCacheDir），可传 null；
     *                      若所有 ts 分片都已缓存（播放过），则直接复制缓存，避免重新下载
     * @param listener     回调
     */
    public void download(final String m3u8Url, final String saveDir, final String fileName,
                         final File videoCacheDir, final HlsDownloadListener listener) {
        coordinator.execute(() -> runDownload(m3u8Url, saveDir, fileName, videoCacheDir, listener));
    }

    private void runDownload(String m3u8Url, String saveDir, String fileName,
                             File videoCacheDir, HlsDownloadListener listener) {
        try {
            doDownload(m3u8Url, saveDir, fileName, videoCacheDir, listener);
        } catch (Exception e) {
            AppLog.e("HlsDownloader", "download failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            notifyError(listener, "下载失败: " + e.getMessage());
        } finally {
            coordinator.shutdown();
        }
    }

    private void doDownload(String m3u8Url, String saveDir, String fileName,
                            File videoCacheDir, HlsDownloadListener listener) throws IOException {
        // 1. 解析 m3u8（协调线程）
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

        // M97/M102/T05：临时目录名稳定——hls_+URL哈希+_分片总数；
        // started.json 记录分片 URL 集合摘要：摘要不匹配（签名变化/碰撞）保守清旧片，不误复用。
        File tempDir = new File(context.getCacheDir(),
                "hls_" + Integer.toHexString(m3u8Url.hashCode()) + "_"
                        + fullUrls.size());
        workingTempDir = tempDir;
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            notifyError(listener, "创建临时目录失败");
            return;
        }
        if (!verifyOrWriteStartedManifest(tempDir, fullUrls)) {
            // 同名目录属本任务（URL hash+片数一致），摘要不匹配 → 清掉旧片重新下载
            cleanSegmentFiles(tempDir);
            if (!verifyOrWriteStartedManifest(tempDir, fullUrls)) {
                notifyError(listener, "创建任务清单失败");
                return;
            }
        }

        // M43：优先复用播放缓存——仅当全部分片都已缓存时使用，避免半缓存错位
        Map<String, File> cacheHits = collectCacheHits(fullUrls, videoCacheDir);
        boolean useCache = cacheHits != null && cacheHits.size() == fullUrls.size();

        // 3. T05：3 个长活 worker 从 AtomicInteger 领取分片（在途 ≤3、无 Runnable 线性堆积），
        //    协调线程只 latch 等待 worker 退出（异常/取消在 worker finally countDown）。
        final File[] tsFiles = new File[fullUrls.size()];
        final AtomicInteger nextIdx = new AtomicInteger();
        final AtomicInteger okCount = new AtomicInteger();
        // L-fix：真实字节统计（每个分片完成后取 length，含缓存命中/断点续传复用），
        // 供下载列表展示“已下载大小”并推算实时速度
        final AtomicLong bytesDone = new AtomicLong();
        final AtomicBoolean failed = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(WORKER_COUNT);
        workersLatch = latch;
        for (int w = 0; w < WORKER_COUNT; w++) {
            final String name = "hls-worker-" + w;
            Thread worker = new Thread(() -> {
                try {
                    while (!failed.get() && !cancelled) {
                        int idx = nextIdx.getAndIncrement();
                        if (idx >= fullUrls.size()) {
                            break;
                        }
                        String segUrl = fullUrls.get(idx);
                        File tsFile = segmentFile(tempDir, idx);
                        // 断点续传：正式片存在即完整（.part→rename 纪律保证），直接复用
                        boolean pieceOk = tsFile.exists() && tsFile.length() > 0;
                        if (!pieceOk) {
                            if (useCache && cacheHits.containsKey(segUrl)) {
                                // M62：缓存复制失败时回退网络下载，而不是静默跳过该分片
                                pieceOk = (copyFile(cacheHits.get(segUrl), partFile(tsFile))
                                        && promotePart(tsFile))
                                        || downloadToFile(segUrl, tsFile);
                            } else {
                                pieceOk = downloadToFile(segUrl, tsFile);
                            }
                        }
                        if (pieceOk) {
                            tsFiles[idx] = tsFile;
                            bytesDone.addAndGet(tsFile.length());
                            notifyProgress(listener, okCount.incrementAndGet(), fullUrls.size(), bytesDone.get());
                        } else {
                            failed.set(true);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }, name);
            worker.setPriority(Thread.NORM_PRIORITY - 1);
            worker.start();
        }
        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            notifyError(listener, "下载已中断");
            return;
        }

        // M62/M97：硬校验——任一分片失败都不得合并，否则产出缺段 mp4 被标成"下载完成"
        List<File> downloadedTsFiles = new ArrayList<>(fullUrls.size());
        for (File f : tsFiles) {
            if (f != null) {
                downloadedTsFiles.add(f);
            }
        }
        if (failed.get() || cancelled || downloadedTsFiles.size() != fullUrls.size()) {
            if (cancelled) {
                notifyError(listener, "下载已取消");
            } else {
                int ok = downloadedTsFiles.size();
                notifyError(listener, "有 " + (fullUrls.size() - ok) + "/" + fullUrls.size()
                        + " 个分片下载失败，已中止（请重试）");
            }
            return;
        }

        // T05：合并前空间预估——片段+merged.ts+mp4（+归档副本）×余量；不足则清晰失败并保留可续传片
        long needed = (long) (bytesDone.get() * 3 * SPACE_MARGIN);
        if (!ensureFreeSpace(tempDir, needed)) {
            notifyError(listener, "存储空间不足（还需约 " + (needed / (1024 * 1024)) + "MB），已保留已下分片");
            return;
        }

        // 4. 合并分片为单个 ts（阶段事件：merging）
        listener.onPhase(PHASE_MERGING);
        File tsMerged = new File(tempDir, "merged.ts");
        if (cancelled) {
            notifyError(listener, "下载已取消");
            return;
        }
        mergeFiles(downloadedTsFiles, tsMerged);

        // 5. ts -> mp4（MediaExtractor + MediaMuxer；阶段事件：remuxing）
        listener.onPhase(PHASE_REMUXING);
        File outDir = new File(saveDir);
        if (!outDir.exists() && !outDir.mkdirs()) {
            notifyError(listener, "创建保存目录失败");
            return;
        }
        File mp4File = new File(outDir, fileName + ".mp4");
        if (cancelled) {
            notifyError(listener, "下载已取消");
            return;
        }
        boolean remuxOk = remuxTsToMp4(tsMerged, mp4File);
        if (!remuxOk || !mp4File.exists() || mp4File.length() <= 0) {
            // 未验证通过不得把 ts 当 mp4 报成功
            notifyError(listener, "视频转码失败（该分片可能不支持转 mp4）");
            return;
        }
        deleteQuietly(tempDir);
        notifySuccess(listener, mp4File);
    }

    /**
     * M102/T05：停止下载。
     *
     * @param discardPieces true=删除已下分片（用户取消任务）——协调线程在 worker 全部
     *                      退出后才清理任务临时目录，避免边写边删；
     *                      false=保留（暂停/被新任务顶替），下次同 m3u8 重启时分片级续传
     */
    public void stop(boolean discard) {
        cancelled = true;
        // 立即断开活动连接：阻塞中的 read 抛错退出，worker 快速收尾（≤读取超时兜底）
        disconnectAll();
        if (discard) {
            // 清理交给协调线程在 worker 全部退出后执行（若流程已结束则任务随队列收尾），
            // 不在这里边写边删
            final File dir = workingTempDir;
            if (dir != null) {
                try {
                    coordinator.execute(() -> {
                        awaitWorkersQuietly();
                        deleteQuietly(dir);
                    });
                } catch (Exception ignored) {
                    // 协调线程池已关闭：流程已结束，此时无人再写文件，可直接清理
                    deleteQuietly(dir);
                }
            }
        }
    }

    /** M26：关闭协调线程池（worker 由 cancelled 标志驱动退出，下载器为单次使用） */
    public void shutdown() {
        try {
            coordinator.shutdown();
        } catch (Exception ignored) {
        }
    }

    // ---------- 私有方法 ----------

    private static File segmentFile(File tempDir, int idx) {
        return new File(tempDir, String.format("seg_%05d.ts", idx));
    }

    private static File partFile(File finalFile) {
        return new File(finalFile.getParent(), finalFile.getName() + ".part");
    }

    /** T05：.part → 正式片原子晋升（同目录 rename），晋升成功才认为分片完整 */
    private static boolean promotePart(File finalFile) {
        File part = partFile(finalFile);
        if (!part.exists() || part.length() <= 0) {
            return false;
        }
        return part.renameTo(finalFile);
    }

    /** T05：started.json——分片 URL 集合摘要，防同名目录跨任务误复用 */
    private static boolean verifyOrWriteStartedManifest(File tempDir, List<String> fullUrls) {
        File manifest = new File(tempDir, "started.json");
        String digest = sha256(joinUrls(fullUrls));
        String expected = "{\"u\":\"" + digest + "\",\"n\":" + fullUrls.size() + "}";
        if (manifest.exists()) {
            try {
                String content = readAll(manifest);
                return expected.equals(content.trim());
            } catch (Exception e) {
                return false;
            }
        }
        try {
            OutputStream out = new FileOutputStream(manifest);
            try {
                out.write(expected.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String joinUrls(List<String> urls) {
        StringBuilder sb = new StringBuilder(urls.size() * 40);
        for (String u : urls) {
            sb.append(u).append('\n');
        }
        return sb.toString();
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(raw.hashCode());
        }
    }

    private static String readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[4096];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return sb.toString();
        } finally {
            in.close();
        }
    }

    /** T05：仅清理本任务目录内的分片文件（明确归属，不全局扫删） */
    private static void cleanSegmentFiles(File tempDir) {
        File[] files = tempDir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            String name = f.getName();
            if ((name.startsWith("seg_") && name.endsWith(".ts")) || name.endsWith(".part")
                    || "merged.ts".equals(name)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** T05：活动连接断开（stop 时立即触发，阻塞 read 立即失败） */
    private void disconnectAll() {
        List<HttpURLConnection> snapshot;
        synchronized (connLock) {
            snapshot = new ArrayList<>(activeConnections);
        }
        for (HttpURLConnection conn : snapshot) {
            try {
                conn.disconnect();
            } catch (Exception ignored) {
            }
        }
        synchronized (connLock) {
            activeConnections.clear();
        }
    }

    private void registerConnection(HttpURLConnection conn) {
        synchronized (connLock) {
            activeConnections.add(conn);
        }
    }

    private void unregisterConnection(HttpURLConnection conn) {
        synchronized (connLock) {
            activeConnections.remove(conn);
        }
    }

    private void awaitWorkersQuietly() {
        CountDownLatch latch = workersLatch;
        if (latch == null) {
            return;
        }
        try {
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** T05：目录剩余空间是否足够（不足时保留分片，供清理后重试续传） */
    private static boolean ensureFreeSpace(File dir, long neededBytes) {
        try {
            long free = dir.getUsableSpace();
            return free <= 0 || free >= neededBytes; // 取不到值时不阻塞下载
        } catch (Exception e) {
            return true;
        }
    }

    private String fetchString(String urlStr) throws IOException {
        HttpURLConnection conn = open(urlStr);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                return null;
            }
            InputStream in = new BufferedInputStream(conn.getInputStream());
            try {
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    sb.append(new String(buf, 0, n, "UTF-8"));
                }
                return sb.toString();
            } finally {
                // M62：异常路径也要关流，避免连接泄漏
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        } finally {
            conn.disconnect();
            unregisterConnection(conn);
        }
    }

    private boolean downloadToFile(String urlStr, File file) {
        // M41：网络抖动导致单个分片失败时重试 1 次，避免静默缺失分片产出损坏文件。
        // T05：先写 .part，成功后原子晋升——半截文件永远不会冒充完整片参与续传/合并。
        File part = partFile(file);
        for (int attempt = 0; attempt < 2; attempt++) {
            if (cancelled) {
                return false;
            }
            if (tryDownloadToFile(urlStr, part) && promotePart(file)) {
                return true;
            }
        }
        //noinspection ResultOfMethodCallIgnored
        part.delete();
        return false;
    }

    /**
     * M43：收集已在播放缓存中的分片（videocache 缓存文件名由 VideoCacheFileNameGenerator 生成）。
     * 返回 null 表示 videoCacheDir 不可用；仅当全部分片都命中时才应使用缓存。
     */
    private Map<String, File> collectCacheHits(List<String> fullUrls, File videoCacheDir) {
        if (videoCacheDir == null || !videoCacheDir.exists()) {
            return null;
        }
        Map<String, File> hits = new HashMap<>();
        VideoCacheFileNameGenerator generator = new VideoCacheFileNameGenerator();
        for (String url : fullUrls) {
            File cached = new File(videoCacheDir, generator.generate(url));
            if (cached.exists() && cached.length() > 0) {
                hits.put(url, cached);
            }
        }
        return hits;
    }

    private static boolean copyFile(File from, File to) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new BufferedInputStream(new FileInputStream(from));
            out = new BufferedOutputStream(new FileOutputStream(to));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            return to.length() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            // M62：异常路径也要关流
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private boolean tryDownloadToFile(String urlStr, File file) {
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            conn = open(urlStr);
            registerConnection(conn);
            if (cancelled) {
                return false;
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                return false;
            }
            in = new BufferedInputStream(conn.getInputStream());
            out = new BufferedOutputStream(new FileOutputStream(file));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (cancelled) {
                    return false; // 暂停/取消：立即停止读取（连接已在 disconnectAll 中断开）
                }
                out.write(buf, 0, n);
            }
            out.flush();
            return file.length() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            // M62：异常路径也要关流，避免每次失败泄一个 fd
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignored) {
            }
            if (conn != null) {
                conn.disconnect();
                unregisterConnection(conn);
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
        // 兜底：直接匹配所有非注释行。
        // M73：不再排除 http 开头的绝对 URL——resolveSegmentUrl 本就支持绝对地址分片，
        // 旧条件会把含绝对 URL 分片的 m3u8 解析成 0 个分片而误报"没有可下载的分片"。
        if (list.isEmpty()) {
            String[] lines = m3u8.split("\\r?\\n");
            for (String line : lines) {
                String t = line.trim();
                if (!TextUtils.isEmpty(t) && !t.startsWith("#")) {
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

    private void mergeFiles(List<File> parts, File out) throws IOException {
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out));
        try {
            byte[] buf = new byte[8192];
            for (File part : parts) {
                if (cancelled) {
                    throw new IOException("下载已取消");
                }
                BufferedInputStream bis = new BufferedInputStream(new FileInputStream(part));
                try {
                    int n;
                    while ((n = bis.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                    }
                } finally {
                    // M62：异常路径也要关流
                    try {
                        bis.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            bos.flush();
        } finally {
            try {
                bos.close();
            } catch (Exception ignored) {
            }
        }
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

    private static void notifyProgress(HlsDownloadListener listener, int done, int total, long bytesDone) {
        if (listener != null) {
            listener.onProgress(done, total, bytesDone);
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
