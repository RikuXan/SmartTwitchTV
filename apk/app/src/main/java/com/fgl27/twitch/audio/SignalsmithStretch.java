package com.fgl27.twitch.audio;

import java.nio.ByteBuffer;

public final class SignalsmithStretch {

    private static final boolean AVAILABLE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("signalsmithstretch");
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
        }
        AVAILABLE = loaded;
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    private long handle;

    public SignalsmithStretch(int channelCount, int blockSamples, int intervalSamples) {
        handle = nativeCreate(channelCount, blockSamples, intervalSamples);
        if (handle == 0) {
            throw new IllegalStateException("SignalsmithStretch native create failed");
        }
    }

    public void setRate(float rate) {
        nativeSetRate(handle, rate);
    }

    public int inputLatencyFrames() {
        return nativeInputLatency(handle);
    }

    public int outputLatencyFrames() {
        return nativeOutputLatency(handle);
    }

    public int process(ByteBuffer input, int inputOffsetBytes, int inputFrames, ByteBuffer output, int outputCapacityFrames) {
        return nativeProcess(handle, input, inputOffsetBytes, inputFrames, output, outputCapacityFrames);
    }

    public int drainCapacityFrames() {
        return nativeDrainCapacity(handle);
    }

    public int drain(ByteBuffer output, int outputCapacityFrames) {
        return nativeDrain(handle, output, outputCapacityFrames);
    }

    public void flush() {
        nativeReset(handle);
    }

    public void release() {
        if (handle != 0) {
            nativeRelease(handle);
            handle = 0;
        }
    }

    private static native long nativeCreate(int channels, int blockSamples, int intervalSamples);

    private static native void nativeSetRate(long handle, float rate);

    private static native int nativeInputLatency(long handle);

    private static native int nativeOutputLatency(long handle);

    private static native int nativeProcess(long handle, ByteBuffer input, int inputOffsetBytes, int inputFrames, ByteBuffer output, int outputCapacityFrames);

    private static native int nativeDrainCapacity(long handle);

    private static native int nativeDrain(long handle, ByteBuffer output, int outputCapacityFrames);

    private static native void nativeReset(long handle);

    private static native void nativeRelease(long handle);
}
