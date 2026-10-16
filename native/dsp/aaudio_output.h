/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * Oboe-backed exclusive float output stream — the USB-direct path (no
 * AudioFlinger mixer between the app and the USB DAC). Built on Google Oboe
 * (https://github.com/google/oboe) so stream lifecycle, disconnect detection
 * and blocking writes follow the library's battle-tested semantics instead
 * of hand-rolled AAudio C calls.
 */

#ifndef ARCHIVETUNE_NATIVE_DSP_AAUDIO_OUTPUT_H_
#define ARCHIVETUNE_NATIVE_DSP_AAUDIO_OUTPUT_H_

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>

namespace archivetune::dsp {

class AaudioExclusiveStream {
public:
    AaudioExclusiveStream() = default;
    ~AaudioExclusiveStream();

    AaudioExclusiveStream(const AaudioExclusiveStream&) = delete;
    AaudioExclusiveStream& operator=(const AaudioExclusiveStream&) = delete;

    struct Timestamp {
        int64_t framePosition;
        int64_t timeNanos;
        bool valid;
    };

    /**
     * Opens an output stream. Returns 0 on success, otherwise a negative
     * oboe::Result value (these mirror the AAudio error codes the Kotlin
     * layer already handles).
     *
     * @param deviceId 0 routes to the default device; a positive AudioManager
     *        AudioDeviceInfo id pins the stream to that device (a USB DAC).
     * @param exclusive when true the stream requests EXCLUSIVE sharing; the
     *        open fails if the HAL cannot grant it (caller falls back).
     */
    int32_t open(int32_t sampleRate,
                 int32_t channels,
                 int32_t deviceId,
                 bool exclusive,
                 int32_t usage,
                 int32_t contentType,
                 int32_t bufferCapacityFrames);

    /**
     * Blocking write of interleaved float PCM already scaled by volume.
     * Returns frames written, or a negative oboe::Result error.
     *
     * @param channels the interleaved layout of @p data. The pointer stride
     *        between consecutive frames comes from THIS argument, not the
     *        stream's own channel count — the two are equal in every correct
     *        configuration, and a mismatch is surfaced as an error instead of
     *        walked past the end of the caller's buffer.
     */
    int64_t write(const float* data, size_t frames, int32_t channels, int64_t timeoutMs);

    int32_t requestStart();
    int32_t requestPause();
    int32_t requestFlush();
    int32_t requestStop();

    void close();

    Timestamp timestamp() const;

    int32_t sampleRate() const;
    int32_t channelCount() const;
    int32_t sessionId() const;
    int64_t framesWritten() const;
    int32_t xRunCount() const;
    int64_t bufferCapacityFrames() const;
    int64_t bufferSizeFrames() const;
    /** True when the HAL actually granted EXCLUSIVE sharing. */
    bool isExclusive() const;
    bool isOpen() const;

private:
    void closeLocked();

    class DisconnectCallback : public oboe::AudioStreamErrorCallback {
    public:
        explicit DisconnectCallback(std::atomic<bool>& disconnected)
            : disconnected_(disconnected) {}

        void onErrorBeforeClose(oboe::AudioStream* /* stream */,
                                oboe::Result /* error */) override {
            disconnected_.store(true, std::memory_order_release);
        }

        void onErrorAfterClose(oboe::AudioStream* /* stream */,
                               oboe::Result /* error */) override {}

    private:
        std::atomic<bool>& disconnected_;
    };

    mutable std::mutex mutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::shared_ptr<DisconnectCallback> errorCallback_;
    bool exclusiveGranted_ = false;
    bool open_ = false;
    int32_t sampleRate_ = 0;
    int32_t channels_ = 0;
    int64_t framesWritten_ = 0;
    std::atomic<bool> disconnected_{false};
};

}  // namespace archivetune::dsp

#endif  // ARCHIVETUNE_NATIVE_DSP_AAUDIO_OUTPUT_H_
