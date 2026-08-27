package com.fgl27.twitch.audio;

import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.audio.AudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class SignalsmithAudioProcessor implements AudioProcessor {

    private static final int BLOCK_MS = 100;
    private static final int INTERVAL_MS = 40;

    private float speed = 1f;

    private AudioFormat pendingAudioFormat = AudioFormat.NOT_SET;
    private AudioFormat audioFormat = AudioFormat.NOT_SET;
    private boolean pendingRecreation;

    private SignalsmithStretch stretch;
    private boolean configurationLogged;

    private ByteBuffer inputScratch = EMPTY_BUFFER;
    private ByteBuffer processBuffer = EMPTY_BUFFER;
    private ByteBuffer outputBuffer = EMPTY_BUFFER;

    private boolean inputEnded;
    private boolean parameterChangeDrain;

    public void setSpeed(float speed) {
        if (inputEnded) {
            parameterChangeDrain = true;
        }
        this.speed = speed;
    }

    @Override
    public AudioFormat configure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        if (inputAudioFormat.sampleRate != pendingAudioFormat.sampleRate
                || inputAudioFormat.channelCount != pendingAudioFormat.channelCount) {
            pendingRecreation = true;
        }
        pendingAudioFormat = inputAudioFormat;
        return inputAudioFormat;
    }

    @Override
    public boolean isActive() {
        return SignalsmithStretch.isAvailable() && pendingAudioFormat.sampleRate != Format.NO_VALUE;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        if (!inputBuffer.hasRemaining() || stretch == null) {
            return;
        }
        int frameSize = audioFormat.bytesPerFrame;
        int frames = inputBuffer.remaining() / frameSize;
        if (frames == 0) {
            return;
        }
        int inputBytes = frames * frameSize;

        ByteBuffer nativeInput;
        int nativeInputOffset;
        if (inputBuffer.isDirect()) {
            nativeInput = inputBuffer;
            nativeInputOffset = inputBuffer.position();
        } else {
            inputScratch = ensureCapacity(inputScratch, inputBytes);
            int savedLimit = inputBuffer.limit();
            inputBuffer.limit(inputBuffer.position() + inputBytes);
            inputScratch.clear();
            inputScratch.put(inputBuffer);
            inputBuffer.limit(savedLimit);
            inputBuffer.position(inputBuffer.position() - inputBytes);
            nativeInput = inputScratch;
            nativeInputOffset = 0;
        }

        int outputCapacityFrames = (int) Math.ceil(frames / (double) speed) + 2;
        processBuffer = ensureCapacity(processBuffer, outputCapacityFrames * frameSize);

        int produced = stretch.process(nativeInput, nativeInputOffset, frames, processBuffer, outputCapacityFrames);
        inputBuffer.position(inputBuffer.position() + inputBytes);
        if (produced > 0) {
            processBuffer.position(0);
            processBuffer.limit(produced * frameSize);
            outputBuffer = processBuffer;
        }
    }

    @Override
    public void queueEndOfStream() {
        inputEnded = true;
    }

    @Override
    public ByteBuffer getOutput() {
        ByteBuffer output = outputBuffer;
        outputBuffer = EMPTY_BUFFER;
        return output;
    }

    @Override
    public boolean isEnded() {
        return inputEnded && outputBuffer == EMPTY_BUFFER;
    }

    @Override
    public long getDurationAfterProcessorApplied(long durationUs) {
        return (long) (durationUs / (double) speed);
    }

    public long getMediaDuration(long playoutDuration) {
        return (long) (playoutDuration * (double) speed);
    }

    @Override
    public void flush() {
        if (isActive()) {
            audioFormat = pendingAudioFormat;
            if (pendingRecreation) {
                releaseStretch();
                stretch = new SignalsmithStretch(
                        audioFormat.channelCount,
                        audioFormat.sampleRate * BLOCK_MS / 1000,
                        audioFormat.sampleRate * INTERVAL_MS / 1000);
                pendingRecreation = false;
                logConfiguration();
            } else if (stretch != null && !parameterChangeDrain) {
                //Only a seek reaches flush() without a preceding queueEndOfStream(), a rate change must keep its history
                stretch.reset();
            }
            if (stretch != null) {
                stretch.setRate(speed);
            }
        }
        outputBuffer = EMPTY_BUFFER;
        inputEnded = false;
        parameterChangeDrain = false;
    }

    @Override
    public void reset() {
        speed = 1f;
        pendingAudioFormat = AudioFormat.NOT_SET;
        audioFormat = AudioFormat.NOT_SET;
        pendingRecreation = false;
        releaseStretch();
        inputScratch = EMPTY_BUFFER;
        processBuffer = EMPTY_BUFFER;
        outputBuffer = EMPTY_BUFFER;
        inputEnded = false;
        parameterChangeDrain = false;
    }

    private void releaseStretch() {
        if (stretch != null) {
            stretch.release();
            stretch = null;
        }
    }

    private void logConfiguration() {
        if (configurationLogged) {
            return;
        }
        configurationLogged = true;
        int latencyFrames = stretch.inputLatencyFrames() + stretch.outputLatencyFrames();
        Log.i("TwitchLL", "audio-stretcher=signalsmith block=" + BLOCK_MS + "ms interval=" + INTERVAL_MS
                + "ms latency=" + (latencyFrames * 1000L / audioFormat.sampleRate) + "ms rate="
                + audioFormat.sampleRate + " channels=" + audioFormat.channelCount);
    }

    private static ByteBuffer ensureCapacity(ByteBuffer buffer, int capacityBytes) {
        if (buffer.capacity() < capacityBytes) {
            return ByteBuffer.allocateDirect(capacityBytes).order(ByteOrder.nativeOrder());
        }
        return buffer;
    }
}
