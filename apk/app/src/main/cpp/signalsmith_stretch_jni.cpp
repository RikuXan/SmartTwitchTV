#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

#include "signalsmith-stretch/signalsmith-stretch.h"

namespace {

constexpr float kInt16Scale = 32768.0f;

struct Stretcher {
    signalsmith::stretch::SignalsmithStretch<float> stretch;
    int channels;
    double rate = 1.0;
    double owedOutputFrames = 0.0;
    std::vector<std::vector<float>> planarIn;
    std::vector<std::vector<float>> planarOut;
    std::vector<float*> inPtrs;
    std::vector<float*> outPtrs;

    Stretcher(int nChannels, int blockSamples, int intervalSamples)
        : channels(nChannels),
          planarIn(nChannels),
          planarOut(nChannels),
          inPtrs(nChannels),
          outPtrs(nChannels) {
        stretch.configure(nChannels, blockSamples, intervalSamples);
    }

    void ensureCapacity(int inFrames, int outFrames) {
        for (int c = 0; c < channels; ++c) {
            if (static_cast<int>(planarIn[c].size()) < inFrames) planarIn[c].resize(inFrames);
            if (static_cast<int>(planarOut[c].size()) < outFrames) planarOut[c].resize(outFrames);
            inPtrs[c] = planarIn[c].data();
            outPtrs[c] = planarOut[c].data();
        }
    }

    void deinterleave(const int16_t* input, int frames) {
        for (int c = 0; c < channels; ++c) {
            float* dst = planarIn[c].data();
            const int16_t* src = input + c;
            for (int i = 0; i < frames; ++i) {
                dst[i] = static_cast<float>(src[static_cast<size_t>(i) * channels]) / kInt16Scale;
            }
        }
    }

    void interleave(int16_t* output, int frames) const {
        for (int c = 0; c < channels; ++c) {
            const float* src = planarOut[c].data();
            int16_t* dst = output + c;
            for (int i = 0; i < frames; ++i) {
                float scaled = src[i] * kInt16Scale;
                scaled = std::min(std::max(scaled, -32768.0f), 32767.0f);
                dst[static_cast<size_t>(i) * channels] = static_cast<int16_t>(std::lrintf(scaled));
            }
        }
    }
};

Stretcher* fromHandle(jlong handle) {
    return reinterpret_cast<Stretcher*>(handle);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_fgl27_twitch_audio_SignalsmithStretch_nativeCreate(
        JNIEnv*, jclass, jint channels, jint blockSamples, jint intervalSamples) {
    if (channels <= 0 || blockSamples <= 0 || intervalSamples <= 0) return 0;
    return reinterpret_cast<jlong>(new Stretcher(channels, blockSamples, intervalSamples));
}

JNIEXPORT void JNICALL Java_com_fgl27_twitch_audio_SignalsmithStretch_nativeSetRate(
        JNIEnv*, jclass, jlong handle, jfloat rate) {
    Stretcher* s = fromHandle(handle);
    if (s != nullptr && rate > 0.0f) s->rate = rate;
}

JNIEXPORT jint JNICALL Java_com_fgl27_twitch_audio_SignalsmithStretch_nativeInputLatency(
        JNIEnv*, jclass, jlong handle) {
    Stretcher* s = fromHandle(handle);
    return s == nullptr ? 0 : s->stretch.inputLatency();
}

JNIEXPORT jint JNICALL Java_com_fgl27_twitch_audio_SignalsmithStretch_nativeOutputLatency(
        JNIEnv*, jclass, jlong handle) {
    Stretcher* s = fromHandle(handle);
    return s == nullptr ? 0 : s->stretch.outputLatency();
}

JNIEXPORT jint JNICALL Java_com_fgl27_twitch_audio_SignalsmithStretch_nativeProcess(
        JNIEnv* env, jclass, jlong handle, jobject inputBuffer, jint inputOffsetBytes,
        jint inputFrames, jobject outputBuffer, jint outputCapacityFrames) {
    Stretcher* s = fromHandle(handle);
    if (s == nullptr || inputFrames < 0) return -1;
    auto* inBase = static_cast<uint8_t*>(env->GetDirectBufferAddress(inputBuffer));
    auto* outBase = static_cast<int16_t*>(env->GetDirectBufferAddress(outputBuffer));
    if (inBase == nullptr || outBase == nullptr) return -1;
    const auto* in = reinterpret_cast<const int16_t*>(inBase + inputOffsetBytes);

    double owed = s->owedOutputFrames + inputFrames / s->rate;
    int outputFrames = std::min(static_cast<int>(owed), outputCapacityFrames);
    s->owedOutputFrames = owed - outputFrames;

    s->ensureCapacity(inputFrames, outputFrames);
    s->deinterleave(in, inputFrames);
    s->stretch.process(s->inPtrs.data(), inputFrames, s->outPtrs.data(), outputFrames);
    s->interleave(outBase, outputFrames);
    return outputFrames;
}

JNIEXPORT void JNICALL Java_com_fgl27_twitch_audio_SignalsmithStretch_nativeReset(
        JNIEnv*, jclass, jlong handle) {
    Stretcher* s = fromHandle(handle);
    if (s != nullptr) {
        s->stretch.reset();
        s->owedOutputFrames = 0.0;
    }
}

JNIEXPORT void JNICALL Java_com_fgl27_twitch_audio_SignalsmithStretch_nativeRelease(
        JNIEnv*, jclass, jlong handle) {
    delete fromHandle(handle);
}

}  // extern "C"
