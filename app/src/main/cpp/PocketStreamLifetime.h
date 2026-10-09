#pragma once

#include <mutex>

/** Serializes access to a stream's lifetime without waiting for its worker. */
class PocketStreamLifetime {
public:
    using Cancel = void (*)(void*);

    bool publish(void* stream) {
        if (!stream) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        if (stream_) return false;
        stream_ = stream;
        return true;
    }

    // ptt_stream_cancel only marks/awakens the reader; it does not join it.
    // Keep the pointer protected until cancellation returns so finish() cannot
    // transfer it to ptt_stream_end and free it concurrently.
    bool cancel(Cancel cancel_stream) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!stream_) return false;
        cancel_stream(stream_);
        return true;
    }

    // Only the synthesis call may take ownership and call ptt_stream_end.
    void* take(void* expected) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!expected || stream_ != expected) return nullptr;
        void* owned = stream_;
        stream_ = nullptr;
        return owned;
    }

private:
    std::mutex mutex_;
    void* stream_ = nullptr;
};
