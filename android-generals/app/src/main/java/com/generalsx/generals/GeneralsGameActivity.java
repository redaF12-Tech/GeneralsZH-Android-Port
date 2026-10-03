/*
**  Command & Conquer Generals(tm)
**  Copyright 2025 Electronic Arts Inc.
**
**  This program is free software: you can redistribute it and/or modify
**  it under the terms of the GNU General Public License as published by
**  the Free Software Foundation, either version 3 of the License, or
**  (at your option) any later version.
**
**  This program is distributed in the hope that it will be useful,
**  but WITHOUT ANY WARRANTY; without even the implied warranty of
**  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**  GNU General Public License for more details.
**
**  You should have received a copy of the GNU General Public License
**  along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// Core/Generals 02/10/2026
//
// Thin shell over SDL3's SDLActivity for the original-Generals app.
// Responsibilities:
//  1. Name the native libraries to load (libmain.so = the g_generals engine,
//     built with preset android-vulkan + -DRTS_BUILD_GENERALS=ON).
//  2. Pass the launcher-selected game folder to the engine as
//     -gxGameDir <path>; SDL3Main.cpp chdirs there before any engine code
//     runs, which is what points the game at the user's own files.
//
// Unlike the Zero Hour app there is no second engine (libmain60), no update
// manager and no replay plumbing here: this app carries exactly one engine.

package com.generalsx.generals;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.libsdl.app.SDLActivity;

public class GeneralsGameActivity extends SDLActivity {

    private static final String TAG = "GeneralsGame";

    @Override
    protected String[] getLibraries() {
        // SDL3 first, then the game itself (g_generals target -> libmain.so).
        // Its DT_NEEDED entries (SDL3_image, openal, c++_shared, gamespy,
        // adrenotools) resolve from the same APK; the DXVK d3d8/d3d9
        // libraries are dlopen()ed by the engine at D3D init.
        return new String[] { "SDL3", "main" };
    }

    @Override
    protected String[] getArguments() {
        String dir = resolveGameDir();
        if (dir == null || dir.isEmpty()) {
            // Should not happen — LauncherActivity validates before starting
            // this activity — but never hand the engine a half-argument.
            Log.w(TAG, "no game dir available; engine will stay in the process CWD");
            return new String[0];
        }
        Log.i(TAG, "launching engine with -gxGameDir " + dir);
        return new String[] { "-gxGameDir", dir };
    }

    private String resolveGameDir() {
        Intent intent = getIntent();
        if (intent != null) {
            String dir = intent.getStringExtra(LauncherActivity.EXTRA_GAME_DIR);
            if (dir != null && !dir.isEmpty()) {
                return dir;
            }
        }
        // Relaunch via a home-screen shortcut: fall back to the saved path.
        return LauncherActivity.getSavedGamePath(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Check BEFORE super.onCreate(): SDLActivity bootstraps the native
        // side (loadLibraries -> dlopen libmain.so) inside its own onCreate,
        // so a misconfigured install must be redirected before that runs —
        // otherwise a missing game folder could look like (or mask) a native
        // crash. The launcher validates before starting us, so this only
        // fires when the user cleared app storage while the game was
        // backgrounded. Same ordering as the Zero Hour app's
        // GeneralsZHActivity.
        if (resolveGameDir() == null) {
            Log.w(TAG, "no configured game folder; returning to launcher");
            finish();
            startActivity(new Intent(this, LauncherActivity.class));
            return;
        }
        super.onCreate(savedInstanceState);
    }

    // SDL3's native window creation calls setOrientationBis() over JNI; for a
    // non-resizable window without an orientations hint it would apply the
    // sensor landscape orientation and re-enable accelerometer rotation.
    // Pin to absolute landscape unconditionally (same fix as the Zero Hour
    // app).
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }
}
