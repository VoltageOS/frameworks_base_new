package app.grapheneos.goscompat.checks.dmabuf

import app.grapheneos.goscompat.checks.GosCompatContract

object DmaBufReleaseRunner {
    private var nativeLoaded = false

    @Synchronized
    fun start(
        mode: String,
        width: Int,
        height: Int,
        bufferCount: Int,
        iterations: Int,
        heapName: String?,
    ): DmaBufReleaseResult {
        release()

        return try {
            ensureNativeLoaded()
            val fields = when (mode) {
                GosCompatContract.DmaBufRelease.Mode.SECURE_CHUNK_HEAP_DIRECT ->
                    nativeStartWorkload(
                        heapName,
                        width,
                        height,
                        bufferCount,
                    )
                GosCompatContract.DmaBufRelease.Mode.PROTECTED_EGL ->
                    nativeStartProtectedEglWorkload(
                        width,
                        height,
                        iterations,
                    )
                else -> return DmaBufReleaseResult.failed(
                    mode = mode,
                    width = width,
                    height = height,
                    requestedBuffers = bufferCount,
                    iterations = iterations,
                    error = "Unsupported DMA-BUF workload mode: $mode",
                )
            }
            DmaBufReleaseResult.fromNativeFields(
                mode = mode,
                width = width,
                height = height,
                requestedBuffers = bufferCount,
                iterations = iterations,
                fields = fields,
            )
        } catch (e: RuntimeException) {
            DmaBufReleaseResult.failedFromThrowable(mode, width, height, bufferCount, iterations, e)
        } catch (e: UnsatisfiedLinkError) {
            DmaBufReleaseResult.failedFromThrowable(mode, width, height, bufferCount, iterations, e)
        }
    }

    @Synchronized
    fun release() {
        if (nativeLoaded) {
            nativeReleaseWorkload()
        }
    }

    private fun ensureNativeLoaded() {
        if (!nativeLoaded) {
            System.loadLibrary("goscompat_dmabuf_release_jni")
            nativeLoaded = true
        }
    }

    private external fun nativeStartWorkload(
        heapName: String?,
        width: Int,
        height: Int,
        bufferCount: Int,
    ): Array<String>

    private external fun nativeStartProtectedEglWorkload(
        width: Int,
        height: Int,
        iterations: Int,
    ): Array<String>

    private external fun nativeReleaseWorkload()
}
