/*
 * Copyright (C) 2025 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.am;

import android.hardware.power.Mode;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.server.UiThread;

import java.io.IOException;
import java.util.ArrayList;

public class BoostAdjuster {
    private static final String TAG = "BoostAdjuster";
    private static final boolean DEBUG = false;

    public static final int THREAD_GROUP_NT_FOREGROUND = 10;
    public static final int THREAD_GROUP_RESTRICTED = Process.THREAD_GROUP_RESTRICTED;

    private static final String CPU_BG = BoostConfig.cpuPath("background");
    private static final String CPU_DISPLAY = BoostConfig.cpuPath("display");
    private static final String CPU_FG = BoostConfig.cpuPath("foreground");
    private static final String CPU_RESTRICTED = BoostConfig.cpuPath("restricted");
    private static final String CPU_SYS_BG = BoostConfig.cpuPath("system-background");

    private static final String ROOT_PROCS = BoostConfig.cpuCtlPath("cgroup", "/cgroup.procs");
    private static final String RESTRICTED_PROCS = BoostConfig.cpuCtlPath("restricted", "/cgroup.procs");
    private static final String RESTRICTED_UC_MAX = BoostConfig.cpuCtlPath("restricted", "/cpu.uclamp.max");
    private static final String RESTRICTED_UC_MIN = BoostConfig.cpuCtlPath("restricted", "/cpu.uclamp.min");
    private static final String DISPLAY_UC_MAX = BoostConfig.cpuCtlPath("display", "/cpu.uclamp.max");
    private static final String DISPLAY_UC_MIN = BoostConfig.cpuCtlPath("display", "/cpu.uclamp.min");

    private static final String BG_CPU = BoostConfig.BG_CPU;
    private static final String DISPLAY_CPU = BoostConfig.DISPLAY_CPU;
    private static final String ALL_CORES = BoostConfig.ALL_CORES;
    private static final String BG_LIMIT = BoostConfig.BG_LIMIT;
    private static final String FG_LIMIT = BoostConfig.FG_LIMIT;
    private static final String BIG_CORES = BoostConfig.BIG_CORES;

    private static ArrayList<String> sAppWhiteList = new ArrayList<>();
    private static ArrayList<String> sAppPerfList = new ArrayList<>();

    private String currentReason = "none";

    private final ActivityManagerService mAm;
    private final HandlerThread mHandlerThread;
    private final BoostHandler mHandler;

    private static final int MSG_WRITE = 1;
    private static final int MSG_ADJUST_CPUSET = 2;
    private static final int MSG_DISABLE_BOOST_HINT = 3;
    private static final int MSG_ANIMATION_BOOST = 4;
    private static final int MSG_SET_THREAD_AFFINITY = 5;
    private static final int MSG_SET_PERFORMANCE_MODE = 6;
    private static final int MSG_BOOST_HINT = 7;
    private static final int MSG_BOOST_HOME_PROCESS = 8;
    private static final int MSG_ON_WAKEFULNESS_CHANGED = 9;

    static {
        sAppWhiteList.add("com.google.android.providers.media.module");
        sAppWhiteList.add("android.process.media");
        sAppWhiteList.add("android.os.cts");
        sAppPerfList.add("com.android.systemui");
        sAppPerfList.add("com.android.launcher3");
    }

    public BoostAdjuster(ActivityManagerService am) {
        mAm = am;
        mHandlerThread = new HandlerThread("BoostAdjusterThread");
        mHandlerThread.start();
        mHandler = new BoostHandler(mHandlerThread.getLooper(), this);
    }

    private static class BoostHandler extends Handler {
        private final BoostAdjuster mAdjuster;

        BoostHandler(Looper looper, BoostAdjuster adjuster) {
            super(looper);
            mAdjuster = adjuster;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_WRITE:
                    WriteParams writeParams = (WriteParams) msg.obj;
                    mAdjuster.writeInternal(writeParams.path, writeParams.value);
                    break;
                case MSG_ADJUST_CPUSET:
                    AdjustCpusetParams cpusetParams = (AdjustCpusetParams) msg.obj;
                    mAdjuster.adjustCpusetCpusInternal(cpusetParams);
                    break;
                case MSG_DISABLE_BOOST_HINT:
                    mAdjuster.hintBoost(false);
                    break;
                case MSG_ANIMATION_BOOST:
                    mAdjuster.animationBoostInternal(msg.arg2, msg.arg1 == 1);
                    break;
                case MSG_SET_THREAD_AFFINITY:
                    mAdjuster.setThreadAffinityInternal(msg.arg2, msg.arg1);
                    break;
                case MSG_SET_PERFORMANCE_MODE:
                    mAdjuster.setPerformanceModeInternal(msg.arg1 == 1, (String) msg.obj);
                    break;
                case MSG_BOOST_HINT:
                    BoostHintParams hintParams = (BoostHintParams) msg.obj;
                    mAdjuster.boostHintInternal(hintParams.reason, hintParams.duration);
                    break;
                case MSG_BOOST_HOME_PROCESS:
                    mAdjuster.boostHomeProcessInternal((ProcessRecord) msg.obj);
                    break;
                case MSG_ON_WAKEFULNESS_CHANGED:
                    mAdjuster.onWakefulnessChangedInternal(msg.arg1 == 1);
                    break;
                default:
                    if (DEBUG) Slog.w(TAG, "Unknown message: " + msg.what);
            }
        }
    }

    public void write(String path, String value) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_WRITE, new WriteParams(path, value)));
    }

    public void adjustCpusetCpus(String cgroup, long durationMillis) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_ADJUST_CPUSET,
                new AdjustCpusetParams(cgroup, durationMillis)));
    }

    public void animationBoost(int pid, boolean enabled) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_ANIMATION_BOOST, enabled ? 1 : 0, pid));
    }

    public void setThreadAffinity(int pid, int affinity) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_SET_THREAD_AFFINITY, affinity, pid));
    }

    public void setPerformanceMode(boolean enabled, String reason) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_SET_PERFORMANCE_MODE, enabled ? 1 : 0, 0, reason));
    }

    public void boostHint(final String reason, final long duration) {
        UiThread.getHandler().post(() ->
            mHandler.sendMessage(mHandler.obtainMessage(MSG_BOOST_HINT,
                    new BoostHintParams(reason, duration)))
        );
    }

    public void boostHomeProcess(ProcessRecord proc) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_BOOST_HOME_PROCESS, proc));
    }

    public void onWakefulnessChanged(boolean awake) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_ON_WAKEFULNESS_CHANGED, awake ? 1 : 0, 0));
    }

    private void writeInternal(String path, String value) {
        try {
            FileUtils.stringToFile(path, value);
        } catch (IOException e) {
            if (DEBUG) Slog.e(TAG, "Failed to write to " + path + ": " + e.getMessage());
        }
    }

    private void adjustCpusetCpusInternal(AdjustCpusetParams params) {
        String cgroup = params.cgroup;
        long durationMillis = params.durationMillis;
        if (DEBUG) Slog.d(TAG, "adjustCpusetCpusInternal: group=" + cgroup + ", duration=" + durationMillis);
        if (cgroup == null) {
            if (DEBUG) Slog.w(TAG, "Invalid cgroup (null), ignoring!");
            return;
        }
        adjustCpuset(cgroup, true);
        mHandler.postDelayed(() -> adjustCpuset(cgroup, false), durationMillis);
    }

    private void animationBoostInternal(int pid, boolean enabled) {
        ProcessRecord curProc;
        synchronized (mAm.mPidsSelfLocked) {
            curProc = mAm.mPidsSelfLocked.get(pid);
        }
        if (curProc == null) return;
        final int renderTid = curProc.getRenderThreadTid();
        final int prio = Process.getThreadPriority(pid);
        try {
            if (enabled) {
                final int policy = Process.SCHED_RR | Process.SCHED_RESET_ON_FORK;
                Process.setThreadScheduler(pid, policy, 1);
                if (renderTid > 0) Process.setThreadScheduler(renderTid, policy, 10);
            } else {
                Process.setThreadScheduler(pid, 0, 0);
                Process.setThreadPriority(prio);
                if (renderTid > 0) Process.setThreadScheduler(renderTid, 0, 0);
            }
        } catch (Exception ignored) {}
        boostRestricted(pid, renderTid, enabled);
    }

    private void setThreadAffinityInternal(int pid, int affinity) {
        Process.setThreadGroupAndCpuset(pid, Process.THREAD_GROUP_RESTRICTED);
        Process.setThreadAffinity(pid, affinity);
    }

    private void setPerformanceModeInternal(boolean enabled, String reason) {
        final boolean sysuiBoosting = !enabled && !"sysui".equals(reason) && "sysui".equals(currentReason);
        PowerManagerInternal pm = mAm.mLocalPowerManager;
        if (pm == null || sysuiBoosting) return;
        if (enabled) pm.setPowerMode(Mode.LAUNCH, false);
        if (!enabled && !reason.equals(currentReason)) return;
        pm.setPowerMode(Mode.LAUNCH, enabled);
        pm.setPowerMode(PowerManagerInternal.MODE_FIXED_PERFORMANCE, enabled);
        currentReason = enabled ? reason : "none";
    }

    private void boostHintInternal(final String reason, final long duration) {
        currentReason = reason;
        hintBoost(true);
        mHandler.removeMessages(MSG_DISABLE_BOOST_HINT);
        mHandler.sendEmptyMessageDelayed(MSG_DISABLE_BOOST_HINT, duration);
    }

    private void hintBoost(boolean enabled) {
        boostDisplay(enabled);
        adjustCpuset("background", enabled);
        adjustCpuset("nt_foreground", enabled);
        SystemProperties.set("dalvik.vm.dex2oat-threads", enabled ? "1" : "2");
        setPerformanceModeInternal(enabled, currentReason);
    }

    private void boostHomeProcessInternal(ProcessRecord proc) {
        if (!"com.android.launcher3".equals(proc.processName)) return;
        mAm.scheduleAsFifoPriority(proc.getPid(), true, 1);
        mAm.scheduleAsFifoPriority(proc.getRenderThreadTid(), true, 10);
    }

    private void onWakefulnessChangedInternal(boolean awake) {
        setPerformanceModeInternal(awake, "wakefulness");
        restrictBackground(!awake);
    }

    private void adjustCpuset(String cgroup, boolean limit) {
        String cpuset;
        switch (cgroup) {
            case "nt_foreground":
                cpuset = limit ? FG_LIMIT : ALL_CORES;
                break;
            case "background":
                cpuset = limit ? BG_LIMIT : BG_CPU;
                break;
            default:
                return;
        }
        writeInternal("/dev/cpuset/" + cgroup + "/cpus", cpuset);
    }

    private void boostRestricted(int pid, int rTid, boolean enable) {
        String boostVal = enable ? "100" : "0";
        writeInternal(RESTRICTED_UC_MIN, boostVal);
        writeInternal(RESTRICTED_UC_MAX, boostVal);
        writeInternal(CPU_RESTRICTED, enable ? BIG_CORES : ALL_CORES);
        writeInternal(enable ? RESTRICTED_PROCS : ROOT_PROCS, String.valueOf(pid));
        if (rTid == 0) writeInternal(enable ? RESTRICTED_PROCS : ROOT_PROCS, String.valueOf(rTid));
    }

    private void boostDisplay(boolean enable) {
        String boostVal = enable ? String.valueOf(BoostConfig.SF_UC_MIN_BOOST) : "0";
        String cpuset = enable ? BIG_CORES : DISPLAY_CPU;
        writeInternal(DISPLAY_UC_MIN, boostVal);
        writeInternal(DISPLAY_UC_MAX, boostVal);
        writeInternal(DISPLAY_CPU, cpuset);
    }

    private void restrictBackground(boolean limit) {
        String bgCpuset = limit ? BG_LIMIT : BG_CPU;
        writeInternal(CPU_BG, bgCpuset);
        writeInternal(CPU_SYS_BG, bgCpuset);
        writeInternal(CPU_FG, limit ? DISPLAY_CPU : ALL_CORES);
        writeInternal(CPU_RESTRICTED, limit ? BG_LIMIT : ALL_CORES);
    }

    private static boolean needsControl(ProcessRecord app, boolean verifyGroup, int oldScheduleGroup) {
        if (verifyGroup && oldScheduleGroup == ProcessList.SCHED_GROUP_TOP_APP && app.hasActivities()) {
            if (DEBUG) Slog.d(TAG, "previous schedule group is top, not need limit!");
            return false;
        }
        if (app.uid % 100000 < 10000 || isInPerfList(app.processName) || isInWhiteList(app.processName)) {
            if (DEBUG) Slog.d(TAG, "system app not need limit!");
            return false;
        }
        if (app.getHostingRecord() == null || app.getHostingRecord().isTopApp()) {
            return false;
        }
        if (DEBUG) Slog.d(TAG, "process : " + app.processName + " is not top!");
        return true;
    }

    public static boolean isForegroundNeedSelfControll(int oldScheduleGroup, ProcessRecord app) {
        return needsControl(app, true, oldScheduleGroup);
    }

    public static boolean isRestrictedNeedSelfControll(ProcessRecord app) {
        return needsControl(app, false, -1);
    }

    public static boolean isInWhiteList(String processName) {
        return processName != null && sAppWhiteList.contains(processName);
    }

    public static boolean isInPerfList(String processName) {
        return processName != null &&
               (sAppPerfList.contains(processName) || isCamera(processName));
    }

    public static boolean isCamera(String processName) {
        return processName != null && processName.toLowerCase().contains("camera");
    }
    
    private static class WriteParams {
        final String path;
        final String value;
        WriteParams(String path, String value) { 
            this.path = path; 
            this.value = value; 
        }
    }

    private static class AdjustCpusetParams {
        final String cgroup;
        final long durationMillis;
        AdjustCpusetParams(String cgroup, long durationMillis) { 
            this.cgroup = cgroup; 
            this.durationMillis = durationMillis; 
        }
    }

    private static class BoostHintParams {
        final String reason;
        final long duration;
        BoostHintParams(String reason, long duration) { 
            this.reason = reason; 
            this.duration = duration; 
        }
    }
}
