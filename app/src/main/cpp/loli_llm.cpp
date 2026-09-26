// Офлайн-модель Лоли: тонкая JNI-обёртка над llama.cpp.
// Одна модель в памяти, генерация по одному запросу за раз (синхронизацию делает Kotlin).
#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "LoliLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::atomic<bool> stop{false};
};

void log_callback(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Модель иногда режет UTF-8 посреди символа: отдаём в Java только целые символы.
size_t complete_utf8_prefix(const std::string &s) {
    size_t i = s.size();
    size_t back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) == 0x80) { i--; back++; continue; }
        size_t need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
        return (back + 1 >= need) ? s.size() : i - 1;
    }
    return s.size();
}

// Стандартный UTF-8 (с эмодзи) → String через byte[]: NewStringUTF принимает только «модифицированный» UTF-8.
jstring to_jstring(JNIEnv *env, const std::string &s) {
    jbyteArray bytes = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    jclass strCls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strCls, "<init>", "([BLjava/lang/String;)V");
    jstring enc = env->NewStringUTF("UTF-8");
    auto *res = (jstring) env->NewObject(strCls, ctor, bytes, enc);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(enc);
    env->DeleteLocalRef(strCls);
    return res;
}

std::string chatml(const std::vector<std::string> &roles, const std::vector<std::string> &texts) {
    std::string p;
    for (size_t i = 0; i < roles.size(); i++) p += "<|im_start|>" + roles[i] + "\n" + texts[i] + "<|im_end|>\n";
    p += "<|im_start|>assistant\n";
    return p;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_ai_loli_app_llm_LlamaNative_init(JNIEnv *, jclass) {
    llama_log_set(log_callback, nullptr);
    llama_backend_init();
}

extern "C" JNIEXPORT jlong JNICALL
Java_ai_loli_app_llm_LlamaNative_load(JNIEnv *env, jclass, jstring jpath, jint n_ctx, jint n_threads) {
    std::string path = jstr(env, jpath);
    llama_model_params mp = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { LOGW("model load failed"); return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { LOGW("context init failed"); llama_model_free(model); return 0; }

    auto *s = new Session();
    s->model = model;
    s->ctx = ctx;
    s->vocab = llama_model_get_vocab(model);
    LOGI("model loaded: ctx=%d threads=%d", n_ctx, n_threads);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_ai_loli_app_llm_LlamaNative_stop(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s) s->stop = true;
}

extern "C" JNIEXPORT void JNICALL
Java_ai_loli_app_llm_LlamaNative_free(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return;
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

// messages: [role0, text0, role1, text1, …]; listener.onToken(String) → false = остановить.
extern "C" JNIEXPORT jstring JNICALL
Java_ai_loli_app_llm_LlamaNative_generate(JNIEnv *env, jclass, jlong handle, jobjectArray jmessages, jint max_tokens,
                                          jfloat temperature, jobject listener) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return env->NewStringUTF("");
    s->stop = false;

    // Сообщения диалога и шаблон модели (у Qwen — ChatML).
    std::vector<std::string> roles, texts;
    const jsize n = env->GetArrayLength(jmessages);
    for (jsize i = 0; i + 1 < n; i += 2) {
        roles.push_back(jstr(env, (jstring) env->GetObjectArrayElement(jmessages, i)));
        texts.push_back(jstr(env, (jstring) env->GetObjectArrayElement(jmessages, i + 1)));
    }
    std::vector<llama_chat_message> msgs;
    for (size_t i = 0; i < roles.size(); i++) msgs.push_back({roles[i].c_str(), texts[i].c_str()});
    std::string prompt;
    const char *tmpl = llama_model_chat_template(s->model, nullptr);
    if (tmpl) {
        std::vector<char> buf(8192);
        int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
        if (len > (int32_t) buf.size()) {
            buf.resize(len + 1);
            len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
        }
        if (len > 0) prompt.assign(buf.data(), len);
    }
    if (prompt.empty()) prompt = chatml(roles, texts);

    // Токены запроса.
    const int32_t n_prompt = -llama_tokenize(s->vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt > 0 ? n_prompt : 0);
    if (n_prompt <= 0 || llama_tokenize(s->vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), n_prompt, true, true) < 0) {
        return env->NewStringUTF("");
    }
    const int32_t n_ctx = (int32_t) llama_n_ctx(s->ctx);
    if (n_prompt + max_tokens + 8 > n_ctx) {
        LOGW("prompt too long: %d", n_prompt);
        return env->NewStringUTF("");
    }

    llama_memory_clear(llama_get_memory(s->ctx), true);
    for (int32_t i = 0; i < n_prompt; i += 512) {
        const int32_t chunk = std::min<int32_t>(512, n_prompt - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, chunk);
        if (llama_decode(s->ctx, batch) != 0) { LOGW("decode prompt failed"); return env->NewStringUTF(""); }
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass cls = listener ? env->GetObjectClass(listener) : nullptr;
    jmethodID onToken = cls ? env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z") : nullptr;

    std::string out, pending;
    char piece[256];
    for (int32_t i = 0; i < max_tokens && !s->stop; i++) {
        llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, tok)) break;
        const int32_t len = llama_token_to_piece(s->vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) {
            pending.append(piece, len);
            const size_t ready = complete_utf8_prefix(pending);
            if (ready > 0) {
                std::string part = pending.substr(0, ready);
                pending.erase(0, ready);
                out += part;
                if (onToken) {
                    jstring js = to_jstring(env, part);
                    const jboolean go = env->CallBooleanMethod(listener, onToken, js);
                    env->DeleteLocalRef(js);
                    if (env->ExceptionCheck()) { env->ExceptionClear(); break; }
                    if (!go) break;
                }
            }
        }
        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(s->ctx, next) != 0) break;
    }
    llama_sampler_free(smpl);
    if (!pending.empty() && complete_utf8_prefix(pending) == pending.size()) out += pending;
    return to_jstring(env, out);
}
