#include <jni.h>
#include <string>
#include <vector>
#include <unistd.h>
#include "android/log_macros.h"
#include "tfile.h"
#include "tfilestream.h"
#include "tpropertymap.h"
#include "fileref.h"
#include "TagLibHelper.h"


// NewStringUTF expects modified UTF-8 and corrupts supplementary Unicode (e.g. emoji).
static jstring toJavaString(JNIEnv *env, const TagLib::String &value) {
    std::vector<jchar> utf16;
    for (const auto wc : value.toWString()) {
        auto cp = static_cast<unsigned int>(wc);
        if (cp <= 0xFFFF) utf16.push_back(static_cast<jchar>(cp));
        else {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

jclass audioMetadataParserClass = nullptr;
jmethodID audioMetadataParserPutTagMethodId = nullptr;
jmethodID audioMetadataParserPutPictureMethodId = nullptr;
jmethodID audioMetadataParserPutAudioPropertyMethodId = nullptr;

extern "C" {
JNIEXPORT jint
JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    const auto _audioMetadataParserClass = env->FindClass(
            "me/zyrouge/symphony/metaphony/AudioMetadataParser");
    audioMetadataParserClass = reinterpret_cast<jclass>(env->NewGlobalRef(
            _audioMetadataParserClass));
    audioMetadataParserPutTagMethodId = env->GetMethodID(
            audioMetadataParserClass,
            "putTag", "(Ljava/lang/String;Ljava/lang/String;)V");
    audioMetadataParserPutPictureMethodId = env->GetMethodID(
            audioMetadataParserClass,
            "putPicture", "(Ljava/lang/String;Ljava/lang/String;[B)V");
    audioMetadataParserPutAudioPropertyMethodId = env->GetMethodID(
            audioMetadataParserClass,
            "putAudioProperty", "(Ljava/lang/String;I)V");
    env->DeleteLocalRef(_audioMetadataParserClass);
    return JNI_VERSION_1_6;
}

JNIEXPORT void
JNI_OnUnload(JavaVM *vm, void *) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return;
    }
    env->DeleteGlobalRef(audioMetadataParserClass);
    audioMetadataParserClass = nullptr;
    audioMetadataParserPutTagMethodId = nullptr;
    audioMetadataParserPutPictureMethodId = nullptr;
    audioMetadataParserPutAudioPropertyMethodId = nullptr;
}

JNIEXPORT jboolean JNICALL
Java_me_zyrouge_symphony_metaphony_AudioMetadataParser_readMetadata(
        JNIEnv *env,
        jobject thiz,
        jstring filename,
        jint fd) {
    const int ownedFd = dup(fd);
    if (ownedFd < 0) return JNI_FALSE;
    const auto stream = std::make_unique<TagLib::FileStream>(ownedFd, true);
    if (!stream->isOpen()) { close(ownedFd); return JNI_FALSE; }
    stream->seek(0);
    const char *name = env->GetStringUTFChars(filename, nullptr);
    if (!name) return JNI_FALSE;
    const auto rawFile = TagLibHelper::detectParser(
            name,
            stream.get(),
            true,
            TagLib::AudioProperties::ReadStyle::Accurate);
    env->ReleaseStringUTFChars(filename, name);
    if (!rawFile) {
        return static_cast<jboolean>(false);
    }
    const std::unique_ptr<TagLib::File> file(rawFile);
    if (file->tag()) {
        const auto tags = file->properties();
        if (!tags.isEmpty()) {
            for (const auto &[key, values]: tags) {
                const auto jKey = env->NewStringUTF(key.toCString(true));
                for (const auto &value: values) {
                    const auto jValue = toJavaString(env, value);
                    env->CallVoidMethod(
                            thiz,
                            audioMetadataParserPutTagMethodId,
                            jKey,
                            jValue);
                    env->DeleteLocalRef(jValue);
                }
                env->DeleteLocalRef(jKey);
            }
        }
        const auto picture = file->complexProperties("PICTURE");
        for (const auto &x: picture) {
            const auto pictureType = x["pictureType"].toString();
            const auto mimeType = x["mimeType"].toString();
            const auto data = x["data"].toByteVector();
            const auto jPictureType = env->NewStringUTF(pictureType.toCString(true));
            const auto jMimeType = env->NewStringUTF(mimeType.toCString(true));
            const auto jDataSize = static_cast<jint>(data.size());
            const auto jData = env->NewByteArray(jDataSize);
            env->SetByteArrayRegion(
                    jData,
                    0,
                    jDataSize,
                    reinterpret_cast<const jbyte *>(data.data()));
            env->CallVoidMethod(
                    thiz,
                    audioMetadataParserPutPictureMethodId,
                    jPictureType,
                    jMimeType,
                    jData);
            env->DeleteLocalRef(jPictureType);
            env->DeleteLocalRef(jMimeType);
            env->DeleteLocalRef(jData);
        }
    }
    const auto audioProperties = file->audioProperties();
    if (audioProperties) {
        env->CallVoidMethod(
                thiz,
                audioMetadataParserPutAudioPropertyMethodId,
                env->NewStringUTF("BITRATE"),
                static_cast<jint>(audioProperties->bitrate()));
        env->CallVoidMethod(
                thiz,
                audioMetadataParserPutAudioPropertyMethodId,
                env->NewStringUTF("LENGTH_SECONDS"),
                static_cast<jint>(audioProperties->lengthInSeconds()));
        env->CallVoidMethod(
                thiz,
                audioMetadataParserPutAudioPropertyMethodId,
                env->NewStringUTF("SAMPLE_RATE"),
                static_cast<jint>(audioProperties->sampleRate()));
        env->CallVoidMethod(
                thiz,
                audioMetadataParserPutAudioPropertyMethodId,
                env->NewStringUTF("CHANNELS"),
                static_cast<jint>(audioProperties->channels()));
    }
    return static_cast<jboolean>(true);
}
}
