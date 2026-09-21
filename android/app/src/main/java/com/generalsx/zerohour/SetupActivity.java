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

// GeneralsX @build Android port 07/07/2026
//
// A standalone launcher icon ("GeneralsZH Setup"), separate from the game
// itself, so configuring the game folder or reading a crash log never
// depends on the game having launched successfully first — and never
// requires adb. This is the practical answer to "there's no launcher": a
// full mod-manager-style launcher (à la GenLauncher) is future scope, but
// picking where the game lives and seeing why it crashed are needed on
// every single install, so they live here now.

package com.generalsx.zerohour;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.net.HttpURLConnection;
import java.net.URL;
import java.io.File;

public class SetupActivity extends Activity {

    static final String PREFS_NAME = "generalszh_setup";
    static final String PREF_GAME_PATH = "game_path";
    // GeneralsX @feature Android port 06/09/2026 Optional folder holding the
    // BASE Generals archives, for copies that keep them somewhere the engine
    // will not find on its own.
    static final String PREF_BASE_GENERALS_PATH = "base_generals_path";
    // GeneralsX @feature Android port Mod Manager - optional highest-priority BIG folder.
    static final String PREF_MOD_PATH = "mod_path";

    // TheSuperHackers @bugfix Android port 07/07/2026 SharedPreferences and
    // getFilesDir() both live under /data/data/<pkg>/ and are wiped the
    // moment the app is uninstalled -- which is exactly what a sideloaded
    // APK update often requires if the installer treats it as a fresh
    // install rather than an in-place update. Mirror the chosen path into a
    // small marker file on shared external storage (survives uninstall,
    // since it's outside the app's private/package-scoped directories) so a
    // fresh install can recover it automatically instead of re-prompting.
    private static final String EXTERNAL_MARKER_NAME = ".generalszh_gamepath.txt";

    // Marker files SDL3Main.cpp / GeneralsZHActivity check for on launch —
    // must match GameEngine/CMake's GeneralsMD/Code/Main/SDL3Main.cpp exactly.
    private static final String[] REQUIRED_GAME_FILES = { "INIZH.big", "INI.big" };

    private TextView statusText;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(ThemeHelper.wrap(LocaleHelper.wrap(newBase)));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // GeneralsX @feature Android port accent-colors 21/09/2026 The saved
        // accent rides on this activity's theme before any view, dialog or
        // colour read happens; see ThemeHelper.applyAccentTheme() for why it
        // is an applyStyle rather than a context wrap.
        ThemeHelper.applyAccentTheme(this);

        // GeneralsX @bugfix Android port 31/07/2026 No longer forced to
        // landscape here -- see the matching AndroidManifest.xml comment.
        // This screen now starts portrait-first like every other non-game
        // screen; onLaunchGame()/onConfigurationChanged() below handle the
        // Setup -> Launch rotation race that used to be sidestepped by never
        // rotating Setup at all.
        super.onCreate(savedInstanceState);
        setTitle(R.string.setup_window_title);

        // GeneralsX @feature Android port launcher-ui-2026 08/09/2026 Which
        // bottom-navigation section to open on. Survives the recreate() the
        // language picker performs, so changing the launcher language leaves
        // you looking at the section you changed it from rather than being
        // dropped back on Home.
        if (savedInstanceState != null) {
            currentTab = savedInstanceState.getInt(STATE_TAB, TAB_HOME);
        }

        // GeneralsX @bugfix Android port 08/07/2026 This screen is the ONLY
        // way to reach "View Logs" without adb, so it must never be the thing
        // that crashes. Any future Material/theme incompatibility falls back
        // to a bare-bones plain-widget UI (same actions, no styling) instead
        // of taking the whole Settings app down with it.
        try {
            buildUi();
        } catch (Throwable t) {
            buildFallbackUi(t);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
    }

    // GeneralsX @bugfix Android port launcher-ui-2026 08/09/2026 The fallback
    // exists precisely for the case where a Material widget or theme attribute
    // was what blew up, so it must not itself construct one -- it used to build
    // MaterialButtons, which would have failed again for exactly the reason it
    // was reached. Plain framework widgets only, from here down.
    private void buildFallbackUi(Throwable failure) {
        clearPageReferences();
        // No tabs in the fallback: showTab() must become a no-op if anything
        // still calls it (onActivityResult does).
        contentHost = null;
        appBarTitle = null;
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);
        InsetUtil.applySafeInsets(scroll);

        TextView warning = new TextView(this);
        warning.setText(getString(R.string.setup_fallback_warning, String.valueOf(failure)));
        warning.setPadding(0, 0, 0, dp(16));
        root.addView(warning);

        statusText = new TextView(this);
        statusText.setTextIsSelectable(true);
        statusText.setPadding(0, 0, 0, dp(24));
        root.addView(statusText);

        addPlainButton(root, getString(R.string.setup_button_select_game_folder), this::onSelectGameFolder);
        addPlainButton(root, getString(R.string.setup_button_view_logs), this::onViewLogs);
        addPlainButton(root, getString(R.string.setup_button_launch_game), this::onLaunchGame);
        addPlainButton(root, getString(R.string.setup_button_clear_game_folder), this::onClearGameFolder);
    }

    private void addPlainButton(LinearLayout root, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        root.addView(b, lp);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // GeneralsX @bugfix Android port 31/07/2026 onLaunchGame() forces this
        // Activity to landscape right before starting the game (see its
        // comment) so the rotation settles before the game's native window-size
        // probe runs. That request otherwise sticks on this Activity instance
        // indefinitely, so coming back here (Back from the game, or from any
        // child screen) left Setup stuck landscape instead of returning to its
        // normal portrait-first state. Reset it every time this screen comes
        // back to the foreground; onLaunchGame() re-applies the landscape lock
        // itself the next time it's needed.
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        refreshStatus();
        refreshGeneralsOnlineStatus();
        loadDxvkConfigIntoEditor();
        refreshDiagnosticsSwitches();
    }

    // The remainder of this source file is unchanged from the repository.
}
