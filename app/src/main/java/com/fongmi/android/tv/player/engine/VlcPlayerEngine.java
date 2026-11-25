package com.fongmi.android.tv.player.engine;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.player.PlaybackTrace;
import com.fongmi.android.tv.player.PlaybackResourceClassifier;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.fongmi.android.tv.player.exo.TrackUtil;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.List;

/** VLC-backed {@link PlayerEngine}. Mirrors IjkPlayerEngine's shape so PlayerManager needs no VLC-specific logic. */
@UnstableApi
public class VlcPlayerEngine implements PlayerEngine {

    private VlcPlayer player;
    private PlaySpec spec;
    private int decode;

    public VlcPlayerEngine(int decode, Player.Listener listener) {
        this.decode = decode;
        this.player = buildPlayer(decode, listener);
    }

    private VlcPlayer buildPlayer(int decode, Player.Listener listener) {
        VlcPlayer created = new VlcPlayer(decode);
        created.addListener(listener);
        return created;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public void release() {
        player.release();
    }

    @Override
    public Player rebuild(Player.Listener listener) {
        player.release();
        PlaybackTrace.log("player-engine", getPlaybackTraceId(), "rebuild vlc decode=%d", decode);
        return player = buildPlayer(decode, listener);
    }

    @Override
    public int getDecode() {
        return decode;
    }

    @Override
    public void setDecode(int decode) {
        this.decode = decode;
    }

    @Override
    public boolean isHard() {
        return decode == HARD;
    }

    @Override
    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[decode];
    }

    @Override
    public void start(PlaySpec spec) {
        start(spec, true);
    }

    @Override
    public void start(PlaySpec spec, boolean playWhenReady) {
        start(spec, C.TIME_UNSET, playWhenReady);
    }

    @Override
    public void start(PlaySpec spec, long position, boolean playWhenReady) {
        this.spec = spec;
        PlaybackTrace.log("player-engine", getPlaybackTraceId(),
                "start vlc decode=%d position=%d play=%s urlLen=%d",
                decode, position, playWhenReady, spec.getUrl() == null ? 0 : spec.getUrl().length());
        MediaItem item = ExoUtil.getMediaItem(spec, decode);
        if (position > 0) player.setMediaItem(item, position);
        else player.setMediaItem(item);
        player.prepare();
        if (playWhenReady) player.play();
        else player.pause();
    }

    @Override
    public void restart(PlaySpec spec, long position, boolean playWhenReady) {
        player.stop();
        start(spec, position, playWhenReady);
    }

    @Override
    public void setMetadata(MediaMetadata data) {
        MediaItem current = player.getCurrentMediaItem();
        if (current != null) player.replaceMediaItem(player.getCurrentMediaItemIndex(),
                current.buildUpon().setMediaMetadata(data).build());
    }

    @Override
    public boolean isLive() {
        return player.isLiveSnapshot();
    }

    @Override
    public boolean isVod() {
        return !isLive();
    }

    @Override
    public void setTrack(List<Track> tracks) {
        if (tracks == null) return;
        for (Track track : tracks) {
            if (track.isDisabled()) {
                if (track.getType() == C.TRACK_TYPE_TEXT) player.selectTrack(C.TRACK_TYPE_TEXT, -1);
                continue;
            }
            int[] ids = parseTrackId(track.getPlayerId());
            if (ids != null) player.selectTrack(ids[0], ids[1]);
        }
    }

    @Override
    public void resetTrack() {
        player.resetTrack();
    }

    /** playerId is Format.id = "<media3Type>:<vlcNativeTrackId>". */
    private static int[] parseTrackId(String playerId) {
        if (playerId == null) return null;
        int split = playerId.indexOf(':');
        if (split <= 0 || split + 1 >= playerId.length()) return null;
        try {
            int type = Integer.parseInt(playerId.substring(0, split));
            int id = Integer.parseInt(playerId.substring(split + 1));
            return new int[]{type, id};
        } catch (NumberFormatException error) {
            return null;
        }
    }

    @Override
    public boolean haveTrack(int type) {
        return TrackUtil.count(getCurrentTracks(), type) > 0;
    }

    @Override
    public Tracks getCurrentTracks() {
        return player.getCurrentTracksSnapshot();
    }

    @Override
    public Format getVideoFormat() {
        return TrackUtil.selectedFormat(getCurrentTracks(), C.TRACK_TYPE_VIDEO);
    }

    @Override
    public PlaybackFactsSnapshot getPlaybackFactsSnapshot() {
        Format video = player.getSelectedVideoFormatSnapshot();
        Format audio = player.getSelectedAudioFormatSnapshot();
        return new PlaybackFactsSnapshot(video, audio, video, audio, "", "",
                DecoderKind.UNKNOWN, null, "", "", null);
    }

    @Override
    public String getPlaybackTraceId() {
        return spec == null ? PlaybackTrace.NONE : spec.getPlaybackTraceId();
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        if (e != null && e.getMessage() != null && !e.getMessage().isEmpty()) return e.getMessage();
        return e == null ? "VLC playback failed" : PlaybackException.getErrorCodeName(e.errorCode);
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        PlaybackTrace.log("player-engine", getPlaybackTraceId(),
                "handleError vlc code=%d errorType=%s action=fatal",
                e == null ? PlaybackException.ERROR_CODE_UNSPECIFIED : e.errorCode,
                e == null ? "none" : e.getClass().getSimpleName());
        return ErrorAction.FATAL;
    }
}
