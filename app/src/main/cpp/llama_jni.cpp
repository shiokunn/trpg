#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <random>
#include <sstream>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"

#define TAG "TrpgLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

std::mutex g_model_mutex;

llama_model * g_model = nullptr;
llama_context * g_ctx = nullptr;
llama_batch_ext * g_batch = nullptr;
int g_n_ctx = 0;
int g_n_batch = 512;
bool g_backend_initialized = false;

std::atomic<bool> g_cancel{false};

void android_log_callback(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr) {
        return;
    }
    switch (level) {
        case GGML_LOG_LEVEL_ERROR:
            __android_log_write(ANDROID_LOG_ERROR, TAG, text);
            break;
        case GGML_LOG_LEVEL_WARN:
            __android_log_write(ANDROID_LOG_WARN, TAG, text);
            break;
        case GGML_LOG_LEVEL_INFO:
            __android_log_write(ANDROID_LOG_INFO, TAG, text);
            break;
        default:
            __android_log_write(ANDROID_LOG_DEBUG, TAG, text);
            break;
    }
}

std::string json_escape(const std::string & value) {
    std::string out;
    out.reserve(value.size() + 16);
    for (unsigned char c : value) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += static_cast<char>(c);
                }
                break;
        }
    }
    return out;
}

std::string error_json(const std::string & message) {
    return "{\"ok\":false,\"error\":\"" + json_escape(message) + "\"}";
}

std::string ok_json() {
    return "{\"ok\":true}";
}

std::string jstring_to_string(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special) {
    const int32_t needed = -llama_tokenize(
        vocab,
        text.data(),
        static_cast<int32_t>(text.size()),
        nullptr,
        0,
        add_special,
        true);

    if (needed <= 0) {
        return {};
    }

    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    const int32_t written = llama_tokenize(
        vocab,
        text.data(),
        static_cast<int32_t>(text.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        add_special,
        true);

    if (written < 0) {
        return {};
    }
    tokens.resize(static_cast<size_t>(written));
    return tokens;
}

std::string token_to_piece(const llama_vocab * vocab, llama_token token) {
    char small[16];
    int32_t n = llama_token_to_piece(vocab, token, small, sizeof(small), 0, false);
    if (n >= 0) {
        return std::string(small, static_cast<size_t>(n));
    }
    const int32_t needed = -n;
    if (needed <= 0 || needed > 1024 * 1024) {
        return {};
    }
    std::vector<char> buffer(static_cast<size_t>(needed));
    n = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, false);
    if (n < 0) {
        return {};
    }
    return std::string(buffer.data(), static_cast<size_t>(n));
}

std::string backend_name_for_reg(ggml_backend_reg_t reg) {
    const char * name = ggml_backend_reg_name(reg);
    return name == nullptr ? "unknown" : std::string(name);
}

std::string backend_info_json() {
    std::ostringstream out;
    out << "{\"system\":\"" << json_escape(llama_print_system_info() == nullptr ? "" : llama_print_system_info());
    out << "\",\"backends\":[";
    bool first = true;
    const size_t count = ggml_backend_reg_count();
    for (size_t i = 0; i < count; ++i) {
        ggml_backend_reg_t reg = ggml_backend_reg_get(i);
        if (reg == nullptr) {
            continue;
        }
        if (!first) {
            out << ",";
        }
        first = false;
        out << "\"" << json_escape(backend_name_for_reg(reg)) << "\"";
    }
    out << "],\"gpuOffload\":";
    out << (llama_supports_gpu_offload() ? "true" : "false");
    out << "}";
    return out.str();
}

std::string model_info_json() {
    if (g_model == nullptr) {
        return error_json("モデルが読み込まれていません");
    }
    char desc[512] = {0};
    llama_model_desc(g_model, desc, sizeof(desc));
    const uint64_t params = llama_model_n_params(g_model);
    const uint64_t size = llama_model_size(g_model);
    const int32_t n_ctx_train = llama_model_n_ctx_train(g_model);
    const bool has_chat_template = llama_model_chat_template(g_model, nullptr) != nullptr;

    std::ostringstream out;
    out << "{\"ok\":true";
    out << ",\"description\":\"" << json_escape(desc) << "\"";
    out << ",\"nParams\":" << params;
    out << ",\"sizeBytes\":" << size;
    out << ",\"nCtxTrain\":" << n_ctx_train;
    out << ",\"nCtx\":" << g_n_ctx;
    out << ",\"hasChatTemplate\":" << (has_chat_template ? "true" : "false");
    out << "}";
    return out.str();
}

void free_model_locked() {
    g_cancel.store(false);
    if (g_batch != nullptr) {
        llama_batch_ext_free(g_batch);
        g_batch = nullptr;
    }
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_n_ctx = 0;
}

struct JvmCallback {
    JNIEnv * env = nullptr;
    jobject callback = nullptr;
    jmethodID on_token = nullptr;
    bool valid = false;

    explicit JvmCallback(JNIEnv * env_, jobject callback_) : env(env_), callback(callback_) {
        if (callback == nullptr) {
            return;
        }
        jclass cls = env->GetObjectClass(callback);
        if (cls == nullptr) {
            return;
        }
        on_token = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
        valid = on_token != nullptr;
    }

    void emit(const std::string & piece) {
        if (!valid || piece.empty()) {
            return;
        }
        jstring text = env->NewStringUTF(piece.c_str());
        if (text == nullptr) {
            return;
        }
        env->CallVoidMethod(callback, on_token, text);
        env->DeleteLocalRef(text);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGW("Kotlin token callback threw an exception; callback disabled");
            valid = false;
        }
    }
};

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_nativeInit(
    JNIEnv * env,
    jobject /*this*/,
    jstring native_lib_dir) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_backend_initialized) {
        return;
    }
    llama_log_set(android_log_callback, nullptr);

    const std::string dir = jstring_to_string(env, native_lib_dir);
    if (!dir.empty()) {
        ggml_backend_load_all_from_path(dir.c_str());
    }
    llama_backend_init();
    g_backend_initialized = true;
    LOGI("llama.cpp backend initialized (dir=%s)", dir.c_str());
}

JNIEXPORT jstring JNICALL
Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_nativeLoadModel(
    JNIEnv * env,
    jobject /*this*/,
    jstring path,
    jint n_ctx,
    jint n_threads,
    jint n_gpu_layers) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    free_model_locked();

    const std::string model_path = jstring_to_string(env, path);
    if (model_path.empty()) {
        return env->NewStringUTF(error_json("モデルパスが空です").c_str());
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = n_gpu_layers;
    // v0.9.0: mmap/mlock are selected by llama_load_mode. AUTO is appropriate on Android.
    model_params.load_mode = LLAMA_LOAD_MODE_AUTO;

    llama_model * model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (model == nullptr) {
        LOGE("Failed to load model: %s", model_path.c_str());
        return env->NewStringUTF(error_json("GGUFモデルの読み込みに失敗しました。ファイル形式と空きメモリを確認してください").c_str());
    }

    if (llama_model_has_encoder(model) && !llama_model_has_decoder(model)) {
        llama_model_free(model);
        return env->NewStringUTF(error_json("エンコーダ専用モデルには対応していません").c_str());
    }

    const int32_t ctx_size = n_ctx > 0 ? n_ctx : 8192;
    const int32_t batch_size = std::min<int32_t>(ctx_size, 1024);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(ctx_size);
    ctx_params.n_batch = static_cast<uint32_t>(batch_size);
    ctx_params.n_ubatch = static_cast<uint32_t>(std::min<int32_t>(batch_size, 512));
    ctx_params.n_threads = n_threads > 0 ? n_threads : 4;
    ctx_params.n_threads_batch = n_threads > 0 ? n_threads : 4;

    llama_context * ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        llama_model_free(model);
        LOGE("Failed to create llama context (n_ctx=%d)", ctx_size);
        return env->NewStringUTF(error_json("推論コンテキストの作成に失敗しました。コンテキストサイズを下げてください").c_str());
    }

    llama_batch_ext * batch = llama_batch_ext_init(ctx);
    if (batch == nullptr) {
        llama_free(ctx);
        llama_model_free(model);
        return env->NewStringUTF(error_json("バッチの初期化に失敗しました").c_str());
    }

    g_model = model;
    g_ctx = ctx;
    g_batch = batch;
    g_n_ctx = ctx_size;
    g_n_batch = batch_size;
    g_cancel.store(false);

    LOGI("Model loaded: n_ctx=%d n_batch=%d threads=%d gpu_layers=%d", ctx_size, batch_size, n_threads, n_gpu_layers);
    return env->NewStringUTF(model_info_json().c_str());
}

JNIEXPORT void JNICALL
Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_nativeUnloadModel(
    JNIEnv * /*env*/,
    jobject /*this*/) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    free_model_locked();
    LOGI("Model unloaded");
}

JNIEXPORT jstring JNICALL
Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_nativeFormatChat(
    JNIEnv * env,
    jobject /*this*/,
    jobjectArray roles,
    jobjectArray contents,
    jboolean add_assistant) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_model == nullptr) {
        return env->NewStringUTF("");
    }
    if (roles == nullptr || contents == nullptr) {
        return env->NewStringUTF("");
    }
    const jsize count = env->GetArrayLength(roles);
    if (count != env->GetArrayLength(contents)) {
        return env->NewStringUTF("");
    }

    const char * tmpl = llama_model_chat_template(g_model, nullptr);
    if (tmpl == nullptr) {
        return env->NewStringUTF("");
    }

    std::vector<std::string> role_storage;
    std::vector<std::string> content_storage;
    role_storage.reserve(static_cast<size_t>(count));
    content_storage.reserve(static_cast<size_t>(count));
    size_t total_chars = 0;

    for (jsize i = 0; i < count; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        role_storage.push_back(jstring_to_string(env, role));
        content_storage.push_back(jstring_to_string(env, content));
        total_chars += role_storage.back().size() + content_storage.back().size();
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }

    std::vector<llama_chat_message> messages;
    messages.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        messages.push_back(llama_chat_message{
            role_storage[static_cast<size_t>(i)].c_str(),
            content_storage[static_cast<size_t>(i)].c_str(),
        });
    }

    std::vector<char> buffer(total_chars * 2 + 4096);
    int32_t needed = llama_chat_apply_template(
        tmpl,
        messages.data(),
        messages.size(),
        add_assistant == JNI_TRUE,
        buffer.data(),
        static_cast<int32_t>(buffer.size()));

    if (needed < 0) {
        return env->NewStringUTF("");
    }
    if (static_cast<size_t>(needed) > buffer.size()) {
        buffer.resize(static_cast<size_t>(needed) + 16);
        needed = llama_chat_apply_template(
            tmpl,
            messages.data(),
            messages.size(),
            add_assistant == JNI_TRUE,
            buffer.data(),
            static_cast<int32_t>(buffer.size()));
        if (needed < 0 || static_cast<size_t>(needed) > buffer.size()) {
            return env->NewStringUTF("");
        }
    }

    return env->NewStringUTF(std::string(buffer.data(), static_cast<size_t>(needed)).c_str());
}

JNIEXPORT jint JNICALL
Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_nativeCountTokens(
    JNIEnv * env,
    jobject /*this*/,
    jstring text) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_model == nullptr) {
        return -1;
    }
    const std::string value = jstring_to_string(env, text);
    const auto tokens = tokenize(llama_model_get_vocab(g_model), value, false);
    return static_cast<jint>(tokens.size());
}

JNIEXPORT jstring JNICALL
Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_nativeGenerate(
    JNIEnv * env,
    jobject /*this*/,
    jstring prompt,
    jint max_tokens,
    jfloat temperature,
    jfloat top_p,
    jint top_k,
    jfloat repeat_penalty,
    jint seed,
    jobject callback) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_model == nullptr || g_ctx == nullptr || g_batch == nullptr) {
        return env->NewStringUTF(error_json("モデルが読み込まれていません").c_str());
    }

    g_cancel.store(false);
    const llama_vocab * vocab = llama_model_get_vocab(g_model);

    const std::string prompt_text = jstring_to_string(env, prompt);
    if (prompt_text.empty()) {
        return env->NewStringUTF(error_json("プロンプトが空です").c_str());
    }

    std::vector<llama_token> prompt_tokens = tokenize(vocab, prompt_text, true);
    if (prompt_tokens.empty()) {
        return env->NewStringUTF(error_json("プロンプトのトークン化に失敗しました").c_str());
    }

    const int32_t n_prompt = static_cast<int32_t>(prompt_tokens.size());
    const int32_t headroom = 8;
    if (n_prompt + headroom >= g_n_ctx) {
        std::ostringstream message;
        message << "プロンプトが長すぎます（" << n_prompt << " トークン / コンテキスト " << g_n_ctx
                << "）。コンテキストサイズを増やすか、記憶や履歴を減らしてください";
        return env->NewStringUTF(error_json(message.str()).c_str());
    }

    int32_t budget = max_tokens > 0 ? max_tokens : 512;
    budget = std::min(budget, g_n_ctx - n_prompt - headroom);
    if (budget < 16) {
        budget = 16;
    }

    llama_memory_clear(llama_get_memory(g_ctx), false);

    // Decode the prompt in batches, requesting logits only for the final token.
    int32_t n_pos = 0;
    while (n_pos < n_prompt) {
        if (g_cancel.load()) {
            return env->NewStringUTF("{\"ok\":false,\"cancelled\":true,\"text\":\"\",\"error\":\"\"}");
        }
        const int32_t chunk = std::min(g_n_batch, n_prompt - n_pos);
        llama_batch_ext_clear(g_batch);
        for (int32_t i = 0; i < chunk; ++i) {
            const int32_t idx = llama_batch_ext_add_token(g_batch, 0, prompt_tokens[static_cast<size_t>(n_pos + i)]);
            if (idx < 0) {
                return env->NewStringUTF(error_json("プロンプトのバッチ追加に失敗しました").c_str());
            }
            const llama_pos pos = n_pos + i;
            if (!llama_batch_ext_set_pos(g_batch, idx, &pos)) {
                return env->NewStringUTF(error_json("バッチ位置の設定に失敗しました").c_str());
            }
            llama_batch_ext_set_output_logits(g_batch, idx, n_pos + i == n_prompt - 1);
        }
        const int32_t rc = llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch);
        if (rc != 0) {
            LOGE("llama_process(prompt) failed: %d", rc);
            return env->NewStringUTF(error_json("プロンプトの推論に失敗しました（メモリ不足の可能性）").c_str());
        }
        n_pos += chunk;
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler * sampler = llama_sampler_chain_init(sparams);
    if (sampler == nullptr) {
        return env->NewStringUTF(error_json("サンプラーの初期化に失敗しました").c_str());
    }

    const int32_t n_vocab = llama_vocab_n_tokens(vocab);
    if (repeat_penalty > 1.0f) {
        llama_sampler_chain_add(
            sampler,
            llama_sampler_init_penalties(n_vocab, 64, repeat_penalty, 0.0f, 0.0f));
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k > 0 ? top_k : 40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p > 0.0f ? top_p : 0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature > 0.0f ? temperature : 0.75f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(static_cast<uint32_t>(seed)));

    JvmCallback jvm_callback(env, callback);
    std::string output;
    output.reserve(static_cast<size_t>(budget) * 6);
    bool cancelled = false;
    std::string failure;

    for (int32_t generated = 0; generated < budget; ++generated) {
        if (g_cancel.load()) {
            cancelled = true;
            break;
        }

        const llama_token token = llama_sampler_sample(sampler, g_ctx, -1);
        if (token == LLAMA_TOKEN_NULL) {
            failure = "トークンのサンプリングに失敗しました";
            break;
        }
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }

        const std::string piece = token_to_piece(vocab, token);
        output += piece;
        jvm_callback.emit(piece);

        if (n_pos + 1 >= g_n_ctx) {
            break;
        }

        llama_batch_ext_clear(g_batch);
        const int32_t idx = llama_batch_ext_add_token(g_batch, 0, token);
        if (idx < 0) {
            failure = "生成バッチの追加に失敗しました";
            break;
        }
        const llama_pos pos = n_pos;
        if (!llama_batch_ext_set_pos(g_batch, idx, &pos)) {
            failure = "生成バッチの位置設定に失敗しました";
            break;
        }
        llama_batch_ext_set_output_logits(g_batch, idx, true);

        const int32_t rc = llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch);
        if (rc != 0) {
            failure = "生成中の推論に失敗しました（メモリ不足の可能性）";
            break;
        }
        n_pos += 1;
    }

    llama_sampler_free(sampler);
    llama_memory_clear(llama_get_memory(g_ctx), false);

    std::ostringstream out;
    out << "{\"ok\":" << (failure.empty() ? "true" : "false");
    out << ",\"cancelled\":" << (cancelled ? "true" : "false");
    out << ",\"text\":\"" << json_escape(output) << "\"";
    out << ",\"error\":\"" << json_escape(failure) << "\"}";
    return env->NewStringUTF(out.str().c_str());
}

JNIEXPORT void JNICALL
Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_nativeCancel(
    JNIEnv * /*env*/,
    jobject /*this*/) {
    g_cancel.store(true);
}

JNIEXPORT jstring JNICALL
Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_nativeBackendInfo(
    JNIEnv * env,
    jobject /*this*/) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    return env->NewStringUTF(backend_info_json().c_str());
}

JNIEXPORT jstring JNICALL
Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_nativeModelInfo(
    JNIEnv * env,
    jobject /*this*/) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    return env->NewStringUTF(model_info_json().c_str());
}

} // extern "C"
