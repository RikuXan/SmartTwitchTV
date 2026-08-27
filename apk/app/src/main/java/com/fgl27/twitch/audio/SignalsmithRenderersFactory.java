package com.fgl27.twitch.audio;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

public final class SignalsmithRenderersFactory extends DefaultRenderersFactory {

    public SignalsmithRenderersFactory(Context context) {
        super(context);
    }

    @Nullable
    @Override
    protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
        if (!SignalsmithStretch.isAvailable()) {
            return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams);
        }
        return new DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(new SignalsmithAudioProcessorChain())
                .build();
    }
}
