package com.fongmi.android.tv.player.engine;

import android.text.TextUtils;

import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.utils.BundledCaTrust;
import com.github.catvod.crawler.SpiderDebug;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * VLC 专用的 AES-128 HLS 明文代理。
 *
 * <p>这个 libvlc 构建的 adaptive demuxer 在拿到加密 HLS 的 key 和 ts 之后，native 解密环节失败
 * （{@code Failed to create demuxer 0x0}，全程无 crypt 模块介入），而 avformat demuxer 又没编进
 * FFmpeg 的 https protocol，两条 native 路都不通。本代理把 EXO 的 {@code Aes128DataSource} 逻辑前移到
 * HTTP 层：取 key、按分片序号做 AES-128-CBC 解密，输出去掉 {@code #EXT-X-KEY} 的明文 m3u8 和明文 TS，
 * 让 VLC 走它本来就擅长的普通 HLS 路径（这条路径已真机验证能解码出画面）。
 *
 * <p>支持两类单层 m3u8：（1）AES-128 加密流，取 key 解密后输出明文；（2）普通非加密流，
 * 原样透传分片但同样走本地缓存/预加载，让进度条能显示真实预加载区间。master playlist
 * （多码率）、fMP4（EXT-X-MAP）、byte range 一律返回 null，由调用方直接用原 URL，交给 VLC 原生处理。
 */
public final class VlcHlsProxy extends NanoHTTPD {

    private static final String TAG = "vlc-hls-proxy";
    private static final String MIME_M3U8 = "application/vnd.apple.mpegurl; charset=utf-8";
    private static final String MIME_TS = "video/MP2T";
    private static final long SESSION_TTL_MS = TimeUnit.MINUTES.toMillis(30);

    private static final long MAX_CACHE_BYTES = 128L * 1024 * 1024; // 内存缓存硬上限，防止 OOM
    private static final long PRELOAD_WINDOW_MS = 5 * 60 * 1000L;   // 只预加载未来 5 分钟内的分片
    private static final int MAX_PRELOAD_PER_TICK = 40;             // 单次 tick 最多提交，防止雪崩

    private final OkHttpClient client;
    private final Map<Integer, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Target> targets = new ConcurrentHashMap<>();
    private final AtomicInteger nextSession = new AtomicInteger();
    private final AtomicInteger nextTarget = new AtomicInteger();
    private volatile boolean started;

    /** 明文 TS 内存缓存：key=原始 ts URL，LRU，按字节淘汰。serveItem 命中即直接返回。 */
    private final LinkedHashMap<String, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final AtomicLong cacheBytes = new AtomicLong();
    private final java.util.Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private volatile ExecutorService preloadExecutor;
    private final AtomicInteger preloadGeneration = new AtomicInteger();
    private volatile long lastPreloadAtMs;

    public VlcHlsProxy() {
        super("127.0.0.1", 0);
        client = BundledCaTrust.apply(new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS))
                .build();
    }

    /**
     * 播放进度回调：预加载当前时间之后、窗口内尚未缓存的分片。
     * 开关/线程数/时长全部读 VLC 自己的预加载设置，与设置界面一致。
     */
    public void preloadAround(long positionMs) {
        if (!PreloadSetting.isPreload(PlayerSetting.VLC)) return;
        long now = System.currentTimeMillis();
        if (now - lastPreloadAtMs < 1500) return; // TimeChanged 很频繁，1.5s 节流
        lastPreloadAtMs = now;
        int sessionId = currentSessionId();
        Session s = sessions.get(sessionId);
        if (s == null || s.segments.isEmpty()) return;
        long start = Math.max(0, positionMs);
        long end = start + PRELOAD_WINDOW_MS;
        int submitted = 0;
        synchronized (cache) {
            for (Segment seg : s.segments) {
                if (submitted >= MAX_PRELOAD_PER_TICK) break;
                if (seg.endMs <= start) continue;      // 已播过
                if (seg.startMs >= end) break;         // 超出窗口（segments 按时间有序）
                if (seg.startMs <= start && seg.endMs > start) continue; // 正在播
                Target t = seg.target;
                if (t == null || cache.containsKey(t.url) || inFlight.contains(t.url)) continue;
                submitPreload(sessionId, t);
                submitted++;
            }
        }
        if (submitted > 0) log("preload submitted=%d pos=%dms cacheBytes=%d", submitted, positionMs, cacheBytes.get());
    }

    /** 切源/暂停停止时作废所有在途预加载任务。 */
    public void cancelPreload() {
        preloadGeneration.incrementAndGet();
    }

    private int currentSessionId() {
        // 只保留一个活跃会话：新会话建立后旧的自然过期；取已建立的最大 id。
        int id = -1;
        for (Integer key : sessions.keySet()) if (key != null && key > id) id = key;
        return id;
    }

    private void submitPreload(int sessionId, Target t) {
        if (!inFlight.add(t.url)) return;
        int generation = preloadGeneration.get();
        ExecutorService executor = ensurePreloadExecutor();
        try {
            executor.execute(() -> {
                try {
                    if (generation != preloadGeneration.get()) return;
                    Session s = sessions.get(sessionId);
                    if (s == null) return;
                    byte[] plain = loadAndDecrypt(t, s);
                    if (plain == null) return;
                    if (generation != preloadGeneration.get()) return;
                    putCache(t.url, plain);
                } catch (Throwable e) {
                    log("preload failed url=%s err=%s", shortUrl(t.url), err(e));
                } finally {
                    inFlight.remove(t.url);
                }
            });
        } catch (Throwable e) {
            inFlight.remove(t.url);
        }
    }

    private synchronized ExecutorService ensurePreloadExecutor() {
        if (preloadExecutor == null || preloadExecutor.isShutdown()) {
            int threads = PreloadSetting.getPreloadThreads(PlayerSetting.VLC);
            preloadExecutor = Executors.newFixedThreadPool(Math.max(1, Math.min(4, threads)));
        }
        return preloadExecutor;
    }

    private byte[] loadAndDecrypt(Target t, Session s) throws IOException {
        try (okhttp3.Response response = fetchBytes(t.url, s.headers)) {
            if (!response.isSuccessful() || response.body() == null) return null;
            byte[] body = response.body().bytes();
            if (t.key == null) return body;
            try {
                return decrypt(body, t.key, t.iv);
            } catch (Throwable e) {
                log("preload decrypt failed url=%s err=%s", shortUrl(t.url), err(e));
                return null;
            }
        }
    }

    private synchronized void putCache(String key, byte[] data) {
        if (data == null || data.length == 0) return;
        long limit = Math.min(MAX_CACHE_BYTES, PreloadSetting.getPreloadSizeBytes(PlayerSetting.VLC));
        if (data.length > limit) return;
        byte[] old = cache.put(key, data);
        if (old != null) cacheBytes.addAndGet(-old.length);
        cacheBytes.addAndGet(data.length);
        while (cacheBytes.get() > limit && !cache.isEmpty()) {
            String eldest = cache.keySet().iterator().next();
            byte[] removed = cache.remove(eldest);
            if (removed != null) cacheBytes.addAndGet(-removed.length);
        }
    }

    private byte[] getCache(String key) {
        synchronized (cache) {
            return cache.get(key);
        }
    }

    /**
     * 返回从 positionMs 往后、已缓存分片的连续终点（毫秒）。用于进度条显示真实预加载进度。
     * 只算连续区间：中间有分片没缓存就停在断点前，不虚报。没有代理会话时返回 C.TIME_UNSET。
     */
    public long bufferedEndMs(long positionMs) {
        int id = currentSessionId();
        Session s = sessions.get(id);
        if (s == null || s.segments.isEmpty()) return -1L; // C.TIME_UNSET
        long end = -1L;
        synchronized (cache) {
            for (Segment seg : s.segments) {
                if (seg.endMs <= positionMs) continue;    // 已播过的跳过
                if (!cache.containsKey(seg.target.url)) break; // 断点：后面的不算
                end = seg.endMs;
            }
        }
        return end;
    }

    /**
     * 探测 url 是否为受支持的单层 HLS（加密 AES-128 或普通非加密）。是则建立会话并返回本地代理 URL，
     * 否则返回 null（调用方直接用原 URL）。master/fMP4/byterange 不在支持范围；探测/取 key 失败降级返回 null。
     */
    public synchronized String proxy(String url, Map<String, String> headers) {
        if (TextUtils.isEmpty(url)) return null;
        String lower = url.toLowerCase(Locale.US);
        if (!lower.contains(".m3u8")) return null;
        Map<String, String> safeHeaders = headers == null ? Map.of() : headers;
        Fetched fetched;
        try {
            ensureStarted();
            fetched = fetchText(url, safeHeaders);
        } catch (Throwable e) {
            log("probe failed, fallback to origin: %s", err(e));
            return null;
        }
        PlaylistKind kind = probe(fetched.text, fetched.finalUrl);
        if (kind == PlaylistKind.UNSUPPORTED) {
            log("master/fmp4/byterange hls, fallback to origin url=%s", shortUrl(url));
            return null;
        }
        byte[] key = null;
        byte[] iv = null;
        String keyUri = null;
        if (kind == PlaylistKind.ENCRYPTED) {
            CipherInfo cipher = detectCipher(fetched.text, fetched.finalUrl);
            if (cipher == null) {
                log("cipher vanished, fallback url=%s", shortUrl(url));
                return null;
            }
            try {
                key = fetchKey(cipher.keyUrl, safeHeaders);
                if (key == null || key.length != 16) {
                    log("bad key length, fallback key=%s", key == null ? -1 : key.length);
                    return null;
                }
                iv = cipher.iv;
                keyUri = cipher.keyUrl;
            } catch (Throwable e) {
                log("fetch key failed, fallback: %s", err(e));
                return null;
            }
        }
        int id = nextSession.incrementAndGet();
        Session s = new Session(url, safeHeaders, key, iv);
        s.keyUri = keyUri;
        sessions.put(id, s);
        prune();
        String proxyUrl = baseUrl() + "/vlc/index.m3u8?s=" + id;
        log("enabled session=%d kind=%s key=%s url=%s proxy=%s", id, kind, keyUri, shortUrl(url), proxyUrl);
        return proxyUrl;
    }

    public void clear() {
        cancelPreload();
        sessions.clear();
        targets.clear();
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
            if (uri.startsWith("/vlc/index.m3u8")) return servePlaylist(session);
            if (uri.startsWith("/vlc/item")) return serveItem(session);
        } catch (Throwable e) {
            log("serve error uri=%s err=%s", uri, err(e));
        }
        return noCache(newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "not found"));
    }

    /** 每次请求都重新取原始 m3u8：动态 key / 直播列表会变，不能用首次探测的快照。 */
    private Response servePlaylist(IHTTPSession hs) throws IOException {
        int id = parseInt(hs.getParms().get("s"), -1);
        Session s = sessions.get(id);
        if (s == null) return error("expired session");
        Fetched fetched = fetchText(s.url, s.headers);
        PlaylistKind kind = probe(fetched.text, fetched.finalUrl);
        if (kind == PlaylistKind.UNSUPPORTED) return error("unsupported playlist");
        if (kind == PlaylistKind.ENCRYPTED) {
            CipherInfo cipher = detectCipher(fetched.text, fetched.finalUrl);
            if (cipher == null) return error("cipher vanished");
            if (!TextUtils.equals(cipher.keyUrl, keyUrlOf(s))) {
                // 动态 key：key URI 变了就重新取并更新会话
                byte[] key;
                try {
                    key = fetchKey(cipher.keyUrl, s.headers);
                } catch (Throwable e) {
                    return error("fetch key failed: " + err(e));
                }
                if (key == null || key.length != 16) return error("bad key length");
                s.key = key;
                s.iv = cipher.iv;
                s.keyUri = cipher.keyUrl;
            }
        } else {
            // 普通明文 HLS：清掉可能残留的 key 状态，rewrite 会原样透传分片
            s.key = null;
            s.iv = null;
            s.keyUri = null;
        }
        RewriteResult result = rewrite(fetched.finalUrl, fetched.text, id, s);
        if (result == null) return error("playlist changed to unsupported format");
        byte[] data = result.playlist.getBytes(StandardCharsets.UTF_8);
        log("playlist session=%d segments=%d bytes=%d keyUri=%s", id, result.segments, data.length, shortUrl(s.keyUri));
        return noCache(newFixedLengthResponse(Status.OK, MIME_M3U8, new ByteArrayInputStream(data), data.length));
    }

    private Response serveItem(IHTTPSession hs) throws IOException {
        String tid = hs.getParms().get("id");
        Target target = tid == null ? null : targets.get(tid);
        if (target == null) return error("expired item");
        Session s = sessions.get(target.sessionId);
        if (s == null) return error("expired session");

        // 预加载命中：直接返回内存缓存的明文 TS，不再走网络
        byte[] cached = getCache(target.url);
        if (cached != null) {
            return noCache(newFixedLengthResponse(Status.OK, MIME_TS, new ByteArrayInputStream(cached), cached.length));
        }
        byte[] body;
        try (okhttp3.Response response = fetchBytes(target.url, s.headers)) {
            if (!response.isSuccessful() || response.body() == null) {
                return error("item http " + response.code());
            }
            body = response.body().bytes();
        }
        byte[] plain;
        if (target.key == null) {
            plain = body;
        } else {
            try {
                plain = decrypt(body, target.key, target.iv);
            } catch (Throwable e) {
                log("decrypt failed id=%s bytes=%d err=%s", tid, body.length, err(e));
                plain = body; // 长度不是 16 倍数等异常：原样吐出，不让单分片异常打断整条流
            }
        }
        // 顺手把这次实际播放的分片也放进缓存
        putCache(target.url, plain);
        return noCache(newFixedLengthResponse(Status.OK, MIME_TS, new ByteArrayInputStream(plain), plain.length));
    }

    /** 重写 m3u8：删掉 KEY 行，ts URI 改成本地明文 item URL，并记录每个分片的 key/IV。 */
    private RewriteResult rewrite(String playlistUrl, String text, int sessionId, Session s) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 512);
        long mediaSequence = 0;
        int segmentIndex = 0;
        int segments = 0;
        double pendingDuration = 0;   // 最近一个 #EXTINF 的时长（秒）
        long elapsedMs = 0;           // 从 playlist 起始累计的时间
        List<Segment> parsed = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String raw = stripCr(lines[i]);
            String line = raw.trim();
            String upper = line.toUpperCase(Locale.US);
            if (upper.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSequence = parseLongAttribute(line, -1);
                out.append(raw);
            } else if (upper.startsWith("#EXTINF:")) {
                pendingDuration = parseExtInf(line);
                out.append(raw);
            } else if (upper.startsWith("#EXT-X-KEY:")) {
                // 明文输出：整行剔除，VLC 不再尝试 native 解密
            } else if (upper.startsWith("#EXT-X-MAP:") || upper.startsWith("#EXT-X-BYTERANGE:")) {
                return null; // fMP4 / byte range 不在本代理支持范围
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                String targetUrl = resolve(playlistUrl, line);
                String tid = String.valueOf(nextTarget.incrementAndGet());
                long seq = mediaSequence + segmentIndex;
                byte[] iv = s.iv != null ? s.iv : sequenceIv(seq);
                Target target = new Target(sessionId, targetUrl, s.key, iv);
                targets.put(tid, target);
                long startMs = elapsedMs;
                long endMs = startMs + (long) (pendingDuration * 1000);
                parsed.add(new Segment(target, startMs, endMs));
                elapsedMs = endMs;
                out.append(baseUrl()).append("/vlc/item?s=").append(sessionId).append("&id=").append(tid);
                segmentIndex++;
                segments++;
                pendingDuration = 0;
            } else {
                out.append(raw);
            }
            if (i < lines.length - 1) out.append('\n');
        }
        // 保存分片时间轴，供 preloadAround 按播放进度挑未看的分片
        s.segments = parsed;
        return new RewriteResult(out.toString(), segments);
    }

    private static byte[] decrypt(byte[] cipherText, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        int usable = cipherText.length - (cipherText.length % 16);
        return cipher.doFinal(cipherText, 0, usable);
    }

    /** HLS 默认 IV：分片序号（十进制）的大端 128 位整数。 */
    private static byte[] sequenceIv(long seq) {
        byte[] iv = new byte[16];
        for (int i = 15; i >= 0 && seq != 0; i--) {
            iv[i] = (byte) (seq & 0xff);
            seq >>>= 8;
        }
        return iv;
    }

    /**
     * 判断 m3u8 是否可代理：master（多码率）、fMP4/EXT-X-MAP、byterange 交给 VLC 原生；
     * 其余单层列表若有 AES-128 KEY 则 ENCRYPTED，否则 PLAIN。
     */
    private static PlaylistKind probe(String text, String playlistUrl) {
        if (TextUtils.isEmpty(text)) return PlaylistKind.UNSUPPORTED;
        boolean encrypted = false;
        boolean hasSegment = false;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            String upper = line.toUpperCase(Locale.US);
            if (upper.startsWith("#EXT-X-STREAM-INF") || upper.startsWith("#EXT-X-MAP:")
                    || upper.startsWith("#EXT-X-BYTERANGE:")) return PlaylistKind.UNSUPPORTED;
            if (upper.startsWith("#EXT-X-KEY:")) {
                String method = attr(line, "METHOD");
                if (!"AES-128".equalsIgnoreCase(method)) return PlaylistKind.UNSUPPORTED;
                if (TextUtils.isEmpty(attr(line, "URI"))) return PlaylistKind.UNSUPPORTED;
                encrypted = true;
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                hasSegment = true;
            }
        }
        if (!hasSegment) return PlaylistKind.UNSUPPORTED;
        return encrypted ? PlaylistKind.ENCRYPTED : PlaylistKind.PLAIN;
    }

    private static CipherInfo detectCipher(String text, String playlistUrl) {
        if (TextUtils.isEmpty(text)) return null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            String upper = line.toUpperCase(Locale.US);
            if (upper.startsWith("#EXT-X-STREAM-INF") || upper.startsWith("#EXT-X-MAP:")
                    || upper.startsWith("#EXT-X-BYTERANGE:")) return null;
            if (upper.startsWith("#EXT-X-KEY:")) {
                String method = attr(line, "METHOD");
                if (!"AES-128".equalsIgnoreCase(method)) return null;
                String uri = attr(line, "URI");
                if (TextUtils.isEmpty(uri)) return null;
                String ivHex = attr(line, "IV");
                byte[] iv = null;
                if (!TextUtils.isEmpty(ivHex)) {
                    iv = hexToBytes(ivHex);
                    if (iv == null || iv.length != 16) return null;
                }
                return new CipherInfo(resolve(playlistUrl, uri), iv);
            }
        }
        return null;
    }

    private Fetched fetchText(String url, Map<String, String> headers) throws IOException {
        try (okhttp3.Response r = fetch(url, headers)) {
            if (!r.isSuccessful() || r.body() == null) throw new IOException("http " + r.code());
            String finalUrl = r.request().url().toString();
            return new Fetched(r.body().string(), finalUrl);
        }
    }

    private byte[] fetchKey(String url, Map<String, String> headers) throws IOException {
        try (okhttp3.Response r = fetch(url, headers)) {
            if (!r.isSuccessful() || r.body() == null) throw new IOException("http " + r.code());
            return r.body().bytes();
        }
    }

    private okhttp3.Response fetchBytes(String url, Map<String, String> headers) throws IOException {
        return fetch(url, headers);
    }

    private okhttp3.Response fetch(String url, Map<String, String> headers) throws IOException {
        Request.Builder b = new Request.Builder().url(url);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (!TextUtils.isEmpty(e.getKey()) && !TextUtils.isEmpty(e.getValue())) b.header(e.getKey(), e.getValue());
        }
        return client.newCall(b.build()).execute();
    }

    private String keyUrlOf(Session s) {
        return s.keyUri;
    }

    private static String resolve(String base, String uri) {
        try {
            return URI.create(base).resolve(uri).toString();
        } catch (Throwable e) {
            return uri;
        }
    }

    private static String attr(String line, String name) {
        String target = name.toUpperCase(Locale.US);
        for (String part : line.substring(Math.min(line.indexOf(':') + 1, line.length())).split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq).trim().toUpperCase(Locale.US);
            if (!key.equals(target)) continue;
            String value = part.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
            return value;
        }
        return null;
    }

    private static long parseLongAttribute(String line, int def) {
        int colon = line.indexOf(':');
        if (colon < 0) return def;
        try {
            return Long.parseLong(line.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 解析 #EXTINF:10.5,xxx 里的时长（秒）。 */
    private static double parseExtInf(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) return 0;
        String value = line.substring(colon + 1).trim();
        int comma = value.indexOf(',');
        if (comma >= 0) value = value.substring(0, comma).trim();
        try {
            double d = Double.parseDouble(value);
            return Double.isFinite(d) && d > 0 ? d : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static byte[] hexToBytes(String hex) {
        String h = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if (h.length() % 2 != 0) return null;
        try {
            byte[] out = new byte[h.length() / 2];
            for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
            return out;
        } catch (NumberFormatException e) {
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

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + getListeningPort();
    }

    private static Response noCache(Response r) {
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private static Response error(String text) {
        return noCache(newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, text == null ? "" : text));
    }

    private static String shortUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        return url.length() <= 96 ? url : url.substring(0, 96) + "...";
    }

    private static String err(Throwable e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    private static void log(String format, Object... args) {
        // 用 Log.d 直接输出，不受 debug 开关限制（SpiderDebug 在 debug 关闭时不落 logcat），
        // 这样真机上代理的探测/取 key/serve 全过程都能抓到，便于定位。
        try {
            android.util.Log.d("VlcHlsProxy", String.format(java.util.Locale.US, format, args));
        } catch (Throwable ignored) {
        }
        SpiderDebug.log(TAG, format, args);
    }

    private enum PlaylistKind {ENCRYPTED, PLAIN, UNSUPPORTED}

    private static final class Fetched {
        final String text;
        final String finalUrl;

        Fetched(String text, String finalUrl) {
            this.text = text;
            this.finalUrl = finalUrl;
        }
    }

    private static final class CipherInfo {
        final String keyUrl;
        final byte[] iv;

        CipherInfo(String keyUrl, byte[] iv) {
            this.keyUrl = keyUrl;
            this.iv = iv;
        }
    }

    private static final class Session {
        final String url;
        final Map<String, String> headers;
        final long created = System.currentTimeMillis();
        byte[] key;
        byte[] iv;
        String keyUri;
        List<Segment> segments = new ArrayList<>(); // 按播放时间有序

        Session(String url, Map<String, String> headers, byte[] key, byte[] iv) {
            this.url = url;
            this.headers = headers;
            this.key = key;
            this.iv = iv;
        }
    }

    /** 一个分片在播放时间轴上的位置。 */
    private static final class Segment {
        final Target target;
        final long startMs;
        final long endMs;

        Segment(Target target, long startMs, long endMs) {
            this.target = target;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    private static final class Target {
        final int sessionId;
        final String url;
        final byte[] key;
        final byte[] iv;

        Target(int sessionId, String url, byte[] key, byte[] iv) {
            this.sessionId = sessionId;
            this.url = url;
            this.key = key;
            this.iv = iv;
        }
    }

    private static final class RewriteResult {
        final String playlist;
        final int segments;

        RewriteResult(String playlist, int segments) {
            this.playlist = playlist;
            this.segments = segments;
        }
    }
}
