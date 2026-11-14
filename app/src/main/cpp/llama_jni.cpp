// PHASE 8: the JNI bridge between LlamaJni (Kotlin) and the portable engine (llama_engine.cpp).
// A loaded model is a native handle (a pointer as a jlong) owned by LlamaCppBackend, which frees it.
// Text crosses as UTF-8 bytes, never as Java strings: model output can hold emoji or a character cut
// in half between two pieces, which NewStringUTF (modified UTF-8) can reject by aborting the app.
#include <jni.h>
#include <android/log.h>
#include <mutex>
#include <string>
#include "llama_engine.h"

static void logToAndroid(int, const char * text) { __android_log_print(ANDROID_LOG_WARN, "SphynxLlama", "%s", text); }

static std::mutex g_errMutex;
static std::string g_loadError;

static std::string jstr(JNIEnv * env, jstring s) {
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

static std::string jbytes(JNIEnv * env, jbyteArray b) {
    if (!b) return "";
    jsize n = env->GetArrayLength(b);
    std::string r((size_t) n, '\0');
    if (n > 0) env->GetByteArrayRegion(b, 0, n, reinterpret_cast<jbyte *>(&r[0]));
    return r;
}

static jbyteArray toBytes(JNIEnv * env, const std::string & s) {
    jbyteArray out = env->NewByteArray((jsize) s.size());
    if (out && !s.empty()) env->SetByteArrayRegion(out, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return out;
}

static SphynxLlama * handle(jlong h) { return reinterpret_cast<SphynxLlama *>(h); }

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_mlbbresearch_inference_LlamaJni_nativeInfo(JNIEnv * env, jobject) {
    sphynx_llama_init(logToAndroid);
    return toBytes(env, sphynx_llama_info());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_mlbbresearch_inference_LlamaJni_nativeLoad(JNIEnv * env, jobject, jstring jpath, jint nCtx, jint threads) {
    sphynx_llama_init(logToAndroid);
    std::string err;
    SphynxLlama * h = sphynx_llama_load(jstr(env, jpath), nCtx, threads, err);
    { std::lock_guard<std::mutex> l(g_errMutex); g_loadError = h ? "" : err; }
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_mlbbresearch_inference_LlamaJni_nativeLoadError(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> l(g_errMutex);
    return toBytes(env, g_loadError);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_mlbbresearch_inference_LlamaJni_nativeDescribe(JNIEnv * env, jobject, jlong h) {
    return toBytes(env, sphynx_llama_describe(handle(h)));
}

struct SinkCall { JNIEnv * env; jobject sink; jmethodID onPiece; };

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_mlbbresearch_inference_LlamaJni_nativeGenerate(JNIEnv * env, jobject, jlong h, jbyteArray jprompt,
                                                                jint maxTokens, jfloat temperature, jint seed, jobject sink) {
    SinkCall call{env, sink, nullptr};
    if (sink) {
        jclass cls = env->GetObjectClass(sink);
        call.onPiece = env->GetMethodID(cls, "onPiece", "([B)V");
        env->DeleteLocalRef(cls);
        if (!call.onPiece) { env->ExceptionClear(); call.sink = nullptr; }
    }
    std::string json = sphynx_llama_generate(handle(h), jbytes(env, jprompt), maxTokens, temperature, (uint32_t) seed,
        [](const char * bytes, int n, void * user) {
            auto * c = static_cast<SinkCall *>(user);
            if (!c->sink || !c->onPiece) return;
            jbyteArray b = c->env->NewByteArray(n);
            if (!b) return;
            c->env->SetByteArrayRegion(b, 0, n, reinterpret_cast<const jbyte *>(bytes));
            c->env->CallVoidMethod(c->sink, c->onPiece, b);
            if (c->env->ExceptionCheck()) c->env->ExceptionClear();   // a broken sink never stops the answer
            c->env->DeleteLocalRef(b);                                 // hundreds of pieces: free each one
        }, &call);
    return toBytes(env, json);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mlbbresearch_inference_LlamaJni_nativeCancel(JNIEnv *, jobject, jlong h) {
    sphynx_llama_cancel(handle(h));
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mlbbresearch_inference_LlamaJni_nativeFree(JNIEnv *, jobject, jlong h) {
    sphynx_llama_free(handle(h));
}
