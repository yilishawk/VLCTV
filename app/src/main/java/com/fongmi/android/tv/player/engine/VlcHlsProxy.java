package com.fongmi.android.tv.player.engine;

import android.text.TextUtils;

import com.fongmi.android.tv.utils.BundledCaTrust;
import com.github.catvod.crawler.SpiderDebug;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>只在检测到 {@code #EXT-X-KEY:METHOD=AES-128} 的单层 m3u8 时启用；非加密流、master playlist、
 * fMP4（EXT-X-MAP）、byte range 一律返回 null，由调用方直接用原 URL，影响面压到最小。
 */
public final class VlcHlsProxy extends NanoHTTPD {

    private static final String TAG = "vlc-hls-proxy";
    private static final String MIME_M3U8 = "application/vnd.apple.mpegurl; charset=utf-8";
    private static final String MIME_TS = "video/MP2T";
    private static final long SESSION_TTL_MS = TimeUnit.MINUTES.toMillis(30);

    private final OkHttpClient client;
    private final Map<Integer, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Target> targets = new ConcurrentHashMap<>();
    private final AtomicInteger nextSession = new AtomicInteger();
    private final AtomicInteger nextTarget = new AtomicInteger();
    private volatile boolean started;

    public VlcHlsProxy() {
        super("127.0.0.1", 0);
        client = BundledCaTrust.apply(new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS))
                .build();
    }

    /**
     * 探测 url 是否为受支持的 AES-128 单层 HLS。是则建立会话并返回本地明文代理 URL，
     * 否则返回 null（调用方直接用原 URL）。探测/取 key 失败一律降级返回 null。
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
        CipherInfo cipher = detectCipher(fetched.text, fetched.finalUrl);
        if (cipher == null) {
            log("not aes-128 single-layer hls, fallback to origin url=%s", shortUrl(url));
            return null;
        }
        byte[] key;
        try {
            key = fetchKey(cipher.keyUrl, safeHeaders);
            if (key == null || key.length != 16) {
                log("bad key length, fallback key=%s", key == null ? -1 : key.length);
                return null;
            }
        } catch (Throwable e) {
            log("fetch key failed, fallback: %s", err(e));
            return null;
        }
        int id = nextSession.incrementAndGet();
        sessions.put(id, new Session(url, safeHeaders, key, cipher.iv));
        prune();
        String proxyUrl = baseUrl() + "/vlc/index.m3u8?s=" + id;
        log("enabled session=%d key=%s url=%s proxy=%s", id, cipher.keyUrl, shortUrl(url), proxyUrl);
        return proxyUrl;
    }

    public void clear() {
        sessions.clear();
        targets.clear();
    }

    public void release() {
        clear();
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
        CipherInfo cipher = detectCipher(fetched.text, fetched.finalUrl);
        if (cipher == null || !TextUtils.equals(cipher.keyUrl, keyUrlOf(s))) {
            // key 变了：按新 URI 重新取，并更新会话
            if (cipher == null) return error("playlist not encrypted");
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
        RewriteResult result = rewrite(fetched.finalUrl, fetched.text, id, s);
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

        byte[] body;
        try (okhttp3.Response response = fetchBytes(target.url, s.headers)) {
            if (!response.isSuccessful() || response.body() == null) {
                return error("item http " + response.code());
            }
            body = response.body().bytes();
        }
        if (target.key == null) {
            return noCache(newFixedLengthResponse(Status.OK, MIME_TS, new ByteArrayInputStream(body), body.length));
        }
        byte[] plain;
        try {
            plain = decrypt(body, target.key, target.iv);
        } catch (Throwable e) {
            log("decrypt failed id=%s bytes=%d err=%s", tid, body.length, err(e));
            // 长度不是 16 倍数等异常：原样吐出，不让单分片异常打断整条流
            plain = body;
        }
        return noCache(newFixedLengthResponse(Status.OK, MIME_TS, new ByteArrayInputStream(plain), plain.length));
    }

    /** 重写 m3u8：删掉 KEY 行，ts URI 改成本地明文 item URL，并记录每个分片的 key/IV。 */
    private RewriteResult rewrite(String playlistUrl, String text, int sessionId, Session s) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 512);
        long mediaSequence = 0;
        int segmentIndex = 0;
        int segments = 0;
        for (int i = 0; i < lines.length; i++) {
            String raw = stripCr(lines[i]);
            String line = raw.trim();
            String upper = line.toUpperCase(Locale.US);
            if (upper.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSequence = parseLongAttribute(line, -1);
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
                targets.put(tid, new Target(sessionId, targetUrl, s.key, iv));
                out.append(baseUrl()).append("/vlc/item?s=").append(sessionId).append("&id=").append(tid);
                segmentIndex++;
                segments++;
            } else {
                out.append(raw);
            }
            if (i < lines.length - 1) out.append('\n');
        }
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

        Session(String url, Map<String, String> headers, byte[] key, byte[] iv) {
            this.url = url;
            this.headers = headers;
            this.key = key;
            this.iv = iv;
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
