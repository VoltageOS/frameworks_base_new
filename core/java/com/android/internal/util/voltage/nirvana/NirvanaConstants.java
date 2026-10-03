/*
 * Copyright (C) 2026 VoltageOS
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
package com.android.internal.util.voltage.nirvana;

public final class NirvanaConstants {
    private NirvanaConstants() {}

    public static final String KEY_APPS = "nirvana_mode_apps_list";
    public static final String KEY_TRACKED_STATE = "nirvana_mode_tracked_state";
    public static final String KEY_MANUAL_ACTIVE = "nirvana_mode_manual_active";
    public static final String KEY_SCHEDULE_ENABLED = "nirvana_mode_schedule_enabled";
    public static final String KEY_START_TIME = "nirvana_mode_start_time";
    public static final String KEY_END_TIME = "nirvana_mode_end_time";
    public static final String KEY_LIMITS = "nirvana_app_time_limits";
    public static final String KEY_BLOCKED_TODAY = "nirvana_app_time_limit_blocked";
    public static final String KEY_BLOCKED_DAY = "nirvana_app_time_limit_blocked_day";
    public static final String KEY_OBSERVERS = "nirvana_app_time_limit_observers";
    public static final String KEY_STATUSBAR_ICON = "show_nirvana_statusbar_icon";

    public static final String ACTION_UPDATE = "com.power.hub.action.UPDATE_NIRVANA_SCHEDULE";
    public static final String ACTION_LIMIT_REACHED =
            "com.power.hub.action.NIRVANA_TIME_LIMIT_REACHED";
    public static final String ACTION_DAILY_RESET = "com.power.hub.action.NIRVANA_DAILY_RESET";
    public static final String ACTION_FORCE_UNSUSPEND =
            "com.power.hub.action.NIRVANA_FORCE_UNSUSPEND";

    public static final String EXTRA_LIMIT_PACKAGE = "com.power.hub.extra.NIRVANA_LIMIT_PACKAGE";
    public static final String EXTRA_LIMIT_USER = "com.power.hub.extra.NIRVANA_LIMIT_USER";

    public static final String TARGET_PACKAGE = "com.android.systemui";
    public static final String LEGACY_TARGET_PACKAGE = "com.android.settings";
    public static final String SYSTEMUI_SUSPENDER = "com.android.systemui";
    public static final String LEGACY_SUSPENDER = "com.android.settings";

    public static final String SLOT_NIRVANA = "nirvana";

    public static final int DEFAULT_START = 540;
    public static final int DEFAULT_END = 1020;
    public static final int OBSERVER_ID_BASE = 710000;
    public static final int OBSERVER_ID_MASK = 0xFFFF;
    public static final int DAILY_RESET_REQUEST_CODE = 709999;
    public static final int SCHEDULE_ALARM_REQUEST_CODE = 710001;
}
