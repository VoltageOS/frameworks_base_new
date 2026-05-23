package app.grapheneos.goscompat.dmabufrelease.tests;

import static com.google.common.truth.Truth.assertWithMessage;

import androidx.test.uiautomator.UiDevice;

import app.grapheneos.goscompat.checks.GosCompatContract;

import java.io.StringReader;
import java.util.Properties;
import java.util.UUID;

final class ReleaseTestSupport {
    private static final long READY_TIMEOUT_MILLIS = 20_000;
    private static final long POST_PROCESS_STOP_WAIT_MILLIS = 5_000;
    private static final long POST_MANUAL_RELEASE_WAIT_MILLIS = 1_000;
    private static final long POLL_INTERVAL_MILLIS = 200;

    private final UiDevice mDevice;

    ReleaseTestSupport(UiDevice device) {
        mDevice = device;
    }

    void forceStopHelperApp() throws Exception {
        mDevice.executeShellCommand("am force-stop " + GosCompatContract.App.PACKAGE_NAME);
    }

    DmaBufResult runReleaseAttempt(Workload workload, ReleaseAction releaseAction)
            throws Exception {
        return runReleaseAttempt(workload, releaseAction, HelperProcessMode.CLEAN_START);
    }

    DmaBufResult runReleaseAttempt(Workload workload, ReleaseAction releaseAction,
            HelperProcessMode helperProcessMode) throws Exception {
        int iterations = GosCompatContract.DmaBufRelease.DEFAULT_ITERATIONS;
        String token = UUID.randomUUID().toString();
        String attemptLabel = workload.label() + " " + releaseAction.label;

        if (helperProcessMode == HelperProcessMode.CLEAN_START) {
            forceStopHelperApp();
        }

        StringBuilder command = new StringBuilder("am start -n ")
                .append(GosCompatContract.DmaBufRelease.ACTIVITY)
                .append(" --es ").append(GosCompatContract.DmaBufRelease.Extra.TOKEN)
                .append(" ").append(token)
                .append(" --es ").append(GosCompatContract.DmaBufRelease.Extra.MODE)
                .append(" ").append(workload.mode())
                .append(" --ei ").append(GosCompatContract.DmaBufRelease.Extra.WIDTH)
                .append(" ").append(workload.width())
                .append(" --ei ").append(GosCompatContract.DmaBufRelease.Extra.HEIGHT)
                .append(" ").append(workload.height())
                .append(" --ei ").append(GosCompatContract.DmaBufRelease.Extra.BUFFER_COUNT)
                .append(" ").append(workload.bufferCount())
                .append(" --ei ").append(GosCompatContract.DmaBufRelease.Extra.ITERATIONS)
                .append(" ").append(iterations)
                .append(" --ez ").append(GosCompatContract.DmaBufRelease.Extra.RELEASE_AFTER_READY)
                .append(" ").append(releaseAction == ReleaseAction.MANUAL_RELEASE);
        if (workload.heapName() != null) {
            command.append(" --es ").append(GosCompatContract.DmaBufRelease.Extra.HEAP_NAME)
                    .append(" ").append(workload.heapName());
        }

        String output = mDevice.executeShellCommand(command.toString());
        assertWithMessage(attemptLabel + ": " + output).that(output).doesNotContain("Error");

        DmaBufResult result = waitForResult(token, workload.mode());

        if (releaseAction == ReleaseAction.MANUAL_RELEASE) {
            java.lang.Thread.sleep(POST_MANUAL_RELEASE_WAIT_MILLIS);
        } else {
            mDevice.executeShellCommand(releaseAction.shellCommand);
            waitForDmaBufProcessExit(result.pid(), attemptLabel);
            java.lang.Thread.sleep(POST_PROCESS_STOP_WAIT_MILLIS);
        }
        mDevice.executeShellCommand("true");
        return result;
    }

    void assertDirectSecureChunkHeapResult(DmaBufResult result, Workload workload,
            ReleaseAction releaseAction) {
        String message = workload.label() + " " + releaseAction.label + ": " + result;
        assertReady(result, workload, releaseAction);
        assertWithMessage(message).that(result.allocatedBuffers())
                .isEqualTo(result.requestedBuffers());
        assertWithMessage(message).that(result.heapPath())
                .contains("/dev/dma_heap/" + workload.heapName());
        assertWithMessage(message).that(result.heapName()).isEqualTo(workload.heapName());
        assertWithMessage(message).that(result.allocator()).isEqualTo("dma_heap");
    }

    void assertProtectedEglResult(DmaBufResult result, Workload workload,
            ReleaseAction releaseAction) {
        String message = workload.label() + " " + releaseAction.label + ": " + result;
        assertReady(result, workload, releaseAction);
        assertWithMessage(message).that(result.allocatedBuffers())
                .isEqualTo(GosCompatContract.DmaBufRelease.PROTECTED_EGL_RESOURCE_COUNT);
        assertWithMessage(message).that(result.heapName()).isEqualTo("vendor-selected");
        assertWithMessage(message).that(result.allocator())
                .isEqualTo("EGL_EXT_protected_content");
    }

    void assertReleaseResult(DmaBufResult result, Workload workload,
            ReleaseAction releaseAction) {
        if (GosCompatContract.DmaBufRelease.Mode.SECURE_CHUNK_HEAP_DIRECT
                .equals(workload.mode())) {
            assertDirectSecureChunkHeapResult(result, workload, releaseAction);
            return;
        }
        if (GosCompatContract.DmaBufRelease.Mode.PROTECTED_EGL.equals(workload.mode())) {
            assertProtectedEglResult(result, workload, releaseAction);
            return;
        }
        throw new AssertionError("Unsupported DMA-BUF workload mode: " + workload.mode());
    }

    private void assertReady(DmaBufResult result, Workload workload,
            ReleaseAction releaseAction) {
        String message = workload.label() + " " + releaseAction.label + ": " + result;
        assertWithMessage(message).that(result.mode()).isEqualTo(workload.mode());
        assertWithMessage(message).that(result.unsupported()).isFalse();
        assertWithMessage(message).that(result.ready()).isTrue();
        assertWithMessage(message).that(result.protectedContent()).isTrue();
        assertWithMessage(message).that(result.released())
                .isEqualTo(releaseAction == ReleaseAction.MANUAL_RELEASE);
        assertWithMessage(message).that(result.width()).isAtLeast(1);
        assertWithMessage(message).that(result.height()).isAtLeast(1);
        assertWithMessage(message).that(result.requestedBuffers()).isAtLeast(1);
        assertWithMessage(message).that(result.allocatedBuffers()).isAtLeast(1);
        assertWithMessage(message).that(result.pid()).isGreaterThan(0);
        assertWithMessage(message).that(result.tid()).isGreaterThan(0);
    }

    private DmaBufResult waitForResult(String token, String mode) throws Exception {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            DmaBufResult result = getStoredResult(token);
            if (result != null) {
                return result;
            }
            java.lang.Thread.sleep(POLL_INTERVAL_MILLIS);
        }

        throw new AssertionError("Timed out waiting for DMA-BUF release workload result in "
                + mode + " mode");
    }

    private DmaBufResult getStoredResult(String token) throws Exception {
        String output = mDevice.executeShellCommand("run-as " + GosCompatContract.App.PACKAGE_NAME
                + " cat files/" + GosCompatContract.DmaBufRelease.RESULT_FILE
                + " 2>/dev/null");
        if (output == null || output.trim().isEmpty()) {
            return null;
        }

        Properties properties = new Properties();
        try {
            properties.load(new StringReader(output));
            if (!token.equals(properties.getProperty(
                    GosCompatContract.DmaBufRelease.Extra.TOKEN))) {
                return null;
            }

            return new DmaBufResult(
                    properties.getProperty(GosCompatContract.DmaBufRelease.Key.MODE, ""),
                    getBoolean(properties, GosCompatContract.DmaBufRelease.Key.READY),
                    getBoolean(properties, GosCompatContract.DmaBufRelease.Key.UNSUPPORTED),
                    getBoolean(properties,
                            GosCompatContract.DmaBufRelease.Key.PROTECTED_CONTENT),
                    getInt(properties, GosCompatContract.DmaBufRelease.Key.WIDTH),
                    getInt(properties, GosCompatContract.DmaBufRelease.Key.HEIGHT),
                    getInt(properties,
                            GosCompatContract.DmaBufRelease.Key.REQUESTED_BUFFERS),
                    getInt(properties,
                            GosCompatContract.DmaBufRelease.Key.ALLOCATED_BUFFERS),
                    getInt(properties, GosCompatContract.DmaBufRelease.Key.ITERATIONS),
                    getInt(properties, GosCompatContract.DmaBufRelease.Key.PID),
                    getInt(properties, GosCompatContract.DmaBufRelease.Key.TID),
                    getBoolean(properties, GosCompatContract.DmaBufRelease.Key.RELEASED),
                    properties.getProperty(GosCompatContract.DmaBufRelease.Key.HEAP_PATH, ""),
                    properties.getProperty(GosCompatContract.DmaBufRelease.Key.HEAP_NAME, ""),
                    properties.getProperty(GosCompatContract.DmaBufRelease.Key.ALLOCATOR, ""),
                    properties.getProperty(GosCompatContract.DmaBufRelease.Key.ALLOCATION, ""),
                    properties.getProperty(GosCompatContract.DmaBufRelease.Key.ERROR, ""));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void waitForDmaBufProcessExit(int pid, String attemptLabel) throws Exception {
        long deadline = System.currentTimeMillis() + POST_PROCESS_STOP_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            String pidofOutput = mDevice.executeShellCommand("pidof "
                    + GosCompatContract.DmaBufRelease.PROCESS + " || true");
            if (!containsPid(pidofOutput, pid)) {
                return;
            }
            java.lang.Thread.sleep(POLL_INTERVAL_MILLIS);
        }

        throw new AssertionError(attemptLabel + ": DMA-BUF release process stayed alive");
    }

    private static boolean containsPid(String pidofOutput, int pid) {
        if (pidofOutput == null) {
            return false;
        }
        String expected = Integer.toString(pid);
        for (String candidate : pidofOutput.trim().split("\\s+")) {
            if (expected.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean getBoolean(Properties properties, String key) {
        return Boolean.parseBoolean(properties.getProperty(key));
    }

    private static int getInt(Properties properties, String key) {
        return Integer.parseInt(properties.getProperty(key, "0"));
    }

    record Workload(String label, String mode, String heapName, int width, int height,
            int bufferCount) {
        static Workload directVframeSecureMultiChunk() {
            return new Workload(
                    "vframe-secure multi chunk",
                    GosCompatContract.DmaBufRelease.Mode.SECURE_CHUNK_HEAP_DIRECT,
                    GosCompatContract.DmaBufRelease.Heap.VFRAME_SECURE,
                    GosCompatContract.DmaBufRelease.VFRAME_SECURE_DIRECT_WIDTH,
                    GosCompatContract.DmaBufRelease.VFRAME_SECURE_DIRECT_HEIGHT,
                    GosCompatContract.DmaBufRelease.VFRAME_SECURE_DIRECT_COUNT);
        }

        static Workload directVstreamSecureMultiChunk() {
            return new Workload(
                    "vstream-secure multi chunk",
                    GosCompatContract.DmaBufRelease.Mode.SECURE_CHUNK_HEAP_DIRECT,
                    GosCompatContract.DmaBufRelease.Heap.VSTREAM_SECURE,
                    GosCompatContract.DmaBufRelease.VSTREAM_SECURE_DIRECT_WIDTH,
                    GosCompatContract.DmaBufRelease.VSTREAM_SECURE_DIRECT_HEIGHT,
                    GosCompatContract.DmaBufRelease.VSTREAM_SECURE_DIRECT_COUNT);
        }

        static Workload directVframeSecureOneChunk() {
            return new Workload(
                    "vframe-secure one chunk",
                    GosCompatContract.DmaBufRelease.Mode.SECURE_CHUNK_HEAP_DIRECT,
                    GosCompatContract.DmaBufRelease.Heap.VFRAME_SECURE,
                    GosCompatContract.DmaBufRelease.SECURE_CHUNK_HEAP_ONE_CHUNK_WIDTH,
                    GosCompatContract.DmaBufRelease.SECURE_CHUNK_HEAP_ONE_CHUNK_HEIGHT,
                    GosCompatContract.DmaBufRelease.SECURE_CHUNK_HEAP_ONE_CHUNK_COUNT);
        }

        static Workload directVstreamSecureOneChunk() {
            return new Workload(
                    "vstream-secure one chunk",
                    GosCompatContract.DmaBufRelease.Mode.SECURE_CHUNK_HEAP_DIRECT,
                    GosCompatContract.DmaBufRelease.Heap.VSTREAM_SECURE,
                    GosCompatContract.DmaBufRelease.SECURE_CHUNK_HEAP_ONE_CHUNK_WIDTH,
                    GosCompatContract.DmaBufRelease.SECURE_CHUNK_HEAP_ONE_CHUNK_HEIGHT,
                    GosCompatContract.DmaBufRelease.SECURE_CHUNK_HEAP_ONE_CHUNK_COUNT);
        }

        static Workload protectedEgl() {
            return new Workload(
                    "protected EGL",
                    GosCompatContract.DmaBufRelease.Mode.PROTECTED_EGL,
                    null,
                    GosCompatContract.DmaBufRelease.PROTECTED_EGL_WIDTH,
                    GosCompatContract.DmaBufRelease.PROTECTED_EGL_HEIGHT,
                    GosCompatContract.DmaBufRelease.PROTECTED_EGL_RESOURCE_COUNT);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    record DmaBufResult(String mode, boolean ready, boolean unsupported,
            boolean protectedContent, int width, int height, int requestedBuffers,
            int allocatedBuffers, int iterations, int pid, int tid, boolean released,
            String heapPath, String heapName, String allocator, String allocation,
            String error) {
        @Override
        public String toString() {
            String resourceText = GosCompatContract.DmaBufRelease.Mode.SECURE_CHUNK_HEAP_DIRECT
                    .equals(mode())
                    ? ", fds=" + allocatedBuffers() + "/" + requestedBuffers()
                    : ", resources=" + allocatedBuffers();
            return "mode=" + mode()
                    + ", ready=" + ready()
                    + ", unsupported=" + unsupported()
                    + ", protectedContent=" + protectedContent()
                    + ", size=" + width() + "x" + height()
                    + resourceText
                    + ", iterations=" + iterations()
                    + ", pid=" + pid()
                    + ", tid=" + tid()
                    + ", released=" + released()
                    + ", heap=" + heapPath()
                    + ", heapName=" + heapName()
                    + ", allocator=" + allocator()
                    + ", allocation=" + allocation()
                    + ", error=" + error();
        }
    }

    enum ReleaseAction {
        FORCE_STOP("force stop", "am force-stop " + GosCompatContract.App.PACKAGE_NAME),
        STOP_APP("stop app", "am stop-app " + GosCompatContract.App.PACKAGE_NAME),
        MANUAL_RELEASE("manual release", "");

        final String label;
        final String shellCommand;

        ReleaseAction(String label, String shellCommand) {
            this.label = label;
            this.shellCommand = shellCommand;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    enum HelperProcessMode {
        CLEAN_START,
        REUSE_PROCESS,
    }
}
