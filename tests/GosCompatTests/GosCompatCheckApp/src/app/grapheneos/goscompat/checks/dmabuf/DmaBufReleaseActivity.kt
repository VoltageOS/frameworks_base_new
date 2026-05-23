package app.grapheneos.goscompat.checks.dmabuf

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import app.grapheneos.goscompat.checks.GosCompatContract

open class DmaBufReleaseActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val token = intent.getStringExtra(GosCompatContract.DmaBufRelease.Extra.TOKEN)
        val mode = intent.getStringExtra(GosCompatContract.DmaBufRelease.Extra.MODE)
            ?: GosCompatContract.DmaBufRelease.Mode.SECURE_CHUNK_HEAP_DIRECT
        val heapName = intent.getStringExtra(GosCompatContract.DmaBufRelease.Extra.HEAP_NAME)
        val width = intent.getIntExtra(
            GosCompatContract.DmaBufRelease.Extra.WIDTH,
            GosCompatContract.DmaBufRelease.VFRAME_SECURE_DIRECT_WIDTH,
        )
        val height = intent.getIntExtra(
            GosCompatContract.DmaBufRelease.Extra.HEIGHT,
            GosCompatContract.DmaBufRelease.VFRAME_SECURE_DIRECT_HEIGHT,
        )
        val bufferCount = intent.getIntExtra(
            GosCompatContract.DmaBufRelease.Extra.BUFFER_COUNT,
            GosCompatContract.DmaBufRelease.VFRAME_SECURE_DIRECT_COUNT,
        )
        val iterations = intent.getIntExtra(
            GosCompatContract.DmaBufRelease.Extra.ITERATIONS,
            GosCompatContract.DmaBufRelease.DEFAULT_ITERATIONS,
        )
        val releaseAfterReady = intent.getBooleanExtra(
            GosCompatContract.DmaBufRelease.Extra.RELEASE_AFTER_READY,
            false,
        )

        DmaBufReleaseProgressStore.clear(this)

        Thread({
            var result = DmaBufReleaseRunner.start(
                mode = mode,
                width = width,
                height = height,
                bufferCount = bufferCount,
                iterations = iterations,
                heapName = heapName,
            )
            if (result.ready && releaseAfterReady) {
                result = try {
                    DmaBufReleaseRunner.release()
                    result.copy(released = true)
                } catch (e: RuntimeException) {
                    result.copy(
                        ready = false,
                        error = "Manual DMA-BUF release failed: " +
                            "${e.javaClass.simpleName}: ${e.message}",
                    )
                }
            }
            DmaBufReleaseProgressStore.save(applicationContext, token, result)
            runOnUiThread { finish() }
            if (!result.ready || releaseAfterReady) {
                return@Thread
            }

            // Keep this process and its native buffer references live until shell stops it.
            while (true) {
                SystemClock.sleep(1_000)
            }
        }, "dmabuf-release-workload").start()
    }
}

class DmaBufReleaseManualActivity : DmaBufReleaseActivity()
