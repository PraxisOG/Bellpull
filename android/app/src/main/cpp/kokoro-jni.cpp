// Kokoro v1 ONNX and Misaki BART fallback, using the existing isolated ORT runtime.
#include <jni.h>
#include <onnxruntime_cxx_api.h>
#include <algorithm>
#include <cmath>
#include <fstream>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {
struct Model {
  Ort::Env env{ORT_LOGGING_LEVEL_ERROR, "BellpullKokoro"};
  Ort::SessionOptions options;
  std::unique_ptr<Ort::Session> voice, g2p;
  std::string voicePath;
  std::vector<float> styles;
  Model(const char* path, const char* phonemes, int threads) {
    options.SetIntraOpNumThreads(std::clamp(threads, 1, 8));
    options.SetInterOpNumThreads(1);
    options.SetExecutionMode(ExecutionMode::ORT_SEQUENTIAL);
    options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
    voice = std::make_unique<Ort::Session>(env, path, options);
    options.SetIntraOpNumThreads(1);
    g2p = std::make_unique<Ort::Session>(env, phonemes, options);
  }
};
struct Utf {
  JNIEnv* env; jstring value; const char* data;
  Utf(JNIEnv* e, jstring v):env(e),value(v),data(e->GetStringUTFChars(v,nullptr)) {
    if (!data) throw std::runtime_error("String allocation failed");
  }
  ~Utf(){env->ReleaseStringUTFChars(value,data);}
};
void fail(JNIEnv* env) {
  if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
      "Kokoro could not synthesize this phrase. Try again or reload the model.");
}
bool cancelled(JNIEnv* env,jobject sink) {
  auto cls=env->GetObjectClass(sink);
  auto method=env->GetMethodID(cls,"isCancelled","()Z");
  env->DeleteLocalRef(cls);
  return env->CallBooleanMethod(sink,method) || env->ExceptionCheck();
}
std::vector<int64_t> ids(JNIEnv* env,jlongArray array) {
  auto n=env->GetArrayLength(array);
  if (n<1 || n>512) throw std::runtime_error("Invalid token count");
  std::vector<jlong> raw(n); env->GetLongArrayRegion(array,0,n,raw.data());
  return std::vector<int64_t>(raw.begin(),raw.end());
}
}
extern "C" JNIEXPORT jlong JNICALL Java_org_praxisog_hermes_KokoroNative_load(
 JNIEnv* env,jobject,jstring path,jstring phonemes,jint threads) {
 try { Utf a(env,path),b(env,phonemes); return reinterpret_cast<jlong>(new Model(a.data,b.data,threads)); }
 catch (...) { fail(env); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_org_praxisog_hermes_KokoroNative_free(JNIEnv*,jobject,jlong handle) {
 delete reinterpret_cast<Model*>(handle);
}
extern "C" JNIEXPORT jintArray JNICALL Java_org_praxisog_hermes_KokoroNative_predict(
 JNIEnv* env,jobject,jlong handle,jlongArray input,jobject sink) {
 try {
  auto* m=reinterpret_cast<Model*>(handle); if(!m) throw std::runtime_error("No model");
  auto words=ids(env,input); std::vector<int64_t> decoded{1};
  auto mem=Ort::MemoryInfo::CreateCpu(OrtArenaAllocator,OrtMemTypeDefault);
  const char* names[]={"input_ids","decoder_input_ids"}; const char* outputs[]={"logits"};
  for(int i=0;i<63;i++) {
   if(cancelled(env,sink)) return env->NewIntArray(0);
   int64_t a[]={1,static_cast<int64_t>(words.size())}, b[]={1,static_cast<int64_t>(decoded.size())};
   std::vector<Ort::Value> values;
   values.emplace_back(Ort::Value::CreateTensor<int64_t>(mem,words.data(),words.size(),a,2));
   values.emplace_back(Ort::Value::CreateTensor<int64_t>(mem,decoded.data(),decoded.size(),b,2));
   auto result=m->g2p->Run(Ort::RunOptions{nullptr},names,values.data(),2,outputs,1);
   auto info=result[0].GetTensorTypeAndShapeInfo(); auto shape=info.GetShape();
   auto width=shape.back(); auto count=info.GetElementCount();
   if(width<4 || count<static_cast<size_t>(width)) throw std::runtime_error("Invalid logits");
   const float* last=result[0].GetTensorData<float>()+count-width;
   int next=static_cast<int>(std::max_element(last,last+width)-last);
   decoded.push_back(next); if(next==2) break;
  }
  std::vector<jint> out(decoded.begin(),decoded.end());
  auto result=env->NewIntArray(out.size()); if(result) env->SetIntArrayRegion(result,0,out.size(),out.data()); return result;
 } catch (...) { fail(env); return nullptr; }
}
extern "C" JNIEXPORT jint JNICALL Java_org_praxisog_hermes_KokoroNative_synthesize(
 JNIEnv* env,jobject,jlong handle,jlongArray input,jstring voice,jobject sink) {
 try {
  if(cancelled(env,sink)) return 1;
  auto* m=reinterpret_cast<Model*>(handle); if(!m) throw std::runtime_error("No model");
  auto tokens=ids(env,input); if(tokens.size()<3 || tokens.front()!=0 || tokens.back()!=0) throw std::runtime_error("Invalid tokens");
  Utf path(env,voice);
  if(m->voicePath!=path.data) {
   std::ifstream file(path.data,std::ios::binary|std::ios::ate);
   if(!file || file.tellg()!=522240) throw std::runtime_error("Invalid voice");
   m->styles.resize(510*256); file.seekg(0); file.read(reinterpret_cast<char*>(m->styles.data()),522240);
   if(!file) throw std::runtime_error("Voice read failed"); m->voicePath=path.data;
  }
  auto mem=Ort::MemoryInfo::CreateCpu(OrtArenaAllocator,OrtMemTypeDefault);
  int64_t tokenShape[]={1,static_cast<int64_t>(tokens.size())}, styleShape[]={1,256}, speedShape[]={1};
  float speed=1;
  std::vector<Ort::Value> values;
  values.emplace_back(Ort::Value::CreateTensor<int64_t>(mem,tokens.data(),tokens.size(),tokenShape,2));
  // The style row is indexed by phoneme count minus one (without the two padding tokens).
  values.emplace_back(Ort::Value::CreateTensor<float>(mem,m->styles.data()+(tokens.size()-3)*256,256,styleShape,2));
  values.emplace_back(Ort::Value::CreateTensor<float>(mem,&speed,1,speedShape,1));
  const char* names[]={"input_ids","style","speed"}; const char* outputs[]={"waveform"};
  auto result=m->voice->Run(Ort::RunOptions{nullptr},names,values.data(),3,outputs,1);
  if(cancelled(env,sink)) return 1;
  auto count=result[0].GetTensorTypeAndShapeInfo().GetElementCount();
  if(count==0 || count>24000*60) throw std::runtime_error("Invalid audio length");
  auto samples=result[0].GetTensorData<float>();
  for(size_t i=0;i<count;i++) if(!std::isfinite(samples[i])) throw std::runtime_error("Invalid audio");
  auto cls=env->GetObjectClass(sink); auto method=env->GetMethodID(cls,"onChunk","([F)Z"); env->DeleteLocalRef(cls);
  for(size_t at=0;at<count;at+=2400) {
   if(cancelled(env,sink)) return 1;
   auto n=static_cast<jsize>(std::min<size_t>(2400,count-at)); auto pcm=env->NewFloatArray(n);
   if(!pcm) return 1;
   env->SetFloatArrayRegion(pcm,0,n,samples+at); bool ok=env->CallBooleanMethod(sink,method,pcm);
   env->DeleteLocalRef(pcm); if(!ok || env->ExceptionCheck()) return 1;
  }
  return 0;
 } catch (...) { fail(env); return 1; }
}
