#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstring>
#include "qwen3_tts.h"
#include "qwen3_tts_c.h"

namespace {

jobject make_result(JNIEnv* env, qwen3_tts_result_t result) {
    jclass result_class = env->FindClass("com/qwen/tts/studio/engine/QwenEngine$NativeResult");
    if (result_class == nullptr) return nullptr;
    jmethodID ctor = env->GetMethodID(result_class, "<init>", "([FIZLjava/lang/String;J)V");
    if (ctor == nullptr) {
        env->DeleteLocalRef(result_class);
        return nullptr;
    }
    jfloatArray audio = nullptr;
    if (result.audio != nullptr && result.audio_len > 0) {
        audio = env->NewFloatArray(result.audio_len);
        if (audio != nullptr) env->SetFloatArrayRegion(audio, 0, result.audio_len, result.audio);
    }
    jstring error = result.error_msg == nullptr ? nullptr : env->NewStringUTF(result.error_msg);
    jobject value = env->NewObject(result_class, ctor, audio, static_cast<jint>(result.sample_rate),
                                   static_cast<jboolean>(result.success != 0), error,
                                   static_cast<jlong>(result.t_total_ms));
    if (audio != nullptr) env->DeleteLocalRef(audio);
    if (error != nullptr) env->DeleteLocalRef(error);
    env->DeleteLocalRef(result_class);
    return value;
}

struct CallbackState {
    JNIEnv* env;
    jobject callback;
    jmethodID method;
};

int32_t emit_chunk(const qwen3_tts_audio_chunk_t* chunk, void* opaque) {
    auto* state = static_cast<CallbackState*>(opaque);
    if (chunk == nullptr || state == nullptr || state->callback == nullptr ||
        chunk->samples == nullptr || chunk->n_samples <= 0 || chunk->sample_rate <= 0) return 0;
    jfloatArray samples = state->env->NewFloatArray(chunk->n_samples);
    if (samples == nullptr) {
        state->env->ExceptionClear();
        return 0;
    }
    state->env->SetFloatArrayRegion(samples, 0, chunk->n_samples, chunk->samples);
    if (state->env->ExceptionCheck()) {
        state->env->ExceptionClear();
        state->env->DeleteLocalRef(samples);
        return 0;
    }
    const jboolean keep_going = state->env->CallBooleanMethod(
        state->callback, state->method, samples, static_cast<jint>(chunk->sample_rate),
        static_cast<jlong>(chunk->start_sample), static_cast<jlong>(chunk->end_sample),
        static_cast<jint>(chunk->start_frame), static_cast<jint>(chunk->end_frame),
        static_cast<jint>(chunk->start_text_byte), static_cast<jint>(chunk->end_text_byte),
        static_cast<jint>(chunk->text_alignment_kind), static_cast<jfloat>(chunk->confidence));
    state->env->DeleteLocalRef(samples);
    if (state->env->ExceptionCheck()) {
        state->env->ExceptionClear();
        return 0;
    }
    return keep_going == JNI_TRUE ? 1 : 0;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_qwen_tts_studio_engine_QwenEngine_nativeValidateIclPrompt(
    JNIEnv* env,
    jobject,
    jlong context,
    jstring prompt_path
) {
    if (context == 0 || prompt_path == nullptr) return JNI_FALSE;
    const char* native_prompt = env->GetStringUTFChars(prompt_path, nullptr);
    if (native_prompt == nullptr) return JNI_FALSE;
    qwen3_tts::icl_prompt prompt;
    const bool valid = qwen3_tts::load_icl_prompt_file(native_prompt, prompt);
    env->ReleaseStringUTFChars(prompt_path, native_prompt);
    return valid ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_qwen_tts_studio_engine_QwenEngine_nativeSynthesizeWithIclPromptStreaming(
    JNIEnv* env,
    jobject,
    jlong context,
    jstring text,
    jstring prompt_path,
    jobject params,
    jfloat chunk_seconds,
    jfloat left_context_seconds,
    jboolean collect_audio,
    jobject callback
) {
    __android_log_print(ANDROID_LOG_INFO, "ARIA.QwenVoiceLab",
                        "event=synthesis_stage stage=JNI_ENTERED_NATIVE");
    if (context == 0 || text == nullptr || prompt_path == nullptr || callback == nullptr) return nullptr;
    const char* native_text = env->GetStringUTFChars(text, nullptr);
    const char* native_prompt = env->GetStringUTFChars(prompt_path, nullptr);
    if (native_text == nullptr || native_prompt == nullptr) {
        if (native_text != nullptr) env->ReleaseStringUTFChars(text, native_text);
        if (native_prompt != nullptr) env->ReleaseStringUTFChars(prompt_path, native_prompt);
        return nullptr;
    }

    qwen3_tts_params_t generation = {4096, 0.9f, 1.0f, 50, 4, 0, 1, 1.05f, -1, nullptr, nullptr, 2.0f};
    if (params != nullptr) {
        jclass params_class = env->GetObjectClass(params);
        jfieldID language = env->GetFieldID(params_class, "languageId", "I");
        jfieldID max_tokens = env->GetFieldID(params_class, "maxAudioTokens", "I");
        if (language != nullptr) generation.language_id = env->GetIntField(params, language);
        if (max_tokens != nullptr) generation.max_audio_tokens = env->GetIntField(params, max_tokens);
        env->DeleteLocalRef(params_class);
    }

    jclass callback_class = env->GetObjectClass(callback);
    jmethodID method = env->GetMethodID(callback_class, "onAudioChunk", "([FIJJIIIIIF)Z");
    env->DeleteLocalRef(callback_class);
    if (method == nullptr) {
        env->ReleaseStringUTFChars(text, native_text);
        env->ReleaseStringUTFChars(prompt_path, native_prompt);
        return nullptr;
    }

    jobject callback_ref = env->NewGlobalRef(callback);
    CallbackState state{env, callback_ref, method};
    qwen3_tts_streaming_params_t streaming{
        generation,
        std::isfinite(chunk_seconds) && chunk_seconds > 0.0f ? chunk_seconds : 1.0f,
        std::isfinite(left_context_seconds) && left_context_seconds >= 0.0f ? left_context_seconds : 2.0f,
        collect_audio == JNI_TRUE ? 1 : 0,
    };
    qwen3_tts_result_t result = qwen3_tts_synthesize_with_icl_prompt_streaming(
        reinterpret_cast<qwen3_tts_context_t*>(context), native_text, native_prompt,
        streaming, emit_chunk, &state);
    __android_log_print(ANDROID_LOG_INFO, "ARIA.QwenVoiceLab",
                        "event=synthesis_native_return success=%d audio_samples=%zu",
                        result.success, result.audio_len);
    env->DeleteGlobalRef(callback_ref);
    env->ReleaseStringUTFChars(text, native_text);
    env->ReleaseStringUTFChars(prompt_path, native_prompt);
    jobject value = make_result(env, result);
    qwen3_tts_free_result(result);
    return value;
}
