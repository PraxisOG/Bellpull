#include <jni.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include <stdexcept>
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include "llama.h"
#include "common.h"
#include "chat.h"

// Backend selection and templates follow the Imagination GPU experiment.
// Bellpull owns cancellation, bounded context, UTF-8 transport and failure handling.
static std::atomic<bool> cancelled{false};
static llama_model *model = nullptr;
static llama_context *context = nullptr;
static llama_sampler *sampler = nullptr;
static common_chat_templates_ptr templates;
static std::mutex log_mutex;
static std::string load_log;
static int offloaded = 0;
static bool initialized = false;
static std::string backend = "Unloaded", description, finish;
static int position = 0, prompt_count = 0, generated = 0, limit = 0;
static int64_t load_us = 0, prompt_us = 0, generation_us = 0;
static std::vector<int> token_ids;
static std::vector<llama_token> cached_tokens;
static std::string cache_key;
static int reused = 0;
static int64_t generation_started = 0;
static bool thinking_requested = false, thinking_marker = false, reasoning_prefill = false;
static int context_size = 2048;
constexpr int batch_size = 128;

static void log_capture(enum ggml_log_level level, const char *message, void *) {
    if (!message) return;
    std::lock_guard<std::mutex> guard(log_mutex);
    const char *marker = strstr(message, "offloaded ");
    int layers = 0, total = 0;
    if (marker && sscanf(marker, "offloaded %d/%d layers to GPU", &layers, &total) == 2) offloaded = layers;
    if (marker || strstr(message, "model buffer size") || level == GGML_LOG_LEVEL_ERROR) {
        if (load_log.size() < 8192) load_log += message;
    }
}
static bool abort_decode(void *) { return cancelled.load(); }
static bool load_progress(float, void *) { return !cancelled.load(); }
static void check_cancel() { if (cancelled.load()) throw std::runtime_error("Stopped"); }
static void cleanup() {
    cached_tokens.clear(); cache_key.clear();
    templates.reset();
    if (sampler) llama_sampler_free(sampler);
    if (context) llama_free(context);
    if (model) llama_model_free(model);
    sampler = nullptr; context = nullptr; model = nullptr;
    backend = "Unloaded";
}
static void fail(JNIEnv *env, const std::exception &error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
static std::string from_bytes(JNIEnv *env, jbyteArray bytes) {
    const auto size = env->GetArrayLength(bytes);
    std::string result(size, '\0');
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte *>(result.data()));
    return result;
}
static jbyteArray to_bytes(JNIEnv *env, const std::string &text) {
    auto bytes = env->NewByteArray(text.size());
    if (bytes) env->SetByteArrayRegion(bytes, 0, text.size(), reinterpret_cast<const jbyte *>(text.data()));
    return bytes;
}
extern "C" JNIEXPORT void JNICALL Java_org_praxisog_hermes_local_LocalLlmNative_initialize(JNIEnv *env, jobject) {
    try {
        if (initialized) return;
        // Conservative PowerVR path: also disable half precision, fusion and async
        // beyond the four switches tested by the earlier experiment.
        for (auto key : {"GGML_VK_DISABLE_COOPMAT", "GGML_VK_DISABLE_COOPMAT2",
                "GGML_VK_DISABLE_COOPMAT2_DECODE_VECTOR", "GGML_VK_DISABLE_INTEGER_DOT_PRODUCT",
                "GGML_VK_DISABLE_F16", "GGML_VK_DISABLE_FUSION", "GGML_VK_DISABLE_ASYNC"}) setenv(key, "1", 1);
        llama_log_set(log_capture, nullptr);
        llama_backend_init();
        initialized = true;
    } catch (const std::exception &e) { fail(env, e); }
}
extern "C" JNIEXPORT void JNICALL Java_org_praxisog_hermes_local_LocalLlmNative_clearCancel(JNIEnv *, jobject) { cancelled = false; }
extern "C" JNIEXPORT void JNICALL Java_org_praxisog_hermes_local_LocalLlmNative_cancel(JNIEnv *, jobject) { cancelled = true; }

extern "C" JNIEXPORT jstring JNICALL Java_org_praxisog_hermes_local_LocalLlmNative_load(JNIEnv *env, jobject, jstring path, jboolean gpu, jint requested_context) {
    try {
        cleanup(); check_cancel();
        if (requested_context != 2048 && requested_context != 4096 && requested_context != 8192)
            throw std::runtime_error("Unsupported local context size.");
        context_size = requested_context;
        const int64_t started = ggml_time_us();
        { std::lock_guard<std::mutex> guard(log_mutex); offloaded = 0; load_log.clear(); }
        auto params = llama_model_default_params();
        ggml_backend_dev_t devices[2] = {nullptr, nullptr};
        if (gpu) {
            for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
                auto dev = ggml_backend_dev_get(i);
                if (strstr(ggml_backend_dev_name(dev), "Vulkan")) { devices[0] = dev; break; }
            }
            if (!devices[0]) throw std::runtime_error("Vulkan is unavailable on this device/ABI. Select CPU explicitly.");
        }
        // An empty list is essential: null would let llama choose accelerators.
        params.devices = devices;
        params.n_gpu_layers = gpu ? -1 : 0;
        params.progress_callback = load_progress;
        const char *name = env->GetStringUTFChars(path, nullptr);
        model = llama_model_load_from_file(name, params);
        env->ReleaseStringUTFChars(path, name);
        check_cancel();
        if (!model) throw std::runtime_error("Cannot load this GGUF. Check model support, available memory and file integrity.");
        if (!llama_model_chat_template(model, nullptr)) throw std::runtime_error("This experiment requires an instruction model with an embedded chat template.");
        if (gpu && offloaded == 0) throw std::runtime_error("Vulkan loaded no GPU layers. GPU chat remains unavailable.");
        auto cp = llama_context_default_params();
        cp.n_ctx = context_size; cp.n_batch = batch_size; cp.n_ubatch = batch_size;
        cp.n_threads = 4; cp.n_threads_batch = 4;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        cp.offload_kqv = gpu;
        cp.abort_callback = abort_decode;
        context = llama_init_from_model(model, cp);
        check_cancel();
        if (!context) throw std::runtime_error("Cannot allocate the selected context. Choose a smaller context in Settings and retry.");
        templates = common_chat_templates_init(model, "");
        sampler = llama_sampler_init_greedy();
        char desc[256]; llama_model_desc(model, desc, sizeof(desc)); description = desc;
        backend = gpu ? std::string("Vulkan: ") + ggml_backend_dev_name(devices[0]) : "CPU (0 GPU layers)";
        load_us = ggml_time_us() - started;
        return env->NewStringUTF(backend.c_str());
    } catch (const std::exception &e) { cleanup(); fail(env, e); return nullptr; }
}

static void decode(const std::vector<llama_token> &tokens) {
    for (size_t start = 0; start < tokens.size(); start += batch_size) {
        check_cancel();
        const int count = std::min<size_t>(batch_size, tokens.size() - start);
        auto batch = llama_batch_init(count, 0, 1);
        for (int i = 0; i < count; ++i)
            common_batch_add(batch, tokens[start + i], position + i, {0}, start + i == tokens.size() - 1);
        int result = llama_decode(context, batch);
        llama_batch_free(batch);
        check_cancel();
        if (result) throw std::runtime_error("Local inference failed while decoding. Unload and retry; no remote fallback was used.");
        position += count;
        cached_tokens.insert(cached_tokens.end(), tokens.begin() + start, tokens.begin() + start + count);
    }
}
static std::vector<llama_token> prompt_tokens(JNIEnv *env, jbyteArray messages, bool thinking) {
    if (!context) throw std::runtime_error("Load a model first.");
    common_chat_templates_inputs input;
    input.use_jinja = true; input.enable_thinking = thinking; input.add_generation_prompt = true;
    for (const auto &item : common_json::parse(from_bytes(env, messages))) {
        common_chat_msg msg;
        msg.role = item.at("role").get<std::string>(); msg.content = item.at("content").get<std::string>();
        if (msg.role != "system" && msg.role != "user" && msg.role != "assistant")
            throw std::runtime_error("Unsupported local message role.");
        input.messages.push_back(std::move(msg));
    }
    auto rendered = common_chat_templates_apply(templates.get(), input).prompt;
    const std::string thought_prefix = "<|channel>thought\n";
    thinking_requested = thinking;
    thinking_marker = rendered.find("<|think|>") != std::string::npos;
    // Gemma's embedded template enables thinking in the system turn but leaves
    // the model channel undecided. Prefill its documented thought channel when
    // the user explicitly enables it, including after earlier non-thinking turns.
    const std::string model_prefix = "<|turn>model\n";
    if (thinking && thinking_marker && rendered.size() >= model_prefix.size() &&
        rendered.compare(rendered.size() - model_prefix.size(), model_prefix.size(), model_prefix) == 0)
        rendered += thought_prefix;
    reasoning_prefill = rendered.size() >= thought_prefix.size() &&
        rendered.compare(rendered.size() - thought_prefix.size(), thought_prefix.size(), thought_prefix) == 0;
    return common_tokenize(context, rendered, true, true);
}
extern "C" JNIEXPORT jint JNICALL Java_org_praxisog_hermes_local_LocalLlmNative_countTokens(JNIEnv *env, jobject, jbyteArray messages, jboolean thinking) {
    try { return prompt_tokens(env, messages, thinking).size(); }
    catch (const std::exception &e) { fail(env, e); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_org_praxisog_hermes_local_LocalLlmNative_begin(JNIEnv *env, jobject, jbyteArray messages, jint max_tokens, jboolean thinking, jstring key) {
    try {
        check_cancel();
        if (!context) throw std::runtime_error("Load a model first.");
        const int64_t started = ggml_time_us();
        const auto tokens = prompt_tokens(env, messages, thinking);
        limit = std::clamp<int>(max_tokens, 1, 768);
        if (tokens.empty() || tokens.size() + limit > context_size)
            throw std::runtime_error("This conversation exceeds the selected context. Compact the chat or shorten the prompt.");
        const char *key_chars = env->GetStringUTFChars(key, nullptr);
        std::string next_key(key_chars); env->ReleaseStringUTFChars(key, key_chars);
        reused = 0;
        if (!next_key.empty() && next_key == cache_key) {
            while (reused < (int) std::min(tokens.size() - 1, cached_tokens.size()) && tokens[reused] == cached_tokens[reused]) ++reused;
        }
        auto memory = llama_get_memory(context);
        if (!reused || !llama_memory_seq_rm(memory, 0, reused, -1)) {
            llama_memory_clear(memory, true); reused = 0;
        }
        cached_tokens.resize(reused); cache_key = next_key;
        llama_sampler_reset(sampler);
        position = reused; generated = 0; token_ids.clear(); finish.clear(); generation_us = 0; prompt_us = 0;
        prompt_count = tokens.size();
        decode(std::vector<llama_token>(tokens.begin() + reused, tokens.end()));
        prompt_us = ggml_time_us() - started;
        generation_started = ggml_time_us();
    } catch (const std::exception &e) { cache_key.clear(); fail(env, e); }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_org_praxisog_hermes_local_LocalLlmNative_next(JNIEnv *env, jobject) {
    try {
        check_cancel();
        if (!context) throw std::runtime_error("Model unloaded.");
        if (generated >= limit) { finish = "token limit"; return nullptr; }
        if (ggml_time_us() - generation_started > 120000000) { finish = "time limit"; return nullptr; }
        const int64_t started = ggml_time_us();
        const auto token = llama_sampler_sample(sampler, context, -1);
        token_ids.push_back(token);
        if (llama_vocab_is_eog(llama_model_get_vocab(model), token)) { finish = "complete"; return nullptr; }
        auto piece = common_token_to_piece(context, token, true);
        decode({token});
        ++generated;
        generation_us += ggml_time_us() - started;
        return to_bytes(env, piece);
    } catch (const std::exception &e) { cache_key.clear(); fail(env, e); return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_org_praxisog_hermes_local_LocalLlmNative_reportUtf8(JNIEnv *env, jobject) {
    try {
        std::lock_guard<std::mutex> guard(log_mutex);
        common_json report = {{"backend", backend}, {"model", description}, {"offloaded_layers", offloaded},
            {"load_ms", load_us / 1000.0}, {"prompt_tokens", prompt_count}, {"generated_tokens", generated},
            {"prompt_ms", prompt_us / 1000.0}, {"generation_ms", generation_us / 1000.0}, {"reused_tokens", reused},
            {"prompt_tps", prompt_us ? (prompt_count - reused) * 1e6 / prompt_us : 0.0},
            {"prefill_tokens", prompt_count - reused}, {"thinking", thinking_requested},
            {"thinking_marker", thinking_marker}, {"reasoning_prefill", reasoning_prefill},
            {"generation_tps", generation_us ? generated * 1e6 / generation_us : 0.0},
            {"finish", cancelled ? "stopped" : finish}, {"token_ids", token_ids}, {"load_log", load_log}};
        return to_bytes(env, report.dump());
    } catch (const std::exception &e) { fail(env, e); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_org_praxisog_hermes_local_LocalLlmNative_unload(JNIEnv *, jobject) { cleanup(); }
