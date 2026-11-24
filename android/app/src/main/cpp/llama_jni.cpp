#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {

// Context window per call. Each call starts from an empty KV cache, so this
// only has to hold one prompt plus its reply.
constexpr uint32_t kContextTokens = 4096;
constexpr uint32_t kBatchTokens = 512;

struct LlmHandle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * sampler = nullptr;
};

std::once_flag g_backend_once;

std::string to_std(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * p = env->GetStringUTFChars(s, nullptr);
    if (!p) return {};
    std::string out(p);
    env->ReleaseStringUTFChars(s, p);
    return out;
}

// Formats system + user with the model's own chat template, falling back to
// plain text when the model has none (or llama.cpp doesn't know it).
std::string format_prompt(const llama_model * model, const std::string & system, const std::string & user) {
    const char * tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl) {
        const llama_chat_message chat[] = {
            {"system", system.c_str()},
            {"user", user.c_str()},
        };
        std::vector<char> buf(2 * (system.size() + user.size()) + 1024);
        int32_t n = llama_chat_apply_template(tmpl, chat, 2, true, buf.data(), static_cast<int32_t>(buf.size()));
        if (n > static_cast<int32_t>(buf.size())) {
            buf.resize(n);
            n = llama_chat_apply_template(tmpl, chat, 2, true, buf.data(), static_cast<int32_t>(buf.size()));
        }
        if (n > 0 && n <= static_cast<int32_t>(buf.size())) return std::string(buf.data(), n);
    }
    return system + "\n\n" + user + "\n\n";
}

bool tokenize(const llama_vocab * vocab, const std::string & text, std::vector<llama_token> & out) {
    const auto len = static_cast<int32_t>(text.size());
    int32_t n = llama_tokenize(vocab, text.data(), len, nullptr, 0, true, true);
    if (n == INT32_MIN) return false;
    if (n < 0) n = -n;
    out.resize(n);
    if (n == 0) return true;
    if (llama_tokenize(vocab, text.data(), len, out.data(), n, true, true) != n) return false;
    // Templates that spell out BOS themselves (e.g. Gemma) plus add_special
    // would give two; keep one.
    const llama_token bos = llama_vocab_bos(vocab);
    if (out.size() >= 2 && out[0] == bos && out[1] == bos) out.erase(out.begin());
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_voicepad_app_llm_LlamaJni_create(JNIEnv * env, jclass, jstring path, jint threads) {
    std::call_once(g_backend_once, [] { llama_backend_init(); });

    const std::string p = to_std(env, path);
    if (p.empty()) return 0;

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;  // CPU-only for now.

    llama_model * model = llama_model_load_from_file(p.c_str(), mp);
    if (!model) return 0;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = kContextTokens;
    cp.n_batch = kBatchTokens;
    cp.n_ubatch = kBatchTokens;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;

    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }

    auto * h = new LlmHandle();
    h->model = model;
    h->ctx = ctx;
    h->sampler = llama_sampler_init_greedy();
    return reinterpret_cast<jlong>(h);
}

// Returns the reply as raw UTF-8 bytes (decoded on the Kotlin side, which
// tolerates a multi-byte character cut off by max_tokens), or null on failure.
JNIEXPORT jbyteArray JNICALL
Java_com_voicepad_app_llm_LlamaJni_complete(
    JNIEnv * env, jclass, jlong handle, jstring system, jstring user, jint max_tokens) {
    auto * h = reinterpret_cast<LlmHandle *>(handle);
    if (!h || !h->model || !h->ctx || max_tokens <= 0) return nullptr;

    const std::string prompt = format_prompt(h->model, to_std(env, system), to_std(env, user));
    const llama_vocab * vocab = llama_model_get_vocab(h->model);

    std::vector<llama_token> tokens;
    if (!tokenize(vocab, prompt, tokens) || tokens.empty()) return nullptr;

    // Every call is independent: drop whatever the previous one left behind.
    llama_memory_clear(llama_get_memory(h->ctx), true);
    llama_sampler_reset(h->sampler);

    const auto n_prompt = static_cast<int32_t>(tokens.size());
    const auto n_ctx = static_cast<int32_t>(llama_n_ctx(h->ctx));
    // Leave room for at least a short reply; longer prompts are refused so
    // the caller types the original text instead.
    if (n_prompt + 16 > n_ctx) return nullptr;
    const int32_t budget = std::min<int32_t>(max_tokens, n_ctx - n_prompt);

    // Feed the prompt in n_batch-sized chunks (llama_decode rejects more).
    const auto n_batch = static_cast<int32_t>(llama_n_batch(h->ctx));
    for (int32_t i = 0; i < n_prompt; i += n_batch) {
        const int32_t n = std::min(n_batch, n_prompt - i);
        if (llama_decode(h->ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) return nullptr;
    }

    std::string out;
    char piece[256];
    for (int32_t i = 0; i < budget; i++) {
        llama_token id = llama_sampler_sample(h->sampler, h->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;

        const int32_t np = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, false);
        if (np > 0) out.append(piece, std::min<int32_t>(np, sizeof(piece)));

        if (llama_decode(h->ctx, llama_batch_get_one(&id, 1)) != 0) break;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    if (!result) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

JNIEXPORT void JNICALL
Java_com_voicepad_app_llm_LlamaJni_release(JNIEnv *, jclass, jlong handle) {
    auto * h = reinterpret_cast<LlmHandle *>(handle);
    if (!h) return;
    if (h->sampler) llama_sampler_free(h->sampler);
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

}  // extern "C"
