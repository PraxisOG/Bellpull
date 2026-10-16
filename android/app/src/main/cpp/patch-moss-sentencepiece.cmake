# Same Android threading removals as
# third-party/sherpa-onnx/cmake/simple-sentencepiece.cmake (v1.13.8).
# FetchContent invokes this after extracting the hash-pinned v0.7 archive.
if(NOT DEFINED SOURCE_DIR)
    message(FATAL_ERROR "SOURCE_DIR is required")
endif()
set(_tp_header "${SOURCE_DIR}/ssentencepiece/csrc/threadpool.h")
file(WRITE "${_tp_header}" [=[
// Empty stub — threading removed for Android/Termux.
#ifndef THREAD_POOL_H
#define THREAD_POOL_H
#endif  // THREAD_POOL_H
]=])

set(_sph "${SOURCE_DIR}/ssentencepiece/csrc/ssentencepiece.h")
file(READ "${_sph}" _sph_content)
string(REPLACE "#include \"ssentencepiece/csrc/threadpool.h\"\n" "" _sph_content "${_sph_content}")
string(REPLACE "int32_t num_threads = std::thread::hardware_concurrency()" "int32_t num_threads = 1" _sph_content "${_sph_content}")
string(REPLACE "pool_ = std::make_unique<ThreadPool>(num_threads);\n    Build(is);" "Build(is);" _sph_content "${_sph_content}")
string(REPLACE "pool_ = std::make_unique<ThreadPool>(num_threads);\n    Build(vocab_path);" "Build(vocab_path);" _sph_content "${_sph_content}")
string(REPLACE "pool_ = std::make_unique<ThreadPool>(num_threads);" "" _sph_content "${_sph_content}")
string(REPLACE "std::unique_ptr<ThreadPool> pool_;" "" _sph_content "${_sph_content}")
file(WRITE "${_sph}" "${_sph_content}")

set(_spc "${SOURCE_DIR}/ssentencepiece/csrc/ssentencepiece.cc")
file(READ "${_spc}" _spc_content)
string(REPLACE
    [==[  ostrs->resize(strs.size());
  std::vector<std::future<void>> results;
  for (int32_t i = 0; i < strs.size(); ++i) {
    results.emplace_back(pool_->enqueue([this, i, &strs, ostrs] {
      return this->Encode(strs[i], &((*ostrs)[i]));
    }));
  }

  for (auto &&result : results) {
    result.get();
  }]==]
    [==[  ostrs->resize(strs.size());
  for (int32_t i = 0; i < strs.size(); ++i) {
    this->Encode(strs[i], &((*ostrs)[i]));
  }]==]
    _spc_content "${_spc_content}")
string(REPLACE
    [==[  oids->resize(strs.size());
  std::vector<std::future<void>> results;
  for (int32_t i = 0; i < strs.size(); ++i) {
    results.emplace_back(pool_->enqueue([this, i, &strs, oids] {
      return this->Encode(strs[i], &((*oids)[i]));
    }));
  }

  for (auto &&result : results) {
    result.get();
  }]==]
    [==[  oids->resize(strs.size());
  for (int32_t i = 0; i < strs.size(); ++i) {
    this->Encode(strs[i], &((*oids)[i]));
  }]==]
    _spc_content "${_spc_content}")
string(REPLACE
    [==[  std::vector<std::string> res;
  std::vector<std::future<std::string>> results;
  for (const auto &id : ids) {
    results.emplace_back(
        pool_->enqueue([this, &id] { return this->Decode(id); }));
  }
  for (auto &&result : results) {
    res.push_back(result.get());
  }
  return res;]==]
    [==[  std::vector<std::string> res;
  for (const auto &id : ids) {
    res.push_back(this->Decode(id));
  }
  return res;]==]
    _spc_content "${_spc_content}")
file(WRITE "${_spc}" "${_spc_content}")
# Fail the build rather than silently compiling a changed upstream threading API.
if(_sph_content MATCHES "ThreadPool|std::thread|pool_" OR
   _spc_content MATCHES "std::future|std::promise|pool_->enqueue")
    message(FATAL_ERROR "MOSS sentencepiece Android threading patch is incomplete")
endif()
