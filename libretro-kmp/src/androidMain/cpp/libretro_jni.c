/*
 * JNI bridge between libretro cores and dev.kotlinds.libretrokmp.LibretroCore (Android).
 *
 * The core is opened with dlopen() and its functions are called through dlsym() addresses. Its
 * callbacks (which carry no user data) are routed to the single open core: the Kotlin object is
 * kept as a global reference, and the JNIEnv of the thread currently calling into the core (all
 * callbacks happen during a call such as retro_run) is remembered in `env_now`.
 *
 * The environment callback is handled in Kotlin (shared with the other platforms): this file only
 * exposes small helpers to read/write the `data` pointer.
 *
 * Package: dev.kotlinds.libretrokmp
 * JNI naming: Java_dev_kotlinds_libretrokmp_LibretroJni_*
 */

#include <jni.h>
#include <dlfcn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <libretro.h>

#define LOG_TAG "LibretroJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define MAX_STRINGS 256

/* ---- Core functions resolved with dlsym ---- */

typedef struct {
    void *handle;
    unsigned (*api_version)(void);
    void (*set_environment)(retro_environment_t);
    void (*set_video_refresh)(retro_video_refresh_t);
    void (*set_audio_sample)(retro_audio_sample_t);
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t);
    void (*set_input_poll)(retro_input_poll_t);
    void (*set_input_state)(retro_input_state_t);
    void (*init)(void);
    void (*deinit)(void);
    void (*get_system_info)(struct retro_system_info *);
    void (*get_system_av_info)(struct retro_system_av_info *);
    void (*set_controller_port_device)(unsigned, unsigned);
    void (*reset)(void);
    void (*run)(void);
    size_t (*serialize_size)(void);
    bool (*serialize)(void *, size_t);
    bool (*unserialize)(const void *, size_t);
    bool (*load_game)(const struct retro_game_info *);
    void (*unload_game)(void);
    void *(*get_memory_data)(unsigned);
    size_t (*get_memory_size)(unsigned);
} core_t;

/* ---- State of the single open core ---- */

static core_t core;
static JNIEnv *env_now = NULL;      /* JNIEnv of the thread currently calling into the core */
static jobject callbacks = NULL;    /* global ref to the Kotlin LibretroCore */
static jmethodID m_environment, m_video, m_audio, m_input_poll, m_input_state, m_log;
static void *game_data = NULL;      /* game bytes, kept alive while the game is loaded */
static char *strings[MAX_STRINGS];  /* strings given to the core, freed on close */
static int string_count = 0;

#define ENTER(env) (env_now = (env))

static char *persistent_string(JNIEnv *env, jstring value) {
    const char *chars = (*env)->GetStringUTFChars(env, value, NULL);
    for (int i = 0; i < string_count; i++) {
        if (strcmp(strings[i], chars) == 0) {
            (*env)->ReleaseStringUTFChars(env, value, chars);
            return strings[i];
        }
    }
    char *copy = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, value, chars);
    if (string_count < MAX_STRINGS) strings[string_count++] = copy;
    return copy;
}

/* ---- Callbacks given to the core ---- */

static bool environment_cb(unsigned cmd, void *data) {
    if (!env_now || !callbacks) return false;
    return (*env_now)->CallBooleanMethod(env_now, callbacks, m_environment, (jint) cmd, (jlong) (intptr_t) data);
}

static void video_cb(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (!env_now || !callbacks || !data) return; /* NULL = duplicated frame */
    jsize size = (jsize) (pitch * height);
    jbyteArray bytes = (*env_now)->NewByteArray(env_now, size);
    (*env_now)->SetByteArrayRegion(env_now, bytes, 0, size, (const jbyte *) data);
    (*env_now)->CallVoidMethod(env_now, callbacks, m_video, bytes, (jint) width, (jint) height, (jint) pitch);
    (*env_now)->DeleteLocalRef(env_now, bytes);
}

static size_t audio_batch_cb(const int16_t *data, size_t frames) {
    if (!env_now || !callbacks || !data) return frames;
    jsize samples = (jsize) (frames * 2);
    jshortArray array = (*env_now)->NewShortArray(env_now, samples);
    (*env_now)->SetShortArrayRegion(env_now, array, 0, samples, data);
    (*env_now)->CallVoidMethod(env_now, callbacks, m_audio, array, (jint) frames);
    (*env_now)->DeleteLocalRef(env_now, array);
    return frames;
}

static void audio_sample_cb(int16_t left, int16_t right) {
    int16_t frame[2] = {left, right};
    audio_batch_cb(frame, 1);
}

static void input_poll_cb(void) {
    if (env_now && callbacks) (*env_now)->CallVoidMethod(env_now, callbacks, m_input_poll);
}

static int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (!env_now || !callbacks) return 0;
    return (*env_now)->CallShortMethod(env_now, callbacks, m_input_state, (jint) port, (jint) device, (jint) index, (jint) id);
}

static void log_cb(enum retro_log_level level, const char *fmt, ...) {
    if (!env_now || !callbacks) return;
    char message[2048];
    va_list args;
    va_start(args, fmt);
    vsnprintf(message, sizeof(message), fmt, args);
    va_end(args);
    jstring text = (*env_now)->NewStringUTF(env_now, message);
    (*env_now)->CallVoidMethod(env_now, callbacks, m_log, (jint) level, text);
    (*env_now)->DeleteLocalRef(env_now, text);
}

/* ---- Lifecycle ---- */

#define RESOLVE(field, name) \
    *(void **) (&core.field) = dlsym(core.handle, name); \
    if (!core.field) { LOGE("missing symbol %s", name); dlclose(core.handle); core.handle = NULL; return JNI_FALSE; }

JNIEXPORT jboolean JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_open(JNIEnv *env, jobject thiz, jstring path, jobject target) {
    if (core.handle) { LOGE("a core is already open"); return JNI_FALSE; }
    const char *c_path = (*env)->GetStringUTFChars(env, path, NULL);
    core.handle = dlopen(c_path, RTLD_NOW | RTLD_LOCAL);
    (*env)->ReleaseStringUTFChars(env, path, c_path);
    if (!core.handle) { LOGE("dlopen failed: %s", dlerror()); return JNI_FALSE; }

    RESOLVE(api_version, "retro_api_version")
    RESOLVE(set_environment, "retro_set_environment")
    RESOLVE(set_video_refresh, "retro_set_video_refresh")
    RESOLVE(set_audio_sample, "retro_set_audio_sample")
    RESOLVE(set_audio_sample_batch, "retro_set_audio_sample_batch")
    RESOLVE(set_input_poll, "retro_set_input_poll")
    RESOLVE(set_input_state, "retro_set_input_state")
    RESOLVE(init, "retro_init")
    RESOLVE(deinit, "retro_deinit")
    RESOLVE(get_system_info, "retro_get_system_info")
    RESOLVE(get_system_av_info, "retro_get_system_av_info")
    RESOLVE(set_controller_port_device, "retro_set_controller_port_device")
    RESOLVE(reset, "retro_reset")
    RESOLVE(run, "retro_run")
    RESOLVE(serialize_size, "retro_serialize_size")
    RESOLVE(serialize, "retro_serialize")
    RESOLVE(unserialize, "retro_unserialize")
    RESOLVE(load_game, "retro_load_game")
    RESOLVE(unload_game, "retro_unload_game")
    RESOLVE(get_memory_data, "retro_get_memory_data")
    RESOLVE(get_memory_size, "retro_get_memory_size")

    if (core.api_version() != RETRO_API_VERSION) {
        LOGE("unsupported API version %u", core.api_version());
        dlclose(core.handle);
        core.handle = NULL;
        return JNI_FALSE;
    }

    callbacks = (*env)->NewGlobalRef(env, target);
    jclass cls = (*env)->GetObjectClass(env, target);
    m_environment = (*env)->GetMethodID(env, cls, "environment", "(IJ)Z");
    m_video = (*env)->GetMethodID(env, cls, "onVideoRefresh", "([BIII)V");
    m_audio = (*env)->GetMethodID(env, cls, "onAudioBatch", "([SI)V");
    m_input_poll = (*env)->GetMethodID(env, cls, "onInputPoll", "()V");
    m_input_state = (*env)->GetMethodID(env, cls, "onInputState", "(IIII)S");
    m_log = (*env)->GetMethodID(env, cls, "onLog", "(ILjava/lang/String;)V");

    ENTER(env);
    /* The environment callback must be set before retro_init(), the others before retro_run(). */
    core.set_environment(environment_cb);
    core.init();
    core.set_video_refresh(video_cb);
    core.set_audio_sample(audio_sample_cb);
    core.set_audio_sample_batch(audio_batch_cb);
    core.set_input_poll(input_poll_cb);
    core.set_input_state(input_state_cb);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_close(JNIEnv *env, jobject thiz) {
    if (!core.handle) return;
    ENTER(env);
    core.unload_game();
    core.deinit();
    dlclose(core.handle);
    memset(&core, 0, sizeof(core));
    if (callbacks) (*env)->DeleteGlobalRef(env, callbacks);
    callbacks = NULL;
    free(game_data);
    game_data = NULL;
    for (int i = 0; i < string_count; i++) free(strings[i]);
    string_count = 0;
    env_now = NULL;
}

/* Returns {library_name, library_version, valid_extensions, need_fullpath ("1"/"0"), block_extract ("1"/"0")}. */
JNIEXPORT jobjectArray JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_systemInfo(JNIEnv *env, jobject thiz) {
    ENTER(env);
    struct retro_system_info info;
    memset(&info, 0, sizeof(info));
    core.get_system_info(&info);
    jobjectArray result = (*env)->NewObjectArray(env, 5, (*env)->FindClass(env, "java/lang/String"), NULL);
    (*env)->SetObjectArrayElement(env, result, 0, (*env)->NewStringUTF(env, info.library_name ? info.library_name : ""));
    (*env)->SetObjectArrayElement(env, result, 1, (*env)->NewStringUTF(env, info.library_version ? info.library_version : ""));
    (*env)->SetObjectArrayElement(env, result, 2, (*env)->NewStringUTF(env, info.valid_extensions ? info.valid_extensions : ""));
    (*env)->SetObjectArrayElement(env, result, 3, (*env)->NewStringUTF(env, info.need_fullpath ? "1" : "0"));
    (*env)->SetObjectArrayElement(env, result, 4, (*env)->NewStringUTF(env, info.block_extract ? "1" : "0"));
    return result;
}

JNIEXPORT jboolean JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_loadGame(JNIEnv *env, jobject thiz, jstring path, jbyteArray data) {
    ENTER(env);
    struct retro_game_info info;
    memset(&info, 0, sizeof(info));
    jstring kept = path;
    info.path = persistent_string(env, kept);
    if (data) {
        jsize size = (*env)->GetArrayLength(env, data);
        free(game_data);
        game_data = malloc((size_t) size);
        (*env)->GetByteArrayRegion(env, data, 0, size, (jbyte *) game_data);
        info.data = game_data;
        info.size = (size_t) size;
    }
    return core.load_game(&info) ? JNI_TRUE : JNI_FALSE;
}

/* Returns {base_width, base_height, max_width, max_height, aspect_ratio, fps, sample_rate}. */
JNIEXPORT jdoubleArray JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_avInfo(JNIEnv *env, jobject thiz) {
    ENTER(env);
    struct retro_system_av_info info;
    memset(&info, 0, sizeof(info));
    core.get_system_av_info(&info);
    jdouble values[7] = {
        info.geometry.base_width, info.geometry.base_height, info.geometry.max_width, info.geometry.max_height,
        info.geometry.aspect_ratio, info.timing.fps, info.timing.sample_rate,
    };
    jdoubleArray result = (*env)->NewDoubleArray(env, 7);
    (*env)->SetDoubleArrayRegion(env, result, 0, 7, values);
    return result;
}

/* ---- Running ---- */

JNIEXPORT void JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_run(JNIEnv *env, jobject thiz) {
    ENTER(env);
    core.run();
}

JNIEXPORT void JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_reset(JNIEnv *env, jobject thiz) {
    ENTER(env);
    core.reset();
}

JNIEXPORT void JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_setControllerPortDevice(JNIEnv *env, jobject thiz, jint port, jint device) {
    ENTER(env);
    core.set_controller_port_device((unsigned) port, (unsigned) device);
}

/* ---- Memory and states ---- */

JNIEXPORT jlong JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_memorySize(JNIEnv *env, jobject thiz, jint type) {
    ENTER(env);
    return (jlong) core.get_memory_size((unsigned) type);
}

JNIEXPORT jbyteArray JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_readMemory(JNIEnv *env, jobject thiz, jint type) {
    ENTER(env);
    size_t size = core.get_memory_size((unsigned) type);
    void *data = core.get_memory_data((unsigned) type);
    if (!data || size == 0) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, (jsize) size);
    (*env)->SetByteArrayRegion(env, result, 0, (jsize) size, (const jbyte *) data);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_writeMemory(JNIEnv *env, jobject thiz, jint type, jbyteArray bytes) {
    ENTER(env);
    size_t size = core.get_memory_size((unsigned) type);
    void *data = core.get_memory_data((unsigned) type);
    if (!data || size == 0) return JNI_FALSE;
    jsize length = (*env)->GetArrayLength(env, bytes);
    jsize count = (size_t) length < size ? length : (jsize) size;
    (*env)->GetByteArrayRegion(env, bytes, 0, count, (jbyte *) data);
    return JNI_TRUE;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_saveState(JNIEnv *env, jobject thiz) {
    ENTER(env);
    size_t size = core.serialize_size();
    if (size == 0) return NULL;
    void *buffer = malloc(size);
    jbyteArray result = NULL;
    if (core.serialize(buffer, size)) {
        result = (*env)->NewByteArray(env, (jsize) size);
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) size, (const jbyte *) buffer);
    }
    free(buffer);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_loadState(JNIEnv *env, jobject thiz, jbyteArray state) {
    ENTER(env);
    jsize size = (*env)->GetArrayLength(env, state);
    void *buffer = malloc((size_t) size);
    (*env)->GetByteArrayRegion(env, state, 0, size, (jbyte *) buffer);
    bool loaded = core.unserialize(buffer, (size_t) size);
    free(buffer);
    return loaded ? JNI_TRUE : JNI_FALSE;
}

/* ---- Helpers for the environment callback (the `data` pointer is passed to Kotlin as a long) ---- */

JNIEXPORT jint JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_envReadInt(JNIEnv *env, jobject thiz, jlong data) {
    return *(int *) (intptr_t) data;
}

JNIEXPORT void JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_envWriteInt(JNIEnv *env, jobject thiz, jlong data, jint value) {
    *(int *) (intptr_t) data = value;
}

JNIEXPORT void JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_envWriteBool(JNIEnv *env, jobject thiz, jlong data, jboolean value) {
    *(bool *) (intptr_t) data = value ? true : false;
}

JNIEXPORT void JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_envWriteString(JNIEnv *env, jobject thiz, jlong data, jstring value) {
    *(const char **) (intptr_t) data = persistent_string(env, value);
}

JNIEXPORT jstring JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_envReadVariableKey(JNIEnv *env, jobject thiz, jlong data) {
    const char *key = ((struct retro_variable *) (intptr_t) data)->key;
    return key ? (*env)->NewStringUTF(env, key) : NULL;
}

JNIEXPORT void JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_envWriteVariableValue(JNIEnv *env, jobject thiz, jlong data, jstring value) {
    ((struct retro_variable *) (intptr_t) data)->value = persistent_string(env, value);
}

JNIEXPORT void JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_envWriteLogCallback(JNIEnv *env, jobject thiz, jlong data) {
    ((struct retro_log_callback *) (intptr_t) data)->log = log_cb;
}

/* Returns {base_width, base_height, max_width, max_height, aspect_ratio, fps, sample_rate}. */
JNIEXPORT jdoubleArray JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_envReadAvInfo(JNIEnv *env, jobject thiz, jlong data) {
    const struct retro_system_av_info *info = (const struct retro_system_av_info *) (intptr_t) data;
    jdouble values[7] = {
        info->geometry.base_width, info->geometry.base_height, info->geometry.max_width, info->geometry.max_height,
        info->geometry.aspect_ratio, info->timing.fps, info->timing.sample_rate,
    };
    jdoubleArray result = (*env)->NewDoubleArray(env, 7);
    (*env)->SetDoubleArrayRegion(env, result, 0, 7, values);
    return result;
}

/* Returns {base_width, base_height, max_width, max_height, aspect_ratio}. */
JNIEXPORT jdoubleArray JNICALL
Java_dev_kotlinds_libretrokmp_LibretroJni_envReadGeometry(JNIEnv *env, jobject thiz, jlong data) {
    const struct retro_game_geometry *geometry = (const struct retro_game_geometry *) (intptr_t) data;
    jdouble values[5] = {
        geometry->base_width, geometry->base_height, geometry->max_width, geometry->max_height, geometry->aspect_ratio,
    };
    jdoubleArray result = (*env)->NewDoubleArray(env, 5);
    (*env)->SetDoubleArrayRegion(env, result, 0, 5, values);
    return result;
}
