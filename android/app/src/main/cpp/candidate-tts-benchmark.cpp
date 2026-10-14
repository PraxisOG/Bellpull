// Compiled only for the isolated device fixture. No production voice selection.
#include <jni.h>
#include <onnxruntime_cxx_api.h>
#include <array>
#include <cmath>
#include <memory>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
Ort::Env environment(ORT_LOGGING_LEVEL_ERROR, "bellpull-candidate-benchmark");
struct Candidate {
    bool paradee;
    std::unique_ptr<Ort::Session> first, second;
};
std::string string(JNIEnv *env, jstring value) {
    const char *raw = env->GetStringUTFChars(value, nullptr);
    if (!raw) throw std::runtime_error("Missing fixture path");
    std::string result(raw); env->ReleaseStringUTFChars(value, raw); return result;
}
void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_org_praxisog_hermes_CandidateTtsNative_load(JNIEnv *env, jobject, jstring directory, jboolean paradee, jint threads) {
    try {
        if (threads < 1 || threads > 4) throw std::runtime_error("Invalid CPU thread count");
        auto result = std::make_unique<Candidate>(); result->paradee = paradee;
        Ort::SessionOptions options;
        options.SetIntraOpNumThreads(threads); options.SetInterOpNumThreads(1);
        options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        options.AddConfigEntry("session.intra_op.allow_spinning", "0");
        const auto root = string(env, directory);
        result->first = std::make_unique<Ort::Session>(environment,
            (root + (paradee ? "/paradee/onnx/paradee_int8.onnx" : "/inflect/onnx/duration.onnx")).c_str(), options);
        if (!paradee) result->second = std::make_unique<Ort::Session>(environment, (root + "/inflect/onnx/decode.onnx").c_str(), options);
        return reinterpret_cast<jlong>(result.release());
    } catch (const std::exception &error) { fail(env, error.what()); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_org_praxisog_hermes_CandidateTtsNative_free(JNIEnv *, jobject, jlong handle) { delete reinterpret_cast<Candidate *>(handle); }
extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_praxisog_hermes_CandidateTtsNative_synthesize(JNIEnv *env, jobject, jlong handle, jlongArray input, jint seed) {
    try {
        auto *model = reinterpret_cast<Candidate *>(handle);
        const auto count = env->GetArrayLength(input);
        if (!model || count < 1 || count > (model->paradee ? 512 : 2048)) throw std::runtime_error("Invalid fixture token count");
        std::vector<int64_t> ids(count); env->GetLongArrayRegion(input, 0, count, reinterpret_cast<jlong *>(ids.data()));
        auto memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
        std::array<int64_t, 2> shape = {1, count};
        auto tokens = Ort::Value::CreateTensor<int64_t>(memory, ids.data(), ids.size(), shape.data(), shape.size());
        std::vector<Ort::Value> waveform;
        float speed = 1.0f; std::array<int64_t, 1> scalar = {1};
        if (model->paradee) {
            std::array<Ort::Value, 2> values = {std::move(tokens), Ort::Value::CreateTensor<float>(memory, &speed, 1, scalar.data(), 1)};
            const char *names[] = {"input_ids", "speed"}; const char *outputs[] = {"waveform"};
            waveform = model->first->Run(Ort::RunOptions{nullptr}, names, values.data(), values.size(), outputs, 1);
        } else {
            int64_t length = count;
            std::array<Ort::Value, 3> values = {std::move(tokens), Ort::Value::CreateTensor<int64_t>(memory, &length, 1, scalar.data(), 1),
                Ort::Value::CreateTensor<float>(memory, &speed, 1, nullptr, 0)};
            const char *names[] = {"tokens", "lengths", "length_scale"}; const char *outputs[] = {"m_p_exp", "logs_p_exp", "y_mask"};
            auto distribution = model->first->Run(Ort::RunOptions{nullptr}, names, values.data(), values.size(), outputs, 3);
            auto info = distribution[0].GetTensorTypeAndShapeInfo(); auto dimensions = info.GetShape();
            if (info.GetElementCount() > 192 * 6000) throw std::runtime_error("Fixture acoustic output exceeds benchmark bound");
            std::vector<float> noise(info.GetElementCount()); std::mt19937 rng(seed); std::normal_distribution<float> normal(0, 1);
            for (auto &value : noise) value = normal(rng);
            float variation = .667f;
            std::array<Ort::Value, 5> decode = {std::move(distribution[0]), std::move(distribution[1]), std::move(distribution[2]),
                Ort::Value::CreateTensor<float>(memory, noise.data(), noise.size(), dimensions.data(), dimensions.size()),
                Ort::Value::CreateTensor<float>(memory, &variation, 1, nullptr, 0)};
            const char *decodeNames[] = {"m_p_exp", "logs_p_exp", "y_mask", "zp_noise", "noise_scale"}; const char *decodeOutput[] = {"waveform"};
            waveform = model->second->Run(Ort::RunOptions{nullptr}, decodeNames, decode.data(), decode.size(), decodeOutput, 1);
        }
        const auto frames = waveform[0].GetTensorTypeAndShapeInfo().GetElementCount();
        if (!frames || frames > 24000 * 60) throw std::runtime_error("Fixture waveform exceeds benchmark bound");
        const auto *samples = waveform[0].GetTensorData<float>();
        for (size_t i = 0; i < frames; i++) if (!std::isfinite(samples[i])) throw std::runtime_error("Non-finite fixture audio");
        auto result = env->NewFloatArray(frames); env->SetFloatArrayRegion(result, 0, frames, samples); return result;
    } catch (const std::exception &error) { fail(env, error.what()); return nullptr; }
}
