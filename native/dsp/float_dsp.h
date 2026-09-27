/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * 32-bit float DSP chain for lossless / high-quality playback.
 */

#ifndef ARCHIVETUNE_NATIVE_DSP_FLOAT_DSP_H_
#define ARCHIVETUNE_NATIVE_DSP_FLOAT_DSP_H_

#include <cstdint>
#include <cstddef>
#include <vector>

namespace archivetune::dsp {

/**
 * A stateful 32-bit float processing chain, one instance per player.
 *
 * Stages (in order):
 *  1. DC blocker — a one-pole highpass at ~5 Hz per channel. Lossless masters
 *     occasionally carry a small DC offset (a mastering-console artifact);
 *     removing it costs nothing audible and restores full headroom for the
 *     limiter.
 *  2. Soft-knee peak limiter — ceiling at -0.5 dBFS with a 6 dB quadratic
 *     knee, ~5 ms attack / ~150 ms release. Below the knee it is exactly
 *     unity: engaged tracks are bit-transparent unless a peak would otherwise
 *     clip, which matters for lossless masters that were normalized hot.
 *  3. TPDF dither — applied only on the float-to-int16 reduction (the default
 *     sink path), so the word-length reduction carries no correlated
 *     truncation distortion. The float output path skips it entirely.
 *
 * All state is per-channel and reset() clears it. None of this allocates
 * after construction (the limiter/dither state is fixed-size), so the audio
 * thread never allocates.
 */
class FloatDsp {
public:
    FloatDsp(int sampleRate, int channels);

    void setEngaged(bool engaged) { engaged_ = engaged; }
    bool engaged() const { return engaged_; }

    /** Ceiling for the limiter, in dBFS (<= 0). */
    void setLimiterCeilingDb(float ceilingDb);

    /** In-place processing of interleaved float PCM. */
    void process(float* data, size_t frames);

    /**
     * Converts interleaved float PCM (already processed) to int16 with TPDF
     * dither. Length: frames * channels.
     */
    void toShort(const float* in, int16_t* out, size_t samples);

    void reset();

private:
    float limiterGainFor(float envelopeDb) const;

    int sampleRate_;
    int channels_;

    bool engaged_;

    // DC blocker state (per channel).
    std::vector<float> dcXm1_;
    std::vector<float> dcYm1_;
    float dcR_;

    // Limiter (per instance — the gain applies to all channels equally so
    // the stereo image never shifts).
    float ceilingDb_;
    float envelope_;
    float attackCoeff_;
    float releaseCoeff_;

    // Dither state.
    uint32_t rngState_;
};

}  // namespace archivetune::dsp

#endif  // ARCHIVETUNE_NATIVE_DSP_FLOAT_DSP_H_
