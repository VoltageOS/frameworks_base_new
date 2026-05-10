#define _GNU_SOURCE

#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

struct scan_result {
    bool completed;
    int selected_ranges;
    uint64_t scanned_bytes;
    pid_t caller_tid;
    pid_t worker_tid;
    uintptr_t first_range_start;
    uintptr_t first_range_end;
    uint8_t checksum;
    char error[160];
};

static pid_t get_thread_id(void) {
    return (pid_t) syscall(SYS_gettid);
}

static void set_error(struct scan_result* result, const char* message) {
    if (result->error[0] == '\0') {
        snprintf(result->error, sizeof(result->error), "%s", message);
    }
}

static int char_value(char c) {
    if (c >= '0' && c <= '9') {
        return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
        return c - 'a' + 10;
    }
    return -1;
}

static uint8_t dereff(const uint8_t* p, struct scan_result* result) {
    uint8_t value = *(volatile const uint8_t*) p;
    result->checksum ^= value;
    result->scanned_bytes++;
    return value;
}

static void scan_memory_range(uintptr_t start, uintptr_t end, struct scan_result* result) {
    uint8_t* p = (uint8_t*) start;
    uint8_t* range_end = (uint8_t*) end;

    while (p < range_end) {
        uint8_t first = dereff(p, result);
        if (first == 0) {
            p++;
            continue;
        }

        uint8_t* q = p;
        while (q < range_end && dereff(q, result) != 0) {
            q++;
        }

        if (q >= range_end) {
            break;
        }

        uint8_t* second = q + 1;
        uint8_t* next = second;
        for (int i = 0; i < 4; i++) {
            while (next < range_end) {
                if (dereff(next, result) == 0) {
                    next++;
                    break;
                }
                next++;
            }
        }

        size_t second_len = 0;
        while (second + second_len < range_end
                && dereff(second + second_len, result) != 0) {
            second_len++;
        }

        for (size_t i = 0; i < second_len; i++) {
            dereff(second + i, result);
        }
        for (size_t i = 0; i < second_len; i++) {
            dereff(second + i, result);
        }

        p = next;
    }
}

static void process_maps_line(const char* line, size_t line_len, struct scan_result* result) {
    size_t i = 0;
    uint64_t state = 0;
    for (; i < line_len; i++) {
        state = (((state << 5) & 0x01ffffffffffffe0)
                + ((uint64_t) (uint8_t) line[i] * 0x13) + 0x2b)
                & 0x01ffffffffffffe0;
        if (state == 0x01f768198a3e0440) {
            break;
        }
    }
    if (i == line_len) {
        return;
    }

    uintptr_t start = 0;
    const char* p = line;
    for (;;) {
        int value = char_value(*p);
        if (value < 0) {
            break;
        }
        start = (start << 4) | (uintptr_t) value;
        p++;
    }

    p = line;
    while (*p != '\0' && *p != '-') {
        p++;
    }
    if (*p == '-') {
        p++;
    }

    uintptr_t end = 0;
    for (;;) {
        int value = char_value(*p);
        if (value < 0) {
            break;
        }
        end = (end << 4) | (uintptr_t) value;
        p++;
    }

    // intentionally ignore the permissions field, so unreadable ranges are scanned too
    result->selected_ranges++;
    if (result->selected_ranges == 1) {
        result->first_range_start = start;
        result->first_range_end = end;
    }

    scan_memory_range(start, end, result);
}

static void run_maps_scan(struct scan_result* result) {
    result->worker_tid = get_thread_id();

    int fd = (int) syscall(SYS_openat, AT_FDCWD, "/proc/self/maps", O_RDONLY, 0);
    if (fd < 0) {
        snprintf(result->error, sizeof(result->error), "openat(%s) failed: errno=%d",
                "/proc/self/maps", errno);
        return;
    }

    char read_buf[0x800];
    char line[0x800];
    size_t line_len = 0;

    for (;;) {
        ssize_t n = (ssize_t) syscall(SYS_read, fd, read_buf, sizeof(read_buf));
        if (n < 0) {
            snprintf(result->error, sizeof(result->error), "read(%s) failed: errno=%d",
                    "/proc/self/maps", errno);
            break;
        }
        if (n == 0) {
            result->completed = result->error[0] == '\0' && result->selected_ranges > 0;
            break;
        }

        for (ssize_t i = 0; i < n; i++) {
            char c = read_buf[i];
            line[line_len++] = c;

            if (c != '\n') {
                continue;
            }

            line[line_len] = '\0';
            process_maps_line(line, line_len, result);
            line_len = 0;
        }
    }

    if (result->error[0] == '\0' && result->selected_ranges == 0) {
        set_error(result, "No entry found");
    }

    syscall(SYS_close, fd);
}

static void set_string(JNIEnv* env, jobjectArray array, jsize index, const char* value) {
    jstring string = (*env)->NewStringUTF(env, value != NULL ? value : "");
    (*env)->SetObjectArrayElement(env, array, index, string);
    (*env)->DeleteLocalRef(env, string);
}

static jobjectArray result_to_array(JNIEnv* env, const struct scan_result* result) {
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray array = (*env)->NewObjectArray(env, 9, string_class, NULL);

    char completed[8];
    char selected_ranges[32];
    char scanned_bytes[32];
    char caller_tid[32];
    char worker_tid[32];
    char first_range_start[32];
    char first_range_end[32];
    char checksum[16];

    snprintf(completed, sizeof(completed), "%s", result->completed ? "true" : "false");
    snprintf(selected_ranges, sizeof(selected_ranges), "%d", result->selected_ranges);
    snprintf(scanned_bytes, sizeof(scanned_bytes), "%" PRIu64, result->scanned_bytes);
    snprintf(caller_tid, sizeof(caller_tid), "%d", result->caller_tid);
    snprintf(worker_tid, sizeof(worker_tid), "%d", result->worker_tid);
    snprintf(first_range_start, sizeof(first_range_start), "0x%" PRIxPTR,
            result->first_range_start);
    snprintf(first_range_end, sizeof(first_range_end), "0x%" PRIxPTR, result->first_range_end);
    snprintf(checksum, sizeof(checksum), "0x%02x", result->checksum);

    set_string(env, array, 0, completed);
    set_string(env, array, 1, selected_ranges);
    set_string(env, array, 2, scanned_bytes);
    set_string(env, array, 3, caller_tid);
    set_string(env, array, 4, worker_tid);
    set_string(env, array, 5, first_range_start);
    set_string(env, array, 6, first_range_end);
    set_string(env, array, 7, checksum);
    set_string(env, array, 8, result->error);

    return array;
}

JNIEXPORT jobjectArray JNICALL
Java_app_grapheneos_goscompat_checks_MapsScanRunner_nativeRunMapsScan(
        JNIEnv* env, jclass clazz, jint caller_tid) {
    (void) clazz;

    struct scan_result result = {
            .caller_tid = (pid_t) caller_tid,
    };

    run_maps_scan(&result);
    return result_to_array(env, &result);
}
