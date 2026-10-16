// Bellpull's SentencePiece adapter for MOSS. See CMakeLists.txt for source/license pin.
#include <jni.h>
#include <cstdint>
#include <fstream>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>
#include "ssentencepiece/csrc/ssentencepiece.h"

namespace {
void fail(JNIEnv *env, const char *message) {
    if (!env->ExceptionCheck()) {
        jclass type = env->FindClass("java/lang/IllegalStateException");
        if (type) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
    }
}

// JNI GetStringUTFChars uses modified UTF-8 (surrogate pairs for supplementary
// characters). SentencePiece needs standard UTF-8, including for multilingual text.
std::string utf8(JNIEnv *env, jstring value) {
    if (!value) throw std::runtime_error("Missing string");
    jclass type = env->FindClass("java/lang/String");
    if (!type) throw std::runtime_error("String unavailable");
    auto method = env->GetMethodID(type, "getBytes", "(Ljava/lang/String;)[B");
    env->DeleteLocalRef(type);
    if (!method) throw std::runtime_error("UTF-8 conversion unavailable");
    jstring encoding = env->NewStringUTF("UTF-8");
    if (!encoding) throw std::runtime_error("Allocation failed");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, method, encoding));
    env->DeleteLocalRef(encoding);
    if (env->ExceptionCheck() || !bytes) throw std::runtime_error("UTF-8 conversion failed");
    std::string result(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(result.size()),
                            reinterpret_cast<jbyte *>(result.data()));
    env->DeleteLocalRef(bytes);
    if (env->ExceptionCheck()) throw std::runtime_error("UTF-8 copy failed");
    return result;
}
}

extern "C" JNIEXPORT jintArray JNICALL
Java_org_praxisog_hermes_MossNative_tokenize(JNIEnv *env, jobject, jstring modelPath, jstring text) {
    try {
        const auto path = utf8(env, modelPath);
        const auto input = utf8(env, text);
        if (path.empty() || path.find('\0') != std::string::npos || input.empty())
            throw std::runtime_error("Invalid tokenizer input");
        std::ifstream model(path, std::ios::binary);
        if (!model.good()) throw std::runtime_error("Tokenizer model unavailable");
        // No BOS/EOS injection: the ONNX prompt templates supply their own framing.
        ssentencepiece::Ssentencepiece tokenizer(path);
        std::vector<int32_t> ids;
        tokenizer.Encode(input, &ids);
        if (ids.empty() || ids.size() > static_cast<size_t>(std::numeric_limits<jsize>::max()))
            throw std::runtime_error("Invalid token count");
        std::vector<jint> result(ids.begin(), ids.end());
        jintArray array = env->NewIntArray(static_cast<jsize>(result.size()));
        if (array) env->SetIntArrayRegion(array, 0, static_cast<jsize>(result.size()), result.data());
        return array;
    } catch (...) {
        fail(env, "MOSS text tokenization failed. Check tokenizer.model; the written answer is available.");
        return nullptr;
    }
}
