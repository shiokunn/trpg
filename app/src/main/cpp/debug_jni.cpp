// ============================================================================
// TEMPORARY DEBUG MODE — JNI entry point for runtime configuration reporting.
//
// This file only reads state. It never touches the model, the context, the
// threadpool, the KV cache or the generation path.
//
// Removal: delete this file and the marked blocks in llama_jni.cpp /
// CMakeLists.txt, then delete app/src/main/kotlin/.../debug/.
// ============================================================================

#include <jni.h>

#include <fstream>
#include <sstream>
#include <string>

#include "debug_bridge.h"

// Defined here so that llama_jni.cpp only needs to assign fields.
TrpgDebugState g_trpg_debug;
std::mutex g_trpg_debug_mutex;

namespace {

std::string json_escape_simple(const std::string & value) {
    std::string out;
    out.reserve(value.size() + 8);
    for (char c : value) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += c; break;
        }
    }
    return out;
}

// Effective CPU mask of this process, e.g. "0-6". Read from /proc so we do not
// depend on glibc-specific cpu_set_t macros in the NDK.
std::string read_allowed_cpus() {
    std::ifstream input("/proc/self/status");
    if (!input) {
        return {};
    }
    std::string line;
    while (std::getline(input, line)) {
        static const std::string prefix = "Cpus_allowed_list:";
        if (line.compare(0, prefix.size(), prefix) == 0) {
            const size_t start = line.find_first_not_of(" \t", prefix.size());
            if (start != std::string::npos) {
                return line.substr(start);
            }
        }
    }
    return {};
}

void append_string(std::ostringstream & out, const char * key, const std::string & value) {
    if (value.empty()) {
        return;
    }
    out << ",\"" << key << "\":\"" << json_escape_simple(value) << "\"";
}

void append_int(std::ostringstream & out, const char * key, int value) {
    if (value <= 0) {
        return;
    }
    out << ",\"" << key << "\":" << value;
}

} // namespace

std::string trpg_debug_runtime_json() {
    std::lock_guard<std::mutex> guard(g_trpg_debug_mutex);
    std::ostringstream out;
    out << "{";
    // Pinned at build time via scripts/fetch-llama.sh (v0.9.0).
    out << "\"llamaVersion\":\"0.9.0\"";
    out << ",\"llamaCommit\":\"cd959ba\"";
    out << ",\"modelLoaded\":" << (g_trpg_debug.model_loaded ? "true" : "false");

    append_int(out, "nCtx", g_trpg_debug.n_ctx);
    append_int(out, "nCtxRequested", g_trpg_debug.n_ctx_requested);
    append_int(out, "nCtxTrain", g_trpg_debug.n_ctx_train);
    append_int(out, "nBatch", g_trpg_debug.n_batch);
    append_int(out, "nBatchRequested", g_trpg_debug.n_batch_requested);
    append_int(out, "nUbatch", g_trpg_debug.n_ubatch);
    append_int(out, "nUbatchRequested", g_trpg_debug.n_ubatch_requested);
    append_int(out, "nSeqMax", g_trpg_debug.n_seq_max);
    append_int(out, "nThreads", g_trpg_debug.n_threads);
    append_int(out, "nThreadsRequested", g_trpg_debug.n_threads_requested);
    append_int(out, "nThreadsBatch", g_trpg_debug.n_threads_batch);

    // gpu_layers may legitimately be 0 (CPU only); append unconditionally.
    out << ",\"gpuLayers\":" << g_trpg_debug.gpu_layers;

    append_string(out, "threadpool", g_trpg_debug.threadpool);
    append_string(out, "flashAttn", g_trpg_debug.flash_attn);
    append_string(out, "kvTypeK", g_trpg_debug.type_k);
    append_string(out, "kvTypeV", g_trpg_debug.type_v);

    const std::string cpus = read_allowed_cpus();
    append_string(out, "allowedCpus", cpus);

    out << "}";
    return out.str();
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_nativeRuntimeInfo(
    JNIEnv * env,
    jobject /*this*/) {
    const std::string payload = trpg_debug_runtime_json();
    return env->NewStringUTF(payload.c_str());
}
