// PHASE 3a SPIKE: the smallest JNI bridge that proves llama.cpp builds, loads a GGUF and answers on
// the phone. One call does everything (load, prompt, generate, free) and returns a JSON report with
// the timings. Phase 3b replaces this with a real InferenceBackend (streaming, cancel, kept loaded).
#include <jni.h>
#include <android/log.h>
#include <string>
#include "llama_spike.h"

static void logToAndroid(int, const char * text) { __android_log_print(ANDROID_LOG_WARN, "SphynxLlama", "%s", text); }

static std::string jstr(JNIEnv * env, jstring s) {
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mlbbresearch_inference_LlamaCppSpike_nativeInfo(JNIEnv * env, jobject) {
    sphynx_llama_init(logToAndroid);
    return env->NewStringUTF(sphynx_llama_info().c_str());
}

// returned as bytes, not a Java string: model text can hold emoji or a character cut in half at the
// token limit, which NewStringUTF (modified UTF-8) can reject by aborting the app; Kotlin decodes
// the bytes as UTF-8 and replaces anything broken
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_mlbbresearch_inference_LlamaCppSpike_nativeRun(JNIEnv * env, jobject, jstring jpath, jstring jprompt,
                                                                jint maxTokens, jint threads, jint nCtx) {
    sphynx_llama_init(logToAndroid);
    std::string json = sphynx_llama_run(jstr(env, jpath), jstr(env, jprompt), maxTokens, threads, nCtx);
    jbyteArray out = env->NewByteArray((jsize) json.size());
    if (out) env->SetByteArrayRegion(out, 0, (jsize) json.size(), reinterpret_cast<const jbyte *>(json.data()));
    return out;
}
