#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <limits>
#include <mutex>
#include <random>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include <unistd.h>

#include "llama.h"
#include "ggml.h"
#include "ggml-backend.h"
#include "chat.h"
#include "utf8_utils.h"

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

using ggml_threadpool_new_fn = ggml_threadpool_t (*)(ggml_threadpool_params *);
using ggml_threadpool_free_fn = void (*)(ggml_threadpool_t);

ggml_threadpool_new_fn g_threadpool_new_fn = nullptr;
ggml_threadpool_free_fn g_threadpool_free_fn = nullptr;
ggml_threadpool_t g_threadpool = nullptr;
ggml_threadpool_t g_threadpool_batch = nullptr;
bool g_threadpool_attached = false;

common_chat_templates_ptr g_chat_templates;

struct ChatTemplateState {
    bool has_metadata = false;
    bool explicit_template = false;
    bool applied = false;
    bool supports_thinking = false;
    bool add_bos = false;
    bool add_eos = false;
    std::string error;
};

ChatTemplateState g_template;

std::atomic<uint64_t> g_next_generation_id{1};
std::atomic<uint64_t> g_active_generation_id{0};
std::atomic<uint64_t> g_cancel_generation_id{0};

bool cancel_requested_for(uint64_t generation_id) {
    if (generation_id == 0) {
        return false;
    }
    return g_cancel_generation_id.load(std::memory_order_acquire) == generation_id;
}

bool abort_callback(void * /*user_data*/) {
    const uint64_t active = g_active_generation_id.load(std::memory_order_acquire);
    if (active == 0) {
        return false;
    }
    return g_cancel_generation_id.load(std::memory_order_acquire) == active;
}

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

std::string chat_template_status() {
    if (!g_template.applied) {
        return "unsupported";
    }
    return g_template.explicit_template ? "applied" : "none";
}

std::string jstring_to_string(JNIEnv * env, jstring value) {
    return trpg::JniStringToUtf8(env, value);
}

jstring new_string_utf8(JNIEnv * env, const std::string & value) {
    return trpg::Utf8ToJniString(env, value);
}

std::vector<std::string> jstring_array_to_vector(JNIEnv * env, jobjectArray values) {
    std::vector<std::string> result;
    if (values == nullptr) {
        return result;
    }
    const jsize count = env->GetArrayLength(values);
    result.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto element = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        result.push_back(jstring_to_string(env, element));
        env->DeleteLocalRef(element);
    }
    return result;
}

std::vector<llama_token> tokenize(
    const llama_vocab * vocab,
    const std::string & text,
    bool add_special,
    bool parse_special) {
    const int32_t needed = -llama_tokenize(
        vocab,
        text.data(),
        static_cast<int32_t>(text.size()),
        nullptr,
        0,
        add_special,
        parse_special);

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
        parse_special);

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
    out << ",\"chatTemplateStatus\":\"" << chat_template_status() << "\"";
    out << ",\"chatTemplateError\":\"" << json_escape(g_template.error) << "\"";
    out << ",\"chatTemplateSupportsThinking\":" << (g_template.supports_thinking ? "true" : "false");
    out << ",\"chatTemplateAddBos\":" << (g_template.add_bos ? "true" : "false");
    out << "}";
    return out.str();
}

std::vector<int> read_cpu_list(const std::string & path) {
    std::ifstream input(path);
    if (!input) {
        return {};
    }
    std::string content;
    std::getline(input, content);
    std::vector<int> cpus;
    std::size_t pos = 0;
    while (pos < content.size()) {
        while (pos < content.size() && (content[pos] == ',' || content[pos] == ' ')) {
            ++pos;
        }
        if (pos >= content.size()) {
            break;
        }
        int start = 0;
        while (pos < content.size() && content[pos] >= '0' && content[pos] <= '9') {
            start = start * 10 + (content[pos] - '0');
            ++pos;
        }
        int end = start;
        if (pos < content.size() && content[pos] == '-') {
            ++pos;
            end = 0;
            while (pos < content.size() && content[pos] >= '0' && content[pos] <= '9') {
                end = end * 10 + (content[pos] - '0');
                ++pos;
            }
        }
        for (int cpu = start; cpu <= end; ++cpu) {
            cpus.push_back(cpu);
        }
        while (pos < content.size() && content[pos] != ',') {
            ++pos;
        }
    }
    return cpus;
}

int read_cpu_max_freq(int cpu) {
    std::ostringstream path;
    path << "/sys/devices/system/cpu/cpu" << cpu << "/cpufreq/cpuinfo_max_freq";
    std::ifstream input(path.str());
    if (!input) {
        return 0;
    }
    int freq = 0;
    input >> freq;
    return freq;
}

bool build_threadpool_params(
    int requested_threads,
    ggml_threadpool_params & params,
    std::string & mask_description) {
    const int profile = TRPG_THREAD_PROFILE;
    std::vector<int> cpus = read_cpu_list("/sys/devices/system/cpu/online");
    if (cpus.empty()) {
        const long online = sysconf(_SC_NPROCESSORS_ONLN);
        if (online > 0) {
            for (long cpu = 0; cpu < online; ++cpu) {
                cpus.push_back(static_cast<int>(cpu));
            }
        }
    }
    if (cpus.empty()) {
        cpus.push_back(0);
    }

    int max_freq = 0;
    for (int cpu : cpus) {
        max_freq = std::max(max_freq, read_cpu_max_freq(cpu));
    }

    std::vector<int> high_cpus;
    if (max_freq > 0) {
        for (int cpu : cpus) {
            if (read_cpu_max_freq(cpu) == max_freq) {
                high_cpus.push_back(cpu);
            }
        }
    }
    if (high_cpus.empty()) {
        high_cpus = cpus;
    }

    std::vector<int> selected;
    int threads = requested_threads > 0 ? requested_threads : 4;
    bool strict_cpu = false;

    if (profile == 0) {
        // Profile A: highest-clock cores, one thread per core.
        selected = high_cpus;
        threads = std::min(threads, static_cast<int>(selected.size()));
        strict_cpu = static_cast<int>(selected.size()) >= threads;

        // Fallback: on some devices (e.g. Dimensity 8350-Extreme on arrows Alpha) only a
        // single core reports the highest clock, so `threads` collapses to 1. A 1-thread
        // threadpool makes llama.cpp's cplan ask for more threads than are available
        // (error 1) and generation fails. Widen the candidate set to cores at >=90% of
        // max_freq, then to any online core, and keep one thread per selected core.
        const int target_threads = std::min(requested_threads > 0 ? requested_threads : 4, 4);
        if (static_cast<int>(selected.size()) < target_threads) {
            const auto add_cpu = [&selected, target_threads](int cpu) {
                if (static_cast<int>(selected.size()) >= target_threads) {
                    return;
                }
                if (std::find(selected.begin(), selected.end(), cpu) != selected.end()) {
                    return;
                }
                selected.push_back(cpu);
            };

            // Second-tier clock: 90% or more of the highest clock (the big core cluster).
            if (max_freq > 0) {
                const int tier2 = static_cast<int>(std::floor(max_freq * 0.9));
                for (int cpu : cpus) {
                    const int freq = read_cpu_max_freq(cpu);
                    if (freq > 0 && freq >= tier2) {
                        add_cpu(cpu);
                    }
                }
            }
            // Last resort: any online core, so `threads` never has to fall back to 1.
            for (int cpu : cpus) {
                add_cpu(cpu);
            }

            threads = std::min(target_threads, static_cast<int>(selected.size()));
            if (threads < 1) {
                threads = 1;
            }
            strict_cpu = static_cast<int>(selected.size()) >= threads;
        }
    } else if (profile == 1) {
        // Profile B: highest-clock cores, six threads (oversubscribed on this device).
        selected = high_cpus;
        strict_cpu = false;
    } else {
        // Profile C: cpu2..cpuN, six threads.
        for (int cpu : cpus) {
            if (cpu >= 2) {
                selected.push_back(cpu);
            }
        }
        if (selected.empty()) {
            selected = cpus;
        }
        threads = std::min(threads, static_cast<int>(selected.size()));
        strict_cpu = false;
    }

    if (threads <= 0) {
        threads = 4;
    }
    if (threads > GGML_MAX_N_THREADS) {
        threads = GGML_MAX_N_THREADS;
    }

    params = ggml_threadpool_params_default(threads);
    std::memset(params.cpumask, 0, sizeof(params.cpumask));
    for (int cpu : selected) {
        if (cpu >= 0 && cpu < GGML_MAX_N_THREADS) {
            params.cpumask[cpu] = true;
        }
    }
    params.strict_cpu = strict_cpu;

    std::ostringstream description;
    description << "profile=" << static_cast<char>('A' + profile)
                << " threads=" << threads
                << " strict_cpu=" << (strict_cpu ? "true" : "false")
                << " cpus=";
    bool first = true;
    for (int cpu : selected) {
        if (!first) {
            description << ",";
        }
        first = false;
        description << cpu;
    }
    mask_description = description.str();
    return true;
}

void free_threadpool_locked() {
    if (g_threadpool_attached && g_ctx != nullptr) {
        llama_detach_threadpool(g_ctx);
    }
    g_threadpool_attached = false;
    if (g_threadpool != nullptr && g_threadpool_free_fn != nullptr) {
        g_threadpool_free_fn(g_threadpool);
    }
    g_threadpool = nullptr;
    g_threadpool_batch = nullptr;
    g_threadpool_new_fn = nullptr;
    g_threadpool_free_fn = nullptr;
}

bool create_threadpool_locked(llama_context * ctx, int n_threads) {
    free_threadpool_locked();

    ggml_backend_dev_t device = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (device == nullptr) {
        LOGW("No CPU backend device for threadpool");
        return false;
    }
    ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(device);
    if (reg == nullptr) {
        LOGW("No CPU backend registry for threadpool");
        return false;
    }

    g_threadpool_new_fn = reinterpret_cast<ggml_threadpool_new_fn>(
        ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new"));
    g_threadpool_free_fn = reinterpret_cast<ggml_threadpool_free_fn>(
        ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_free"));
    if (g_threadpool_new_fn == nullptr || g_threadpool_free_fn == nullptr) {
        LOGW("ggml_threadpool_new/free unavailable; using llama.cpp default threadpool");
        g_threadpool_new_fn = nullptr;
        g_threadpool_free_fn = nullptr;
        return false;
    }

    ggml_threadpool_params params;
    std::string mask_description;
    if (!build_threadpool_params(n_threads, params, mask_description)) {
        return false;
    }

    g_threadpool = g_threadpool_new_fn(&params);
    if (g_threadpool == nullptr) {
        LOGW("ggml_threadpool_new failed; using llama.cpp default threadpool");
        g_threadpool_new_fn = nullptr;
        g_threadpool_free_fn = nullptr;
        return false;
    }

    g_threadpool_batch = g_threadpool;
    llama_attach_threadpool(ctx, g_threadpool, g_threadpool_batch);
    g_threadpool_attached = true;
    LOGI("Threadpool attached: %s", mask_description.c_str());
    return true;
}

void initialize_chat_templates_locked() {
    g_chat_templates.reset();
    g_template = ChatTemplateState{};

    if (g_model == nullptr) {
        return;
    }

    const char * raw_template = llama_model_chat_template(g_model, nullptr);
    g_template.has_metadata = raw_template != nullptr && raw_template[0] != '\0';

    try {
        g_chat_templates = common_chat_templates_init(g_model, "");
        if (!g_chat_templates) {
            g_template.error = "チャットテンプレートを初期化できませんでした";
            return;
        }

        g_template.explicit_template = common_chat_templates_was_explicit(g_chat_templates.get());
        const llama_vocab * vocab = llama_model_get_vocab(g_model);
        g_template.add_bos = vocab != nullptr && llama_vocab_get_add_bos(vocab);
        g_template.add_eos = vocab != nullptr && llama_vocab_get_add_eos(vocab);

        common_chat_templates_inputs inputs;
        common_chat_msg system;
        system.role = "system";
        system.content = "システム";
        common_chat_msg user;
        user.role = "user";
        user.content = "こんにちは";
        inputs.messages = {system, user};
        inputs.add_generation_prompt = true;
        inputs.use_jinja = true;
        inputs.enable_thinking = false;

        const common_chat_params params = common_chat_templates_apply(g_chat_templates.get(), inputs);
        g_template.applied = !params.prompt.str().empty();
        if (!g_template.applied) {
            g_template.error = "チャットテンプレートの評価結果が空です";
        }

        try {
            g_template.supports_thinking = common_chat_templates_support_enable_thinking(g_chat_templates.get());
        } catch (const std::exception & e) {
            g_template.supports_thinking = false;
            LOGW("enable_thinking capability probe failed: %s", e.what());
        }
    } catch (const std::exception & e) {
        g_template.applied = false;
        g_template.error = e.what();
        LOGE("Failed to initialize chat template: %s", e.what());
    }
}

void free_model_locked() {
    g_cancel_generation_id.store(0, std::memory_order_release);
    g_active_generation_id.store(0, std::memory_order_release);

    free_threadpool_locked();
    g_chat_templates.reset();
    g_template = ChatTemplateState{};

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

std::vector<std::pair<std::string, std::string>> parse_messages(
    JNIEnv * env,
    jobjectArray roles,
    jobjectArray contents,
    std::string & error) {
    std::vector<std::pair<std::string, std::string>> messages;
    if (roles == nullptr || contents == nullptr) {
        error = "メッセージ配列が無効です";
        return messages;
    }
    const jsize count = env->GetArrayLength(roles);
    if (count != env->GetArrayLength(contents)) {
        error = "メッセージの role/content 数が一致しません";
        return messages;
    }

    messages.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        std::string role_text = jstring_to_string(env, role);
        std::string content_text = jstring_to_string(env, content);
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
        messages.emplace_back(std::move(role_text), std::move(content_text));
    }
    return messages;
}

bool apply_chat_template_locked(
    const std::vector<std::pair<std::string, std::string>> & messages,
    bool add_generation_prompt,
    common_chat_params & params,
    std::string & error) {
    if (g_chat_templates == nullptr) {
        error = "チャットテンプレートが初期化されていません";
        return false;
    }
    if (!g_template.applied) {
        error = g_template.error.empty() ? "チャットテンプレートを適用できません" : g_template.error;
        return false;
    }

    common_chat_templates_inputs inputs;
    inputs.messages.reserve(messages.size());
    for (const auto & message : messages) {
        common_chat_msg msg;
        msg.role = message.first;
        msg.content = message.second;
        inputs.messages.push_back(std::move(msg));
    }
    inputs.add_generation_prompt = add_generation_prompt;
    inputs.use_jinja = true;
    inputs.enable_thinking = false;

    try {
        params = common_chat_templates_apply(g_chat_templates.get(), inputs);
    } catch (const std::exception & e) {
        error = e.what();
        return false;
    }
    return true;
}

std::string format_chat_json_locked(
    JNIEnv * env,
    jobjectArray roles,
    jobjectArray contents,
    bool add_generation_prompt) {
    std::string error;
    auto messages = parse_messages(env, roles, contents, error);
    if (!error.empty()) {
        return error_json(error);
    }

    common_chat_params params;
    if (!apply_chat_template_locked(messages, add_generation_prompt, params, error)) {
        std::ostringstream out;
        out << "{\"ok\":false";
        out << ",\"status\":\"" << chat_template_status() << "\"";
        out << ",\"prompt\":\"\"";
        out << ",\"stops\":[]";
        out << ",\"supportsThinking\":false";
        out << ",\"error\":\"" << json_escape(error) << "\"}";
        return out.str();
    }

    std::ostringstream out;
    out << "{\"ok\":true";
    out << ",\"status\":\"" << chat_template_status() << "\"";
    out << ",\"prompt\":\"" << json_escape(params.prompt.str()) << "\"";
    out << ",\"stops\":[";
    bool first = true;
    for (const auto & stop : params.additional_stops) {
        if (!first) {
            out << ",";
        }
        first = false;
        out << "\"" << json_escape(stop) << "\"";
    }
    out << "]";
    out << ",\"addBos\":" << (g_template.add_bos ? "true" : "false");
    out << ",\"addEos\":" << (g_template.add_eos ? "true" : "false");
    out << ",\"supportsThinking\":" << (g_template.supports_thinking ? "true" : "false");
    out << ",\"error\":\"\"}";
    return out.str();
}

bool format_chat_prompt_locked(
    JNIEnv * env,
    jobjectArray roles,
    jobjectArray contents,
    bool add_generation_prompt,
    std::string & prompt,
    std::string & error) {
    auto messages = parse_messages(env, roles, contents, error);
    if (!error.empty()) {
        return false;
    }

    common_chat_params params;
    if (!apply_chat_template_locked(messages, add_generation_prompt, params, error)) {
        return false;
    }
    prompt = params.prompt.str();
    return !prompt.empty();
}

struct JvmCallback {
    JNIEnv * env = nullptr;
    jobject callback = nullptr;
    jmethodID on_token = nullptr;
    trpg::Utf8StreamDecoder decoder;
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

    bool emit_utf16(const std::vector<jchar> & utf16) {
        if (!valid || utf16.empty()) {
            return valid;
        }
        jstring text = env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
        if (text == nullptr) {
            valid = false;
            return false;
        }
        env->CallVoidMethod(callback, on_token, text);
        env->DeleteLocalRef(text);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGW("Kotlin token callback threw an exception; callback disabled");
            valid = false;
            return false;
        }
        return true;
    }

    bool emit(const std::string & piece) {
        if (!valid || piece.empty()) {
            return valid;
        }
        return emit_utf16(decoder.append(piece));
    }

    bool finish() {
        if (!valid) {
            return false;
        }
        return emit_utf16(decoder.flush());
    }
};

std::string run_generation_locked(
    JNIEnv * env,
    jobject callback,
    const std::vector<llama_token> & prompt_tokens,
    const std::vector<std::string> & stops,
    uint64_t generation_id,
    int32_t max_tokens,
    float temperature,
    float top_p,
    int32_t top_k,
    float repeat_penalty,
    int32_t seed) {
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const int32_t n_prompt = static_cast<int32_t>(prompt_tokens.size());
    const int32_t headroom = 8;
    if (n_prompt + headroom >= g_n_ctx) {
        std::ostringstream message;
        message << "プロンプトが長すぎます（" << n_prompt << " トークン / コンテキスト " << g_n_ctx
                << "）。コンテキストサイズを増やすか、記憶や履歴を減らしてください";
        return error_json(message.str());
    }

    int32_t budget = max_tokens > 0 ? max_tokens : 512;
    budget = std::min(budget, g_n_ctx - n_prompt - headroom);
    if (budget < 16) {
        budget = 16;
    }

    const auto clear_memory = []() {
        if (g_ctx != nullptr) {
            llama_memory_clear(llama_get_memory(g_ctx), false);
        }
    };

    const auto clear_start = std::chrono::steady_clock::now();
    clear_memory();
    const auto clear_end = std::chrono::steady_clock::now();

    LOGI("generate start: prompt_tokens=%d budget=%d clear_ms=%lld temp=%.2f top_p=%.2f top_k=%d",
         n_prompt, budget,
         static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(
             clear_end - clear_start).count()),
         temperature, top_p, top_k);

    bool cancelled = false;
    std::string failure;
    int32_t n_pos = 0;

    const auto prompt_start = std::chrono::steady_clock::now();
    while (n_pos < n_prompt) {
        if (cancel_requested_for(generation_id)) {
            cancelled = true;
            break;
        }
        const int32_t chunk = std::min(g_n_batch, n_prompt - n_pos);
        llama_batch_ext_clear(g_batch);
        for (int32_t i = 0; i < chunk; ++i) {
            const int32_t idx = llama_batch_ext_add_token(g_batch, 0, prompt_tokens[static_cast<size_t>(n_pos + i)]);
            if (idx < 0) {
                failure = "プロンプトのバッチ追加に失敗しました";
                break;
            }
            const llama_pos pos = n_pos + i;
            if (!llama_batch_ext_set_pos(g_batch, idx, &pos)) {
                failure = "バッチ位置の設定に失敗しました";
                break;
            }
            llama_batch_ext_set_output_logits(g_batch, idx, n_pos + i == n_prompt - 1);
        }
        if (!failure.empty()) {
            break;
        }
        const int32_t rc = llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch);
        if (rc != 0) {
            if (cancel_requested_for(generation_id)) {
                cancelled = true;
            } else {
                LOGE("llama_process(prompt) failed: %d", rc);
                failure = "プロンプトの推論に失敗しました（メモリ不足の可能性）";
            }
            break;
        }
        n_pos += chunk;
    }
    const auto prompt_end = std::chrono::steady_clock::now();

    std::string generated_raw;
    std::chrono::steady_clock::time_point first_token_at{};
    int32_t generated_tokens = 0;
    bool stopped = false;
    size_t sent = 0;
    size_t max_stop_length = 0;
    for (const auto & stop : stops) {
        max_stop_length = std::max(max_stop_length, stop.size());
    }

    if (!cancelled && failure.empty()) {
        llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
        sparams.no_perf = true;
        llama_sampler * sampler = llama_sampler_chain_init(sparams);
        if (sampler == nullptr) {
            failure = "サンプラーの初期化に失敗しました";
        } else {
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
            generated_raw.reserve(static_cast<size_t>(budget) * 6);

            for (int32_t generated = 0; generated < budget; ++generated) {
                if (cancel_requested_for(generation_id)) {
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

                if (generated_tokens == 0) {
                    first_token_at = std::chrono::steady_clock::now();
                }

                const std::string piece = token_to_piece(vocab, token);
                generated_raw += piece;
                ++generated_tokens;

                for (const auto & stop : stops) {
                    if (stop.empty()) {
                        continue;
                    }
                    const size_t stop_pos = generated_raw.find(stop);
                    if (stop_pos != std::string::npos) {
                        generated_raw.resize(stop_pos);
                        stopped = true;
                        break;
                    }
                }

                if (stopped) {
                    if (sent > generated_raw.size()) {
                        sent = generated_raw.size();
                    }
                    if (sent < generated_raw.size()) {
                        jvm_callback.emit(generated_raw.substr(sent));
                        sent = generated_raw.size();
                    }
                    break;
                }

                const size_t safe_length = max_stop_length == 0
                    ? generated_raw.size()
                    : (generated_raw.size() > max_stop_length - 1
                        ? generated_raw.size() - (max_stop_length - 1)
                        : 0);
                if (safe_length > sent) {
                    jvm_callback.emit(generated_raw.substr(sent, safe_length - sent));
                    sent = safe_length;
                }

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
                    if (cancel_requested_for(generation_id)) {
                        cancelled = true;
                    } else {
                        failure = "生成中の推論に失敗しました（メモリ不足の可能性）";
                    }
                    break;
                }
                n_pos += 1;
            }

            if (!stopped && sent < generated_raw.size()) {
                jvm_callback.emit(generated_raw.substr(sent));
            }
            jvm_callback.finish();

            llama_sampler_free(sampler);
        }
    }

    clear_memory();

    const auto generation_end = std::chrono::steady_clock::now();
    auto elapsed_ms = [](auto from, auto to) {
        return std::chrono::duration_cast<std::chrono::milliseconds>(to - from).count();
    };
    const auto prompt_ms = elapsed_ms(prompt_start, prompt_end);
    const auto gen_ms = generated_tokens > 0 ? elapsed_ms(first_token_at, generation_end) : 0LL;
    const auto ttft_ms = generated_tokens > 0 ? elapsed_ms(prompt_start, first_token_at) : -1LL;
    const double tok_per_sec = gen_ms > 0
        ? static_cast<double>(generated_tokens) * 1000.0 / static_cast<double>(gen_ms)
        : 0.0;
    LOGI("generate: prompt_tokens=%d prompt_ms=%lld generated_tokens=%d gen_ms=%lld tok_per_sec=%.2f ttft_ms=%lld cancelled=%d failed=%d",
         n_prompt,
         static_cast<long long>(prompt_ms),
         generated_tokens,
         static_cast<long long>(gen_ms),
         tok_per_sec,
         static_cast<long long>(ttft_ms),
         cancelled ? 1 : 0,
         failure.empty() ? 0 : 1);

    std::ostringstream out;
    out << "{\"ok\":" << (failure.empty() ? "true" : "false");
    out << ",\"cancelled\":" << (cancelled ? "true" : "false");
    out << ",\"text\":\"" << json_escape(generated_raw) << "\"";
    out << ",\"error\":\"" << json_escape(failure) << "\"}";
    return out.str();
}

void clear_generation_state(uint64_t generation_id) {
    if (generation_id == 0) {
        return;
    }
    if (g_active_generation_id.load(std::memory_order_acquire) == generation_id) {
        g_active_generation_id.store(0, std::memory_order_release);
    }
    if (g_cancel_generation_id.load(std::memory_order_acquire) == generation_id) {
        g_cancel_generation_id.store(0, std::memory_order_release);
    }
}

// Releases the active/cancel generation ids on every exit path, including a
// C++ exception, so a failed generation can never leave a stale id behind and
// poison the next generation's cancel handling.
class GenerationStateGuard {
public:
    explicit GenerationStateGuard(uint64_t id) : id_(id) {}
    GenerationStateGuard(const GenerationStateGuard &) = delete;
    GenerationStateGuard & operator=(const GenerationStateGuard &) = delete;
    ~GenerationStateGuard() { clear_generation_state(id_); }

private:
    uint64_t id_;
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
        return new_string_utf8(env, error_json("モデルパスが空です"));
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = n_gpu_layers;
    // v0.9.0: mmap/mlock are selected by llama_load_mode. AUTO is appropriate on Android.
    model_params.load_mode = LLAMA_LOAD_MODE_AUTO;

    llama_model * model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (model == nullptr) {
        LOGE("Failed to load model: %s", model_path.c_str());
        return new_string_utf8(env, error_json("GGUFモデルの読み込みに失敗しました。ファイル形式と空きメモリを確認してください"));
    }

    if (llama_model_has_encoder(model) && !llama_model_has_decoder(model)) {
        llama_model_free(model);
        return new_string_utf8(env, error_json("エンコーダ専用モデルには対応していません"));
    }

    const int32_t ctx_size = n_ctx > 0 ? n_ctx : 8192;
    const int32_t batch_size = std::min<int32_t>(ctx_size, 1024);
    const int32_t thread_count = n_threads > 0 ? n_threads : 4;

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(ctx_size);
    ctx_params.n_batch = static_cast<uint32_t>(batch_size);
    ctx_params.n_ubatch = static_cast<uint32_t>(std::min<int32_t>(batch_size, 512));
    ctx_params.n_threads = thread_count;
    ctx_params.n_threads_batch = thread_count;

    llama_context * ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        llama_model_free(model);
        LOGE("Failed to create llama context (n_ctx=%d)", ctx_size);
        return new_string_utf8(env, error_json("推論コンテキストの作成に失敗しました。コンテキストサイズを下げてください"));
    }

    llama_set_abort_callback(ctx, abort_callback, nullptr);

    // Publish the globals first: initialize_chat_templates_locked() and
    // create_threadpool_locked() both read g_model / g_ctx.
    g_model = model;
    g_ctx = ctx;
    g_n_ctx = ctx_size;
    g_n_batch = batch_size;

    const auto init_start = std::chrono::steady_clock::now();
    initialize_chat_templates_locked();
    const auto init_end = std::chrono::steady_clock::now();
    LOGI("template_init=%lldms template=%s explicit=%d applied=%d",
         static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(
             init_end - init_start).count()),
         chat_template_status().c_str(),
         g_template.explicit_template ? 1 : 0,
         g_template.applied ? 1 : 0);
    if (!g_template.error.empty()) {
        LOGW("chat template probe error: %s", g_template.error.c_str());
    }

    const auto pool_start = std::chrono::steady_clock::now();
    const bool threadpool_ok = create_threadpool_locked(ctx, thread_count);
    const auto pool_end = std::chrono::steady_clock::now();

    std::string pool_desc;
    {
        ggml_backend_dev_t device = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        if (device != nullptr) {
            ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(device);
            if (reg != nullptr) {
                void *proc = ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new");
                if (proc != nullptr) {
                    pool_desc = "ggml_threadpool available; profile=" +
                                std::to_string(TRPG_THREAD_PROFILE) +
                                "; attach=" + (threadpool_ok ? "true" : "false");
                } else {
                    pool_desc = "ggml_threadpool API missing; fallback to llama default";
                }
            }
        }
    }
    LOGI("threadpool_attach=%lldms %s",
         static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(
             pool_end - pool_start).count()),
         pool_desc.c_str());

    llama_batch_ext * batch = llama_batch_ext_init(ctx);
    if (batch == nullptr) {
        free_model_locked();
        return new_string_utf8(env, error_json("バッチの初期化に失敗しました"));
    }

    g_batch = batch;

    return new_string_utf8(env, model_info_json());
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
        return new_string_utf8(env, "");
    }
    std::string prompt;
    std::string error;
    if (!format_chat_prompt_locked(env, roles, contents, add_assistant == JNI_TRUE, prompt, error)) {
        return new_string_utf8(env, "");
    }
    return new_string_utf8(env, prompt);
}

JNIEXPORT jstring JNICALL
Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_nativeFormatChatEx(
    JNIEnv * env,
    jobject /*this*/,
    jobjectArray roles,
    jobjectArray contents,
    jboolean add_assistant) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_model == nullptr) {
        return new_string_utf8(env, error_json("モデルが読み込まれていません"));
    }
    return new_string_utf8(env, format_chat_json_locked(env, roles, contents, add_assistant == JNI_TRUE));
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
    const auto tokens = tokenize(llama_model_get_vocab(g_model), value, false, true);
    return static_cast<jint>(tokens.size());
}

JNIEXPORT jstring JNICALL
Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_nativeGenerateEx(
    JNIEnv * env,
    jobject /*this*/,
    jstring prompt,
    jint max_tokens,
    jfloat temperature,
    jfloat top_p,
    jint top_k,
    jfloat repeat_penalty,
    jint seed,
    jlong generation_id,
    jobject callback,
    jobjectArray stops,
    jboolean add_special) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_model == nullptr || g_ctx == nullptr || g_batch == nullptr) {
        return new_string_utf8(env, error_json("モデルが読み込まれていません"));
    }

    const std::string prompt_text = jstring_to_string(env, prompt);
    if (prompt_text.empty()) {
        return new_string_utf8(env, error_json("プロンプトが空です"));
    }

    uint64_t id = generation_id != 0
        ? static_cast<uint64_t>(generation_id)
        : g_next_generation_id.fetch_add(1, std::memory_order_acq_rel);
    if (id == 0) {
        id = g_next_generation_id.fetch_add(1, std::memory_order_acq_rel);
    }
    g_active_generation_id.store(id, std::memory_order_release);
    const GenerationStateGuard state_guard(id);

    if (cancel_requested_for(id)) {
        return new_string_utf8(env, "{\"ok\":false,\"cancelled\":true,\"text\":\"\",\"error\":\"\"}");
    }
    uint64_t stale = g_cancel_generation_id.load(std::memory_order_acquire);
    if (stale != 0 && stale != id) {
        g_cancel_generation_id.compare_exchange_strong(stale, 0, std::memory_order_acq_rel);
    }

    const auto tokenize_start = std::chrono::steady_clock::now();
    std::vector<llama_token> prompt_tokens = tokenize(
        llama_model_get_vocab(g_model), prompt_text, add_special == JNI_TRUE, true);
    const auto tokenize_end = std::chrono::steady_clock::now();
    LOGI("tokenize[ex]: tokens=%zu ms=%lld",
         prompt_tokens.size(),
         static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(
             tokenize_end - tokenize_start).count()));
    if (prompt_tokens.empty()) {
        return new_string_utf8(env, error_json("プロンプトのトークン化に失敗しました"));
    }

    std::vector<std::string> stop_list = jstring_array_to_vector(env, stops);
    std::string result = run_generation_locked(
        env,
        callback,
        prompt_tokens,
        stop_list,
        id,
        max_tokens,
        temperature,
        top_p,
        top_k,
        repeat_penalty,
        seed);
    return new_string_utf8(env, result);
}

JNIEXPORT jstring JNICALL
Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_nativeGenerateChatEx(
    JNIEnv * env,
    jobject /*this*/,
    jobjectArray roles,
    jobjectArray contents,
    jboolean add_assistant,
    jint max_tokens,
    jfloat temperature,
    jfloat top_p,
    jint top_k,
    jfloat repeat_penalty,
    jint seed,
    jlong generation_id,
    jobject callback) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_model == nullptr || g_ctx == nullptr || g_batch == nullptr) {
        return new_string_utf8(env, error_json("モデルが読み込まれていません"));
    }

    std::string error;
    auto messages = parse_messages(env, roles, contents, error);
    if (!error.empty()) {
        return new_string_utf8(env, error_json(error));
    }

    common_chat_params params;
    const auto apply_start = std::chrono::steady_clock::now();
    if (!apply_chat_template_locked(messages, add_assistant == JNI_TRUE, params, error)) {
        return new_string_utf8(env, error_json(error));
    }
    const auto apply_end = std::chrono::steady_clock::now();

    const auto tokenize_start = std::chrono::steady_clock::now();
    std::vector<llama_token> prompt_tokens;
    bool first_part = true;
    for (const auto & part : params.prompt.parts) {
        const bool add_special = first_part && g_template.add_bos;
        auto tokens = tokenize(
            llama_model_get_vocab(g_model),
            part.text,
            add_special,
            !part.is_input);
        prompt_tokens.insert(prompt_tokens.end(), tokens.begin(), tokens.end());
        first_part = false;
    }
    const auto tokenize_end = std::chrono::steady_clock::now();
    LOGI("template_apply[chat]: apply_ms=%lld tokenize_ms=%lld parts=%zu tokens=%zu",
         static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(
             apply_end - apply_start).count()),
         static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(
             tokenize_end - tokenize_start).count()),
         params.prompt.parts.size(),
         prompt_tokens.size());
    if (prompt_tokens.empty()) {
        return new_string_utf8(env, error_json("プロンプトのトークン化に失敗しました"));
    }

    uint64_t id = generation_id != 0
        ? static_cast<uint64_t>(generation_id)
        : g_next_generation_id.fetch_add(1, std::memory_order_acq_rel);
    if (id == 0) {
        id = g_next_generation_id.fetch_add(1, std::memory_order_acq_rel);
    }
    g_active_generation_id.store(id, std::memory_order_release);
    const GenerationStateGuard state_guard(id);

    if (cancel_requested_for(id)) {
        return new_string_utf8(env, "{\"ok\":false,\"cancelled\":true,\"text\":\"\",\"error\":\"\"}");
    }
    uint64_t stale = g_cancel_generation_id.load(std::memory_order_acquire);
    if (stale != 0 && stale != id) {
        g_cancel_generation_id.compare_exchange_strong(stale, 0, std::memory_order_acq_rel);
    }

    std::string result = run_generation_locked(
        env,
        callback,
        prompt_tokens,
        params.additional_stops,
        id,
        max_tokens,
        temperature,
        top_p,
        top_k,
        repeat_penalty,
        seed);
    return new_string_utf8(env, result);
}

JNIEXPORT jstring JNICALL
Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_nativeGenerate(
    JNIEnv * env,
    jobject this_clazz,
    jstring prompt,
    jint max_tokens,
    jfloat temperature,
    jfloat top_p,
    jint top_k,
    jfloat repeat_penalty,
    jint seed,
    jobject callback) {
    return Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_nativeGenerateEx(
        env,
        this_clazz,
        prompt,
        max_tokens,
        temperature,
        top_p,
        top_k,
        repeat_penalty,
        seed,
        0,
        callback,
        nullptr,
        JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_nativeCancel(
    JNIEnv * /*env*/,
    jobject /*this*/) {
    uint64_t active = g_active_generation_id.load(std::memory_order_acquire);
    if (active != 0) {
        g_cancel_generation_id.store(active, std::memory_order_release);
    }
}

JNIEXPORT void JNICALL
Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_nativeCancelGeneration(
    JNIEnv * /*env*/,
    jobject /*this*/,
    jlong generation_id) {
    if (generation_id != 0) {
        g_cancel_generation_id.store(static_cast<uint64_t>(generation_id), std::memory_order_release);
    } else {
        uint64_t active = g_active_generation_id.load(std::memory_order_acquire);
        if (active != 0) {
            g_cancel_generation_id.store(active, std::memory_order_release);
        }
    }
}

JNIEXPORT jstring JNICALL
Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_nativeBackendInfo(
    JNIEnv * env,
    jobject /*this*/) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    return new_string_utf8(env, backend_info_json());
}

JNIEXPORT jstring JNICALL
Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_nativeModelInfo(
    JNIEnv * env,
    jobject /*this*/) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    return new_string_utf8(env, model_info_json());
}

} // extern "C"
