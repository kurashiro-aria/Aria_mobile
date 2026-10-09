#pragma once

#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <utility>

/** Keeps JNI handles alive while an already-started call is using them. */
template <typename T>
class PocketEngineRegistry {
public:
    using Handle = std::int64_t;

    Handle insert(std::shared_ptr<T> engine) {
        std::lock_guard<std::mutex> lock(mutex_);
        const Handle handle = next_handle_++;
        engines_.emplace(handle, std::move(engine));
        return handle;
    }

    std::shared_ptr<T> get(Handle handle) const {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = engines_.find(handle);
        return found == engines_.end() ? nullptr : found->second;
    }

    std::shared_ptr<T> remove(Handle handle) {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = engines_.find(handle);
        if (found == engines_.end()) return nullptr;
        auto engine = std::move(found->second);
        engines_.erase(found);
        return engine;
    }

private:
    mutable std::mutex mutex_;
    std::unordered_map<Handle, std::shared_ptr<T>> engines_;
    Handle next_handle_ = 1;
};
