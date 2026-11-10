// JNI bridge between Kotlin (com.dku.malnote.whisper.WhisperLib) and whisper.cpp
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <string>

#include "whisper.h"

#define TAG "MalNoteWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Handle {
    whisper_context *ctx = nullptr;
    std::atomic<bool> cancel{false};
};

struct CallbackCtx {
    JNIEnv *env;
    jobject cb;
    jmethodID onProgress;
    jmethodID onSegment;
};

// whisper 의 segment 텍스트는 UTF-8 이지만 토큰 경계에서 잘린 바이트가 섞일 수 있어
// NewStringUTF 대신 byte[] 로 넘기고 Kotlin 에서 디코드한다.
jbyteArray toBytes(JNIEnv *env, const char *text) {
    const jsize len = text ? (jsize) std::strlen(text) : 0;
    jbyteArray arr = env->NewByteArray(len);
    if (arr && len > 0) {
        env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte *>(text));
    }
    return arr;
}

void onNewSegment(whisper_context *ctx, whisper_state * /*state*/, int nNew, void *user) {
    auto *c = static_cast<CallbackCtx *>(user);
    if (!c || !c->cb) return;
    const int n = whisper_full_n_segments(ctx);
    for (int i = n - nNew; i < n; ++i) {
        if (i < 0) continue;
        const jlong t0 = (jlong) whisper_full_get_segment_t0(ctx, i) * 10; // 10ms 단위 -> ms
        const jlong t1 = (jlong) whisper_full_get_segment_t1(ctx, i) * 10;
        jbyteArray bytes = toBytes(c->env, whisper_full_get_segment_text(ctx, i));
        c->env->CallVoidMethod(c->cb, c->onSegment, t0, t1, bytes);
        if (c->env->ExceptionCheck()) c->env->ExceptionClear();
        c->env->DeleteLocalRef(bytes);
    }
}

void onProgress(whisper_context * /*ctx*/, whisper_state * /*state*/, int progress, void *user) {
    auto *c = static_cast<CallbackCtx *>(user);
    if (!c || !c->cb) return;
    c->env->CallVoidMethod(c->cb, c->onProgress, (jint) progress);
    if (c->env->ExceptionCheck()) c->env->ExceptionClear();
}

// ggml 워커 스레드에서 호출될 수 있으므로 JNI 를 쓰지 않고 atomic 플래그만 읽는다.
bool onAbort(void *user) {
    auto *h = static_cast<Handle *>(user);
    return h && h->cancel.load();
}

bool onEncoderBegin(whisper_context *, whisper_state *, void *user) {
    auto *h = static_cast<Handle *>(user);
    return !(h && h->cancel.load());
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_dku_malnote_whisper_WhisperLib_nativeInit(JNIEnv *env, jobject, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = true;
    LOGI("loading model: %s", path);
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (!ctx) {
        LOGE("failed to load model");
        return 0;
    }
    auto *h = new Handle();
    h->ctx = ctx;
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_dku_malnote_whisper_WhisperLib_nativeFree(JNIEnv *, jobject, jlong ptr) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (!h) return;
    if (h->ctx) whisper_free(h->ctx);
    delete h;
}

JNIEXPORT void JNICALL
Java_com_dku_malnote_whisper_WhisperLib_nativeCancel(JNIEnv *, jobject, jlong ptr) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (h) h->cancel.store(true);
}

JNIEXPORT jint JNICALL
Java_com_dku_malnote_whisper_WhisperLib_nativeTranscribe(
        JNIEnv *env, jobject,
        jlong ptr, jfloatArray jpcm, jint length,
        jstring jlang, jint threads, jint beamSize, jstring jprompt,
        jobject callback) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (!h || !h->ctx) return -100;
    h->cancel.store(false);

    const bool beam = beamSize > 1;
    whisper_full_params p = whisper_full_default_params(
            beam ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    if (beam) p.beam_search.beam_size = beamSize;

    const char *lang = env->GetStringUTFChars(jlang, nullptr);
    std::string language(lang);
    env->ReleaseStringUTFChars(jlang, lang);

    std::string prompt;
    if (jprompt) {
        const char *pr = env->GetStringUTFChars(jprompt, nullptr);
        prompt = pr;
        env->ReleaseStringUTFChars(jprompt, pr);
    }

    p.n_threads = threads > 0 ? threads : 4;
    p.language = language.c_str();
    p.detect_language = false;
    p.translate = false;
    p.no_timestamps = false;
    p.single_segment = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();

    CallbackCtx cbCtx{};
    cbCtx.env = env;
    cbCtx.cb = callback;
    if (callback) {
        jclass cls = env->GetObjectClass(callback);
        cbCtx.onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
        cbCtx.onSegment = env->GetMethodID(cls, "onSegment", "(JJ[B)V");
        env->DeleteLocalRef(cls);
        if (!cbCtx.onProgress || !cbCtx.onSegment) {
            env->ExceptionClear();
            cbCtx.cb = nullptr;
        }
    }
    p.new_segment_callback = onNewSegment;
    p.new_segment_callback_user_data = &cbCtx;
    p.progress_callback = onProgress;
    p.progress_callback_user_data = &cbCtx;
    p.abort_callback = onAbort;
    p.abort_callback_user_data = h;
    p.encoder_begin_callback = onEncoderBegin;
    p.encoder_begin_callback_user_data = h;

    jfloat *pcm = env->GetFloatArrayElements(jpcm, nullptr);
    const jsize total = env->GetArrayLength(jpcm);
    const int n = (length > 0 && length <= total) ? length : total;

    LOGI("transcribe: %d samples (%.1fs), threads=%d, beam=%d, lang=%s",
         n, n / 16000.0, p.n_threads, beamSize, language.c_str());
    const int rc = whisper_full(h->ctx, p, pcm, n);
    env->ReleaseFloatArrayElements(jpcm, pcm, JNI_ABORT);

    if (h->cancel.load()) return -1;
    return rc;
}

JNIEXPORT jstring JNICALL
Java_com_dku_malnote_whisper_WhisperLib_nativeSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
