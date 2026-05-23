package app.grapheneos.goscompat.checks.dmabuf

import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.grapheneos.goscompat.checks.GosCompatContract
import app.grapheneos.goscompat.checks.ResultSection
import java.util.UUID
import java.util.concurrent.CountDownLatch

@Composable
internal fun DmaBufReleasePanel(activity: Activity) {
    val runs = remember {
        DMABUF_UI_OPTIONS.map { option ->
            DmaBufUiRun(
                option = option,
                result = mutableStateOf<DmaBufReleaseResult?>(null),
                running = mutableStateOf(false),
            )
        }
    }
    val allRunning = remember { mutableStateOf(false) }

    DmaBufReleaseCard(
        runs = runs,
        allRunning = allRunning.value,
        onRun = { run -> startDmaBufRelease(activity, run) },
        onRunAll = {
            startAllDmaBufRelease(
                activity = activity,
                runs = runs,
                allRunning = allRunning,
            )
        },
    )
}

private data class DmaBufUiOption(
    val title: String,
    val detailsTitle: String,
    val mode: String,
    val heapName: String?,
    val width: Int,
    val height: Int,
    val bufferCount: Int,
)

private data class DmaBufUiRun(
    val option: DmaBufUiOption,
    val result: MutableState<DmaBufReleaseResult?>,
    val running: MutableState<Boolean>,
)

private val DMABUF_UI_OPTIONS = listOf(
    DmaBufUiOption(
        title = "Direct vframe-secure multi chunk",
        detailsTitle = "Direct vframe-secure multi chunk details",
        mode = GosCompatContract.DmaBufRelease.Mode.SECURE_CHUNK_HEAP_DIRECT,
        heapName = GosCompatContract.DmaBufRelease.Heap.VFRAME_SECURE,
        width = GosCompatContract.DmaBufRelease.VFRAME_SECURE_DIRECT_WIDTH,
        height = GosCompatContract.DmaBufRelease.VFRAME_SECURE_DIRECT_HEIGHT,
        bufferCount = GosCompatContract.DmaBufRelease.VFRAME_SECURE_DIRECT_COUNT,
    ),
    DmaBufUiOption(
        title = "Direct vstream-secure multi chunk",
        detailsTitle = "Direct vstream-secure multi chunk details",
        mode = GosCompatContract.DmaBufRelease.Mode.SECURE_CHUNK_HEAP_DIRECT,
        heapName = GosCompatContract.DmaBufRelease.Heap.VSTREAM_SECURE,
        width = GosCompatContract.DmaBufRelease.VSTREAM_SECURE_DIRECT_WIDTH,
        height = GosCompatContract.DmaBufRelease.VSTREAM_SECURE_DIRECT_HEIGHT,
        bufferCount = GosCompatContract.DmaBufRelease.VSTREAM_SECURE_DIRECT_COUNT,
    ),
    DmaBufUiOption(
        title = "Direct vframe-secure one chunk",
        detailsTitle = "Direct vframe-secure one chunk details",
        mode = GosCompatContract.DmaBufRelease.Mode.SECURE_CHUNK_HEAP_DIRECT,
        heapName = GosCompatContract.DmaBufRelease.Heap.VFRAME_SECURE,
        width = GosCompatContract.DmaBufRelease.SECURE_CHUNK_HEAP_ONE_CHUNK_WIDTH,
        height = GosCompatContract.DmaBufRelease.SECURE_CHUNK_HEAP_ONE_CHUNK_HEIGHT,
        bufferCount = GosCompatContract.DmaBufRelease.SECURE_CHUNK_HEAP_ONE_CHUNK_COUNT,
    ),
    DmaBufUiOption(
        title = "Direct vstream-secure one chunk",
        detailsTitle = "Direct vstream-secure one chunk details",
        mode = GosCompatContract.DmaBufRelease.Mode.SECURE_CHUNK_HEAP_DIRECT,
        heapName = GosCompatContract.DmaBufRelease.Heap.VSTREAM_SECURE,
        width = GosCompatContract.DmaBufRelease.SECURE_CHUNK_HEAP_ONE_CHUNK_WIDTH,
        height = GosCompatContract.DmaBufRelease.SECURE_CHUNK_HEAP_ONE_CHUNK_HEIGHT,
        bufferCount = GosCompatContract.DmaBufRelease.SECURE_CHUNK_HEAP_ONE_CHUNK_COUNT,
    ),
    DmaBufUiOption(
        title = "Protected EGL resources",
        detailsTitle = "Protected EGL details",
        mode = GosCompatContract.DmaBufRelease.Mode.PROTECTED_EGL,
        heapName = null,
        width = GosCompatContract.DmaBufRelease.PROTECTED_EGL_WIDTH,
        height = GosCompatContract.DmaBufRelease.PROTECTED_EGL_HEIGHT,
        bufferCount = GosCompatContract.DmaBufRelease.PROTECTED_EGL_RESOURCE_COUNT,
    ),
)

private fun startDmaBufRelease(
    activity: Activity,
    run: DmaBufUiRun,
) {
    val result = run.result
    val running = run.running
    if (running.value) {
        return
    }
    running.value = true
    result.value = null

    Thread {
        val checkResult = runDmaBufReleaseWorkload(activity, run.option)
        activity.runOnUiThread {
            result.value = checkResult
            running.value = false
        }
    }.start()
}

private fun startAllDmaBufRelease(
    activity: Activity,
    runs: List<DmaBufUiRun>,
    allRunning: MutableState<Boolean>,
) {
    if (allRunning.value || runs.any { it.running.value }) {
        return
    }
    allRunning.value = true

    Thread {
        try {
            runs.forEach { run ->
                activity.runOnUiThread {
                    run.result.value = null
                    run.running.value = true
                }
                val checkResult = runDmaBufReleaseWorkload(
                    activity = activity,
                    option = run.option,
                )
                activity.runOnUiThread {
                    run.result.value = checkResult
                    run.running.value = false
                }
            }
        } finally {
            activity.runOnUiThread {
                runs.forEach { it.running.value = false }
                allRunning.value = false
            }
        }
    }.start()
}

private fun runDmaBufReleaseWorkload(
    activity: Activity,
    option: DmaBufUiOption,
): DmaBufReleaseResult {
    val width = option.width
    val height = option.height
    val bufferCount = option.bufferCount
    val iterations = GosCompatContract.DmaBufRelease.DEFAULT_ITERATIONS
    val token = UUID.randomUUID().toString()

    return try {
        activity.runOnUiThreadBlocking {
            DmaBufReleaseProgressStore.clear(activity)
            val intent = Intent(activity, DmaBufReleaseManualActivity::class.java)
                .putExtra(GosCompatContract.DmaBufRelease.Extra.TOKEN, token)
                .putExtra(GosCompatContract.DmaBufRelease.Extra.MODE, option.mode)
                .putExtra(GosCompatContract.DmaBufRelease.Extra.WIDTH, width)
                .putExtra(GosCompatContract.DmaBufRelease.Extra.HEIGHT, height)
                .putExtra(GosCompatContract.DmaBufRelease.Extra.BUFFER_COUNT, bufferCount)
                .putExtra(GosCompatContract.DmaBufRelease.Extra.ITERATIONS, iterations)
                .putExtra(
                    GosCompatContract.DmaBufRelease.Extra.RELEASE_AFTER_READY,
                    true,
                )
            if (option.heapName != null) {
                intent.putExtra(
                    GosCompatContract.DmaBufRelease.Extra.HEAP_NAME,
                    option.heapName,
                )
            }
            activity.startActivity(intent)
        }

        val deadline = SystemClock.elapsedRealtime() + DMABUF_UI_RESULT_TIMEOUT_MILLIS
        var latest: DmaBufReleaseResult? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = DmaBufReleaseProgressStore.load(activity, token)
            if (latest != null) {
                break
            }
            SystemClock.sleep(DMABUF_UI_POLL_INTERVAL_MILLIS)
        }

        latest ?: DmaBufReleaseResult.failed(
            mode = option.mode,
            width = width,
            height = height,
            requestedBuffers = bufferCount,
            iterations = iterations,
            error = "Timed out waiting for DMA-BUF workload readiness",
        )
    } catch (e: Exception) {
        DmaBufReleaseResult.failedFromThrowable(
            mode = option.mode,
            width = width,
            height = height,
            requestedBuffers = bufferCount,
            iterations = iterations,
            throwable = e,
            errorPrefix = "Failed to start DMA-BUF workload",
        )
    }
}

private fun Activity.runOnUiThreadBlocking(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        action()
        return
    }

    val latch = CountDownLatch(1)
    var failure: Throwable? = null
    runOnUiThread {
        try {
            action()
        } catch (e: Throwable) {
            failure = e
        } finally {
            latch.countDown()
        }
    }
    latch.await()
    failure?.let { throw it }
}

@Composable
private fun DmaBufReleaseCard(
    runs: List<DmaBufUiRun>,
    allRunning: Boolean,
    onRun: (DmaBufUiRun) -> Unit,
    onRunAll: () -> Unit,
) {
    val anyRunning = allRunning || runs.any { it.running.value }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "DMA-BUF release",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onRunAll, enabled = !anyRunning) {
                    Text("Run all")
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (allRunning) "Running all options" else "Ready",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            runs.forEach { run ->
                val result = run.result.value
                val running = run.running.value
                DmaBufReleaseControl(
                    title = run.option.title,
                    result = result,
                    running = running,
                    enabled = !allRunning && !running,
                    onRun = { onRun(run) },
                )
                ResultSection(run.option.detailsTitle, result?.details.orEmpty())
            }
        }
    }
}

@Composable
private fun DmaBufReleaseControl(
    title: String,
    result: DmaBufReleaseResult?,
    running: Boolean,
    enabled: Boolean,
    onRun: () -> Unit,
) {
    val status = when {
        running -> "Running"
        result != null -> result.statusText
        else -> "Not run"
    }
    val statusColor = when {
        running -> MaterialTheme.colorScheme.tertiary
        result?.ready == true -> MaterialTheme.colorScheme.primary
        result != null -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = status,
                modifier = Modifier
                    .background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = statusColor,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Button(onClick = onRun, enabled = enabled) {
                Text("Run")
            }
        }

        Text(
            text = result?.summary ?: "DMA-BUF workload has not been run.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private const val DMABUF_UI_RESULT_TIMEOUT_MILLIS = 15_000L
private const val DMABUF_UI_POLL_INTERVAL_MILLIS = 200L
