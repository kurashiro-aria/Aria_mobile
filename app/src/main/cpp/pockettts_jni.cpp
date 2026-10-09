#include <jni.h>
#include "PocketEngineRegistry.h"
#include "PocketStreamLifetime.h"
#include <atomic>
#include <array>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>

extern "C" {
void* ptt_create(const char*, const char*, const char*, const char*, float, int, int, int, int, int, uint64_t);
void ptt_destroy(void*);
void* ptt_stream_start(void*, const char*, const char*);
int ptt_stream_read(void*, float**, int*);
int ptt_stream_get_metrics(void*, int64_t*, int64_t*, int64_t*, int64_t*);
void ptt_stream_cancel(void*);
void ptt_stream_end(void*);
void ptt_free_audio(float*);
}

struct Engine {
    void* tts = nullptr;
    PocketStreamLifetime stream;
    std::mutex synth_mutex;
    std::array<int64_t, 4> last_metrics{0, 0, 0, 0};
    std::atomic<bool> closing{false};
};

static PocketEngineRegistry<Engine> engines;

static std::shared_ptr<Engine> engine_from(jlong handle) { return engines.get(handle); }

static void cancel_native_stream(void* stream) {
    ptt_stream_cancel(stream);
}

static void notify_stage(JNIEnv* env, jobject observer, int stage, int value) {
    if (!observer) return;
    jclass observer_class = env->GetObjectClass(observer);
    if (!observer_class) return;
    jmethodID method = env->GetMethodID(observer_class, "onStage", "(II)V");
    if (method) env->CallVoidMethod(observer, method, static_cast<jint>(stage), static_cast<jint>(value));
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(observer_class);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_kura_aria_voice_pocket_NativePocketTts_nativeCreate(
        JNIEnv* env, jobject, jstring models, jstring voices, jstring precision,
        jfloat temperature, jint lsd_steps, jint threads, jint sentence_pause_ms,
        jint max_text_tokens, jint kv_mode, jlong random_seed) {
    const char* model_path = env->GetStringUTFChars(models, nullptr);
    const char* voice_path = env->GetStringUTFChars(voices, nullptr);
    const char* precision_value = env->GetStringUTFChars(precision, nullptr);
    auto engine = std::make_shared<Engine>();
    // The upstream C API defaults to a relative "models/tokenizer.model".
    // Android stores assets in the app-private model directory, so pass the
    // absolute tokenizer path explicitly.
    const std::string tokenizer_path = std::string(model_path) + "/tokenizer.model";
    engine->tts = ptt_create(model_path, voice_path, tokenizer_path.c_str(), precision_value,
                             temperature, lsd_steps, threads, sentence_pause_ms, max_text_tokens,
                             kv_mode, static_cast<uint64_t>(random_seed));
    env->ReleaseStringUTFChars(models, model_path);
    env->ReleaseStringUTFChars(voices, voice_path);
    env->ReleaseStringUTFChars(precision, precision_value);
    if (!engine->tts) return 0;
    return static_cast<jlong>(engines.insert(std::move(engine)));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kura_aria_voice_pocket_NativePocketTts_nativeSynthesize(
        JNIEnv* env, jobject, jlong value, jstring text, jstring voice, jobject sink, jobject observer) {
    auto engine = engine_from(value);
    if (!engine) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(engine->synth_mutex);
    if (engine->closing.load() || !engine->tts) return JNI_FALSE;
    engine->last_metrics.fill(0);
    const char* utf8 = env->GetStringUTFChars(text, nullptr);
    const char* voice_utf8 = env->GetStringUTFChars(voice, nullptr);
    notify_stage(env, observer, 1, 0);
    void* stream = ptt_stream_start(engine->tts, utf8, voice_utf8);
    env->ReleaseStringUTFChars(text, utf8);
    env->ReleaseStringUTFChars(voice, voice_utf8);
    if (!stream) {
        notify_stage(env, observer, 3, 0);
        return JNI_FALSE;
    }
    notify_stage(env, observer, 2, 0);
    if (!engine->stream.publish(stream)) {
        ptt_stream_end(stream);
        return JNI_FALSE;
    }
    // Destruction may mark the Engine closing between the pre-synthesis check
    // and publication. In that ordering its first cancel sees an empty slot;
    // this second check guarantees the newly published stream is cancelled.
    if (engine->closing.load()) engine->stream.cancel(cancel_native_stream);
    jclass sink_class = env->GetObjectClass(sink);
    jmethodID on_audio = env->GetMethodID(sink_class, "onAudio", "([F)Z");
    bool success = on_audio != nullptr;
    bool first_read = true;
    while (success) {
        float* samples = nullptr;
        int count = 0;
        const int state = ptt_stream_read(stream, &samples, &count);
        if (first_read) {
            notify_stage(env, observer, 4, state > 0 ? count : state);
            first_read = false;
        }
        if (state == 0) break;
        if (state < 0) { success = false; break; }
        jfloatArray chunk = env->NewFloatArray(count);
        if (!chunk) { ptt_free_audio(samples); success = false; break; }
        env->SetFloatArrayRegion(chunk, 0, count, samples);
        ptt_free_audio(samples);
        const jboolean accepted = env->CallBooleanMethod(sink, on_audio, chunk);
        env->DeleteLocalRef(chunk);
        if (env->ExceptionCheck() || !accepted) { env->ExceptionClear(); success = false; break; }
    }
    if (success) {
        ptt_stream_get_metrics(stream, &engine->last_metrics[0], &engine->last_metrics[1],
                               &engine->last_metrics[2], &engine->last_metrics[3]);
    }
    // take() clears the published pointer under the same lock used by stop().
    // Exactly this synthesis call owns the returned pointer and ends it once.
    if (void* owned_stream = engine->stream.take(stream)) ptt_stream_end(owned_stream);
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_kura_aria_voice_pocket_NativePocketTts_nativeLastRunMetrics(
        JNIEnv* env, jobject, jlong value) {
    auto engine = engine_from(value);
    if (!engine) return nullptr;
    std::lock_guard<std::mutex> lock(engine->synth_mutex);
    jlongArray result = env->NewLongArray(4);
    if (!result) return nullptr;
    jlong values[4] = {
        static_cast<jlong>(engine->last_metrics[0]),
        static_cast<jlong>(engine->last_metrics[1]),
        static_cast<jlong>(engine->last_metrics[2]),
        static_cast<jlong>(engine->last_metrics[3])
    };
    env->SetLongArrayRegion(result, 0, 4, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kura_aria_voice_pocket_NativePocketTts_nativeStop(JNIEnv*, jobject, jlong value) {
    auto engine = engine_from(value);
    if (engine) engine->stream.cancel(cancel_native_stream);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kura_aria_voice_pocket_NativePocketTts_nativeDestroy(JNIEnv*, jobject, jlong value) {
    std::shared_ptr<Engine> engine = engines.remove(value);
    if (!engine) return;
    // Cancel before waiting for synthesis: ptt_stream_read may be blocked until
    // awakened. The shared_ptr keeps Engine alive for all JNI calls already in
    // flight; calls arriving after erase cannot acquire it.
    engine->closing.store(true);
    engine->stream.cancel(cancel_native_stream);
    std::lock_guard<std::mutex> guard(engine->synth_mutex);
    ptt_destroy(engine->tts);
    engine->tts = nullptr;
}
