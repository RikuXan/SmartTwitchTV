package com.fgl27.twitch.audio;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.Util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class SignalsmithAudioProcessor implements AudioProcessor {

    private static final float CLOSE_THRESHOLD = 0.0001f;
    private static final int MIN_OUTPUT_FRAMES_FOR_SCALING = 4096;
    private static final int BLOCK_MS = 100;
    private static final int INTERVAL_MS = 40;

    private float speed = 1f;

    private AudioFormat pendingAudioFormat = AudioFormat.NOT_SET;
    private AudioFormat audioFormat = AudioFormat.NOT_SET;
    private boolean pendingRecreation;

    private SignalsmithStretch stretch;
    private int inputLatencyFrames;
    private int outputLatencyFrames;

    private ByteBuffer inputScratch = EMPTY_BUFFER;
    private ByteBuffer processBuffer = EMPTY_BUFFER;
    private ByteBuffer drainBuffer = EMPTY_BUFFER;
    private ByteBuffer outputBuffer = EMPTY_BUFFER;
    private boolean pendingDrain;

    private long inputFrames;
    private long outputFrames;
    private boolean inputEnded;

    public void setSpeed(float speed) {
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
        return SignalsmithStretch.isAvailable()
                && pendingAudioFormat.sampleRate != Format.NO_VALUE
                && Math.abs(speed - 1f) >= CLOSE_THRESHOLD;
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
            inputFrames += frames;
            outputFrames += produced;
            processBuffer.position(0);
            processBuffer.limit(produced * frameSize);
            outputBuffer = processBuffer;
        } else if (produced == 0) {
            inputFrames += frames;
        }
    }

    @Override
    public void queueEndOfStream() {
        inputEnded = true;
        if (stretch != null) {
            int frameSize = audioFormat.bytesPerFrame;
            int capacityFrames = stretch.drainCapacityFrames();
            drainBuffer = ensureCapacity(drainBuffer, capacityFrames * frameSize);
            int produced = stretch.drain(drainBuffer, capacityFrames);
            if (produced > 0) {
                outputFrames += produced;
                drainBuffer.position(0);
                drainBuffer.limit(produced * frameSize);
                pendingDrain = true;
            }
        }
    }

    @Override
    public ByteBuffer getOutput() {
        ByteBuffer output = outputBuffer;
        if (output == EMPTY_BUFFER && pendingDrain) {
            output = drainBuffer;
            pendingDrain = false;
        }
        outputBuffer = EMPTY_BUFFER;
        return output;
    }

    @Override
    public boolean isEnded() {
        return inputEnded && outputBuffer == EMPTY_BUFFER && !pendingDrain;
    }

    @Override
    public long getDurationAfterProcessorApplied(long durationUs) {
        long in = processedInputFrames();
        long out = processedOutputFrames();
        if (out >= MIN_OUTPUT_FRAMES_FOR_SCALING && in > 0) {
            return Util.scaleLargeTimestamp(durationUs, out, in);
        }
        return (long) (durationUs / (double) speed);
    }

    public long getMediaDuration(long playoutDuration) {
        long in = processedInputFrames();
        long out = processedOutputFrames();
        if (out >= MIN_OUTPUT_FRAMES_FOR_SCALING && in > 0) {
            return Util.scaleLargeTimestamp(playoutDuration, in, out);
        }
        return (long) (playoutDuration * (double) speed);
    }

    private long processedInputFrames() {
        return Math.max(0, inputFrames - inputLatencyFrames);
    }

    private long processedOutputFrames() {
        return Math.max(0, outputFrames - outputLatencyFrames);
    }

    @Override
    public void flush() {
        if (isActive()) {
            audioFormat = pendingAudioFormat;
            if (pendingRecreation) {
                if (stretch != null) {
                    stretch.release();
                    stretch = null;
                }
                stretch = new SignalsmithStretch(
                        audioFormat.channelCount,
                        audioFormat.sampleRate * BLOCK_MS / 1000,
                        audioFormat.sampleRate * INTERVAL_MS / 1000);
                inputLatencyFrames = stretch.inputLatencyFrames();
                outputLatencyFrames = stretch.outputLatencyFrames();
                pendingRecreation = false;
            } else if (stretch != null) {
                stretch.flush();
            }
            if (stretch != null) {
                stretch.setRate(speed);
            }
        }
        outputBuffer = EMPTY_BUFFER;
        pendingDrain = false;
        inputFrames = 0;
        outputFrames = 0;
        inputEnded = false;
    }

    @Override
    public void reset() {
        speed = 1f;
        pendingAudioFormat = AudioFormat.NOT_SET;
        audioFormat = AudioFormat.NOT_SET;
        pendingRecreation = false;
        if (stretch != null) {
            stretch.release();
            stretch = null;
        }
        inputLatencyFrames = 0;
        outputLatencyFrames = 0;
        inputScratch = EMPTY_BUFFER;
        processBuffer = EMPTY_BUFFER;
        drainBuffer = EMPTY_BUFFER;
        outputBuffer = EMPTY_BUFFER;
        pendingDrain = false;
        inputFrames = 0;
        outputFrames = 0;
        inputEnded = false;
    }

    private static ByteBuffer ensureCapacity(ByteBuffer buffer, int capacityBytes) {
        if (buffer.capacity() < capacityBytes) {
            return ByteBuffer.allocateDirect(capacityBytes).order(ByteOrder.nativeOrder());
        }
        return buffer;
    }
}
