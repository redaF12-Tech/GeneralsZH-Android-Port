/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// GeneralsX @feature Android port daylight-darkmode 20/09/2026
//
// In-app Daylight/Darkmode choice for the launcher UI (Setup, folder picker,
// log viewer, GeneralsOnline account screen), sitting beside the language
// override in LocaleHelper. Values: 0 = system, 1 = daylight, 2 = darkmode.
//
// Why the manual Configuration.uiMode wrap instead of
// AppCompatDelegate.setDefaultNightMode(): this app deliberately does not
// depend on androidx.appcompat (every Activity extends plain android Activity
// -- see LocaleHelper's header for the same reasoning on the language side),
// and the launcher theme is Theme.Material3.DayNight over a two-palette
// colour set in res/values and res/values-night. Overriding uiMode in the
// base context is the platform mechanism for forcing -night resource
// selection and works identically on every API level this project targets
// (minSdk 28), for every widget that resolves through the Activity context.
//
// "System" (no saved preference) intentionally does nothing: the base
// context already carries the system's own uiMode, so
// UiModeManager.getApplicationNightMode() / battery saver / sunrise-sunset
// all keep working untouched.

package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

final class ThemeHelper {

    static final String PREFS_NAME = LocaleHelper.PREFS_NAME;
    static final String PREF_UI_MODE = "ui_mode";

    /** Follow the system (no override). Stored as "absent", never as 0. */
    static final int MODE_SYSTEM = 0;
    /** Daylight palette: force the values/ (not -night) colour set. */
    static final int MODE_DAYLIGHT = 1;
    /** Darkmode palette: force the values-night colour set. */
    static final int MODE_DARK = 2;

    private ThemeHelper() {}

    static int getSavedUiMode(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(PREF_UI_MODE, MODE_SYSTEM);
    }

    static void setSavedUiMode(Context ctx, int mode) {
        SharedPreferences.Editor editor =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        if (mode == MODE_SYSTEM) {
            // Absent = "follow the system", matching LocaleHelper's treatment
            // of the unset language tag. Nothing in config changes either way.
            editor.remove(PREF_UI_MODE);
        } else {
            editor.putInt(PREF_UI_MODE, mode);
        }
        editor.apply();
    }

    // Call from every launcher Activity's attachBaseContext(Context), after
    // the language wrap: super.attachBaseContext(ThemeHelper.wrap(LocaleHelper.wrap(base))).
    // LocaleHelper must stay the outer wrap it always was -- it snapshots the
    // configuration it is given -- so the order below keeps the language
    // override authoritative while this one only adjusts uiMode.
    static Context wrap(Context base) {
        int mode = getSavedUiMode(base);
        if (mode == MODE_SYSTEM) {
            return base;  // follow the system; nothing to override.
        }

        Configuration config = new Configuration(base.getResources().getConfiguration());
        int night = (mode == MODE_DARK)
            ? Configuration.UI_MODE_NIGHT_YES
            : Configuration.UI_MODE_NIGHT_NO;
        // Preserve the qualifier bits the system set (UI_MODE_TYPE_*, watch/
        // car/etc.) and replace only the night half, exactly how the
        // framework's own night-mode override does it.
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return base.createConfigurationContext(config);
    }
}
