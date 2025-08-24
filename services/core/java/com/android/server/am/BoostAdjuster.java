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
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.server.UiThread;

import java.io.IOException;

public class BoostAdjuster {
    private static final String BOOSTER_TAG = "BoostAdjuster";

    private static final String CPU_BG = "/dev/cpuset/background/cpus";
    private static final String CPU_FG = "/dev/cpuset/nt_foreground/cpus";
    private static final String CPU_RESTRICTED = "/dev/cpuset/restricted/cpus";
    private static final String CPU_DISPLAY = "/dev/cpuset/display/cpus";

    private static final String RESTRICTED_PROCS = "/dev/cpuctl/restricted/cgroup.procs";
    private static final String ROOT_PROCS = "/dev/cpuctl/cgroup.procs";
    private static final String RESTRICTED_UC_MAX = "/dev/cpuctl/restricted/cpu.uclamp.max";
    private static final String RESTRICTED_UC_MIN = "/dev/cpuctl/restricted/cpu.uclamp.min";
    private static final String DISPLAY_UC_MAX = "/dev/cpuctl/display/cpu.uclamp.max";
    private static final String DISPLAY_UC_MIN = "/dev/cpuctl/display/cpu.uclamp.min";

    private static final String BG_CPU = SystemProperties.get("persist.sys.voltage_cpu_bg", "0-3");
    private static final String DISPLAY_CPU = SystemProperties.get("persist.sys.voltage_cpu_display", "0-5");
    private static final String NT_FG_CPU = SystemProperties.get("persist.sys.voltage_cpu_unlimit_ui", "0-7");
    private static final String BG_LIMIT = SystemProperties.get("persist.sys.voltage_cpu_limit_bg", "0-1");
    private static final String FG_LIMIT = SystemProperties.get("persist.sys.voltage_cpu_limit_ui", "0-2");
    private static final String ALL_CORES = SystemProperties.get("persist.sys.voltage_cpu_unlimit_ui", "0-7");
    private static final String BIG_CORES = getCpuRange(SystemProperties.get("persist.sys.voltage_cpu_big", "4,5,6,7"));

    private static final int SF_UC_MIN_BOOST =
            Math.round(SystemProperties.getInt("ro.surface_flinger.uclamp.min", 165) * 100f / 1024f);

    private String currentReason = "none";

    private Handler mBoostHandler;
    private final Runnable mDisableRunnable = this::disableBoostHint;
    private ActivityManagerService mAm;

    public BoostAdjuster(ActivityManagerService am) {
        mAm = am;
    }

    private static String getCpuRange(String cores) {
        if (cores == null || cores.isEmpty()) return "";
        String[] parts = cores.split(",");
        return parts.length == 1 ? parts[0] : parts[0] + "-" + parts[parts.length - 1];
    }

    public void write(String path, String value) {
        try {
            FileUtils.stringToFile(path, value);
        } catch (IOException e) {
            Slog.e(BOOSTER_TAG, "Failed to write to " + path + ": " + e.getMessage());
        }
    }

    public void adjustCpusetCpus(String cgroup, long durationMillis, Handler handler) {
        adjustCpuset(cgroup, true);
        handler.postDelayed(() -> adjustCpuset(cgroup, false), durationMillis);
    }

    private void adjustCpuset(String cgroup, boolean limit) {
        String cpuset;
        switch (cgroup) {
            case "fg": cpuset = limit ? FG_LIMIT : NT_FG_CPU; break;
            case "bg": cpuset = limit ? BG_LIMIT : BG_CPU; break;
            default: return;
        }
        write(cgroup.equals("fg") ? CPU_FG : CPU_BG, cpuset);
    }

    public void animationBoost(int pid, boolean enabled) {
        ProcessRecord curProc;
        synchronized (mAm.mPidsSelfLocked) {
            curProc = mAm.mPidsSelfLocked.get(pid);
        }
        if (curProc == null) return;
        mAm.setFifoPriority(curProc, enabled, 1);
        boostPid(pid, curProc.getRenderThreadTid(), enabled);
    }

    private void boostPid(int pid, int rTid, boolean enable) {
        boostRestricted(enable);
        write(enable ? RESTRICTED_PROCS : ROOT_PROCS, String.valueOf(pid));
        if (rTid == 0) return;
        write(enable ? RESTRICTED_PROCS : ROOT_PROCS, String.valueOf(rTid));
    }

    private void boostRestricted(boolean enable) {
        write(RESTRICTED_UC_MIN, enable ? "100" : "0");
        write(RESTRICTED_UC_MAX, "100");
        write(CPU_RESTRICTED, enable ? BIG_CORES : ALL_CORES);
    }

    private void boostDisplay(boolean enable) {
        try {
            write(DISPLAY_UC_MIN, enable ? String.valueOf(SF_UC_MIN_BOOST) : "0");
            write(DISPLAY_UC_MAX, "29");
        } catch (Exception ignored) {}
    }

    public void setThreadAffinity(int pid, int affinity) {
        if (affinity == 0) {
            Process.setThreadGroupAndCpuset(pid, 5);
        } else {
            Process.setThreadGroupAndCpuset(pid, 0);
        }
        Process.setThreadAffinity(pid, affinity);
    }

    public void setPerformanceMode(boolean enabled, String reason) {
        final boolean sysuiBoosting = !enabled && !"sysui".equals(reason) && "sysui".equals(currentReason);
        PowerManagerInternal pm = mAm.mLocalPowerManager;
        if (pm == null || sysuiBoosting) return;
        if (enabled) {
            pm.setPowerMode(Mode.LAUNCH, false);
        }
        if (!enabled && !reason.equals(currentReason)) return;
        pm.setPowerMode(Mode.LAUNCH, enabled);
        pm.setPowerMode(PowerManagerInternal.MODE_FIXED_PERFORMANCE, enabled);
        currentReason = enabled ? reason : "none";
    }

    public void boostHint(final String reason, final long duration) {
        final boolean inputBoost = "inputBoost".equals(reason);
        final Handler handler = inputBoost ? UiThread.getHandler() : mAm.mHandler;
        if (handler == null) return;
        handler.post(() -> {
            currentReason = reason;
            setPerformanceMode(true, reason);
            adjustCpusetCpus("bg", duration, handler);
            adjustCpusetCpus("fg", duration, handler);
            disableBoostHint(handler, duration);
        });
    }

    private void disableBoostHint(Handler handler, long delay) {
        removeDisableBoost();
        mBoostHandler = handler;
        handler.postDelayed(mDisableRunnable, delay);
    }
    
    private void removeDisableBoost() {
        if (mBoostHandler != null) mBoostHandler.removeCallbacks(mDisableRunnable);
    }

    private void disableBoostHint() {
        setPerformanceMode(false, currentReason);
    }

    public void onWakefulnessChanged(boolean awake) {
        boostDisplay(awake);
        boostRestricted(awake);
    }
}
