/*
** Command & Conquer Generals Zero Hour(tm)
** Copyright 2025 Electronic Arts Inc.
** Licensed under GPL-3.0-or-later. See LICENSE.md.
*/

package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.graphics.Color;
import android.net.Uri;

import java.io.InputStream;
import java.io.OutputStream;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/** Persistent model shared by the Settings editor and the in-game overlay. */
final class TouchControlConfig {
    static final int SHAPE_RECTANGLE = 0;
    static final int SHAPE_ROUNDED = 1;
    static final int SHAPE_SQUARE = 2;
    static final int SHAPE_CIRCLE = 3;
    static final int SHAPE_OVAL = 4;
    static final String PREFS_NAME = "generalszh_touch_controls";
    static final String PREF_ENABLED = "enabled";
    static final String PREF_PAN_SENSITIVITY = "pan_sensitivity";
    static final String PREF_BUTTON_SCALE = "button_scale";
    static final String PREF_BUTTON_OPACITY = "button_opacity";
    static final String PREF_BUTTONS = "buttons";

    static final int MOD_CTRL = 1;
    static final int MOD_SHIFT = 2;
    static final int MOD_ALT = 4;

    static final class ButtonSpec {
        String label;
        int keyCode;
        int modifiers;
        float x;
        float y;
        float widthDp;
        float heightDp;
        int shape;
        int fillColor;
        int borderColor;
        int textColor;

        ButtonSpec(String label, int keyCode, int modifiers, float x, float y) {
            this(label, keyCode, modifiers, x, y, 52f, 52f, SHAPE_ROUNDED,
                Color.rgb(22, 33, 44), Color.rgb(220, 181, 86), Color.WHITE);
        }

        ButtonSpec(String label, int keyCode, int modifiers, float x, float y,
                   float widthDp, float heightDp, int shape, int fillColor,
                   int borderColor, int textColor) {
            this.label = label;
            this.keyCode = keyCode;
            this.modifiers = modifiers;
            this.x = x;
            this.y = y;
            this.widthDp = clamp(widthDp, 28f, 220f);
            this.heightDp = clamp(heightDp, 28f, 220f);
            this.shape = shape;
            this.fillColor = fillColor;
            this.borderColor = borderColor;
            this.textColor = textColor;
        }

        ButtonSpec copy() {
            return new ButtonSpec(label, keyCode, modifiers, x, y, widthDp, heightDp,
                shape, fillColor, borderColor, textColor);
        }
    }

    boolean enabled = true;
    float panSensitivity = 1.0f;
    float buttonScale = 1.0f;
    float buttonOpacity = 0.78f;
    final ArrayList<ButtonSpec> buttons = new ArrayList<>();

    private static final int[] KEY_CODES = buildKeyCodes();
    private static final String[] KEY_NAMES = buildKeyNames();

    private static int[] buildKeyCodes() {
        ArrayList<Integer> codes = new ArrayList<>();
        for (int code = KeyEvent.KEYCODE_A; code <= KeyEvent.KEYCODE_Z; ++code) codes.add(code);
        for (int code = KeyEvent.KEYCODE_0; code <= KeyEvent.KEYCODE_9; ++code) codes.add(code);
        codes.add(KeyEvent.KEYCODE_SPACE);
        codes.add(KeyEvent.KEYCODE_TAB);
        codes.add(KeyEvent.KEYCODE_ESCAPE);
        codes.add(KeyEvent.KEYCODE_ENTER);
        codes.add(KeyEvent.KEYCODE_DEL);
        codes.add(KeyEvent.KEYCODE_DPAD_UP);
        codes.add(KeyEvent.KEYCODE_DPAD_DOWN);
        codes.add(KeyEvent.KEYCODE_DPAD_LEFT);
        codes.add(KeyEvent.KEYCODE_DPAD_RIGHT);
        for (int code = KeyEvent.KEYCODE_F1; code <= KeyEvent.KEYCODE_F12; ++code) codes.add(code);
        int[] result = new int[codes.size()];
        for (int i = 0; i < codes.size(); ++i) result[i] = codes.get(i);
        return result;
    }

    private static String[] buildKeyNames() {
        String[] result = new String[KEY_CODES.length];
        for (int i = 0; i < KEY_CODES.length; ++i) result[i] = displayNameForKey(KEY_CODES[i]);
        return result;
    }

    static int[] keyCodes() {
        return KEY_CODES.clone();
    }

    static String[] keyNames() {
        return KEY_NAMES.clone();
    }

    static String displayNameForKey(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return String.valueOf((char)('A' + keyCode - KeyEvent.KEYCODE_A));
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return String.valueOf((char)('0' + keyCode - KeyEvent.KEYCODE_0));
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            return "F" + (keyCode - KeyEvent.KEYCODE_F1 + 1);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE: return "SPACE";
            case KeyEvent.KEYCODE_TAB: return "TAB";
            case KeyEvent.KEYCODE_ESCAPE: return "ESC";
            case KeyEvent.KEYCODE_ENTER: return "ENTER";
            case KeyEvent.KEYCODE_DEL: return "BACK";
            case KeyEvent.KEYCODE_DPAD_UP: return "UP";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "DOWN";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "RIGHT";
            default: return "KEY " + keyCode;
        }
    }

    static TouchControlConfig defaults() {
        TouchControlConfig config = new TouchControlConfig();
        String[] labels = { "E", "Q", "W", "R", "A", "S", "D", "F", "SPACE", "ESC" };
        int[] keys = {
            KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_R,
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ESCAPE
        };
        for (int i = 0; i < labels.length; ++i) {
            float x = 0.065f + i * 0.096f;
            config.buttons.add(new ButtonSpec(labels[i], keys[i], 0, x, 0.675f));
        }
        return config;
    }

    static TouchControlConfig load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.contains(PREF_BUTTONS)) return defaults();

        TouchControlConfig config = new TouchControlConfig();
        config.enabled = prefs.getBoolean(PREF_ENABLED, true);
        config.panSensitivity = clamp(prefs.getFloat(PREF_PAN_SENSITIVITY, 1.0f), 0.35f, 2.5f);
        config.buttonScale = clamp(prefs.getFloat(PREF_BUTTON_SCALE, 1.0f), 0.65f, 1.75f);
        config.buttonOpacity = clamp(prefs.getFloat(PREF_BUTTON_OPACITY, 0.78f), 0.25f, 1.0f);
        try {
            JSONArray array = new JSONArray(prefs.getString(PREF_BUTTONS, "[]"));
            for (int i = 0; i < array.length() && i < 20; ++i) {
                JSONObject item = array.getJSONObject(i);
                String label = item.optString("label", "?");
                int keyCode = item.optInt("key", KeyEvent.KEYCODE_UNKNOWN);
                int modifiers = item.optInt("modifiers", 0) & (MOD_CTRL | MOD_SHIFT | MOD_ALT);
                float x = clamp((float)item.optDouble("x", 0.5), 0.02f, 0.98f);
                float y = clamp((float)item.optDouble("y", 0.5), 0.04f, 0.96f);
                float widthDp = clamp((float)item.optDouble("widthDp", 52.0), 28f, 220f);
                float heightDp = clamp((float)item.optDouble("heightDp", 52.0), 28f, 220f);
                int shape = item.optInt("shape", SHAPE_ROUNDED);
                if (shape < SHAPE_RECTANGLE || shape > SHAPE_OVAL) shape = SHAPE_ROUNDED;
                if (shape == SHAPE_SQUARE || shape == SHAPE_CIRCLE) {
                    float side = Math.min(widthDp, heightDp);
                    widthDp = side; heightDp = side;
                }
                int fillColor = parseColor(item.optString("fillColor", "#161F2C"), Color.rgb(22, 33, 44));
                int borderColor = parseColor(item.optString("borderColor", "#DCB556"), Color.rgb(220, 181, 86));
                int textColor = parseColor(item.optString("textColor", "#FFFFFF"), Color.WHITE);
                if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    config.buttons.add(new ButtonSpec(label, keyCode, modifiers, x, y,
                        widthDp, heightDp, shape, fillColor, borderColor, textColor));
                }
            }
        } catch (Exception ignored) {
            return defaults();
        }
        return config;
    }

    void save(Context context) {
        JSONArray array = new JSONArray();
        try {
            for (ButtonSpec button : buttons) {
                JSONObject item = new JSONObject();
                item.put("label", button.label);
                item.put("key", button.keyCode);
                item.put("modifiers", button.modifiers);
                item.put("x", button.x);
                item.put("y", button.y);
                item.put("widthDp", button.widthDp);
                item.put("heightDp", button.heightDp);
                item.put("shape", button.shape);
                item.put("fillColor", colorToString(button.fillColor));
                item.put("borderColor", colorToString(button.borderColor));
                item.put("textColor", colorToString(button.textColor));
                array.put(item);
            }
        } catch (Exception ignored) {
            // org.json only throws for non-finite numbers; all coordinates are clamped.
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_ENABLED, enabled)
            .putFloat(PREF_PAN_SENSITIVITY, clamp(panSensitivity, 0.35f, 2.5f))
            .putFloat(PREF_BUTTON_SCALE, clamp(buttonScale, 0.65f, 1.75f))
            .putFloat(PREF_BUTTON_OPACITY, clamp(buttonOpacity, 0.25f, 1.0f))
            .putString(PREF_BUTTONS, array.toString())
            .apply();
    }

    /**
     * Export the complete touch-button profile as a portable JSON file.
     * The file contains the button mapping plus position, size, shape, colors
     * and global touch settings, so it can be imported on another device.
     */
    void exportTo(Context context, Uri uri) throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", "GeneralsZHTouchControls");
        root.put("version", 2);
        root.put("enabled", enabled);
        root.put("panSensitivity", panSensitivity);
        root.put("buttonScale", buttonScale);
        root.put("buttonOpacity", buttonOpacity);

        JSONArray array = new JSONArray();
        for (ButtonSpec button : buttons) {
            JSONObject item = new JSONObject();
            item.put("label", button.label);
            item.put("key", button.keyCode);
            item.put("keyName", displayNameForKey(button.keyCode));
            item.put("modifiers", button.modifiers);
            item.put("x", button.x);
            item.put("y", button.y);
            item.put("widthDp", button.widthDp);
            item.put("heightDp", button.heightDp);
            item.put("shape", button.shape);
            item.put("fillColor", colorToString(button.fillColor));
            item.put("borderColor", colorToString(button.borderColor));
            item.put("textColor", colorToString(button.textColor));
            array.put(item);
        }
        root.put("buttons", array);

        try (OutputStream output = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new Exception("Unable to open export file");
            output.write(root.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.flush();
        }
    }

    /**
     * Import a portable JSON profile. The current profile is not changed if
     * parsing/validation fails. Older exports without global settings are also
     * accepted as long as they contain a buttons array.
     */
    static TouchControlConfig importFrom(Context context, Uri uri) throws Exception {
        StringBuilder json = new StringBuilder();
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new Exception("Unable to open import file");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                json.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
                if (json.length() > 1024 * 1024) throw new Exception("Import file is too large");
            }
        }

        JSONObject root = new JSONObject(json.toString());
        JSONArray array = root.optJSONArray("buttons");
        if (array == null) throw new Exception("No buttons mapping found");
        if (array.length() > 20) throw new Exception("The profile contains more than 20 buttons");

        TouchControlConfig config = new TouchControlConfig();
        config.enabled = root.optBoolean("enabled", true);
        config.panSensitivity = clamp((float)root.optDouble("panSensitivity", 1.0), 0.35f, 2.5f);
        config.buttonScale = clamp((float)root.optDouble("buttonScale", 1.0), 0.65f, 1.75f);
        config.buttonOpacity = clamp((float)root.optDouble("buttonOpacity", 0.78), 0.25f, 1.0f);

        for (int i = 0; i < array.length(); ++i) {
            JSONObject item = array.getJSONObject(i);
            int keyCode = item.optInt("key", KeyEvent.KEYCODE_UNKNOWN);
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) continue;

            String label = item.optString("label", displayNameForKey(keyCode));
            int modifiers = item.optInt("modifiers", 0) & (MOD_CTRL | MOD_SHIFT | MOD_ALT);
            float x = clamp((float)item.optDouble("x", 0.5), 0.02f, 0.98f);
            float y = clamp((float)item.optDouble("y", 0.5), 0.04f, 0.96f);
            float widthDp = clamp((float)item.optDouble("widthDp", 52.0), 28f, 220f);
            float heightDp = clamp((float)item.optDouble("heightDp", 52.0), 28f, 220f);
            int shape = item.optInt("shape", SHAPE_ROUNDED);
            if (shape < SHAPE_RECTANGLE || shape > SHAPE_OVAL) shape = SHAPE_ROUNDED;
            if (shape == SHAPE_SQUARE || shape == SHAPE_CIRCLE) {
                float side = Math.min(widthDp, heightDp);
                widthDp = side;
                heightDp = side;
            }
            int fillColor = parseColor(item.optString("fillColor", "#161F2C"), Color.rgb(22, 33, 44));
            int borderColor = parseColor(item.optString("borderColor", "#DCB556"), Color.rgb(220, 181, 86));
            int textColor = parseColor(item.optString("textColor", "#FFFFFF"), Color.WHITE);
            config.buttons.add(new ButtonSpec(label, keyCode, modifiers, x, y,
                widthDp, heightDp, shape, fillColor, borderColor, textColor));
        }
        if (config.buttons.isEmpty()) throw new Exception("The profile contains no valid buttons");
        return config;
    }

    static void reset(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /**
     * Synchronize the Java-only editor state with the native touch path before libmain.so starts.
     * The native engine only needs pan sensitivity; overlay buttons stay in SharedPreferences.
     */
    static void prepareForLaunch(Context context, File gameFolder) {
        if (gameFolder == null) return;
        TouchControlConfig config = load(context);
        File nativeConfig = new File(gameFolder, "GeneralsXTouch.ini");
        try (FileWriter writer = new FileWriter(nativeConfig, false)) {
            writer.write("PanSensitivity=" + config.panSensitivity + "\n");
        } catch (Exception ignored) {
            // A missing config safely falls back to 1.0 in native code.
        }

        File groupPanel = new File(new File(gameFolder, "Window"), "GroupPanel.wnd");
        if (config.enabled) {
            // The configurable Android overlay replaces the old fixed 0-9 panel.
            // This file is one of our generated overrides, never user game data.
            if (groupPanel.isFile()) groupPanel.delete();
        } else {
            File bundledRoot = context.getExternalFilesDir(null);
            if (bundledRoot != null) {
                SetupActivity.copyBundledRuntimeIfMissing(bundledRoot, gameFolder.getAbsolutePath());
            }
        }
    }


    private static int parseColor(String value, int fallback) {
        try { return Color.parseColor(value); } catch (Exception ignored) { return fallback; }
    }

    static String colorToString(int color) {
        return String.format("#%08X", color);
    }

    static List<ButtonSpec> copyButtons(List<ButtonSpec> source) {
        ArrayList<ButtonSpec> copy = new ArrayList<>();
        for (ButtonSpec button : source) copy.add(button.copy());
        return copy;
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
