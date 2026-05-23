package app.grapheneos.goscompat.dmabufrelease.tests;

import android.app.Instrumentation;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.List;

@RunWith(Parameterized.class)
public final class SecureChunkHeapReleaseTests {
    private final ReleaseTestSupport.Workload mWorkload;
    private final ReleaseTestSupport.ReleaseAction mReleaseAction;
    private ReleaseTestSupport mSupport;

    @Parameterized.Parameters(name = "{0}, {1}")
    public static List<Object[]> parameters() {
        return Arrays.asList(new Object[][] {
                {
                        ReleaseTestSupport.Workload.directVframeSecureMultiChunk(),
                        ReleaseTestSupport.ReleaseAction.FORCE_STOP,
                },
                {
                        ReleaseTestSupport.Workload.directVframeSecureMultiChunk(),
                        ReleaseTestSupport.ReleaseAction.MANUAL_RELEASE,
                },
                {
                        ReleaseTestSupport.Workload.directVstreamSecureMultiChunk(),
                        ReleaseTestSupport.ReleaseAction.FORCE_STOP,
                },
                {
                        ReleaseTestSupport.Workload.directVstreamSecureMultiChunk(),
                        ReleaseTestSupport.ReleaseAction.MANUAL_RELEASE,
                },
                {
                        ReleaseTestSupport.Workload.directVframeSecureOneChunk(),
                        ReleaseTestSupport.ReleaseAction.FORCE_STOP,
                },
                {
                        ReleaseTestSupport.Workload.directVframeSecureOneChunk(),
                        ReleaseTestSupport.ReleaseAction.MANUAL_RELEASE,
                },
                {
                        ReleaseTestSupport.Workload.directVstreamSecureOneChunk(),
                        ReleaseTestSupport.ReleaseAction.FORCE_STOP,
                },
                {
                        ReleaseTestSupport.Workload.directVstreamSecureOneChunk(),
                        ReleaseTestSupport.ReleaseAction.MANUAL_RELEASE,
                },
        });
    }

    public SecureChunkHeapReleaseTests(ReleaseTestSupport.Workload workload,
            ReleaseTestSupport.ReleaseAction releaseAction) {
        mWorkload = workload;
        mReleaseAction = releaseAction;
    }

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
    public void secureChunkHeapBufferCanBeReleasedAfterReady() throws Exception {
        ReleaseTestSupport.DmaBufResult result =
                mSupport.runReleaseAttempt(mWorkload, mReleaseAction);
        mSupport.assertDirectSecureChunkHeapResult(result, mWorkload, mReleaseAction);
    }
}
