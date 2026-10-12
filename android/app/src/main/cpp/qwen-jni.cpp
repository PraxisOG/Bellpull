#include <jni.h>
#include "qwen.h"
#include <string>
#include <stdexcept>

namespace {
void quiet(qt_log_level, const char *, void *) {} // Never log user text or model dumps.
void fail(JNIEnv *env, const char *message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
struct Utf8 {
    JNIEnv *env; jstring value; const char *data;
    Utf8(JNIEnv *e, jstring v): env(e), value(v), data(e->GetStringUTFChars(v, nullptr)) {}
    ~Utf8() { if (data) env->ReleaseStringUTFChars(value, data); }
};
// Callbacks execute on Qwen's own compute worker. Attach only for the callback.
struct Callback {
    JavaVM *vm; jobject object; jmethodID chunk, cancel;
    template<typename F> bool call(F f) {
        JNIEnv *env = nullptr;
        const bool attach = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK;
        if (attach && vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
        bool result = f(env);
        if (env->ExceptionCheck()) { env->ExceptionClear(); result = false; }
        if (attach) vm->DetachCurrentThread();
        return result;
    }
};
bool cancelled(void *data) {
    auto *cb = static_cast<Callback *>(data);
    return !cb->call([&](JNIEnv *env) { return !env->CallBooleanMethod(cb->object, cb->cancel); });
}
bool chunk(const float *samples, int n, void *data) {
    auto *cb = static_cast<Callback *>(data);
    return cb->call([&](JNIEnv *env) {
        jfloatArray array = env->NewFloatArray(n);
        if (!array) return false;
        env->SetFloatArrayRegion(array, 0, n, samples);
        bool keep = env->CallBooleanMethod(cb->object, cb->chunk, array);
        env->DeleteLocalRef(array);
        return keep;
    });
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_org_praxisog_hermes_QwenNative_load(JNIEnv *env, jobject, jstring talker, jstring codec) {
    try {
        qt_log_set(quiet, nullptr);
        Utf8 t(env, talker), c(env, codec);
        if (!t.data || !c.data) return 0;
        qt_init_params params{}; qt_init_default_params(&params);
        params.talker_path = t.data; params.codec_path = c.data;
        params.use_fa = false; params.max_batch = 1;
        auto *ctx = qt_init(&params);
        if (!ctx) fail(env, "Qwen model could not load. Check model files and available memory.");
        return reinterpret_cast<jlong>(ctx);
    } catch (...) { fail(env, "Qwen model loading failed."); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_org_praxisog_hermes_QwenNative_free(JNIEnv *, jobject, jlong handle) {
    qt_free(reinterpret_cast<qt_context *>(handle));
}
extern "C" JNIEXPORT jint JNICALL
Java_org_praxisog_hermes_QwenNative_synthesize(JNIEnv *env, jobject, jlong handle, jbyteArray text,
    jstring speaker, jstring language, jfloat temperature, jfloat topP, jint topK, jfloat repetition,
    jlong seed, jint maxTokens, jobject sink) {
    try {
        std::string input(env->GetArrayLength(text), '\0');
        env->GetByteArrayRegion(text, 0, static_cast<jsize>(input.size()), reinterpret_cast<jbyte *>(input.data()));
        Utf8 s(env, speaker), l(env, language);
        if (env->ExceptionCheck() || !s.data || !l.data) return -1;
        Callback cb{}; env->GetJavaVM(&cb.vm);
        cb.object = env->NewGlobalRef(sink);
        jclass klass = env->GetObjectClass(sink);
        cb.chunk = env->GetMethodID(klass, "onChunk", "([F)Z");
        cb.cancel = env->GetMethodID(klass, "isCancelled", "()Z");
        env->DeleteLocalRef(klass);
        if (!cb.object || !cb.chunk || !cb.cancel) { if (cb.object) env->DeleteGlobalRef(cb.object); return -1; }
        qt_tts_params p{}; qt_tts_default_params(&p);
        p.text = input.c_str(); p.speaker = s.data; p.lang = l.data;
        p.temperature = temperature; p.top_p = topP; p.top_k = topK;
        p.repetition_penalty = repetition; p.seed = seed; p.max_new_tokens = maxTokens;
        p.cancel = cancelled; p.cancel_user_data = &cb;
        p.on_chunk = chunk; p.on_chunk_user_data = &cb;
        qt_audio audio{};
        auto status = qt_synthesize(reinterpret_cast<qt_context *>(handle), &p, &audio);
        qt_audio_free(&audio); env->DeleteGlobalRef(cb.object);
        if (status != QT_STATUS_OK && status != QT_STATUS_CANCELLED)
            fail(env, "Qwen speech generation failed. The written answer is still available.");
        return status;
    } catch (...) { fail(env, "Qwen speech generation failed."); return -1; }
}
