package app.grapheneos.goscompat.dmabufrelease.tests;

import android.app.Instrumentation;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Certain devices like shiba can't reproduce the panic immediately due to different GPU memory 
 * management models. Multiple runs are required for the panic to trigger from shiba's 
 * mali-mem-purge task
 */
public final class DmaBufReleaseSequenceTests {
    private static final int REPEAT_COUNT = 8;
    private static final ReleaseTestSupport.Workload[] RUN_ALL_SEQUENCE = {
            ReleaseTestSupport.Workload.directVframeSecureMultiChunk(),
            ReleaseTestSupport.Workload.directVstreamSecureMultiChunk(),
            ReleaseTestSupport.Workload.directVframeSecureOneChunk(),
            ReleaseTestSupport.Workload.directVstreamSecureOneChunk(),
            ReleaseTestSupport.Workload.protectedEgl(),
    };

    private ReleaseTestSupport mSupport;

    @Before
    public void setUp() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        UiDevice device = UiDevice.getInstance(instrumentation);
        mSupport = new ReleaseTestSupport(device);
        mSupport.forceStopHelperApp();
    }

    @After
    public void tearDown() throws Exception {
        if (mSupport != null) {
            mSupport.forceStopHelperApp();
        }
    }

    @Test
    public void manualReleaseRunAllSequenceCanBeRepeatedInOneProcess() throws Exception {
        for (int pass = 0; pass < REPEAT_COUNT; pass++) {
            for (ReleaseTestSupport.Workload workload : RUN_ALL_SEQUENCE) {
                try {
                    ReleaseTestSupport.DmaBufResult result = mSupport.runReleaseAttempt(
                            workload,
                            ReleaseTestSupport.ReleaseAction.MANUAL_RELEASE,
                            ReleaseTestSupport.HelperProcessMode.REUSE_PROCESS);
                    mSupport.assertReleaseResult(
                            result,
                            workload,
                            ReleaseTestSupport.ReleaseAction.MANUAL_RELEASE);
                } catch (AssertionError e) {
                    throw new AssertionError("Run all sequence pass " + (pass + 1) + "/"
                            + REPEAT_COUNT + ", workload=" + workload, e);
                }
            }
        }
    }
}
