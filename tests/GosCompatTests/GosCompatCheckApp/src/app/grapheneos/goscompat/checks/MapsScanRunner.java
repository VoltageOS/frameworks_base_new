package app.grapheneos.goscompat.checks;

import android.os.Process;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class MapsScanRunner {
    private static final int RESULT_COMPLETED = 0;
    private static final int RESULT_SELECTED_RANGES = 1;
    private static final int RESULT_SCANNED_BYTES = 2;
    private static final int RESULT_CALLER_TID = 3;
    private static final int RESULT_WORKER_TID = 4;
    private static final int RESULT_FIRST_RANGE_START = 5;
    private static final int RESULT_FIRST_RANGE_END = 6;
    private static final int RESULT_CHECKSUM = 7;
    private static final int RESULT_ERROR = 8;
    private static final int RESULT_FIELD_COUNT = 9;
    private static final long WORKER_TIMEOUT_SECONDS = 20;

    private static boolean sNativeLoaded;

    private MapsScanRunner() {
    }

    public static MapsScanResult run() {
        return runDirect();
    }

    public static MapsScanResult runDirect() {
        return runOnJavaWorker("direct JNI", new NativeInvocation() {
            @Override
            public String[] invoke(int callerTid) {
                return nativeRunMapsScan(callerTid);
            }
        });
    }

    public static MapsScanResult runReflective() {
        return runOnJavaWorker("reflective JNI", new NativeInvocation() {
            @Override
            public String[] invoke(int callerTid) throws ReflectiveOperationException {
                Method method = MapsScanRunner.class.getDeclaredMethod(
                        "nativeRunMapsScan", int.class);
                method.setAccessible(true);
                try {
                    return (String[]) method.invoke(null, callerTid);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException) {
                        throw (RuntimeException) cause;
                    }
                    if (cause instanceof Error) {
                        throw (Error) cause;
                    }
                    throw e;
                }
            }
        });
    }

    private static MapsScanResult runOnJavaWorker(String invocationMode,
            NativeInvocation invocation) {
        ArrayList<String> details = new ArrayList<>();
        ArrayList<String> errors = new ArrayList<>();
        ExecutorService executor = null;

        try {
            ensureNativeLoaded();
            final int providerTid = Process.myTid();
            executor = Executors.newSingleThreadExecutor();
            Future<String[]> future = executor.submit(new Callable<String[]>() {
                @Override
                public String[] call() throws Exception {
                    return invocation.invoke(providerTid);
                }
            });
            String[] fields = future.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (fields == null || fields.length != RESULT_FIELD_COUNT) {
                errors.add("Native maps scan returned an invalid result");
                return new MapsScanResult(false, false, 0, 0, 0, 0, details, errors);
            }

            boolean completed = Boolean.parseBoolean(fields[RESULT_COMPLETED]);
            int selectedRanges = parseInt(fields[RESULT_SELECTED_RANGES]);
            long scannedBytes = parseLong(fields[RESULT_SCANNED_BYTES]);
            int callerTid = parseInt(fields[RESULT_CALLER_TID]);
            int workerTid = parseInt(fields[RESULT_WORKER_TID]);
            boolean workerThread = workerTid > 0 && workerTid != callerTid;

            details.add("Invocation mode: " + invocationMode);
            details.add("Selected " + selectedRanges + " maps range(s)");
            details.add("Executed " + scannedBytes + " selected-range byte load(s)");
            details.add("callerTid=" + callerTid + ", workerTid=" + workerTid);
            details.add("First selected range " + fields[RESULT_FIRST_RANGE_START]
                    + "-" + fields[RESULT_FIRST_RANGE_END]);
            details.add("Read checksum " + fields[RESULT_CHECKSUM]);

            String error = fields[RESULT_ERROR];
            if (error != null && !error.isEmpty()) {
                errors.add(error);
            }
            if (!workerThread) {
                errors.add("Native maps scan did not run on a distinct worker thread");
            }
            if (selectedRanges == 0) {
                errors.add("Native maps scan did not find the expected main stack/TLS entry");
            }

            return new MapsScanResult(completed, workerThread, selectedRanges, scannedBytes,
                    callerTid, workerTid, details, errors);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            errors.add("Native maps scan failed: " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage());
            return new MapsScanResult(false, false, 0, 0, 0, 0, details, errors);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errors.add("Native maps scan interrupted");
            return new MapsScanResult(false, false, 0, 0, 0, 0, details, errors);
        } catch (TimeoutException e) {
            errors.add("Native maps scan timed out");
            return new MapsScanResult(false, false, 0, 0, 0, 0, details, errors);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            errors.add("Native maps scan failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return new MapsScanResult(false, false, 0, 0, 0, 0, details, errors);
        } finally {
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }

    private static int parseInt(String value) {
        return value == null || value.isEmpty() ? 0 : Integer.parseInt(value);
    }

    private static long parseLong(String value) {
        return value == null || value.isEmpty() ? 0 : Long.parseLong(value);
    }

    private static synchronized void ensureNativeLoaded() {
        if (!sNativeLoaded) {
            System.loadLibrary("goscompat_maps_scan_jni");
            sNativeLoaded = true;
        }
    }

    private interface NativeInvocation {
        String[] invoke(int callerTid) throws Exception;
    }

    private static native String[] nativeRunMapsScan(int callerTid);
}
