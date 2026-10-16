// Bellpull's CPU-only Pocket adapter. Upstream inference: Sherpa-ONNX (Apache-2.0).
#include <jni.h>
#include <array>
#include <fstream>
#include <stdexcept>
#include "sherpa-onnx/csrc/session.h"
#include "sherpa-onnx/csrc/onnx-utils.h"
#include "sherpa-onnx/csrc/offline-tts-pocket-impl.h"

namespace sherpa_onnx {
thread_local std::function<bool()> bellpull_cancelled;
Ort::SessionOptions GetSessionOptionsImpl(int32_t threads, const std::string &provider,
                                          const ProviderConfig *) {
    if (provider != "cpu" || threads < 1 || threads > 8) throw std::runtime_error("CPU only");
    Ort::SessionOptions options;
    options.SetIntraOpNumThreads(threads);
    options.SetInterOpNumThreads(1);
    options.SetExecutionMode(ExecutionMode::ORT_SEQUENTIAL);
    options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
    options.SetLogSeverityLevel(4);
    // No execution-provider registration: ONNX Runtime CPU only, no partial fallback.
    return options;
}
}
namespace {
void fail(JNIEnv *env, const char *message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
struct Utf8 {
    JNIEnv *env; jstring value; const char *data;
    Utf8(JNIEnv *e, jstring v): env(e), value(v), data(e->GetStringUTFChars(v, nullptr)) {}
    ~Utf8() { if (data) env->ReleaseStringUTFChars(value, data); }
};
struct Pocket {
    std::unique_ptr<sherpa_onnx::OfflineTtsPocketImpl> model;
    std::vector<float> reference;
};
uint32_t little(const unsigned char *p, int bytes) {
    uint32_t n = 0;
    for (int i = 0; i < bytes; ++i) n |= uint32_t(p[i]) << (8 * i);
    return n;
}
std::vector<float> reference(const std::string &path) {
    std::ifstream file(path, std::ios::binary);
    std::vector<unsigned char> data((std::istreambuf_iterator<char>(file)), {});
    if (data.size() < 44 || data.size() > 500000 || std::memcmp(data.data(), "RIFF", 4) ||
        std::memcmp(data.data() + 8, "WAVE", 4)) throw std::runtime_error("Invalid reference WAV");
    bool format = false;
    for (size_t i = 12; i + 8 <= data.size();) {
        auto n = little(data.data() + i + 4, 4);
        auto at = i + 8;
        if (n > data.size() - at) throw std::runtime_error("Invalid WAV chunk");
        if (!std::memcmp(data.data() + i, "fmt ", 4)) {
            format = n >= 16 && little(data.data() + at, 2) == 1 &&
                little(data.data() + at + 2, 2) == 1 && little(data.data() + at + 4, 4) == 24000 &&
                little(data.data() + at + 14, 2) == 16;
        }
        if (!std::memcmp(data.data() + i, "data", 4) && format && n > 0 && n % 2 == 0) {
            std::vector<float> result(n / 2);
            for (size_t k = 0; k < result.size(); ++k)
                result[k] = static_cast<int16_t>(little(data.data() + at + k * 2, 2)) / 32768.0f;
            return result;
        }
        i = at + n + (n & 1);
    }
    throw std::runtime_error("Missing mono 24kHz PCM reference");
}
struct CancelScope {
    ~CancelScope() { sherpa_onnx::bellpull_cancelled = nullptr; }
};
}
extern "C" JNIEXPORT jlong JNICALL
Java_org_praxisog_hermes_PocketNative_load(JNIEnv *env, jobject, jstring directory, jint threads) {
    try {
        Utf8 path(env, directory);
        if (!path.data) return 0;
        std::string root(path.data);
        sherpa_onnx::OfflineTtsConfig config;
        config.model.provider = "cpu";
        config.model.num_threads = threads;
        config.model.debug = false;
        auto &p = config.model.pocket;
        p.lm_flow = root + "/lm_flow.int8.onnx"; p.lm_main = root + "/lm_main.int8.onnx";
        p.encoder = root + "/encoder.onnx"; p.decoder = root + "/decoder.int8.onnx";
        p.text_conditioner = root + "/text_conditioner.onnx";
        p.vocab_json = root + "/vocab.json"; p.token_scores_json = root + "/token_scores.json";
        p.voice_embedding_cache_capacity = 1;
        auto result = std::make_unique<Pocket>();
        result->reference = reference(root + "/loona.wav");
        result->model = std::make_unique<sherpa_onnx::OfflineTtsPocketImpl>(config);
        return reinterpret_cast<jlong>(result.release());
    } catch (...) { fail(env, "Pocket could not load. Check model files and available memory."); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_org_praxisog_hermes_PocketNative_free(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Pocket *>(handle);
}
extern "C" JNIEXPORT jint JNICALL
Java_org_praxisog_hermes_PocketNative_synthesize(JNIEnv *env, jobject, jlong handle,
    jbyteArray text, jint steps, jfloat temperature, jint chunks, jint seed, jobject sink) {
    CancelScope reset;
    try {
        if (!handle || steps < 1 || steps > 10 || !std::isfinite(temperature) || temperature < 0 ||
            temperature > 1.5 || chunks < 1 || chunks > 30) throw std::runtime_error("Invalid config");
        const auto size = env->GetArrayLength(text);
        if (size < 1 || size > 4096) throw std::runtime_error("Invalid text length");
        std::string input(size, '\0');
        env->GetByteArrayRegion(text, 0, size, reinterpret_cast<jbyte *>(input.data()));
        jclass klass = env->GetObjectClass(sink);
        auto chunk = env->GetMethodID(klass, "onChunk", "([F)Z");
        auto cancel = env->GetMethodID(klass, "isCancelled", "()Z");
        env->DeleteLocalRef(klass);
        if (env->ExceptionCheck() || !chunk || !cancel) return -1;
        bool cancelled = false;
        sherpa_onnx::bellpull_cancelled = [&] {
            cancelled = env->CallBooleanMethod(sink, cancel);
            if (env->ExceptionCheck()) { env->ExceptionClear(); cancelled = true; }
            return cancelled;
        };
        auto *p = reinterpret_cast<Pocket *>(handle);
        sherpa_onnx::GenerationConfig config;
        config.silence_scale = 1;
        config.num_steps = steps;
        config.reference_audio = p->reference;
        config.reference_sample_rate = 24000;
        config.extra = {{"temperature", std::to_string(temperature)}, {"chunk_size", std::to_string(chunks)},
            {"seed", std::to_string(seed)}, {"max_frames", "500"}, {"max_char_in_sentence", "200"}};
        auto audio = p->model->Generate(input, config, [&](const float *samples, int32_t n, float) -> int32_t {
            if (sherpa_onnx::bellpull_cancelled()) return 0;
            jfloatArray array = env->NewFloatArray(n);
            if (!array) return 0;
            env->SetFloatArrayRegion(array, 0, n, samples);
            bool keep = env->CallBooleanMethod(sink, chunk, array);
            env->DeleteLocalRef(array);
            if (env->ExceptionCheck()) { env->ExceptionClear(); keep = false; }
            if (!keep) cancelled = true;
            return keep;
        });
        return cancelled ? -5 : audio.samples.empty() ? -1 : 0;
    } catch (...) {
        if (sherpa_onnx::bellpull_cancelled && sherpa_onnx::bellpull_cancelled()) return -5;
        fail(env, "Pocket synthesis failed. The written answer is available."); return -1;
    }
}
