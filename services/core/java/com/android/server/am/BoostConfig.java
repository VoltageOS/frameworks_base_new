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

import android.os.SystemProperties;

public final class BoostConfig {
    private static final String CPUSET = "/dev/cpuset/";
    private static final String CPUCTL = "/dev/cpuctl/";
    private static final String CPUS = "/cpus";
    private static final String PROCS = "/cgroup.procs";
    private static final String UCLAMP_MAX = "/cpu.uclamp.max";
    private static final String UCLAMP_MIN = "/cpu.uclamp.min";

    private static final String CPU_SYS_PATH = "/sys/devices/system/cpu/";
    private static final String CPU_FREQ_PATH = "/cpufreq/";
    private static final String SCALING_MIN_FREQ_FILE = "scaling_min_freq";

    private static final String RESTRICTED = "restricted";
    private static final String BACKGROUND = "background";
    private static final String SYSTEM_BG = "system-background";
    private static final String DISPLAY = "display";

    private static final String VOLTAGE_PROP = "persist.sys.voltage_";

    public static final String MIN_CPU_FREQ_BOOST = getCpuProp("min_freq_boost", "1100000");

    public static final String BG_CPU = getCpuProp("cpu_bg", "0-3");
    public static final String DISPLAY_CPU = getCpuProp("cpu_display", "0-5");
    public static final String ALL_CORES = getCpuProp("cpu_unlimit_ui", "0-7");
    public static final String BG_LIMIT = getCpuProp("cpu_limit_bg", "0-1");
    public static final String FG_LIMIT = getCpuProp("cpu_limit_ui", "0-2");
    public static final String BIG_CORES = getCpuRange(getCpuProp("cpu_big", "4,5,6,7"));
    public static final String SMALL_CORES = getCpuRange(getCpuProp("cpu_small", "0,1,2,3"));
    public static final String LITTLE_CLUSTER_INDEX = SMALL_CORES.split("[-,]")[0];
    public static final String CPU_BOOST_PATH = CPU_SYS_PATH + "cpu" + LITTLE_CLUSTER_INDEX + CPU_FREQ_PATH + SCALING_MIN_FREQ_FILE;

    public static final int SF_UC_MIN_BOOST =
            Math.round(SystemProperties.getInt("ro.surface_flinger.uclamp.min", 100) * 100f / 1024f);

    public static String cpuPath(String type) {
        switch (type) {
            case "restricted": return CPUSET + RESTRICTED + CPUS;
            case "background": return CPUSET + BACKGROUND + CPUS;
            case "system-background": return CPUSET + SYSTEM_BG + CPUS;
            case "display": return CPUSET + DISPLAY + CPUS;
            default: return "";
        }
    }

    public static String cpuCtlPath(String type, String file) {
        String base;
        switch (type) {
            case "restricted": base = CPUCTL + RESTRICTED; break;
            case "display": base = CPUCTL + DISPLAY; break;
            default: base = CPUCTL + type; break;
        }
        return base + file;
    }

    private static String getCpuProp(String name, String defaultVal) {
        return SystemProperties.get(VOLTAGE_PROP + name, defaultVal);
    }

    private static String getCpuRange(String cores) {
        if (cores == null || cores.isEmpty()) return "";
        String[] parts = cores.split(",");
        return parts.length == 1 ? parts[0] : parts[0] + "-" + parts[parts.length - 1];
    }

    private BoostConfig() {}
}
