package com.fgl27.twitch.audio;

import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessorChain;
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor;

public final class SignalsmithAudioProcessorChain implements AudioProcessorChain {

    private final SilenceSkippingAudioProcessor silenceSkippingAudioProcessor;
    private final SignalsmithAudioProcessor signalsmithAudioProcessor;
    private final AudioProcessor[] audioProcessors;

    public SignalsmithAudioProcessorChain() {
        silenceSkippingAudioProcessor = new SilenceSkippingAudioProcessor();
        signalsmithAudioProcessor = new SignalsmithAudioProcessor();
        audioProcessors = new AudioProcessor[] {silenceSkippingAudioProcessor, signalsmithAudioProcessor};
    }

    @Override
    public AudioProcessor[] getAudioProcessors() {
        return audioProcessors;
    }

    @Override
    public PlaybackParameters applyPlaybackParameters(PlaybackParameters playbackParameters) {
        signalsmithAudioProcessor.setSpeed(playbackParameters.speed);
        return playbackParameters;
    }

    @Override
    public boolean applySkipSilenceEnabled(boolean skipSilenceEnabled) {
        silenceSkippingAudioProcessor.setEnabled(skipSilenceEnabled);
        return skipSilenceEnabled;
    }

    @Override
    public long getMediaDuration(long playoutDuration) {
        return signalsmithAudioProcessor.isActive()
                ? signalsmithAudioProcessor.getMediaDuration(playoutDuration)
                : playoutDuration;
    }

    @Override
    public long getSkippedOutputFrameCount() {
        return silenceSkippingAudioProcessor.getSkippedFrames();
    }
}
