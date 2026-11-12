#pragma once

// ============================================================================
// TEMPORARY DEBUG MODE — shared state between llama_jni.cpp and debug_jni.cpp.
//
// This header exists only to expose the *actual* runtime values that the debug
// screen reports. Nothing here changes inference behaviour: the values are
// copied after the model/context/threadpool have already been created.
//
// Removal: delete this file, debug_jni.cpp, and the blocks marked
// "DEBUG MODE (temporary)" in llama_jni.cpp / CMakeLists.txt.
// ============================================================================

#include <string>
#include <mutex>

struct TrpgDebugState {
    bool model_loaded = false;

    int n_ctx = 0;
    int n_ctx_requested = 0;
    int n_ctx_train = 0;

    int n_batch = 0;
    int n_batch_requested = 0;

    int n_ubatch = 0;
    int n_ubatch_requested = 0;

    int n_seq_max = 0;

    int n_threads = 0;
    int n_threads_requested = 0;
    int n_threads_batch = 0;

    int gpu_layers = 0;

    std::string threadpool;   // "profile=A threads=4 strict_cpu=true cpus=7,4,5,6"
    std::string flash_attn;   // llama_flash_attn_type_name()
    std::string type_k;       // ggml_type_name()
    std::string type_v;
};

// Timing values that the generation entry points already measure. Passed into
// run_generation_locked() only so the result JSON can carry them.
struct TrpgGenerationTiming {
    long long apply_ms = 0;
    long long tokenize_ms = 0;
};

extern TrpgDebugState g_trpg_debug;

// Guards g_trpg_debug. Write sites hold the engine mutex and then this mutex; the
// reader (runtime JSON) takes only this mutex, so there is no lock cycle.
extern std::mutex g_trpg_debug_mutex;

// Builds the JSON consumed by LlamaCppNative.nativeRuntimeInfo().
std::string trpg_debug_runtime_json();
