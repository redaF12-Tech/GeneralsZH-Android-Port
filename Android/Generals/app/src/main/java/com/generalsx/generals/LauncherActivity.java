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
// The single launcher screen of the original-Generals Android app
// (com.generalsx.generals — a separate install from the Zero Hour app).
// Responsibilities:
//  1. Pick the game folder (real filesystem path via FolderPickerActivity,
//     backed by MANAGE_EXTERNAL_STORAGE) and remember it.
//  2. Validate it against the original Generals' required .big archives —
//     the 15 base-game archives, everything WITHOUT "ZH" in the name (a
//     Complete Edition install puts both games in one directory; pointing
//     this engine at the ZH archives makes it fail parsing Multiplayer.ini,
//     because the two games' INI schemas differ).
//  3. Extract the small bundled runtime files (fonts/, dxvk.conf,
//     DefaultOptions.ini) from APK assets into the selected folder, which is
//     the engine's working directory (SDL3Main.cpp chdirs there via
//     -gxGameDir).
//  4. Start GeneralsGameActivity only once the folder is valid, so a
//     misconfigured install can never look like (or mask) a native crash.

package com.generalsx.generals;

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class LauncherActivity extends Activity {

    static final String PREFS_NAME = "generals_launcher";
    static final String PREF_GAME_PATH = "game_path";
    static final String EXTRA_GAME_DIR = "gx_game_dir";

    private static final int REQ_PICK_FOLDER = 1;
    private static final int REQ_ALL_FILES_ACCESS = 2;
    private static final int REQ_LEGACY_STORAGE = 3;

    // The original Generals' required archives: the 15 base-game .big files,
    // everything without ZH in the name. INI.big is the hard gate (the
    // engine dies in INI::loadFileDirectory() without it); the count drives
    // the status line so an incomplete copy is visible before launching.
    static final String[] REQUIRED_ARCHIVES = {
        "INI.big", "Terrain.big", "Textures.big", "W3D.big", "Window.big",
        "Audio.big", "Music.big", "English.big", "maps.big", "shaders.big",
        "gensec.big", "Speech.big", "Patch.big", "AudioEnglish.big",
        "SpeechEnglish.big"
    };

    private TextView statusView;
    private MaterialButton launchButton;
    private boolean pendingPickAfterPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(ContextCompat.getColor(this, R.color.gzh_background));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int gutter = dp(20);
        root.setPadding(gutter, 0, gutter, gutter);
        scroll.addView(root, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---- Header -------------------------------------------------------
        TextView title = new TextView(this);
        title.setText(R.string.launcher_title);
        title.setTextColor(ContextCompat.getColor(this, R.color.gzh_primary));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26);
        title.setPadding(0, dp(28), 0, 0);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.launcher_subtitle);
        subtitle.setTextColor(ContextCompat.getColor(this, R.color.gzh_on_surface_variant));
        subtitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        root.addView(subtitle);

        // ---- Folder status card ------------------------------------------
        LinearLayout folderCard = new LinearLayout(this);
        folderCard.setOrientation(LinearLayout.VERTICAL);
        folderCard.setBackgroundResource(R.drawable.gzh_card);
        int cardPad = dp(16);
        folderCard.setPadding(cardPad, cardPad, cardPad, cardPad);
        root.addView(folderCard, matchWrap());

        TextView folderLabel = new TextView(this);
        folderLabel.setText(R.string.launcher_folder_label);
        folderLabel.setTextColor(ContextCompat.getColor(this, R.color.gzh_on_surface_variant));
        folderLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        folderCard.addView(folderLabel);

        statusView = new TextView(this);
        statusView.setTextIsSelectable(true);
        statusView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        statusView.setPadding(0, dp(8), 0, 0);
        folderCard.addView(statusView);

        // ---- Actions ------------------------------------------------------
        MaterialButton pickButton = new MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle);
        pickButton.setText(R.string.launcher_button_pick);
        LinearLayout.LayoutParams pickParams = matchWrap();
        pickParams.topMargin = dp(16);
        root.addView(pickButton, pickParams);
        pickButton.setOnClickListener(v -> onPickFolderClicked());

        launchButton = new MaterialButton(this);
        launchButton.setText(R.string.launcher_button_launch);
        LinearLayout.LayoutParams launchParams = matchWrap();
        launchParams.topMargin = dp(12);
        root.addView(launchButton, launchParams);
        launchButton.setOnClickListener(v -> onLaunchClicked());

        // ---- Required archives card --------------------------------------
        TextView requiredTitle = new TextView(this);
        requiredTitle.setText(R.string.launcher_required_title);
        requiredTitle.setTextColor(ContextCompat.getColor(this, R.color.gzh_on_surface));
        requiredTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        requiredTitle.setPadding(0, dp(28), 0, dp(8));
        root.addView(requiredTitle);

        TextView requiredBody = new TextView(this);
        requiredBody.setText(R.string.launcher_required_body);
        requiredBody.setBackgroundResource(R.drawable.gzh_card);
        requiredBody.setTextColor(ContextCompat.getColor(this, R.color.gzh_on_surface_variant));
        requiredBody.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        requiredBody.setLineSpacing(dp(4), 1f);
        requiredBody.setPadding(cardPad, cardPad, cardPad, cardPad);
        root.addView(requiredBody);

        setContentView(scroll);
        applySafeInsets(scroll);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from the all-files-access Settings screen: if the grant
        // landed, continue straight into the picker the user asked for.
        if (pendingPickAfterPermission && hasStorageAccess()) {
            pendingPickAfterPermission = false;
            openFolderPicker();
        }
        refreshStatus();
    }

    // ---- folder resolution ------------------------------------------------

    static String getSavedGamePath(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_GAME_PATH, null);
    }

    private void saveGamePath(String path) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putString(PREF_GAME_PATH, path).apply();
    }

    /** Default landing spot: this app's own external files dir
     *  (Android/data/com.generalsx.generals/files) — the one location that
     *  works with no permission dance at all, and where a USB/file-manager
     *  copy of the game lands naturally. */
    private File defaultFolder() {
        File dir = getExternalFilesDir(null);
        return dir != null ? dir : new File("/storage/emulated/0/Android/data/com.generalsx.generals/files");
    }

    private File currentFolder() {
        String saved = getSavedGamePath(this);
        return saved != null ? new File(saved) : defaultFolder();
    }

    static boolean isValidGameFolder(File dir) {
        return dir != null && dir.isDirectory() && new File(dir, "INI.big").isFile();
    }

    static int countFoundArchives(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return 0;
        }
        int found = 0;
        for (String name : REQUIRED_ARCHIVES) {
            if (new File(dir, name).isFile()) {
                found++;
            }
        }
        return found;
    }

    private void refreshStatus() {
        File folder = currentFolder();
        boolean usingDefault = getSavedGamePath(this) == null;
        int found = countFoundArchives(folder);
        boolean valid = isValidGameFolder(folder);

        String status;
        int color;
        if (valid && found == REQUIRED_ARCHIVES.length) {
            status = getString(R.string.launcher_status_valid);
            color = ContextCompat.getColor(this, R.color.gzh_status_ok);
        } else if (found > 0) {
            status = getString(R.string.launcher_status_partial, found, REQUIRED_ARCHIVES.length);
            color = ContextCompat.getColor(this, R.color.gzh_status_warn);
        } else {
            status = getString(R.string.launcher_status_invalid);
            color = ContextCompat.getColor(this, R.color.gzh_status_error);
        }
        // Path stays on-surface (readable, selectable); only the verdict line
        // carries the semantic colour, same as the Zero Hour launcher's
        // refreshStatus().
        String pathLine = folder.getAbsolutePath();
        String note = usingDefault ? "\n" + getString(R.string.launcher_default_folder_note) : "";
        String full = pathLine + "\n\n" + status + note;
        android.text.SpannableStringBuilder styled = new android.text.SpannableStringBuilder(full);
        int statusStart = pathLine.length() + 2;
        styled.setSpan(new android.text.style.ForegroundColorSpan(color),
            statusStart, statusStart + status.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        statusView.setText(styled);
        statusView.setTextColor(ContextCompat.getColor(this, R.color.gzh_on_surface));

        launchButton.setEnabled(valid);
        launchButton.setText(valid
            ? R.string.launcher_button_launch
            : R.string.launcher_button_launch_disabled);
    }

    // ---- storage permission -> picker ------------------------------------

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE)
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void onPickFolderClicked() {
        if (hasStorageAccess()) {
            openFolderPicker();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The in-app picker resolves to real filesystem paths, which needs
            // "All files access" on Android 11+. Without it the engine's
            // fopen()/chdir() could not read a folder outside our own dir.
            pendingPickAfterPermission = true;
            try {
                startActivityForResult(new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQ_ALL_FILES_ACCESS);
            } catch (android.content.ActivityNotFoundException e) {
                startActivityForResult(new Intent(
                    Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_ALL_FILES_ACCESS);
            }
        } else {
            requestPermissions(new String[] {
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQ_LEGACY_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LEGACY_STORAGE) {
            if (hasStorageAccess()) {
                openFolderPicker();
            } else {
                Toast.makeText(this, R.string.launcher_permission_needed, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openFolderPicker() {
        startActivityForResult(new Intent(this, FolderPickerActivity.class), REQ_PICK_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FOLDER && resultCode == RESULT_OK && data != null) {
            String path = data.getStringExtra(FolderPickerActivity.EXTRA_SELECTED_PATH);
            if (path != null && !path.isEmpty()) {
                saveGamePath(path);
                refreshStatus();
            }
        }
    }

    // ---- launch ----------------------------------------------------------

    private void onLaunchClicked() {
        final File folder = currentFolder();
        if (!isValidGameFolder(folder)) {
            refreshStatus();
            return;
        }
        launchButton.setEnabled(false);
        Toast.makeText(this, R.string.launcher_extracting, Toast.LENGTH_SHORT).show();
        // Asset extraction touches storage; keep it off the main thread so a
        // slow SD card doesn't jank the launcher.
        new Thread(() -> {
            try {
                prepareRuntimeFiles(folder);
                runOnUiThread(() -> {
                    launchButton.setEnabled(true);
                    Intent intent = new Intent(this, GeneralsGameActivity.class);
                    intent.putExtra(EXTRA_GAME_DIR, folder.getAbsolutePath());
                    startActivity(intent);
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    launchButton.setEnabled(true);
                    Toast.makeText(this,
                        getString(R.string.launcher_extract_failed, String.valueOf(e.getMessage())),
                        Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * Extract the small bundled runtime files from APK assets into the game
     * folder (the engine's working directory):
     *   fonts/            -> fonts/       (Liberation renamed to the Windows
     *                                       names the game requests; Android
     *                                       APK assets are invisible to
     *                                       fopen(), and the engine's font
     *                                       locator expects fonts/*.ttf)
     *   dxvk.conf         -> dxvk.conf    (only when absent: a user-tuned one
     *                                       wins)
     *   DefaultOptions.ini-> DefaultOptions.ini (seed full detail on first
     *                                       run; the 2003 GPU auto-detect
     *                                       would otherwise pick Low LOD)
     */
    private void prepareRuntimeFiles(File gameDir) throws IOException {
        AssetManager assets = getAssets();

        File fontsDir = new File(gameDir, "fonts");
        if (!fontsDir.isDirectory() && !fontsDir.mkdirs()) {
            throw new IOException("cannot create " + fontsDir);
        }
        String[] fonts = assets.list("gamedata/fonts");
        if (fonts != null) {
            for (String name : fonts) {
                copyAsset("gamedata/fonts/" + name, new File(fontsDir, name), true);
            }
        }
        copyAssetIfMissing("gamedata/dxvk.conf", new File(gameDir, "dxvk.conf"));
        copyAssetIfMissing("gamedata/DefaultOptions.ini", new File(gameDir, "DefaultOptions.ini"));
    }

    private void copyAssetIfMissing(String assetPath, File dest) throws IOException {
        if (!dest.isFile()) {
            copyAsset(assetPath, dest, false);
        }
    }

    private void copyAsset(String assetPath, File dest, boolean overwrite) throws IOException {
        if (dest.isFile() && !overwrite) {
            return;
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = getAssets().open(assetPath);
            out = new FileOutputStream(dest);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
            if (out != null) try { out.close(); } catch (IOException ignored) {}
        }
    }

    // ---- helpers ---------------------------------------------------------

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** targetSdk 35 draws behind the system bars; pad the page out from
     *  under them once, at the root. */
    private static void applySafeInsets(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });
    }
}
