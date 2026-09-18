package com.fgl27.twitch;

import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.exoplayer.upstream.Loader;
import androidx.media3.exoplayer.util.SntpClient;
import java.io.IOException;

final class TwitchNetworkClock {

    private static final long REFRESH_INTERVAL_MS = 30000;
    private static volatile long offsetMs = C.TIME_UNSET;
    private static long lastAttemptMs = C.TIME_UNSET;
    private static Loader loader;

    static long getOffsetMs() {
        return offsetMs;
    }

    static void refreshIfDue() {
        long nowMs = SystemClock.elapsedRealtime();
        if (lastAttemptMs != C.TIME_UNSET && nowMs - lastAttemptMs < REFRESH_INTERVAL_MS) return;
        if (loader == null) {
            loader = new Loader("TwitchClock");
            SntpClient.setMaxElapsedTimeUntilUpdateMs(REFRESH_INTERVAL_MS);
        }
        if (loader.isLoading()) return;
        lastAttemptMs = nowMs;
        loader.clearFatalError();
        SntpClient.initialize(loader, new SntpClient.InitializationCallback() {
            @Override
            public void onInitialized() {
                long refreshedOffsetMs = SntpClient.getElapsedRealtimeOffsetMs();
                if (refreshedOffsetMs == C.TIME_UNSET) return;
                long changeMs = offsetMs == C.TIME_UNSET ? 0 : refreshedOffsetMs - offsetMs;
                offsetMs = refreshedOffsetMs;
                TwitchDiagnosticLog.i("LTB-CLOCK-REFRESH offsetMs=" + offsetMs + " changeMs=" + changeMs);
            }

            @Override
            public void onInitializationFailed(IOException error) {
                TwitchDiagnosticLog.i("LTB-CLOCK-REFRESH failed=" + error.getClass().getSimpleName());
            }
        });
    }
}
