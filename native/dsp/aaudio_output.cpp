/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * Oboe exclusive-mode float output — implementation.
 */

#include "aaudio_output.h"

#include <algorithm>
#include <ctime>

namespace archivetune::dsp {

AaudioExclusiveStream::~AaudioExclusiveStream() {
    close();
}

int32_t AaudioExclusiveStream::open(int32_t sampleRate,
                                     int32_t channels,
                                     int32_t deviceId,
                                     bool exclusive,
                                     int32_t usage,
                                     int32_t contentType,
                                     int32_t bufferCapacityFrames) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (open_) {
        closeLocked();
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setSampleRate(sampleRate);
    builder.setChannelCount(channels);
    builder.setSharingMode(exclusive ? oboe::SharingMode::Exclusive
                                     : oboe::SharingMode::Shared);
    // LowLatency routes through the FastMixer path when the HAL grants it;
    // exclusive already bypasses the mixer entirely on most devices.
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    if (deviceId > 0) {
        builder.setDeviceId(deviceId);
    }
    builder.setUsage(static_cast<oboe::Usage>(usage));
    builder.setContentType(static_cast<oboe::ContentType>(contentType));
    if (bufferCapacityFrames > 0) {
        builder.setBufferCapacityInFrames(bufferCapacityFrames);
    }

    errorCallback_ = std::make_shared<DisconnectCallback>(disconnected_);
    builder.setErrorCallback(errorCallback_.get());
    disconnected_.store(false, std::memory_order_release);

    std::shared_ptr<oboe::AudioStream> opened;
    oboe::Result result = builder.openStream(opened);
    if (result != oboe::Result::OK || opened == nullptr) {
        return result != oboe::Result::OK
                   ? static_cast<int32_t>(result)
                   : static_cast<int32_t>(oboe::Result::ErrorInternal);
    }

    stream_ = opened;
    sampleRate_ = opened->getSampleRate();
    channels_ = opened->getChannelCount();
    exclusiveGranted_ = opened->getSharingMode() == oboe::SharingMode::Exclusive;
    framesWritten_ = 0;
    open_ = true;

    if (exclusive && !exclusiveGranted_) {
        // The caller asked for exclusive and the HAL demoted us to shared:
        // report failure so the caller falls back to the standard sink
        // instead of silently running through the mixer anyway.
        closeLocked();
        return static_cast<int32_t>(oboe::Result::ErrorInvalidState);
    }
    return static_cast<int32_t>(oboe::Result::OK);
}

int64_t AaudioExclusiveStream::write(const float* data, size_t frames, int32_t channels, int64_t timeoutMs) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!open_ || stream_ == nullptr ||
        disconnected_.load(std::memory_order_acquire)) {
        return static_cast<int64_t>(oboe::Result::ErrorDisconnected);
    }
    // The caller's interleave governs the pointer stride. A stream whose
    // granted layout disagrees with the buffer's cannot be written safely —
    // striding by the stream's own count would read past the caller's buffer.
    if (channels <= 0 || channels != channels_) {
        return static_cast<int64_t>(oboe::Result::ErrorIllegalArgument);
    }
    const int64_t timeoutNanos = timeoutMs * 1000000LL;
    int64_t written = 0;
    while (written < static_cast<int64_t>(frames)) {
        oboe::ResultWithValue<int32_t> result = stream_->write(
            data + written * static_cast<size_t>(channels),
            static_cast<int32_t>(frames - static_cast<size_t>(written)),
            timeoutNanos);
        if (!result) {
            return static_cast<int64_t>(result.error());
        }
        if (result.value() == 0) {
            // Timeout with nothing accepted — surface as a short write so the
            // caller can distinguish a stall from an error.
            return written;
        }
        written += result.value();
        framesWritten_ += result.value();
    }
    return written;
}

int32_t AaudioExclusiveStream::requestStart() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (open_ && stream_ != nullptr &&
        !disconnected_.load(std::memory_order_acquire)) {
        return static_cast<int32_t>(stream_->requestStart());
    }
    return static_cast<int32_t>(oboe::Result::ErrorInvalidState);
}

int32_t AaudioExclusiveStream::requestPause() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (open_ && stream_ != nullptr &&
        !disconnected_.load(std::memory_order_acquire)) {
        return static_cast<int32_t>(stream_->requestPause());
    }
    return static_cast<int32_t>(oboe::Result::ErrorInvalidState);
}

int32_t AaudioExclusiveStream::requestFlush() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (open_ && stream_ != nullptr &&
        !disconnected_.load(std::memory_order_acquire)) {
        return static_cast<int32_t>(stream_->requestFlush());
    }
    return static_cast<int32_t>(oboe::Result::ErrorInvalidState);
}

int32_t AaudioExclusiveStream::requestStop() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (open_ && stream_ != nullptr &&
        !disconnected_.load(std::memory_order_acquire)) {
        return static_cast<int32_t>(stream_->requestStop());
    }
    return static_cast<int32_t>(oboe::Result::ErrorInvalidState);
}

void AaudioExclusiveStream::close() {
    std::lock_guard<std::mutex> lock(mutex_);
    closeLocked();
}

void AaudioExclusiveStream::closeLocked() {
    if (stream_ != nullptr) {
        stream_->close();
        stream_.reset();
    }
    errorCallback_.reset();
    open_ = false;
    exclusiveGranted_ = false;
}

AaudioExclusiveStream::Timestamp AaudioExclusiveStream::timestamp() const {
    std::lock_guard<std::mutex> lock(mutex_);
    Timestamp ts{0, 0, false};
    if (open_ && stream_ != nullptr &&
        !disconnected_.load(std::memory_order_acquire)) {
        oboe::Result result =
            stream_->getTimestamp(CLOCK_MONOTONIC, &ts.framePosition, &ts.timeNanos);
        ts.valid = result == oboe::Result::OK;
    }
    return ts;
}

int32_t AaudioExclusiveStream::sampleRate() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return open_ && stream_ != nullptr ? stream_->getSampleRate() : sampleRate_;
}

int32_t AaudioExclusiveStream::channelCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return open_ && stream_ != nullptr ? stream_->getChannelCount() : channels_;
}

int32_t AaudioExclusiveStream::sessionId() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return open_ && stream_ != nullptr ? stream_->getSessionId() : 0;
}

int64_t AaudioExclusiveStream::framesWritten() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return open_ && stream_ != nullptr ? stream_->getFramesWritten() : framesWritten_;
}

int32_t AaudioExclusiveStream::xRunCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (open_ && stream_ != nullptr) {
        oboe::ResultWithValue<int32_t> count = stream_->getXRunCount();
        return count ? count.value() : 0;
    }
    return 0;
}

int64_t AaudioExclusiveStream::bufferCapacityFrames() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return open_ && stream_ != nullptr ? stream_->getBufferCapacityInFrames() : 0;
}

int64_t AaudioExclusiveStream::bufferSizeFrames() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return open_ && stream_ != nullptr ? stream_->getBufferSizeInFrames() : 0;
}

bool AaudioExclusiveStream::isExclusive() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return open_ && exclusiveGranted_;
}

bool AaudioExclusiveStream::isOpen() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return open_ && stream_ != nullptr &&
           !disconnected_.load(std::memory_order_acquire);
}

}  // namespace archivetune::dsp
