#include <jni.h>
#include "executable_patch_checks.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_peerlink_app_bot_ExecutablePatchTest_check(JNIEnv *env, jclass, jstring path, jboolean enforcing) {
    const char *text = env->GetStringUTFChars(path, nullptr);
    if (!text) return nullptr;
    const std::string result = executable_patch_checks(text, enforcing == JNI_TRUE);
    env->ReleaseStringUTFChars(path, text);
    return env->NewStringUTF(result.c_str());
}
