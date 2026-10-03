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
//  4. Pick the render backend (GLES / GLES+ANGLE / Vulkan) — see the
//     RENDER_BACKEND_* comment block below.
//  5. Start GeneralsGameActivity only once the folder is valid, so a
//     misconfigured install can never look like (or mask) a native crash.
//
// Core/Generals 03/10/2026 — why the render backend needs a picker here.
// The engine ships two D3D8 implementations (Core's d3d8gles GLES layer, and
// DXVK's Direct3D8 -> Vulkan) and picks one at runtime in DX8Wrapper::Init().
// Which one is chosen also decides which kind of SDL window SDL3Main.cpp must
// create, so the two must agree — see d3d8gles.h. That choice used to be
// reachable only through GENERALSX_RENDER_BACKEND/GENERALSX_GLES_ANGLE adb
// environment variables, i.e. not at all for a normal user. Without a picker
// the app had no way to switch a device that renders incorrectly (or not at
// all), which is exactly the black-screen class of report this section exists
// to make diagnosable.

package com.generalsx.generals;

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

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

    // ---- render backend (Core/Generals 03/10/2026) ------------------------
    // The marker file is written to getFilesDir(), which is exactly what
    // SDL_GetAndroidInternalStoragePath() returns on the native side (SDL3's
    // implementation is literally context.getFilesDir() -- see
    // src/core/android/SDL_android.c). d3d8gles_ShouldUseVulkanBackend() and
    // d3d8gles_ShouldUseANGLE() read <that path>/render_backend.cfg before the
    // game starts and fall back to GLES when the file is absent. Identical
    // file name, location and values as the Zero Hour launcher, so the two
    // engines cannot drift apart on the format.
    private static final String RENDER_BACKEND_CFG_NAME = "render_backend.cfg";
    private static final String RENDER_BACKEND_VULKAN = "vulkan";
    private static final String RENDER_BACKEND_GLES = "gles";
    private static final String RENDER_BACKEND_GLES_ANGLE = "gles_angle";

    // GLES first: the order is itself the recommendation, and GLES is what the
    // native side defaults to with no config file present (and what has been
    // verified on every device so far). Vulkan is opt-in because it is the one
    // that depends on the phone's own driver.
    private static final String[] RENDER_BACKEND_CHOICES = {
        RENDER_BACKEND_GLES, RENDER_BACKEND_GLES_ANGLE, RENDER_BACKEND_VULKAN
    };

    private TextView renderBackendStatusView;
    private MaterialButtonToggleGroup renderBackendGroup;
    // Kept alongside the group so a failed save can put the lit segment back
    // on the value that is actually in effect.
    private final int[] renderBackendButtonIds = new int[RENDER_BACKEND_CHOICES.length];
    private String cachedGpuName;

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

        // ---- Graphics / render backend card -------------------------------
        // Placed right after the primary action and before the reference
        // archives list: it is the first thing to reach for when the game
        // runs but renders wrong (black screen, corrupted geometry), and it
        // must stay reachable regardless of whether the game folder is valid.
        root.addView(buildGraphicsCard(cardPad), matchWrap());

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

    // ---- render backend --------------------------------------------------

    /**
     * Build the "Graphics" card: a three-way segmented control over the render
     * backends, the current selection, the device's own GPU name, and the
     * explanation of what each option does.
     *
     * The segmented control is built inline rather than pulled into a shared
     * UiKit helper: this app is a three-activity launcher with no other screen
     * needing one, and copying a helper for a single call site would be more
     * code, not less. The colours are the same gzh_* tokens the Zero Hour
     * UiKit uses, so both launchers look like one product family.
     */
    private View buildGraphicsCard(int cardPad) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.gzh_card);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);

        TextView label = new TextView(this);
        label.setText(R.string.launcher_graphics_backend_label);
        label.setTextColor(ContextCompat.getColor(this, R.color.gzh_on_surface_variant));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        card.addView(label);

        renderBackendStatusView = new TextView(this);
        renderBackendStatusView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        renderBackendStatusView.setPadding(0, dp(8), 0, 0);
        card.addView(renderBackendStatusView);

        // --- segmented control ---
        String current = getRenderBackendChoice();
        int currentIndex = 0;
        CharSequence[] labels = new CharSequence[RENDER_BACKEND_CHOICES.length];
        for (int i = 0; i < RENDER_BACKEND_CHOICES.length; i++) {
            labels[i] = shortRenderBackendLabel(RENDER_BACKEND_CHOICES[i]);
            if (RENDER_BACKEND_CHOICES[i].equals(current)) {
                currentIndex = i;
            }
        }

        MaterialButtonToggleGroup group = new MaterialButtonToggleGroup(this);
        group.setSingleSelection(true);
        // Required, because the config file must always name one backend: an
        // unselected group would leave the previous value in place while the UI
        // claimed something else.
        group.setSelectionRequired(true);

        final int[] ids = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            MaterialButton b = new MaterialButton(this);
            b.setId(View.generateViewId());
            ids[i] = b.getId();
            renderBackendButtonIds[i] = ids[i];
            b.setText(labels[i]);
            b.setAllCaps(false);
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
            b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            b.setMaxLines(1);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            b.setCornerRadius(dp(22));
            b.setInsetTop(0);
            b.setInsetBottom(0);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(dp(46));
            b.setPadding(dp(6), dp(10), dp(6), dp(10));
            b.setGravity(Gravity.CENTER);
            b.setElevation(0f);
            b.setStateListAnimator(null);
            b.setStrokeWidth(Math.max(1, dp(1)));
            b.setStrokeColor(checkedTint(R.color.gzh_primary, R.color.gzh_outline));
            b.setBackgroundTintList(checkedTint(R.color.gzh_primary, android.R.color.transparent));
            b.setTextColor(checkedTint(R.color.gzh_on_primary, R.color.gzh_on_surface_variant));
            b.setRippleColor(plainTint(R.color.gzh_ripple_primary));
            group.addView(b, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        // check() BEFORE attaching the listener: group.check() fires
        // onButtonChecked. onRenderBackendSelected()'s own guard compares
        // against the value on disk, which already covers this case, so the
        // ordering here is about not doing pointless work on every launch.
        group.check(ids[currentIndex]);
        renderBackendGroup = group;
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == checkedId) {
                    onRenderBackendSelected(RENDER_BACKEND_CHOICES[i]);
                    return;
                }
            }
        });

        LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        groupParams.topMargin = dp(16);
        card.addView(group, groupParams);
        // One spoken label for the whole row of three brand names, instead of
        // a screen reader announcing them as unrelated buttons.
        group.setContentDescription(getString(R.string.launcher_graphics_backend_label));

        renderBackendStatusView.setText(
            getString(R.string.launcher_graphics_current, renderBackendLabel(current)));

        // --- GPU name ---
        // Shown, not acted on. Every driver-specific rendering report on this
        // project has been a GPU family rather than a device model, and the
        // backend choice is the only thing this setting actually turns on --
        // so knowing which GPU the driver reports is the diagnostic that lets
        // the user decide. Auto-switching on a string match would be guesswork.
        String gpu = detectGpuName();
        if (!gpu.isEmpty()) {
            TextView gpuView = new TextView(this);
            gpuView.setText(getString(R.string.launcher_graphics_gpu, gpu));
            gpuView.setTextColor(ContextCompat.getColor(this, R.color.gzh_on_surface_variant));
            gpuView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            gpuView.setPadding(0, dp(12), 0, 0);
            card.addView(gpuView);
        }

        TextView help = new TextView(this);
        help.setText(R.string.launcher_graphics_help);
        help.setTextColor(ContextCompat.getColor(this, R.color.gzh_on_surface_variant));
        help.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        help.setLineSpacing(dp(4), 1f);
        help.setPadding(0, dp(12), 0, 0);
        card.addView(help);

        return card;
    }

    /**
     * The configured backend, or GLES when there is no config file yet or it
     * holds anything unrecognised. A fresh install deliberately gets GLES --
     * the same value the native side defaults to with the file absent -- rather
     * than a "best for this device" guess, so adding the picker does not
     * silently change the renderer on an existing install.
     */
    private String getRenderBackendChoice() {
        File cfg = new File(getFilesDir(), RENDER_BACKEND_CFG_NAME);
        if (!cfg.isFile()) {
            return RENDER_BACKEND_GLES;
        }
        String value = readFirstLine(cfg);
        if (RENDER_BACKEND_VULKAN.equals(value) || RENDER_BACKEND_GLES_ANGLE.equals(value)) {
            return value;
        }
        return RENDER_BACKEND_GLES;
    }

    /**
     * Persist a backend selection.
     *
     * The guard compares against the value on disk rather than against "what
     * was lit when the screen opened", because both callers that can arrive
     * here with a no-change selection must be filtered: the programmatic
     * group.check() done while building the card, and the re-check below after
     * a failed write. Both pass a value that already equals what is on disk, so
     * both return here without a pointless save or a spurious "Saved" toast.
     */
    private void onRenderBackendSelected(String choice) {
        String onDisk = getRenderBackendChoice();
        if (choice.equals(onDisk)) {
            return;
        }
        File cfg = new File(getFilesDir(), RENDER_BACKEND_CFG_NAME);
        try (java.io.FileWriter w = new java.io.FileWriter(cfg, false)) {
            w.write(choice);
            w.write("\n");
        } catch (java.io.IOException e) {
            Toast.makeText(this,
                getString(R.string.launcher_graphics_save_failed, String.valueOf(e.getMessage())),
                Toast.LENGTH_LONG).show();
            // Nothing was written, so put the lit segment back on the value
            // that IS in effect -- otherwise the control would claim a setting
            // the engine will not use. That re-check calls straight back into
            // here with choice == onDisk and stops at the guard above, so it
            // settles instead of looping.
            syncRenderBackendGroup(onDisk);
            return;
        }
        Toast.makeText(this, R.string.launcher_graphics_saved, Toast.LENGTH_LONG).show();
        refreshRenderBackend();
    }

    /** Re-light the segment for {@code choice} and repaint the "Current:" line. */
    private void syncRenderBackendGroup(String choice) {
        for (int i = 0; i < RENDER_BACKEND_CHOICES.length; i++) {
            if (RENDER_BACKEND_CHOICES[i].equals(choice)) {
                if (renderBackendGroup != null) {
                    renderBackendGroup.check(renderBackendButtonIds[i]);
                }
                break;
            }
        }
        refreshRenderBackend();
    }

    /** Re-read the marker file and repaint the "Current:" line. */
    private void refreshRenderBackend() {
        if (renderBackendStatusView != null) {
            renderBackendStatusView.setText(getString(R.string.launcher_graphics_current,
                renderBackendLabel(getRenderBackendChoice())));
        }
    }

    private String readFirstLine(File f) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String line = r.readLine();
            return line != null ? line.trim() : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private String shortRenderBackendLabel(String choice) {
        switch (choice) {
            case RENDER_BACKEND_VULKAN:
                return getString(R.string.launcher_graphics_vulkan_short);
            case RENDER_BACKEND_GLES_ANGLE:
                return getString(R.string.launcher_graphics_gles_angle_short);
            default:
                return getString(R.string.launcher_graphics_gles_short);
        }
    }

    private String renderBackendLabel(String choice) {
        switch (choice) {
            case RENDER_BACKEND_VULKAN:
                return getString(R.string.launcher_graphics_vulkan);
            case RENDER_BACKEND_GLES_ANGLE:
                return getString(R.string.launcher_graphics_gles_angle);
            default:
                return getString(R.string.launcher_graphics_gles);
        }
    }

    private ColorStateList plainTint(int colorRes) {
        return ColorStateList.valueOf(ContextCompat.getColor(this, colorRes));
    }

    private ColorStateList checkedTint(int checkedRes, int uncheckedRes) {
        return new ColorStateList(
            new int[][] { new int[] { android.R.attr.state_checked }, new int[0] },
            new int[] { ContextCompat.getColor(this, checkedRes),
                        ContextCompat.getColor(this, uncheckedRes) });
    }

    /**
     * The GPU name as its own driver reports it (GL_RENDERER), via a throwaway
     * 1x1 pbuffer EGL context.
     *
     * Everything is best-effort: any failure returns "", because a launcher must
     * never fail to open because a driver misbehaved while being asked its own
     * name, and the backend choice must stay usable without this line.
     */
    private String detectGpuName() {
        if (cachedGpuName != null) {
            return cachedGpuName;
        }
        cachedGpuName = "";
        android.opengl.EGLDisplay display = android.opengl.EGL14.EGL_NO_DISPLAY;
        android.opengl.EGLContext context = android.opengl.EGL14.EGL_NO_CONTEXT;
        android.opengl.EGLSurface surface = android.opengl.EGL14.EGL_NO_SURFACE;
        try {
            display = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY);
            if (display == android.opengl.EGL14.EGL_NO_DISPLAY) {
                return cachedGpuName;
            }
            int[] version = new int[2];
            if (!android.opengl.EGL14.eglInitialize(display, version, 0, version, 1)) {
                return cachedGpuName;
            }
            int[] cfgAttribs = {
                android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT,
                android.opengl.EGL14.EGL_NONE
            };
            android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!android.opengl.EGL14.eglChooseConfig(display, cfgAttribs, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] == 0) {
                return cachedGpuName;
            }
            int[] ctxAttribs = {
                android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE
            };
            context = android.opengl.EGL14.eglCreateContext(display, configs[0],
                android.opengl.EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
            if (context == android.opengl.EGL14.EGL_NO_CONTEXT) {
                return cachedGpuName;
            }
            int[] surfAttribs = {
                android.opengl.EGL14.EGL_WIDTH, 1,
                android.opengl.EGL14.EGL_HEIGHT, 1,
                android.opengl.EGL14.EGL_NONE
            };
            surface = android.opengl.EGL14.eglCreatePbufferSurface(display, configs[0], surfAttribs, 0);
            if (surface == android.opengl.EGL14.EGL_NO_SURFACE) {
                return cachedGpuName;
            }
            if (!android.opengl.EGL14.eglMakeCurrent(display, surface, surface, context)) {
                return cachedGpuName;
            }
            String renderer = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER);
            if (renderer != null && !renderer.isEmpty()) {
                cachedGpuName = renderer;
            }
        } catch (Throwable t) {
            cachedGpuName = "";
        } finally {
            try {
                if (display != android.opengl.EGL14.EGL_NO_DISPLAY) {
                    android.opengl.EGL14.eglMakeCurrent(display, android.opengl.EGL14.EGL_NO_SURFACE,
                        android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT);
                    if (surface != android.opengl.EGL14.EGL_NO_SURFACE) {
                        android.opengl.EGL14.eglDestroySurface(display, surface);
                    }
                    if (context != android.opengl.EGL14.EGL_NO_CONTEXT) {
                        android.opengl.EGL14.eglDestroyContext(display, context);
                    }
                    android.opengl.EGL14.eglTerminate(display);
                }
            } catch (Throwable ignored) {
                // nothing useful to do here
            }
        }
        return cachedGpuName;
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
