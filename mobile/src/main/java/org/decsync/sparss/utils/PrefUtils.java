/**
 * spaRSS
 * <p/>
 * Copyright (c) 2015-2016 Arnaud Renaud-Goud
 * Copyright (c) 2012-2015 Frederic Julian
 * <p/>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p/>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p/>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.decsync.sparss.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Build;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.decsync.sparss.Constants;
import org.decsync.sparss.MainApplication;
import org.decsync.sparss.worker.FetcherWorker;

import java.util.concurrent.TimeUnit;

public class PrefUtils {
    private static final String TAG = "PrefUtils";

    public static final String INTRO_DONE = "INTRO_DONE";
    public static final String UPDATE_FORCES_SAF = "UPDATE_FORCES_SAF";
    public static final String APP_VERSION = "APP_VERSION";
    public static final String FIRST_OPEN = "FIRST_OPEN";
    public static final String DISPLAY_TIP = "DISPLAY_TIP";

    public static final String IS_REFRESHING = "IS_REFRESHING";

    public static final String REFRESH_INTERVAL_MINUTES = "refresh.interval_minutes";
    public static final String REFRESH_INTERVAL_OLD = "refresh.interval";
    public static final String REFRESH_ENABLED = "refresh.enabled";
    public static final String REFRESH_ON_OPEN_ENABLED = "refreshonopen.enabled";
    public static final String REFRESH_WIFI_ONLY = "refreshwifionly.enabled";

    public static final String NOTIFICATIONS_ENABLED = "notifications.enabled";
    public static final String NOTIFICATIONS_RINGTONE = "notifications.ringtone";
    public static final String NOTIFICATIONS_VIBRATE = "notifications.vibrate";
    public static final String NOTIFICATIONS_LIGHT = "notifications.light";

    public static final String LIGHT_THEME = "lighttheme";
    public static final String LEFT_PANEL = "leftpanel";
    public static final String DISPLAY_IMAGES = "display_images";
    public static final String PRELOAD_IMAGE_MODE = "preload_image_mode";
    public static final String DISPLAY_OLDEST_FIRST = "display_oldest_first";
    public static final String DISPLAY_ENTRIES_FULLSCREEN = "display_entries_fullscreen";
    public static final String MARK_AS_READ = "display_mark_as_read";
    public static final String SHOW_NEW_ENTRIES = "display_show_new_entries";

    public static final String PROXY_ENABLED = "proxy.enabled";
    public static final String PROXY_PORT = "proxy.port";
    public static final String PROXY_HOST = "proxy.host";
    public static final String PROXY_WIFI_ONLY = "proxy.wifionly";
    public static final String PROXY_TYPE = "proxy.type";

    public static final String KEEP_TIME = "keeptime";

    public static final String FONT_SIZE = "fontsize";
    public static final String FONT_SERIF = "font_serif";

    public static final String SHOW_READ = "show_read";

    public static final String DECSYNC_ENABLED = "decsync.enabled";
    public static final String DECSYNC_USE_SAF = "decsync.use_saf";
    public static final String DECSYNC_FILE = "decsync.directory";
    public static final String DECSYNC_MANAGE_DATA = "decsync.manage_data";

    public static boolean getBoolean(String key, boolean defValue) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(MainApplication.getContext());
        return settings.getBoolean(key, defValue);
    }

    public static void putBoolean(String key, boolean value) {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(MainApplication.getContext()).edit();
        editor.putBoolean(key, value);
        editor.apply();
    }

    public static int getInt(String key, int defValue) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(MainApplication.getContext());
        return settings.getInt(key, defValue);
    }

    public static void putInt(String key, int value) {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(MainApplication.getContext()).edit();
        editor.putInt(key, value);
        editor.apply();
    }

    public static long getLong(String key, long defValue) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(MainApplication.getContext());
        return settings.getLong(key, defValue);
    }

    public static void putLong(String key, long value) {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(MainApplication.getContext()).edit();
        editor.putLong(key, value);
        editor.apply();
    }

    public static String getString(String key, String defValue) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(MainApplication.getContext());
        return settings.getString(key, defValue);
    }

    public static void putString(String key, String value) {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(MainApplication.getContext()).edit();
        editor.putString(key, value);
        editor.apply();
    }

    public static void remove(String key) {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(MainApplication.getContext()).edit();
        editor.remove(key);
        editor.apply();
    }

    public static void registerOnPrefChangeListener(OnSharedPreferenceChangeListener listener) {
        try {
            PreferenceManager.getDefaultSharedPreferences(MainApplication.getContext()).registerOnSharedPreferenceChangeListener(listener);
        } catch (Exception ignored) { // Seems to be possible to have a NPE here... Why?? Because MainApplication.getContext() might not be set, yet.
            Log.e(TAG, "Exception", ignored);
        }
    }

    public static void unregisterOnPrefChangeListener(OnSharedPreferenceChangeListener listener) {
        try {
            PreferenceManager.getDefaultSharedPreferences(MainApplication.getContext()).unregisterOnSharedPreferenceChangeListener(listener);
        } catch (Exception ignored) { // Seems to be possible to have a NPE here... Why?? Because MainApplication.getContext() might not be set, yet.
            Log.e(TAG, "Exception", ignored);
        }
    }

    public static void updateAutomaticRefresh(Context context, Boolean enabled, String minutes, Boolean onlyWifi) {
        if (enabled == null) {
            enabled = getBoolean(REFRESH_ENABLED, true);
        }
        if (minutes == null) {
            minutes = getString(REFRESH_INTERVAL_MINUTES, "30");
        }
        if (onlyWifi == null) {
            onlyWifi = getBoolean(REFRESH_WIFI_ONLY, false);
        }

        int minutesInt = Integer.parseInt(minutes);
        if (enabled) {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(onlyWifi ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                    .build();
            Data inputData = new Data.Builder()
                    .putString(FetcherWorker.ACTION, FetcherWorker.ACTION_REFRESH_FEEDS)
                    .putBoolean(Constants.FROM_AUTO_REFRESH, true)
                    .build();
            PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(FetcherWorker.class, minutesInt, TimeUnit.MINUTES)
                    .setInitialDelay(minutesInt, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .setInputData(inputData)
                    .build();
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(REFRESH_ENABLED, ExistingPeriodicWorkPolicy.REPLACE, workRequest);
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(REFRESH_ENABLED);
        }
    }

    public static void checkAppUpgrade(Context context) {
        int currentAppVersion = 3;
        int appVersion = getInt(APP_VERSION, 0);
        if (appVersion != currentAppVersion) {
            if (appVersion > 0) {
                if (appVersion < 2) {
                    putBoolean(INTRO_DONE, true);
                }
                if (appVersion < 3) {
                    int intervalMilliSeconds = Integer.parseInt(getString(REFRESH_INTERVAL_OLD, "1800000"));
                    int intervalMinutes = intervalMilliSeconds / 60000;
                    putString(REFRESH_INTERVAL_MINUTES, Integer.toString(intervalMinutes));
                    updateAutomaticRefresh(context, null, null, null);
                }
            }
            putInt(APP_VERSION, currentAppVersion);
        }

        if (Build.VERSION.SDK_INT >= 29 &&
                !getBoolean(DECSYNC_USE_SAF, false) &&
                !Environment.isExternalStorageLegacy()) {
            putBoolean(DECSYNC_USE_SAF, true);
            if (getBoolean(INTRO_DONE, false) &&
                    getBoolean(DECSYNC_ENABLED, false)) {
                putBoolean(UPDATE_FORCES_SAF, true);
            }
        }
    }
}
