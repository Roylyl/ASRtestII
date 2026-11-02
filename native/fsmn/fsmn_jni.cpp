#include <jni.h>
#include <algorithm>
#include <cmath>
#include <exception>
#include <vector>
#include "funasr_vad.h"

static void fail(JNIEnv* env, const char* message) {
    jclass c = env->FindClass("java/lang/IllegalStateException");
    if (c) env->ThrowNew(c, message);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_org_hotwordtest_FsmnVad_detectNative(JNIEnv* env, jclass, jstring path,
                                           jfloatArray input, jint threads) {
    if (!path || !input) { fail(env, "FSMN VAD: missing input"); return nullptr; }
    const jsize n = env->GetArrayLength(input);
    // The upstream fbank front end assumes a complete 25 ms frame.
    if (n < 400) return env->NewIntArray(0);
    // The offline reference materializes features for the full input; bound memory.
    if (n > 60 * 16000) { fail(env, "FSMN VAD: input exceeds 60 seconds"); return nullptr; }
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return nullptr;
    std::string model(p);
    env->ReleaseStringUTFChars(path, p);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(input, 0, n, pcm.data());
    if (env->ExceptionCheck()) return nullptr;
    for (float x : pcm) {
        if (!std::isfinite(x) || x < -1.0f || x > 1.0f) {
            fail(env, "FSMN VAD: expected finite normalized 16 kHz mono PCM"); return nullptr;
        }
    }
    try {
        std::vector<std::pair<int, int>> segments;
        if (!funasr_vad_segments(model, pcm, 60000, segments, std::max(1, std::min(4, (int)threads)))) {
            fail(env, "FSMN VAD inference failed"); return nullptr;
        }
        std::vector<jint> output;
        for (const auto& s : segments) { output.push_back(s.first); output.push_back(s.second); }
        jintArray result = env->NewIntArray((jsize)output.size());
        if (result && !output.empty()) env->SetIntArrayRegion(result, 0, (jsize)output.size(), output.data());
        return result;
    } catch (const std::exception& e) { fail(env, e.what()); return nullptr; }
      catch (...) { fail(env, "FSMN VAD native exception"); return nullptr; }
}
