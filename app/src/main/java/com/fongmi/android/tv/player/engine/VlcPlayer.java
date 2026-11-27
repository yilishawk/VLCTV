package com.fongmi.android.tv.player.engine;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;

import com.github.catvod.crawler.SpiderDebug;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;

import com.fongmi.android.tv.App;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Media3 {@link SimpleBasePlayer} adapter around LibVLC.
 *
 * <p>Keeps the rest of the app untouched: the app only talks to the media3 Player
 * interface, while VLC does the actual decoding/rendering. This is the same shape
 * used by IjkSimplePlayer and MpvPlayer in this project.
 */
@UnstableApi
class VlcPlayer extends SimpleBasePlayer implements MediaPlayer.EventListener, IVLCVout.OnNewVideoLayoutListener {

    private static final String TAG = "VlcPlayer";

    private static final AtomicBoolean NATIVE_LOG_CAPTURED = new AtomicBoolean(false);

    private static final Commands COMMANDS = new Commands.Builder()
            .add(COMMAND_PLAY_PAUSE)
            .add(COMMAND_PREPARE)
            .add(COMMAND_STOP)
            .add(COMMAND_RELEASE)
            .add(COMMAND_SET_REPEAT_MODE)
            .add(COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(COMMAND_GET_TIMELINE)
            .add(COMMAND_GET_METADATA)
            .add(COMMAND_SET_MEDIA_ITEM)
            .add(COMMAND_CHANGE_MEDIA_ITEMS)
            .add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(COMMAND_SEEK_TO_DEFAULT_POSITION)
            .add(COMMAND_GET_VOLUME)
            .add(COMMAND_SET_VOLUME)
            .add(COMMAND_SET_SPEED_AND_PITCH)
            .add(COMMAND_SET_VIDEO_SURFACE)
            .add(COMMAND_GET_TRACKS)
            .build();

    private final LibVLC libVLC;
    private final MediaPlayer vlc;
    private final int decode;
    private final VlcHlsProxy hlsProxy;

    private MediaItem mediaItem;
    private Object videoOutput;
    private PlaybackParameters playbackParameters = PlaybackParameters.DEFAULT;
    private PlaybackException playerError;
    private Tracks currentTracks = Tracks.EMPTY;
    private VideoSize videoSize = VideoSize.UNKNOWN;
    private int playbackState = Player.STATE_IDLE;
    private boolean playWhenReady = true;
    private boolean loading;
    private boolean repeatOne;
    private boolean prepared;
    private boolean newlyRenderedFirstFrame;
    private float volume = 1f;
    private long pendingSeekMs = C.TIME_UNSET;

    VlcPlayer(int decode) {
        super(Looper.getMainLooper());
        this.decode = decode;
        Context context = App.get();
        List<String> args = new ArrayList<>();
        args.add("--no-video-title-show");
        args.add("--network-caching=1000");
        args.add("--no-osd");
        if (decode == PlayerEngine.SOFT) args.add("--avcodec-hw=none");
        // Android 6 系统信任库没有 Let's Encrypt 的 ISRG Root X1（API 25 才加入），
        // VLC native 只读系统 CA（so 内不带根），导致 LE 新链的源全部在打开阶段失败。
        // 复用 app 自带的 Mozilla CA 包，让 VLC 的 gnutls 也能验通这些链。
        String trustDir = prepareBundledTrustDir(context);
        if (trustDir != null) args.add("--gnutls-dir-trust=" + trustDir);
        // VLC native 日志默认走 Android logcat，不是 logcat 的反面：libvlc Android 构建里
        // --file-logging 即使参数存在也不会真正落盘（实测 vlc-native.log 为 0 字节）。
        // 所以 debug 时用 --verbose=2 提高 native 输出级别，再由 prepareNativeLog() 捕获 logcat 写文件。
        if (com.fongmi.android.tv.setting.Setting.isDebugLog()) args.add("--verbose=2");
        prepareNativeLog(context);
        libVLC = new LibVLC(context, args);
        vlc = new MediaPlayer(libVLC);
        vlc.setEventListener(this);
        hlsProxy = new VlcHlsProxy();
    }

    /** debug 开启时捕获 logcat 里的 VLC native 日志到文件，供 /debug/vlc 导出。只启动一次。 */
    private static void prepareNativeLog(Context context) {
        if (!com.fongmi.android.tv.setting.Setting.isDebugLog()) return;
        if (NATIVE_LOG_CAPTURED.compareAndSet(false, true)) {
            File file = new File(context.getFilesDir(), "vlc-native.log");
            try {
                new FileOutputStream(file, false).close();
                Thread thread = new Thread(() -> captureLogcat(file), "vlc-native-log");
                thread.setDaemon(true);
                thread.start();
                SpiderDebug.log("vlc-native", "capture started file=%s", file.getAbsolutePath());
            } catch (Throwable e) {
                SpiderDebug.log("vlc-native", "start failed %s: %s", e.getClass().getSimpleName(), e.getMessage() == null ? "" : e.getMessage());
            }
        }
    }

    /** 过滤 VLC 相关 tag 的 logcat 行写入文件；VLC native 日志的 tag 不固定，用宽松匹配。 */
    private static void captureLogcat(File file) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "threadtime"});
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                 java.io.Writer writer = new java.io.OutputStreamWriter(new FileOutputStream(file, true), java.nio.charset.StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("VLC") || line.contains("vlc") || line.contains("GnuTLS") || line.contains("gnutls") || line.contains("libvlc")) {
                        writer.write(line);
                        writer.write('\n');
                        writer.flush();
                    }
                }
            }
        } catch (Throwable e) {
            SpiderDebug.log("vlc-native", "capture failed %s: %s", e.getClass().getSimpleName(), e.getMessage() == null ? "" : e.getMessage());
        } finally {
            if (process != null) process.destroy();
        }
    }

    /** 把 assets/cacert.pem 落到 filesDir 供 VLC gnutls 读取；失败返回 null，退回系统行为。 */
    private static String prepareBundledTrustDir(Context context) {
        File dir = new File(context.getFilesDir(), "vlc-trust");
        File target = new File(dir, "cacert.pem");
        try {
            if (!target.exists() || target.length() == 0) {
                if (!dir.exists() && !dir.mkdirs()) {
                    SpiderDebug.log("vlc-trust", "mkdir failed %s", dir.getAbsolutePath());
                    return null;
                }
                try (InputStream in = context.getAssets().open("cacert.pem");
                     OutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[16384];
                    int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
            }
            SpiderDebug.log("vlc-trust", "ready dir=%s size=%d sdk=%d", dir.getAbsolutePath(), target.length(), android.os.Build.VERSION.SDK_INT);
            return dir.getAbsolutePath();
        } catch (Throwable e) {
            SpiderDebug.log("vlc-trust", "failed %s: %s", e.getClass().getSimpleName(), e.getMessage() == null ? "" : e.getMessage());
            return null;
        }
    }

    int getDecode() {
        return decode;
    }

    Tracks getCurrentTracksSnapshot() {
        return currentTracks;
    }

    @Nullable
    Format getSelectedVideoFormatSnapshot() {
        Format format = findSelectedFormat(C.TRACK_TYPE_VIDEO);
        return format;
    }

    @Nullable
    Format getSelectedAudioFormatSnapshot() {
        return findSelectedFormat(C.TRACK_TYPE_AUDIO);
    }

    private Format findSelectedFormat(int trackType) {
        if (currentTracks == null) return null;
        for (Tracks.Group group : currentTracks.getGroups()) {
            if (group.getType() != trackType) continue;
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i)) return group.getTrackFormat(i);
            }
        }
        return null;
    }

    String getErrorMessageSnapshot() {
        return playerError == null ? "" : String.valueOf(playerError.getMessage());
    }

    boolean isLiveSnapshot() {
        long duration = durationMs();
        return duration == C.TIME_UNSET || duration < java.util.concurrent.TimeUnit.MINUTES.toMillis(1);
    }

    @Override
    protected State getState() {
        int state = playbackState;
        boolean isLoading = loading && state != Player.STATE_IDLE && state != Player.STATE_ENDED;
        boolean firstFrame = newlyRenderedFirstFrame;
        newlyRenderedFirstFrame = false;
        State.Builder builder = new State.Builder()
                .setAvailableCommands(COMMANDS)
                .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(state)
                .setIsLoading(isLoading)
                .setPlayerError(playerError)
                .setRepeatMode(repeatOne ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF)
                .setPlaybackParameters(playbackParameters)
                .setVideoSize(videoSize)
                .setNewlyRenderedFirstFrame(firstFrame)
                .setVolume(volume)
                .setPlaylist(mediaItem == null ? ImmutableList.of() : ImmutableList.of(mediaItemData()))
                .setCurrentMediaItemIndex(mediaItem == null ? C.INDEX_UNSET : 0);
        if (mediaItem != null) {
            long duration = durationMs();
            long position = positionMs();
            long buffered = Math.max(position, Math.min(duration == C.TIME_UNSET ? position : duration, position + 1000));
            builder.setContentPositionMs(isPlayingInternal()
                    ? PositionSupplier.getExtrapolating(position, playbackParameters.speed)
                    : PositionSupplier.getConstant(position));
            builder.setContentBufferedPositionMs(PositionSupplier.getConstant(buffered));
            builder.setTotalBufferedDurationMs(PositionSupplier.getConstant(Math.max(0, buffered - position)));
        }
        return builder.build();
    }

    private MediaItemData mediaItemData() {
        long duration = durationMs();
        return new MediaItemData.Builder(mediaItem.mediaId)
                .setMediaItem(mediaItem)
                .setMediaMetadata(mediaItem.mediaMetadata)
                .setDurationUs(duration == C.TIME_UNSET ? C.TIME_UNSET : duration * 1000)
                .setIsSeekable(true)
                .setIsDynamic(false)
                .setTracks(currentTracks)
                .build();
    }

    private long durationMs() {
        if (!prepared) return C.TIME_UNSET;
        long length = vlc.getLength();
        return length > 0 ? length : C.TIME_UNSET;
    }

    private long positionMs() {
        if (!prepared) return 0;
        long time = vlc.getTime();
        return Math.max(0, time);
    }

    private boolean isPlayingInternal() {
        return prepared && playbackState == Player.STATE_READY && vlc.isPlaying();
    }

    private int openGeneration;

    private void openCurrent() {
        if (mediaItem == null) return;
        String url = mediaItem.localConfiguration == null ? null : mediaItem.localConfiguration.uri.toString();
        if (url == null || url.isEmpty()) {
            playerError = new PlaybackException("VLC empty url", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS);
            invalidateState();
            return;
        }
        final int generation = ++openGeneration;
        // AES-128 加密 HLS：本 libvlc 的 native 解密路径是坏的，走本地明文代理。
        // 探测要取 m3u8 + key，必须在后台线程做（播放器回调在主线程）。
        // 只有探测确认是加密流才启用代理，其余一律走原 URL，影响面压到最小。
        if (url.toLowerCase(java.util.Locale.US).contains(".m3u8")) {
            Map<String, String> headers = com.fongmi.android.tv.player.exo.ExoUtil.extractHeaders(mediaItem);
            new Thread(() -> {
                String playable = hlsProxy.proxy(url, headers);
                final String resolved = playable == null ? url : playable;
                new android.os.Handler(Looper.getMainLooper()).post(() -> {
                    if (generation != openGeneration) return; // 已被后续切源取代，丢弃
                    openUrl(resolved);
                });
            }, "vlc-hls-resolve").start();
            // 探测期间先把状态置为缓冲，避免界面以为还在播上一个源
            prepared = false;
            loading = true;
            playbackState = Player.STATE_BUFFERING;
            invalidateState();
            return;
        }
        openUrl(url);
    }

    private void openUrl(String url) {
        vlc.stop();
        // Switching source destroys the old Vout. Detach the views so the new Vout
        // can bind its own Surface; otherwise areViewsAttached() stays true and
        // attachOutput() is skipped, leaving the previous source's last frame on screen.
        clearOutput();
        // 不要在这里 clear()：加密流的 session 是后台 proxy() 刚建的，
        // 清掉它会导致 VLC 访问 /vlc/index.m3u8 时 expired。proxy() 内已有 TTL prune。
        releaseMedia();
        Media media = new Media(libVLC, Uri.parse(url));
        if (decode == PlayerEngine.SOFT) media.setHWDecoderEnabled(false, false);
        if (repeatOne) media.addOption(":input-repeat=65535");
        prepared = false;
        loading = true;
        playbackState = Player.STATE_BUFFERING;
        playerError = null;
        vlc.setMedia(media);
        media.release();
        attachOutput();
        if (playWhenReady) vlc.play();
        invalidateState();
    }

    private void releaseMedia() {
        org.videolan.libvlc.interfaces.IMedia media = vlc.getMedia();
        if (media != null) media.release();
    }

    private void attachOutput() {
        IVLCVout vout = vlc.getVLCVout();
        if (videoOutput == null || vout.areViewsAttached()) return;
        try {
            if (videoOutput instanceof SurfaceView surfaceView) {
                vout.setVideoView(surfaceView);
            } else if (videoOutput instanceof TextureView textureView) {
                vout.setVideoView(textureView);
            } else if (videoOutput instanceof SurfaceHolder holder) {
                vout.setVideoSurface((Surface) null, holder);
            } else if (videoOutput instanceof Surface surface) {
                vout.setVideoSurface(surface, null);
            } else {
                return;
            }
            if (videoOutput instanceof View view) view.addOnLayoutChangeListener(layoutListener);
            vout.attachViews(this);
            Log.d(TAG, "attachOutput " + describeOutput(videoOutput));
        } catch (Throwable error) {
            clearOutput();
        }
    }

    /**
     * VLC reports the decoded video geometry here. Feed the display aspect ratio into
     * media3's VideoSize so PlayerView lays out the SurfaceView correctly, and tell VLC
     * the ACTUAL surface pixel size via setWindowSize. Passing the decoded video size
     * (the old behaviour) makes VLC lay the picture out as if the window were 1920x1080
     * while the surface is only e.g. 960x540, producing a shrunken, corner-anchored
     * image in fullscreen.
     */
    @Override
    public void onNewVideoLayout(IVLCVout vout, int width, int height,
                                 int visibleWidth, int visibleHeight, int sarNum, int sarDen) {
        int w = visibleWidth > 0 ? visibleWidth : width;
        int h = visibleHeight > 0 ? visibleHeight : height;
        Log.d(TAG, "layout cb video=" + width + "x" + height + " visible=" + w + "x" + h
                + " sar=" + sarNum + "/" + sarDen + " output=" + describeOutput(videoOutput));
        if (w > 0 && h > 0) {
            publishVideoSize(w, h, sarNum, sarDen);
        } else {
            // VLC 3.7.8 reports 0x0 here in this binding; fall back to the current video track.
            probeVideoSize();
        }
        applyWindowSize();
    }

    /** Read decoded geometry from VLC's current video track and publish it to media3. */
    private void probeVideoSize() {
        try {
            org.videolan.libvlc.interfaces.IMedia.VideoTrack track = vlc.getCurrentVideoTrack();
            if (track != null && track.width > 0 && track.height > 0) {
                publishVideoSize(track.width, track.height, track.sarNum, track.sarDen);
            }
        } catch (Throwable error) {
            Log.w(TAG, "probeVideoSize failed", error);
        }
    }

    private void publishVideoSize(int w, int h, int sarNum, int sarDen) {
        float sar = sarDen > 0 && sarNum > 0 ? (float) sarNum / sarDen : 1f;
        int pixelWidth = Math.round(w * sar);
        VideoSize size = new VideoSize(pixelWidth, h);
        if (!size.equals(videoSize)) {
            videoSize = size;
            Log.d(TAG, "publishVideoSize " + pixelWidth + "x" + h + " sar=" + sarNum + "/" + sarDen);
            invalidateState();
        }
    }

    /** Tell VLC the real pixel size of the current output surface. */
    private void applyWindowSize() {
        if (videoOutput == null) return;
        int[] size = outputSize(videoOutput);
        if (size == null || size[0] <= 0 || size[1] <= 0) return;
        try {
            vlc.getVLCVout().setWindowSize(size[0], size[1]);
            Log.d(TAG, "setWindowSize " + size[0] + "x" + size[1]);
        } catch (Throwable error) {
            Log.w(TAG, "setWindowSize failed", error);
        }
    }

    private static int[] outputSize(Object output) {
        if (output instanceof SurfaceView surfaceView)
            return new int[]{surfaceView.getWidth(), surfaceView.getHeight()};
        if (output instanceof TextureView textureView)
            return new int[]{textureView.getWidth(), textureView.getHeight()};
        if (output instanceof SurfaceHolder holder) {
            android.graphics.Rect frame = holder.getSurfaceFrame();
            return new int[]{frame.width(), frame.height()};
        }
        return null;
    }

    private static String describeOutput(Object output) {
        int[] size = outputSize(output);
        String cls = output == null ? "null" : output.getClass().getSimpleName();
        return cls + (size == null ? "" : " " + size[0] + "x" + size[1]);
    }

    private final View.OnLayoutChangeListener layoutListener = (v, l, t, r, b, ol, ot, or, ob) -> {
        if (r - l != or - ol || b - t != ob - ot) {
            Log.d(TAG, "output resized " + describeOutput(videoOutput));
            applyWindowSize();
        }
    };

    private void clearOutput() {
        try {
            IVLCVout vout = vlc.getVLCVout();
            if (vout.areViewsAttached()) vout.detachViews();
        } catch (Throwable ignored) {
        }
    }

    private void clearVideoOutput() {
        if (videoOutput instanceof View view) view.removeOnLayoutChangeListener(layoutListener);
        clearOutput();
        videoOutput = null;
    }

    /**
     * Build the media3 track list from VLC's track descriptions. This fork encodes the
     * track type in the TrackGroup id ("vlc:<type>:<id>") so group.getType() resolves
     * correctly; Format.id is "<type>:<id>" which the UI stores as Track.playerId and
     * is later parsed back by VlcPlayerEngine.setTrack().
     */
    private void refreshTracks() {
        try {
            List<Tracks.Group> groups = new ArrayList<>();
            appendTracks(groups, C.TRACK_TYPE_VIDEO, vlc.getVideoTracks(), vlc.getVideoTrack());
            appendTracks(groups, C.TRACK_TYPE_AUDIO, vlc.getAudioTracks(), vlc.getAudioTrack());
            appendTracks(groups, C.TRACK_TYPE_TEXT, vlc.getSpuTracks(), vlc.getSpuTrack());
            currentTracks = groups.isEmpty() ? Tracks.EMPTY : new Tracks(groups);
        } catch (Throwable error) {
            currentTracks = Tracks.EMPTY;
        }
        invalidateState();
    }

    private void appendTracks(List<Tracks.Group> groups, int type,
                              MediaPlayer.TrackDescription[] tracks, int selectedId) {
        if (tracks == null) return;
        for (MediaPlayer.TrackDescription track : tracks) {
            String mime = type == C.TRACK_TYPE_VIDEO ? androidx.media3.common.MimeTypes.VIDEO_UNKNOWN
                    : type == C.TRACK_TYPE_AUDIO ? androidx.media3.common.MimeTypes.AUDIO_UNKNOWN
                    : androidx.media3.common.MimeTypes.TEXT_UNKNOWN;
            Format format = new Format.Builder()
                    .setId(type + ":" + track.id)
                    .setLabel(track.name)
                    .setSampleMimeType(mime)
                    .build();
            TrackGroup group = new TrackGroup("vlc:" + type + ":" + track.id, format);
            boolean selected = track.id == selectedId;
            groups.add(new Tracks.Group(group, false, new int[]{C.FORMAT_HANDLED}, new boolean[]{selected}));
        }
    }

    /** Select a VLC track by its native id. type is a media3 C.TRACK_TYPE_* value. */
    void selectTrack(int type, int trackId) {
        switch (type) {
            case C.TRACK_TYPE_VIDEO -> vlc.setVideoTrack(trackId);
            case C.TRACK_TYPE_AUDIO -> vlc.setAudioTrack(trackId);
            case C.TRACK_TYPE_TEXT -> vlc.setSpuTrack(trackId);
            default -> {
            }
        }
        refreshTracks();
    }

    void resetTrack() {
        int video = firstTrackId(vlc.getVideoTracks());
        int audio = firstTrackId(vlc.getAudioTracks());
        if (video >= 0) vlc.setVideoTrack(video);
        if (audio >= 0) vlc.setAudioTrack(audio);
        vlc.setSpuTrack(-1);
        refreshTracks();
    }

    private static int firstTrackId(MediaPlayer.TrackDescription[] tracks) {
        return tracks != null && tracks.length > 0 ? tracks[0].id : -1;
    }

    @Override
    protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
        mediaItem = mediaItems.isEmpty() ? null : mediaItems.get(0);
        openGeneration++; // 让尚未完成的明文代理探测失效
        pendingSeekMs = startPositionMs > 0 ? startPositionMs : C.TIME_UNSET;
        playbackState = Player.STATE_IDLE;
        loading = false;
        prepared = false;
        playerError = null;
        currentTracks = Tracks.EMPTY;
        videoSize = VideoSize.UNKNOWN;
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleAddMediaItems(int index, List<MediaItem> mediaItems) {
        if (mediaItem == null && !mediaItems.isEmpty()) mediaItem = mediaItems.get(0);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleReplaceMediaItems(int fromIndex, int toIndex, List<MediaItem> mediaItems) {
        mediaItem = mediaItems.isEmpty() ? null : mediaItems.get(0);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRemoveMediaItems(int fromIndex, int toIndex) {
        mediaItem = null;
        playbackState = Player.STATE_IDLE;
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handlePrepare() {
        openCurrent();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
        this.playWhenReady = playWhenReady;
        if (prepared) {
            if (playWhenReady) vlc.play();
            else vlc.pause();
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        stopInternal();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRelease() {
        openGeneration++;
        stopInternal();
        clearOutput();
        hlsProxy.release();
        vlc.setEventListener(null);
        vlc.release();
        libVLC.release();
        return Futures.immediateVoidFuture();
    }

    private void stopInternal() {
        try {
            vlc.stop();
        } catch (Throwable ignored) {
        }
        releaseMedia();
        prepared = false;
        loading = false;
        playbackState = Player.STATE_IDLE;
        currentTracks = Tracks.EMPTY;
        videoSize = VideoSize.UNKNOWN;
        invalidateState();
    }

    @Override
    protected ListenableFuture<?> handleSetRepeatMode(int repeatMode) {
        repeatOne = repeatMode == Player.REPEAT_MODE_ONE;
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
        if (positionMs == C.TIME_UNSET) positionMs = 0;
        pendingSeekMs = positionMs;
        if (prepared) vlc.setTime(positionMs, false);
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetPlaybackParameters(PlaybackParameters playbackParameters) {
        this.playbackParameters = playbackParameters;
        vlc.setRate(playbackParameters.speed);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetVolume(float volume, int volumeOperationType) {
        this.volume = volume;
        vlc.setVolume(Math.round(volume * 100));
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetVideoOutput(Object videoOutput) {
        this.videoOutput = videoOutput;
        attachOutput();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleClearVideoOutput(@Nullable Object videoOutput) {
        if (videoOutput == null || videoOutput == this.videoOutput) clearVideoOutput();
        return Futures.immediateVoidFuture();
    }

    @Override
    public void onEvent(MediaPlayer.Event event) {
        switch (event.type) {
            case MediaPlayer.Event.Opening -> {
                loading = true;
                playbackState = Player.STATE_BUFFERING;
                invalidateState();
            }
            case MediaPlayer.Event.Buffering -> {
                loading = event.getBuffering() < 100;
                invalidateState();
            }
            case MediaPlayer.Event.Playing -> {
                prepared = true;
                loading = false;
                playbackState = Player.STATE_READY;
                vlc.setRate(playbackParameters.speed);
                vlc.setVolume(Math.round(volume * 100));
                if (pendingSeekMs != C.TIME_UNSET) {
                    vlc.setTime(pendingSeekMs, false);
                    pendingSeekMs = C.TIME_UNSET;
                }
                refreshTracks();
                probeVideoSize();
                invalidateState();
            }
            case MediaPlayer.Event.Paused -> {
                playbackState = Player.STATE_READY;
                invalidateState();
            }
            case MediaPlayer.Event.Stopped -> {
                playbackState = Player.STATE_IDLE;
                invalidateState();
            }
            case MediaPlayer.Event.EndReached -> {
                playbackState = Player.STATE_ENDED;
                invalidateState();
            }
            case MediaPlayer.Event.EncounteredError -> {
                playerError = new PlaybackException("VLC playback error", null,
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
                loading = false;
                playbackState = Player.STATE_IDLE;
                invalidateState();
            }
            case MediaPlayer.Event.Vout -> {
                // Vout is created asynchronously after play(); attach here if the surface
                // arrived earlier, otherwise the first frames render at the wrong size.
                if (videoOutput != null && !vlc.getVLCVout().areViewsAttached()) attachOutput();
                probeVideoSize();
                newlyRenderedFirstFrame = true;
                invalidateState();
            }
            case MediaPlayer.Event.LengthChanged, MediaPlayer.Event.TimeChanged -> invalidateState();
            case MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected, MediaPlayer.Event.ESDeleted -> {
                refreshTracks();
                probeVideoSize();
            }
            default -> {
            }
        }
    }
}
