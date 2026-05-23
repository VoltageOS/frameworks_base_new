package app.grapheneos.goscompat.checks.dmabuf

import app.grapheneos.goscompat.checks.GosCompatContract
import java.util.Properties

data class DmaBufReleaseResult(
    val mode: String,
    val ready: Boolean,
    val unsupported: Boolean,
    val protectedContent: Boolean,
    val width: Int,
    val height: Int,
    val requestedBuffers: Int,
    val allocatedBuffers: Int,
    val iterations: Int,
    val pid: Int,
    val tid: Int,
    val heapPath: String,
    val heapName: String,
    val allocator: String,
    val allocation: String,
    val error: String,
    val released: Boolean = false,
) {
    val statusText: String
        get() = when {
            ready && released -> "Released"
            ready -> "Ready"
            unsupported -> "Unsupported"
            else -> "Failed"
        }

    val summary: String
        get() = when {
            mode == GosCompatContract.DmaBufRelease.Mode.PROTECTED_EGL && ready && released ->
                "Created and released protected EGL resources."
            mode == GosCompatContract.DmaBufRelease.Mode.PROTECTED_EGL && ready ->
                "Created protected EGL resources."
            ready && released ->
                "Allocated and released $allocatedBuffers direct secure chunk heap DMA-BUF " +
                    "file descriptor."
            ready ->
                "Allocated $allocatedBuffers direct secure chunk heap DMA-BUF file descriptor."
            unsupported -> "Unsupported: $error"
            error.isNotEmpty() -> "Failed: $error"
            else -> "DMA-BUF workload failed."
        }

    val details: List<String>
        get() = buildList {
            add("mode=$mode")
            add("pid=$pid, tid=$tid")
            add("size=${width}x$height")
            if (mode == GosCompatContract.DmaBufRelease.Mode.SECURE_CHUNK_HEAP_DIRECT) {
                add("fds=$allocatedBuffers/$requestedBuffers")
            } else {
                add("resources=$allocatedBuffers")
            }
            add("iterations=$iterations")
            add("protectedContent=$protectedContent")
            add("released=$released")
            add("heap=$heapPath")
            add("heapName=$heapName")
            add("allocator=$allocator")
            add("allocation=$allocation")
            if (error.isNotEmpty()) {
                add("error=$error")
            }
        }

    fun toProperties(token: String): Properties = Properties().apply {
        setProperty(GosCompatContract.DmaBufRelease.Extra.TOKEN, token)
        setProperty(GosCompatContract.DmaBufRelease.Key.RESULT_AVAILABLE, "true")
        setProperty(GosCompatContract.DmaBufRelease.Key.READY, ready.toString())
        setProperty(GosCompatContract.DmaBufRelease.Key.UNSUPPORTED, unsupported.toString())
        setProperty(GosCompatContract.DmaBufRelease.Key.MODE, mode)
        setProperty(GosCompatContract.DmaBufRelease.Key.WIDTH, width.toString())
        setProperty(GosCompatContract.DmaBufRelease.Key.HEIGHT, height.toString())
        setProperty(
            GosCompatContract.DmaBufRelease.Key.REQUESTED_BUFFERS,
            requestedBuffers.toString(),
        )
        setProperty(
            GosCompatContract.DmaBufRelease.Key.ALLOCATED_BUFFERS,
            allocatedBuffers.toString(),
        )
        setProperty(GosCompatContract.DmaBufRelease.Key.ITERATIONS, iterations.toString())
        setProperty(GosCompatContract.DmaBufRelease.Key.PID, pid.toString())
        setProperty(GosCompatContract.DmaBufRelease.Key.TID, tid.toString())
        setProperty(
            GosCompatContract.DmaBufRelease.Key.PROTECTED_CONTENT,
            protectedContent.toString(),
        )
        setProperty(GosCompatContract.DmaBufRelease.Key.RELEASED, released.toString())
        setProperty(GosCompatContract.DmaBufRelease.Key.HEAP_PATH, heapPath)
        setProperty(GosCompatContract.DmaBufRelease.Key.HEAP_NAME, heapName)
        setProperty(GosCompatContract.DmaBufRelease.Key.ALLOCATOR, allocator)
        setProperty(GosCompatContract.DmaBufRelease.Key.ALLOCATION, allocation)
        setProperty(GosCompatContract.DmaBufRelease.Key.ERROR, error)
    }

    companion object {
        private const val FIELD_READY = 0
        private const val FIELD_UNSUPPORTED = 1
        private const val FIELD_PROTECTED_CONTENT = 2
        private const val FIELD_ALLOCATED_BUFFERS = 3
        private const val FIELD_PID = 4
        private const val FIELD_TID = 5
        private const val FIELD_HEAP_PATH = 6
        private const val FIELD_HEAP_NAME = 7
        private const val FIELD_ALLOCATOR = 8
        private const val FIELD_ALLOCATION = 9
        private const val FIELD_ERROR = 10
        private const val FIELD_COUNT = 11

        fun fromNativeFields(
            mode: String,
            width: Int,
            height: Int,
            requestedBuffers: Int,
            iterations: Int,
            fields: Array<String>?,
        ): DmaBufReleaseResult {
            if (fields == null || fields.size != FIELD_COUNT) {
                return failed(
                    mode = mode,
                    width = width,
                    height = height,
                    requestedBuffers = requestedBuffers,
                    iterations = iterations,
                    error = "Native DMA-BUF workload returned an invalid result",
                )
            }

            return DmaBufReleaseResult(
                mode = mode,
                ready = fields[FIELD_READY].toBoolean(),
                unsupported = fields[FIELD_UNSUPPORTED].toBoolean(),
                protectedContent = fields[FIELD_PROTECTED_CONTENT].toBoolean(),
                width = width,
                height = height,
                requestedBuffers = requestedBuffers,
                allocatedBuffers = fields[FIELD_ALLOCATED_BUFFERS].toIntOrZero(),
                iterations = iterations,
                pid = fields[FIELD_PID].toIntOrZero(),
                tid = fields[FIELD_TID].toIntOrZero(),
                heapPath = fields[FIELD_HEAP_PATH],
                heapName = fields[FIELD_HEAP_NAME],
                allocator = fields[FIELD_ALLOCATOR],
                allocation = fields[FIELD_ALLOCATION],
                error = fields[FIELD_ERROR],
            )
        }

        fun fromProperties(properties: Properties): DmaBufReleaseResult = DmaBufReleaseResult(
            mode = properties.getProperty(GosCompatContract.DmaBufRelease.Key.MODE, ""),
            ready = properties.getProperty(
                GosCompatContract.DmaBufRelease.Key.READY,
                "",
            ).toBoolean(),
            unsupported = properties.getProperty(
                GosCompatContract.DmaBufRelease.Key.UNSUPPORTED,
                "",
            ).toBoolean(),
            protectedContent = properties.getProperty(
                GosCompatContract.DmaBufRelease.Key.PROTECTED_CONTENT,
                "",
            ).toBoolean(),
            width = properties.getProperty(
                GosCompatContract.DmaBufRelease.Key.WIDTH,
            ).toIntOrZero(),
            height = properties.getProperty(
                GosCompatContract.DmaBufRelease.Key.HEIGHT,
            ).toIntOrZero(),
            requestedBuffers = properties.getProperty(
                GosCompatContract.DmaBufRelease.Key.REQUESTED_BUFFERS,
            ).toIntOrZero(),
            allocatedBuffers = properties.getProperty(
                GosCompatContract.DmaBufRelease.Key.ALLOCATED_BUFFERS,
            ).toIntOrZero(),
            iterations = properties.getProperty(
                GosCompatContract.DmaBufRelease.Key.ITERATIONS,
            ).toIntOrZero(),
            pid = properties.getProperty(
                GosCompatContract.DmaBufRelease.Key.PID,
            ).toIntOrZero(),
            tid = properties.getProperty(
                GosCompatContract.DmaBufRelease.Key.TID,
            ).toIntOrZero(),
            heapPath = properties.getProperty(
                GosCompatContract.DmaBufRelease.Key.HEAP_PATH,
                "",
            ),
            heapName = properties.getProperty(
                GosCompatContract.DmaBufRelease.Key.HEAP_NAME,
                "",
            ),
            allocator = properties.getProperty(GosCompatContract.DmaBufRelease.Key.ALLOCATOR, ""),
            allocation = properties.getProperty(
                GosCompatContract.DmaBufRelease.Key.ALLOCATION,
                "",
            ),
            error = properties.getProperty(GosCompatContract.DmaBufRelease.Key.ERROR, ""),
            released = properties.getProperty(
                GosCompatContract.DmaBufRelease.Key.RELEASED,
                "",
            ).toBoolean(),
        )

        fun failed(
            mode: String,
            width: Int,
            height: Int,
            requestedBuffers: Int,
            iterations: Int,
            error: String,
        ): DmaBufReleaseResult = DmaBufReleaseResult(
            mode = mode,
            ready = false,
            unsupported = false,
            protectedContent = false,
            width = width,
            height = height,
            requestedBuffers = requestedBuffers,
            allocatedBuffers = 0,
            iterations = iterations,
            pid = 0,
            tid = 0,
            heapPath = "",
            heapName = "",
            allocator = "",
            allocation = "",
            error = error,
        )

        fun failedFromThrowable(
            mode: String,
            width: Int,
            height: Int,
            requestedBuffers: Int,
            iterations: Int,
            throwable: Throwable,
            errorPrefix: String = "",
        ): DmaBufReleaseResult = failed(
            mode = mode,
            width = width,
            height = height,
            requestedBuffers = requestedBuffers,
            iterations = iterations,
            error = throwableError(errorPrefix, throwable),
        )

        private fun throwableError(errorPrefix: String, throwable: Throwable): String =
            buildString {
                if (errorPrefix.isNotEmpty()) {
                    append(errorPrefix)
                    append(": ")
                }
                append(throwable.javaClass.simpleName)
                append(": ")
                append(throwable.message)
            }

        private fun String?.toIntOrZero(): Int = this?.toIntOrNull() ?: 0
    }
}
