#include <jni.h>

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/log.h>
#include <cerrno>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <linux/dma-heap.h>
#include <limits>
#include <mutex>
#include <string>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <utility>
#include <vector>

#define LOG_TAG "GosCompatDmaBufRelease"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef EGL_PROTECTED_CONTENT_EXT
#define EGL_PROTECTED_CONTENT_EXT 0x32C0
#endif

#ifndef GL_TEXTURE_PROTECTED_EXT
#define GL_TEXTURE_PROTECTED_EXT 0x8BFA
#endif

namespace {

constexpr const char* DIRECT_HEAP_DEVICE_PREFIX = "/dev/dma_heap/";
constexpr const char* VFRAME_SECURE_HEAP_NAME = "vframe-secure";
constexpr const char* VSTREAM_SECURE_HEAP_NAME = "vstream-secure";
constexpr uint64_t DIRECT_HEAP_BYTES_PER_PIXEL = 4;
constexpr uint64_t DIRECT_HEAP_EXPECTED_CHUNK_SIZE = 65536;
constexpr const char* EGL_PROTECTED_CONTENT_EXTENSION = "EGL_EXT_protected_content";
constexpr const char* GL_PROTECTED_TEXTURES_EXTENSION = "GL_EXT_protected_textures";

std::mutex g_workload_mutex;
/*
 * Retained DMA-BUF fds intentionally stay outside ScopedFd. The tests need to
 * choose between explicit close through release_workload() and process teardown.
 */
std::vector<int> g_retained_fds;

struct ProtectedEglWorkload {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    GLuint texture = 0;
};

ProtectedEglWorkload g_protected_egl_workload;

struct NativeResult {
    bool ready = false;
    bool unsupported = false;
    bool protected_content = true;
    int allocated_buffers = 0;
    int pid = 0;
    int tid = 0;
    std::string heap_path;
    std::string heap_name;
    std::string allocator;
    std::string allocation;
    std::string error;
};

int get_thread_id() {
    return static_cast<int>(syscall(SYS_gettid));
}

void close_fd(int fd) {
    if (fd >= 0) {
        close(fd);
    }
}

bool has_extension(const char* extensions, const char* extension) {
    if (extensions == nullptr || extension == nullptr) {
        return false;
    }

    const size_t extension_length = std::strlen(extension);
    const char* current = extensions;
    while ((current = std::strstr(current, extension)) != nullptr) {
        const bool starts_token = current == extensions || current[-1] == ' ';
        const char next = current[extension_length];
        const bool ends_token = next == '\0' || next == ' ';
        if (starts_token && ends_token) {
            return true;
        }
        current += extension_length;
    }
    return false;
}

std::string hex_error(const char* prefix, unsigned int error) {
    char buffer[64];
    std::snprintf(buffer, sizeof(buffer), "%s 0x%x", prefix, error);
    return buffer;
}

void destroy_protected_egl_workload(ProtectedEglWorkload* workload) {
    if (workload->display == EGL_NO_DISPLAY) {
        return;
    }

    if (workload->context != EGL_NO_CONTEXT && workload->surface != EGL_NO_SURFACE) {
        eglMakeCurrent(workload->display, workload->surface, workload->surface,
                workload->context);
    }
    if (workload->texture != 0) {
        glDeleteTextures(1, &workload->texture);
    }
    eglMakeCurrent(workload->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (workload->surface != EGL_NO_SURFACE) {
        eglDestroySurface(workload->display, workload->surface);
    }
    if (workload->context != EGL_NO_CONTEXT) {
        eglDestroyContext(workload->display, workload->context);
    }
    eglTerminate(workload->display);
    *workload = ProtectedEglWorkload();
}

class ScopedFd {
public:
    explicit ScopedFd(int fd = -1) : mFd(fd) {}

    ~ScopedFd() {
        reset();
    }

    ScopedFd(const ScopedFd&) = delete;
    ScopedFd& operator=(const ScopedFd&) = delete;

    ScopedFd(ScopedFd&& other) noexcept : mFd(other.release()) {}

    ScopedFd& operator=(ScopedFd&& other) noexcept {
        if (this != &other) {
            reset(other.release());
        }
        return *this;
    }

    int get() const {
        return mFd;
    }

    int release() {
        int fd = mFd;
        mFd = -1;
        return fd;
    }

    void reset(int fd = -1) {
        close_fd(mFd);
        mFd = fd;
    }

private:
    int mFd;
};

void release_workload() {
    std::lock_guard<std::mutex> lock(g_workload_mutex);
    for (int fd : g_retained_fds) {
        close_fd(fd);
    }
    g_retained_fds.clear();

    destroy_protected_egl_workload(&g_protected_egl_workload);
}

bool is_allowed_heap_name(const std::string& heap_name) {
    return heap_name == VFRAME_SECURE_HEAP_NAME || heap_name == VSTREAM_SECURE_HEAP_NAME;
}

void set_errno_error(NativeResult* result, const std::string& operation, int error_number) {
    result->error = operation + ": " + std::strerror(error_number);
}

uint64_t direct_heap_allocation_size(int width, int height, NativeResult* result) {
    const uint64_t safe_width = width > 0 ? static_cast<uint64_t>(width) : 1;
    const uint64_t safe_height = height > 0 ? static_cast<uint64_t>(height) : 1;
    const uint64_t max_size = std::numeric_limits<uint64_t>::max();
    if (safe_width > max_size / safe_height ||
            safe_width * safe_height > max_size / DIRECT_HEAP_BYTES_PER_PIXEL) {
        result->error = "Direct secure DMA-BUF allocation size overflowed";
        return 0;
    }
    return safe_width * safe_height * DIRECT_HEAP_BYTES_PER_PIXEL;
}

NativeResult start_workload(const std::string& requested_heap_name, int width, int height,
        int buffer_count) {
    release_workload();

    NativeResult result;
    result.pid = getpid();
    result.tid = get_thread_id();

    const std::string heap_name = requested_heap_name.empty()
            ? VFRAME_SECURE_HEAP_NAME : requested_heap_name;
    result.heap_name = heap_name;
    result.allocator = "dma_heap";

    if (!is_allowed_heap_name(heap_name)) {
        result.unsupported = true;
        result.error = "Unsupported direct secure DMA-BUF heap name";
        return result;
    }

    const uint64_t allocation_size = direct_heap_allocation_size(width, height, &result);
    if (allocation_size == 0) {
        return result;
    }
    result.allocation = "bytes=" + std::to_string(allocation_size)
            + ", expected_chunks="
            + std::to_string(allocation_size / DIRECT_HEAP_EXPECTED_CHUNK_SIZE)
            + ", expected_chunk_size=" + std::to_string(DIRECT_HEAP_EXPECTED_CHUNK_SIZE);

    const std::string heap_path = std::string(DIRECT_HEAP_DEVICE_PREFIX) + heap_name;
    result.heap_path = heap_path;
    int raw_heap_fd;
    do {
        raw_heap_fd = open(heap_path.c_str(), O_RDONLY | O_CLOEXEC);
    } while (raw_heap_fd < 0 && errno == EINTR);
    if (raw_heap_fd < 0) {
        const int error_number = errno;
        if (error_number == ENOENT || error_number == EACCES) {
            result.unsupported = true;
        }
        set_errno_error(&result, "open " + heap_path, error_number);
        return result;
    }
    ScopedFd heap_fd(raw_heap_fd);

    /*
     * This follows the same DMA-BUF allocation and fd release shape as
     * DmaBufHeapTest.Allocate and DmaBufHeapTest.RepeatedAllocate in
     * system/memory/libdmabufheap/tests/dmabuf_heap_test.cpp. This helper uses
     * the raw DMA heap UAPI because it must target the Pixel secure heap device
     * names directly and run inside the SDK-built helper APK process.
     */
    std::vector<ScopedFd> allocated_fds;
    const int requested_buffers = buffer_count > 0 ? buffer_count : 1;
    for (int i = 0; i < requested_buffers; i++) {
        struct dma_heap_allocation_data data = {};
        data.len = allocation_size;
        data.fd_flags = O_RDWR | O_CLOEXEC;
        data.heap_flags = 0;

        int ret;
        do {
            ret = ioctl(heap_fd.get(), DMA_HEAP_IOCTL_ALLOC, &data);
        } while (ret < 0 && errno == EINTR);
        if (ret < 0) {
            set_errno_error(&result, "DMA_HEAP_IOCTL_ALLOC " + heap_name, errno);
            break;
        }
        ScopedFd allocation(static_cast<int>(data.fd));
        allocated_fds.push_back(std::move(allocation));
    }

    result.allocated_buffers = static_cast<int>(allocated_fds.size());
    if (!result.error.empty()) {
        return result;
    }
    if (allocated_fds.empty()) {
        if (result.error.empty()) {
            result.error = "No DMA-BUF file descriptors were allocated";
        }
        return result;
    }

    result.ready = true;
    result.unsupported = false;
    result.error.clear();
    {
        std::vector<int> fds;
        fds.reserve(allocated_fds.size());
        for (ScopedFd& fd : allocated_fds) {
            fds.push_back(fd.release());
        }

        std::lock_guard<std::mutex> lock(g_workload_mutex);
        g_retained_fds = std::move(fds);
    }
    return result;
}

/*
 * This follows the public CTS protected EGL shape rather than private GPU APIs:
 * - cts/tests/tests/media/common/src/android/media/cts/OutputSurface.java creates a
 *   protected GLES context and protected pbuffer with EGL_PROTECTED_CONTENT_EXT.
 * - cts/tests/vr/src/android/vr/cts/RendererProtectedTexturesTest.java marks a
 *   texture with GL_TEXTURE_PROTECTED_EXT, and VrExtensionBehaviorTest.java validates
 *   those protected attributes.
 *
 * Unlike CTS, this helper keeps the protected EGL resources live until release_workload()
 * or process teardown, so the test covers the DMA-BUF final release path.
 */
NativeResult start_protected_egl_workload(int width, int height, int iterations) {
    release_workload();

    NativeResult result;
    ProtectedEglWorkload workload;
    result.pid = getpid();
    result.tid = get_thread_id();
    result.heap_name = "vendor-selected";
    result.allocator = "EGL_EXT_protected_content";
    result.protected_content = true;

    auto fail = [&result, &workload](const std::string& error) -> NativeResult {
        result.error = error;
        destroy_protected_egl_workload(&workload);
        return result;
    };
    auto unsupported = [&result, &workload](const std::string& error) -> NativeResult {
        result.unsupported = true;
        result.error = error;
        destroy_protected_egl_workload(&workload);
        return result;
    };

    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) {
        result.error = hex_error("eglGetDisplay failed with EGL error", eglGetError());
        return result;
    }

    if (!eglInitialize(display, nullptr, nullptr)) {
        result.error = hex_error("eglInitialize failed with EGL error", eglGetError());
        return result;
    }
    workload.display = display;

    const char* egl_extensions = eglQueryString(display, EGL_EXTENSIONS);
    if (!has_extension(egl_extensions, EGL_PROTECTED_CONTENT_EXTENSION)) {
        return unsupported("EGL_EXT_protected_content is not advertised");
    }

    if (!eglBindAPI(EGL_OPENGL_ES_API)) {
        return fail(hex_error("eglBindAPI failed with EGL error", eglGetError()));
    }

    const EGLint config_attributes[] = {
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_NONE,
    };
    EGLConfig config = nullptr;
    EGLint config_count = 0;
    if (!eglChooseConfig(display, config_attributes, &config, 1, &config_count) ||
            config_count == 0) {
        return fail(hex_error("eglChooseConfig failed with EGL error", eglGetError()));
    }

    EGLint max_pbuffer_width = 1;
    EGLint max_pbuffer_height = 1;
    eglGetConfigAttrib(display, config, EGL_MAX_PBUFFER_WIDTH, &max_pbuffer_width);
    eglGetConfigAttrib(display, config, EGL_MAX_PBUFFER_HEIGHT, &max_pbuffer_height);
    if (max_pbuffer_width < 1 || max_pbuffer_height < 1) {
        return fail("EGL config reported an invalid pbuffer size limit");
    }
    const EGLint requested_width = width > 0 ? width : 1;
    const EGLint requested_height = height > 0 ? height : 1;
    const EGLint actual_width = requested_width <= max_pbuffer_width
            ? requested_width : max_pbuffer_width;
    const EGLint actual_height = requested_height <= max_pbuffer_height
            ? requested_height : max_pbuffer_height;

    const EGLint context_attributes[] = {
            EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL_PROTECTED_CONTENT_EXT, EGL_TRUE,
            EGL_NONE,
    };
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attributes);
    if (context == EGL_NO_CONTEXT) {
        return fail(hex_error("eglCreateContext failed with EGL error", eglGetError()));
    }
    workload.context = context;

    const EGLint surface_attributes[] = {
            EGL_WIDTH, actual_width,
            EGL_HEIGHT, actual_height,
            EGL_PROTECTED_CONTENT_EXT, EGL_TRUE,
            EGL_NONE,
    };
    EGLSurface surface = eglCreatePbufferSurface(display, config, surface_attributes);
    if (surface == EGL_NO_SURFACE) {
        return fail(hex_error("eglCreatePbufferSurface failed with EGL error", eglGetError()));
    }
    workload.surface = surface;

    if (!eglMakeCurrent(display, surface, surface, context)) {
        return fail(hex_error("eglMakeCurrent failed with EGL error", eglGetError()));
    }

    const char* gl_extensions = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
    if (!has_extension(gl_extensions, GL_PROTECTED_TEXTURES_EXTENSION)) {
        return unsupported("GL_EXT_protected_textures is not advertised");
    }

    GLuint texture = 0;
    glGenTextures(1, &texture);
    workload.texture = texture;
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_PROTECTED_EXT, GL_TRUE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, actual_width, actual_height, 0, GL_RGBA,
            GL_UNSIGNED_BYTE, nullptr);
    GLenum gl_error = glGetError();
    if (gl_error != GL_NO_ERROR) {
        return fail(hex_error("protected glTexImage2D failed with GL error", gl_error));
    }

    const int draw_iterations = iterations > 0 ? iterations : 1;
    glViewport(0, 0, actual_width, actual_height);
    for (int i = 0; i < draw_iterations; i++) {
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
    }
    glFinish();
    gl_error = glGetError();
    if (gl_error != GL_NO_ERROR) {
        return fail(hex_error("protected GL workload failed with GL error", gl_error));
    }

    result.ready = true;
    result.unsupported = false;
    result.allocated_buffers = 2;
    result.allocation = "protected_pbuffer=" + std::to_string(actual_width)
            + "x" + std::to_string(actual_height)
            + ", protected_texture=" + std::to_string(actual_width)
            + "x" + std::to_string(actual_height)
            + ", requested=" + std::to_string(requested_width)
            + "x" + std::to_string(requested_height)
            + ", iterations=" + std::to_string(draw_iterations);

    {
        std::lock_guard<std::mutex> lock(g_workload_mutex);
        g_protected_egl_workload = workload;
    }
    return result;
}

jobjectArray result_to_array(JNIEnv* env, const NativeResult& result) {
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(11, string_class, nullptr);
    const std::string values[] = {
            result.ready ? "true" : "false",
            result.unsupported ? "true" : "false",
            result.protected_content ? "true" : "false",
            std::to_string(result.allocated_buffers),
            std::to_string(result.pid),
            std::to_string(result.tid),
            result.heap_path,
            result.heap_name,
            result.allocator,
            result.allocation,
            result.error,
    };
    for (jsize i = 0; i < 11; i++) {
        jstring value = env->NewStringUTF(values[i].c_str());
        env->SetObjectArrayElement(array, i, value);
        env->DeleteLocalRef(value);
    }
    return array;
}

}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_grapheneos_goscompat_checks_dmabuf_DmaBufReleaseRunner_nativeStartWorkload(
        JNIEnv* env, jobject /* thiz */, jstring heap_name, jint width, jint height,
        jint buffer_count) {
    const char* heap_name_chars = heap_name != nullptr
            ? env->GetStringUTFChars(heap_name, nullptr) : nullptr;
    std::string heap_name_string = heap_name_chars != nullptr ? heap_name_chars : "";
    if (heap_name_chars != nullptr) {
        env->ReleaseStringUTFChars(heap_name, heap_name_chars);
    }

    NativeResult result = start_workload(heap_name_string, width, height, buffer_count);
    if (!result.ready && !result.error.empty()) {
        ALOGE("%s", result.error.c_str());
    }
    return result_to_array(env, result);
}

extern "C" JNIEXPORT void JNICALL
Java_app_grapheneos_goscompat_checks_dmabuf_DmaBufReleaseRunner_nativeReleaseWorkload(
        JNIEnv* /* env */, jobject /* thiz */) {
    release_workload();
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_grapheneos_goscompat_checks_dmabuf_DmaBufReleaseRunner_nativeStartProtectedEglWorkload(
        JNIEnv* env, jobject /* thiz */, jint width, jint height, jint iterations) {
    NativeResult result = start_protected_egl_workload(width, height, iterations);
    if (!result.ready && !result.error.empty()) {
        ALOGE("%s", result.error.c_str());
    }
    return result_to_array(env, result);
}
