#include "PocketStreamLifetime.h"
#include "PocketEngineRegistry.h"

#include <atomic>
#include <cstdlib>
#include <thread>

struct FakeStream {
    std::atomic<bool> freed{false};
    std::atomic<int> cancel_calls{0};
    std::atomic<int> end_calls{0};
    std::atomic<int> use_after_free{0};
};

static void require(bool condition) {
    if (!condition) std::abort();
}

static void fake_cancel(void* raw) {
    auto* stream = static_cast<FakeStream*>(raw);
    if (stream->freed.load(std::memory_order_acquire)) {
        stream->use_after_free.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    stream->cancel_calls.fetch_add(1, std::memory_order_relaxed);
}

static void finish(PocketStreamLifetime& slot, FakeStream& stream) {
    if (void* owned = slot.take(&stream)) {
        auto* transferred = static_cast<FakeStream*>(owned);
        transferred->end_calls.fetch_add(1, std::memory_order_relaxed);
        transferred->freed.store(true, std::memory_order_release);
    }
}

int main() {
    constexpr int iterations = 20'000;
    for (int i = 0; i < iterations; ++i) {
        FakeStream stream;
        PocketStreamLifetime slot;
        require(slot.publish(&stream));

        std::atomic<bool> start{false};
        std::thread canceller([&] {
            while (!start.load(std::memory_order_acquire)) {}
            for (int n = 0; n < 4; ++n) slot.cancel(fake_cancel);
        });
        std::thread finisher([&] {
            while (!start.load(std::memory_order_acquire)) {}
            finish(slot, stream);
        });
        start.store(true, std::memory_order_release);
        canceller.join();
        finisher.join();

        require(stream.end_calls.load() == 1);
        require(stream.use_after_free.load() == 0);
        require(slot.take(&stream) == nullptr);
        require(!slot.cancel(fake_cancel));
    }

    struct EngineProbe {
        explicit EngineProbe(std::atomic<int>& destroyed) : destroyed(destroyed) {}
        ~EngineProbe() { destroyed.fetch_add(1, std::memory_order_relaxed); }
        std::atomic<int>& destroyed;
    };
    std::atomic<int> destroyed{0};
    PocketEngineRegistry<EngineProbe> registry;
    const auto handle = registry.insert(std::make_shared<EngineProbe>(destroyed));
    auto active_call = registry.get(handle);
    auto removed = registry.remove(handle);
    require(removed && !registry.get(handle));
    removed.reset();
    require(destroyed.load() == 0); // in-flight JNI reference keeps Engine alive
    active_call.reset();
    require(destroyed.load() == 1);
}
