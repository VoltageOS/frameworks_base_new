package app.grapheneos.goscompat.dmabufrelease.tests;

import android.app.Instrumentation;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class ProtectedEglReleaseTests {
    private final ReleaseTestSupport.Workload mWorkload =
            ReleaseTestSupport.Workload.protectedEgl();
    private ReleaseTestSupport mSupport;

    @Before
    public void setUp() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        UiDevice device = UiDevice.getInstance(instrumentation);
        mSupport = new ReleaseTestSupport(device);
    }

    @After
    public void tearDown() throws Exception {
        if (mSupport != null) {
            mSupport.forceStopHelperApp();
        }
    }

    @Test
    public void protectedEglResourcesCanBeForceStoppedAfterReady() throws Exception {
        run(ReleaseTestSupport.ReleaseAction.FORCE_STOP);
    }

    @Test
    public void protectedEglResourcesCanBeStoppedAfterReady() throws Exception {
        run(ReleaseTestSupport.ReleaseAction.STOP_APP);
    }

    @Test
    public void protectedEglResourcesCanBeManuallyReleasedAfterReady() throws Exception {
        run(ReleaseTestSupport.ReleaseAction.MANUAL_RELEASE);
    }

    private void run(ReleaseTestSupport.ReleaseAction releaseAction) throws Exception {
        ReleaseTestSupport.DmaBufResult result =
                mSupport.runReleaseAttempt(mWorkload, releaseAction);
        mSupport.assertProtectedEglResult(result, mWorkload, releaseAction);
    }
}
