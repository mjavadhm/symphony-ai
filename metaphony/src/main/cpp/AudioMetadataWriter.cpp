#include <jni.h>
#include "LyricsWriter.h"
#include <string>

extern "C" JNIEXPORT jint JNICALL
Java_me_zyrouge_symphony_metaphony_AudioMetadataWriter_writeLyrics(
        JNIEnv *env, jobject, jstring filename, jint fd, jbyteArray lyrics) {
    if (!filename || !lyrics) return -2;
    const auto size = env->GetArrayLength(lyrics);
    if (size <= 0 || size > 1024 * 1024) return -2;
    const char *name = env->GetStringUTFChars(filename, nullptr);
    if (!name) return -2;
    const std::string path(name);
    env->ReleaseStringUTFChars(filename, name);
    std::string utf8(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(lyrics, 0, size, reinterpret_cast<jbyte *>(utf8.data()));
    if (env->ExceptionCheck()) return -2;
    try { return LyricsWriter::write(path, fd, utf8); }
    catch (...) { return -2; }
}
