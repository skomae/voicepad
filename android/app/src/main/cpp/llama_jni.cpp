#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

#include "llama.h"

namespace {

struct LlmHandle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * sampler = nullptr;
};

bool g_backend_ready = false;

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_voicepad_app_llm_LlamaJni_create(JNIEnv * env, jclass, jstring path, jint threads) {
    if (!g_backend_ready) {
        llama_backend_init();
        g_backend_ready = true;
    }

    const char * p = env->GetStringUTFChars(path, nullptr);
    if (!p) return 0;

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;  // CPU-only for now.

    llama_model * model = llama_model_load_from_file(p, mp);
    env->ReleaseStringUTFChars(path, p);
    if (!model) return 0;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 512;
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

JNIEXPORT jstring JNICALL
Java_com_voicepad_app_llm_LlamaJni_complete(JNIEnv * env, jclass, jlong handle, jstring prompt, jint max_tokens) {
    auto * h = reinterpret_cast<LlmHandle *>(handle);
    if (!h || !h->model || !h->ctx) return nullptr;

    const char * p = env->GetStringUTFChars(prompt, nullptr);
    if (!p) return nullptr;
    std::string user(p);
    env->ReleaseStringUTFChars(prompt, p);

    // Format the prompt with the model's embedded chat template, treating the
    // whole input as a single user message and asking for the assistant start.
    const char * tmpl = llama_model_chat_template(h->model, nullptr);
    llama_chat_message chat[] = {{"user", user.c_str()}};

    std::vector<char> buf(2 * user.size() + 8192);
    int32_t n = -1;
    if (tmpl) {
        n = llama_chat_apply_template(tmpl, chat, 1, true, buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (n < 0) {
        // No template (or buffer too small): fall back to the raw prompt.
        n = static_cast<int32_t>(user.size());
        if (n >= static_cast<int32_t>(buf.size())) n = static_cast<int32_t>(buf.size()) - 1;
        std::copy(user.begin(), user.begin() + n, buf.data());
        buf[n] = '\0';
    }

    const llama_vocab * vocab = llama_model_get_vocab(h->model);
    std::vector<llama_token> tokens(2048);
    int32_t ntok = llama_tokenize(vocab, buf.data(), n, tokens.data(), static_cast<int32_t>(tokens.size()), true, false);
    if (ntok < 0) {
        // Only happens for very long prompts; clamp rather than fail.
        ntok = static_cast<int32_t>(tokens.size());
    }

    llama_batch batch = llama_batch_get_one(tokens.data(), ntok);
    if (llama_decode(h->ctx, batch) != 0) {
        return nullptr;
    }

    std::string out;
    for (int32_t i = 0; i < max_tokens; i++) {
        llama_token id = llama_sampler_sample(h->sampler, h->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;

        char piece[256];
        int32_t np = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, false);
        if (np > 0) out.append(piece, np);

        llama_batch one = llama_batch_get_one(&id, 1);
        if (llama_decode(h->ctx, one) != 0) break;
    }

    return env->NewStringUTF(out.c_str());
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
