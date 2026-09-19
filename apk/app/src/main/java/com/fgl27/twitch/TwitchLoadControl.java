package com.fgl27.twitch;

import androidx.media3.common.C;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.upstream.DefaultAllocator;

final class TwitchLoadControl extends DefaultLoadControl {
    private final TwitchLivePlaybackSpeedControl control;
    private final long legacyRecoveryMs;
    private boolean wasRecovering;
    private long lastThresholdMs = -1;

    TwitchLoadControl(int bufferMs, int targetBytes, TwitchLivePlaybackSpeedControl control) {
        super(new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE), 60000, 36000000,
            bufferMs, bufferMs + 1000, targetBytes, false, 0, false);
        this.control = control;
        legacyRecoveryMs = bufferMs + 1000L;
    }

    @Override
    public boolean shouldStartPlayback(Parameters parameters) {
        if (!parameters.rebuffering) {
            wasRecovering = false;
            return super.shouldStartPlayback(parameters);
        }
        long thresholdMs = control.getRecoveryThresholdMs(legacyRecoveryMs);
        boolean ready = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs,
            parameters.playbackSpeed) >= thresholdMs * 1000;
        if (!wasRecovering || lastThresholdMs != thresholdMs || ready) {
            TwitchDiagnosticLog.i("RECOVERY-FILL controlId=" + System.identityHashCode(control) +
                " thresholdMs=" + thresholdMs + " legacyMs=" + legacyRecoveryMs +
                " buf=" + parameters.bufferedDurationUs / 1000 + " ready=" + ready);
        }
        wasRecovering = !ready;
        lastThresholdMs = thresholdMs;
        return thresholdMs == legacyRecoveryMs ? super.shouldStartPlayback(parameters) : ready;
    }
}
