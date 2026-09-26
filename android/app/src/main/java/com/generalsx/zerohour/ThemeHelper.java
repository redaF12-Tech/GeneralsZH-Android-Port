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
// GeneralsX @feature Android port accent-colors 21/09/2026
//
// The same storage carries the launcher ACCENT choice (default violet plus
// five alternates, Interface tab): unlike the night mode -- which changes
// which half of res/values(-night) resolves, a resource-selector question
// that must be answered before the context exists -- an accent is a plain
// Material 3 theme overlay, so it is applied per-Activity in onCreate()
// (ThemeHelper.applyAccentTheme) rather than in attachBaseContext(). One
// overlay per accent covers both daylight and darkmode: the overlay names
// palette colours, and the palette halves resolve per mode underneath it.
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
import android.util.TypedValue;

final class ThemeHelper {

    static final String PREFS_NAME = LocaleHelper.PREFS_NAME;
    static final String PREF_UI_MODE = "ui_mode";
    static final String PREF_ACCENT = "accent";

    /** Follow the system (no override). Stored as "absent", never as 0. */
    static final int MODE_SYSTEM = 0;
    /** Daylight palette: force the values/ (not -night) colour set. */
    static final int MODE_DAYLIGHT = 1;
    /** Darkmode palette: force the values-night colour set. */
    static final int MODE_DARK = 2;

    /** The base theme's violet -- the absence of a saved accent preference. */
    static final int ACCENT_DEFAULT = 0;
    static final int ACCENT_BLUE = 1;
    static final int ACCENT_GREEN = 2;
    static final int ACCENT_TEAL = 3;
    static final int ACCENT_ORANGE = 4;
    static final int ACCENT_RED = 5;
    /** Same order everywhere: storage, the segmented picker, the previews. */
    static final int ACCENT_COUNT = 6;

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

    // ---------------------------------------------------------------------
    // Accent (Interface tab). Five alternate palettes ride on the base theme
    // through ThemeOverlay styles in values/themes_accents.xml; the default
    // violet is the base theme itself, so it stores no preference and applies
    // no overlay -- theme-overlaid dialogs and colour-attribute reads below
    // all treat ACCENT_DEFAULT as the plain theme.

    // Index-aligned with the ACCENT_* constants; 0 = no overlay (default).
    private static final int[] ACCENT_THEMES = {
        0,
        R.style.ThemeOverlay_GeneralsZH_Accent_Blue,
        R.style.ThemeOverlay_GeneralsZH_Accent_Green,
        R.style.ThemeOverlay_GeneralsZH_Accent_Teal,
        R.style.ThemeOverlay_GeneralsZH_Accent_Orange,
        R.style.ThemeOverlay_GeneralsZH_Accent_Red,
    };

    // The picker's swatch dots. These are literal hexes rather than resource
    // reads on purpose: the dots must show BOTH modes' accent inside a single
    // activity, and the daylight value of an overlayed colour resource cannot
    // be resolved from a darkmode activity (or vice versa) without building a
    // second Resources against the other mode's configuration. The values
    // mirror accent_colors.xml and the two halves of gzh_primary -- keep them
    // in sync with those when a palette changes.
    private static final int[] ACCENT_PREVIEW_DAYLIGHT = {
        0xFF6750A4, 0xFF1565C0, 0xFF386A20, 0xFF006A60, 0xFF8B5000, 0xFFB3261E,
    };
    private static final int[] ACCENT_PREVIEW_DARK = {
        0xFFB9AEEA, 0xFFA8C8FF, 0xFF9CD67D, 0xFF80D5C7, 0xFFFFB86B, 0xFFFFB4AB,
    };

    static int getSavedAccent(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return clampAccent(prefs.getInt(PREF_ACCENT, ACCENT_DEFAULT));
    }

    static void setSavedAccent(Context ctx, int accent) {
        SharedPreferences.Editor editor =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        if (accent == ACCENT_DEFAULT) {
            // Absent = default violet, matching how the "system" night mode
            // stays absent above and LocaleHelper treats the unset language.
            editor.remove(PREF_ACCENT);
        } else {
            editor.putInt(PREF_ACCENT, clampAccent(accent));
        }
        editor.apply();
    }

    static int clampAccent(int accent) {
        return (accent >= 0 && accent < ACCENT_COUNT) ? accent : ACCENT_DEFAULT;
    }

    static boolean hasAccentOverlay(int accent) {
        return clampAccent(accent) != ACCENT_DEFAULT;
    }

    // Applied in every launcher Activity's onCreate() BEFORE super.onCreate()
    // (and so before any view or dialog is built):
    //
    //     ThemeHelper.applyAccentTheme(this);
    //
    // this.applyStyle() folds the overlay into the ACTIVITY's own theme rather
    // than wrapping the context in a ContextThemeWrapper. That is the whole
    // trick that makes the accent work app-wide with no call-site changes:
    // every existing UiKit/Activity colour read goes through this activity's theme, and all existing `this`-based builder methods keep working.
    // force=true is intentional: the base theme already defines the Material
    // color attributes, so the selected accent overlay must be allowed to
    // replace those existing values. The default violet applies no overlay:
    // the base theme already IS that accent.
    static void applyAccentTheme(android.app.Activity activity) {
        int accent = getSavedAccent(activity);
        if (hasAccentOverlay(accent)) {
            activity.getTheme().applyStyle(ACCENT_THEMES[accent], true);
        }
    }

    /** The accent colour, resolved for the context's own (effective) mode. */
    static int accentColor(Context ctx) {
        TypedValue tv = new TypedValue();
        // Material's attr is the one this project's M3 theme actually sets;
        // the framework attr is the fallback bridge (M3 themes re-expose it).
        if (ctx.getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorPrimary, tv, true)
            || ctx.getTheme().resolveAttribute(android.R.attr.colorPrimary, tv, true)) {
            return tv.data;
        }
        return UiKit.color(ctx, R.color.gzh_primary);
    }

    /** Swatch dot colour for the picker: the accent as seen in BOTH modes. */
    static int previewColor(Context ctx, int accent) {
        boolean night = (ctx.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return (night ? ACCENT_PREVIEW_DARK : ACCENT_PREVIEW_DAYLIGHT)[clampAccent(accent)];
    }

    /**
     * Same, but for the mode the current activity is NOT in -- the swatch
     * strip shows both halves of each accent so the choice is informed no
     * matter which mode the picker is being read in.
     */
    static int previewColorOtherMode(Context ctx, int accent) {
        boolean night = (ctx.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return (night ? ACCENT_PREVIEW_DAYLIGHT : ACCENT_PREVIEW_DARK)[clampAccent(accent)];
    }
}
