package com.fongmi.android.tv.player.engine;

import android.text.TextUtils;

import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.utils.BundledCaTrust;
import com.github.catvod.crawler.SpiderDebug;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * VLC 单文件源（mp4/flv/mkv 等）的分段 Range 预加载代理。
 *
 * <p>{@link VlcHlsProxy} 只覆盖 HLS 分片流；普通点播源解析后是单个媒体文件，没有分片时间轴，
 * VLC 直接用 {@code http stream} 单连接边下边播，看不到预加载区间。本代理把这个单文件按固定大小的
 * 逻辑块（{@link #BLOCK_SIZE}）切开：VLC 的 Range 请求（含 seek 快进后退）逐块命中内存缓存，
 * 未命中的块回源拉取并顺手缓存；后台按播放进度把"还没看的"块提前下载好，进度条据此显示真实缓冲区间。
 *
 * <p>时间轴映射是线性的：{@code byte = (positionMs / durationMs) * contentLength}，对恒定码率准确，
 * VBR 有偏差但预加载本就是预测，不影响播放正确性（seek 仍由 VLC 按字节精确控制）。
 *
 * <p>只代理 http(s) 且能 HEAD 到 Content-Length 的单文件；HEAD 失败/无长度/不支持 Range 一律返回 null，
 * 由调用方直接用原 URL，影响面压到最小。
 */
public final class VlcFileProxy extends NanoHTTPD {

    private static final String TAG = "vlc-file-proxy";
    private static final String MIME_DEFAULT = "application/octet-stream";
    private static final long SESSION_TTL_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long BLOCK_SIZE = 512L * 1024;          // 逻辑块 512KB
    private static final long MAX_CACHE_BYTES = 128L * 1024 * 1024; // 内存硬上限，防 OOM
    private static final long PRELOAD_WINDOW_MS = 5 * 60 * 1000L;  // 只预加载未来 5 分钟
    private static final int MAX_PRELOAD_PER_TICK = 24;            // 单次 tick 最多提交，防雪崩
    private static final long MIN_CACHE_FILE_BYTES = 1;

    private final OkHttpClient client;
    private final Map<Integer, FileSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger nextSession = new AtomicInteger();
    private volatile boolean started;

    /** 块缓存：key=全局块序号，LRU，按字节淘汰。value 是完整块（末块可能不足 BLOCK_SIZE）。 */
    private final LinkedHashMap<Long, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final AtomicLong cacheBytes = new AtomicLong();
    private final java.util.Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private volatile ExecutorService preloadExecutor;
    private final AtomicInteger preloadGeneration = new AtomicInteger();
    private volatile long lastPreloadAtMs;

    public VlcFileProxy() {
        super("127.0.0.1", 0);
        client = BundledCaTrust.apply(new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS))
                .build();
    }

    /**
     * 探测单文件源能否代理（HEAD 拿长度/MIME/Range 支持）。可代理则建会话返回本地 URL，
     * 否则返回 null（调用方直接用原 URL）。
     */
    public synchronized String proxy(String url, Map<String, String> headers) {
        if (TextUtils.isEmpty(url)) return null;
        String lower = url.toLowerCase(Locale.US);
        if (lower.contains(".m3u8") || lower.contains(".mpd")) return null;
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null;
        Map<String, String> safeHeaders = headers == null ? Map.of() : headers;
        long length;
        String mime;
        boolean range;
        try {
            ensureStarted();
            try (okhttp3.Response head = request(url, safeHeaders, null, "HEAD")) {
                if (!head.isSuccessful()) {
                    log("HEAD failed code=%d, fallback url=%s", head.code(), shortUrl(url));
                    return null;
                }
                length = headerLong(head, "Content-Length", -1);
                String accept = head.header("Accept-Ranges");
                range = accept != null && accept.toLowerCase(Locale.US).contains("bytes");
                String ct = head.header("Content-Type");
                mime = TextUtils.isEmpty(ct) ? MIME_DEFAULT : ct.split(";")[0].trim();
            }
            if (length < MIN_CACHE_FILE_BYTES || !range) {
                log("no length/range (len=%d range=%b), fallback url=%s", length, range, shortUrl(url));
                return null;
            }
        } catch (Throwable e) {
            log("probe failed, fallback: %s", err(e));
            return null;
        }
        int id = nextSession.incrementAndGet();
        sessions.put(id, new FileSession(url, safeHeaders, length, mime));
        prune();
        String proxyUrl = baseUrl() + "/vlc/file?s=" + id;
        log("enabled session=%d len=%d mime=%s url=%s proxy=%s", id, length, mime, shortUrl(url), proxyUrl);
        return proxyUrl;
    }

    /** 播放进度回调：预加载当前字节位置之后、窗口内尚未缓存的块。 */
    public void preloadAround(long positionMs, long durationMs) {
        if (!PreloadSetting.isPreload(PlayerSetting.VLC)) return;
        long now = System.currentTimeMillis();
        if (now - lastPreloadAtMs < 1500) return; // TimeChanged 很频繁，1.5s 节流
        lastPreloadAtMs = now;
        int id = currentSessionId();
        FileSession s = sessions.get(id);
        if (s == null || s.length <= 0 || durationMs <= 0) return;
        long posByte = Math.min(s.length - 1, Math.max(0, (long) ((double) positionMs / durationMs * s.length)));
        long endByte = Math.min(s.length - 1, posByte + (long) ((double) PRELOAD_WINDOW_MS / durationMs * s.length));
        long firstBlock = posByte / BLOCK_SIZE + 1; // 当前块之后开始（当前块由播放请求自然命中）
        long lastBlock = endByte / BLOCK_SIZE;
        int submitted = 0;
        synchronized (cache) {
            for (long b = firstBlock; b <= lastBlock && submitted < MAX_PRELOAD_PER_TICK; b++) {
                if (cache.containsKey(b) || inFlight.contains(b)) continue;
                submitPreload(id, b, s);
                submitted++;
            }
        }
        if (submitted > 0) log("preload submitted=%d posByte=%d blocks=%d..%d cacheBytes=%d",
                submitted, posByte, firstBlock, lastBlock, cacheBytes.get());
    }

    /** 切源/停止作废在途预加载。 */
    public void cancelPreload() {
        preloadGeneration.incrementAndGet();
    }

    /** 返回从 positionMs 往后连续缓存到的时间终点（毫秒），无会话返回 -1。 */
    public long bufferedEndMs(long positionMs, long durationMs) {
        int id = currentSessionId();
        FileSession s = sessions.get(id);
        if (s == null || s.length <= 0 || durationMs <= 0) return -1L;
        long posBlock = blockOf(positionMs, durationMs, s.length);
        long end = -1L;
        synchronized (cache) {
            // 从当前块往后数连续缓存块，遇断点即停
            for (long b = posBlock; ; b++) {
                if (b * BLOCK_SIZE >= s.length) break;
                if (!cache.containsKey(b)) break;
                long blockEndMs = (long) ((double) ((b + 1) * BLOCK_SIZE) / s.length * durationMs);
                end = Math.min(durationMs, blockEndMs);
            }
        }
        return end;
    }

    /** positionMs 对应的块序号（按字节线性映射）。 */
    private static long blockOf(long positionMs, long durationMs, long length) {
        if (durationMs <= 0 || length <= 0) return 0;
        long bytePos = Math.min(length - 1, Math.max(0, (long) ((double) positionMs / durationMs * length)));
        return bytePos / BLOCK_SIZE;
    }

    private int currentSessionId() {
        int id = -1;
        for (Integer key : sessions.keySet()) if (key != null && key > id) id = key;
        return id;
    }

    private void submitPreload(int sessionId, long blockIndex, FileSession s) {
        if (!inFlight.add(blockIndex)) return;
        int generation = preloadGeneration.get();
        ExecutorService executor = ensurePreloadExecutor();
        try {
            executor.execute(() -> {
                try {
                    if (generation != preloadGeneration.get()) return;
                    if (sessions.get(sessionId) == null) return;
                    byte[] data = loadBlock(s, blockIndex);
                    if (data == null) return;
                    if (generation != preloadGeneration.get()) return;
                    putCache(blockIndex, data);
                } catch (Throwable e) {
                    log("preload failed block=%d err=%s", blockIndex, err(e));
                } finally {
                    inFlight.remove(blockIndex);
                }
            });
        } catch (Throwable e) {
            inFlight.remove(blockIndex);
        }
    }

    private synchronized ExecutorService ensurePreloadExecutor() {
        if (preloadExecutor == null || preloadExecutor.isShutdown()) {
            int threads = PreloadSetting.getPreloadThreads(PlayerSetting.VLC);
            preloadExecutor = Executors.newFixedThreadPool(Math.max(1, Math.min(4, threads)));
        }
        return preloadExecutor;
    }

    /** 下载指定块（按 byte range 回源），失败返回 null。 */
    private byte[] loadBlock(FileSession s, long blockIndex) throws IOException {
        long start = blockIndex * BLOCK_SIZE;
        long end = Math.min(s.length - 1, start + BLOCK_SIZE - 1);
        if (start > end) return null;
        try (okhttp3.Response response = request(s.url, s.headers, "bytes=" + start + "-" + end, "GET")) {
            if (!response.isSuccessful() || response.body() == null) return null;
            return response.body().bytes();
        }
    }

    private synchronized void putCache(long key, byte[] data) {
        if (data == null || data.length == 0) return;
        long limit = Math.min(MAX_CACHE_BYTES, PreloadSetting.getPreloadSizeBytes(PlayerSetting.VLC));
        if (data.length > limit) return;
        byte[] old = cache.put(key, data);
        if (old != null) cacheBytes.addAndGet(-old.length);
        cacheBytes.addAndGet(data.length);
        while (cacheBytes.get() > limit && !cache.isEmpty()) {
            Long eldest = cache.keySet().iterator().next();
            byte[] removed = cache.remove(eldest);
            if (removed != null) cacheBytes.addAndGet(-removed.length);
        }
    }

    private byte[] getCache(long key) {
        synchronized (cache) {
            return cache.get(key);
        }
    }

    public void clear() {
        cancelPreload();
        sessions.clear();
        synchronized (cache) {
            cache.clear();
            cacheBytes.set(0);
        }
        inFlight.clear();
    }

    public void release() {
        clear();
        ExecutorService executor = preloadExecutor;
        if (executor != null) executor.shutdownNow();
        preloadExecutor = null;
        try {
            stop();
        } catch (Throwable ignored) {
        }
    }

    private synchronized void ensureStarted() throws IOException {
        if (started && getListeningPort() > 0) return;
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        started = true;
        log("listening port=%d", getListeningPort());
    }

    private void prune() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> now - e.getValue().created > SESSION_TTL_MS);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        try {
            if (uri != null && uri.startsWith("/vlc/file")) return serveFile(session);
        } catch (Throwable e) {
            log("serve error uri=%s err=%s", uri, err(e));
        }
        return noCache(newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "not found"));
    }

    /** 按 Range 逐块服务：命中缓存直接返回，未命中回源并缓存；返回 206 + Content-Range。 */
    private Response serveFile(IHTTPSession hs) throws IOException {
        int id = parseInt(hs.getParms().get("s"), -1);
        FileSession s = sessions.get(id);
        if (s == null) return error("expired session");
        String rangeHeader = header(hs, "range");
        Range range = parseRange(rangeHeader, s.length);
        if (rangeHeader != null && range == null) {
            Response r = error("invalid range");
            r.addHeader("Content-Range", "bytes */" + s.length);
            return r;
        }
        long start = range == null ? 0 : range.start;
        long end = range == null ? s.length - 1 : range.end;
        long length = end - start + 1;
        // 流式输出：内存恒定，避免 bytes=0-（open-ended，可能几百 MB）一次性读入 OOM
        Response response = newFixedLengthResponse(range == null ? Status.OK : Status.PARTIAL_CONTENT,
                s.mime, new RangeStream(s, start, end), length);
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Cache-Control", "no-cache");
        response.addHeader("Accept-Ranges", "bytes");
        if (range != null) response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + s.length);
        return response;
    }

    /**
     * 逐块读取 [start,end] 的 InputStream：每块优先命中内存缓存，未命中回源拉取并缓存。
     * 块边界对齐缓存，seek 到任意位置都能命中已缓存块。
     */
    private final class RangeStream extends java.io.InputStream {
        private final FileSession session;
        private long pos;
        private final long end;
        private byte[] block;
        private long blockStart = -1;
        private int blockOffset;

        RangeStream(FileSession session, long start, long end) {
            this.session = session;
            this.pos = start;
            this.end = end;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos > end || len <= 0) return -1;
            if (!ensureBlock()) return -1;
            int copy = Math.min(len, block.length - blockOffset);
            copy = (int) Math.min(copy, end - pos + 1);
            System.arraycopy(block, blockOffset, b, off, copy);
            blockOffset += copy;
            pos += copy;
            return copy;
        }

        /** 确保 pos 落在当前块内；否则加载 pos 所在块。 */
        private boolean ensureBlock() throws IOException {
            long needed = pos / BLOCK_SIZE * BLOCK_SIZE;
            if (block != null && needed == blockStart && blockOffset < block.length) return true;
            byte[] data = getCache(needed / BLOCK_SIZE);
            if (data == null) {
                data = loadBlock(session, needed / BLOCK_SIZE);
                if (data == null) return false;
                putCache(needed / BLOCK_SIZE, data);
            }
            block = data;
            blockStart = needed;
            blockOffset = (int) (pos - needed);
            return blockOffset < block.length;
        }

        @Override
        public void close() {
            block = null; // 不关闭底层（OkHttp 响应在 loadBlock 里已关闭）
        }
    }

    private okhttp3.Response request(String url, Map<String, String> headers, String range, String method) throws IOException {
        Request.Builder b = new Request.Builder().url(url);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (!TextUtils.isEmpty(e.getKey()) && !TextUtils.isEmpty(e.getValue())) b.header(e.getKey(), e.getValue());
        }
        if (!TextUtils.isEmpty(range)) b.header("Range", range);
        return client.newCall(b.method(method, null).build()).execute();
    }

    private static long headerLong(okhttp3.Response response, String name, long def) {
        String value = response.header(name);
        if (value == null) return def;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String header(IHTTPSession hs, String name) {
        String value = hs.getHeaders().get(name);
        if (value == null) {
            for (Map.Entry<String, String> e : hs.getHeaders().entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
            }
        }
        return value;
    }

    private static Range parseRange(String header, long length) {
        if (TextUtils.isEmpty(header) || length <= 0) return null;
        String value = header.trim().toLowerCase(Locale.US);
        if (!value.startsWith("bytes=")) return null;
        String spec = value.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        try {
            long start;
            long end;
            String left = spec.substring(0, dash).trim();
            String right = spec.substring(dash + 1).trim();
            if (left.isEmpty()) {
                long suffix = Long.parseLong(right);
                if (suffix <= 0) return null;
                start = Math.max(0, length - suffix);
                end = length - 1;
            } else {
                start = Long.parseLong(left);
                end = right.isEmpty() ? length - 1 : Long.parseLong(right);
            }
            if (start < 0 || end < start || start >= length) return null;
            return new Range(start, Math.min(end, length - 1));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int parseInt(String value, int def) {
        try {
            return value == null ? def : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private Response noCache(Response r) {
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private static Response error(String text) {
        return NanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, text == null ? "" : text);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + getListeningPort();
    }

    private static String shortUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        return url.length() <= 96 ? url : url.substring(0, 96) + "...";
    }

    private static String err(Throwable e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    private static void log(String format, Object... args) {
        try {
            android.util.Log.d("VlcFileProxy", String.format(java.util.Locale.US, format, args));
        } catch (Throwable ignored) {
        }
        SpiderDebug.log(TAG, format, args);
    }

    private static final class Range {
        final long start;
        final long end;

        Range(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class FileSession {
        final String url;
        final Map<String, String> headers;
        final long length;
        final String mime;
        final long created = System.currentTimeMillis();

        FileSession(String url, Map<String, String> headers, long length, String mime) {
            this.url = url;
            this.headers = headers;
            this.length = length;
            this.mime = mime;
        }
    }
}
