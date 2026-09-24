/*
** Command & Conquer Generals Zero Hour(tm)
** Copyright 2025 Electronic Arts Inc.
** Licensed under GPL-3.0-or-later. See LICENSE.md.
*/

package com.generalsx.zerohour;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

import java.io.File;
import java.util.ArrayList;

/** Landscape editor for the hotkey overlay shown above the running game. */
public class TouchControlsActivity extends Activity {
    private TouchControlConfig config;
    private EditorCanvas editor;
    private SwitchCompat enabledSwitch;
    private TextView selectedText;
    private TextView panValue;
    private TextView sizeValue;
    private TextView opacityValue;
    private SeekBar panSeek;
    private SeekBar sizeSeek;
    private SeekBar opacitySeek;
    private int selectedIndex = -1;

    private static final int REQUEST_EXPORT_CONTROLS = 7101;
    private static final int REQUEST_IMPORT_CONTROLS = 7102;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // GeneralsX @feature Codex 28/08/2026 Allow either landscape rotation for cabled controllers.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setTitle(R.string.touch_editor_title);
        config = TouchControlConfig.load(this);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(6), dp(8), dp(6));
        root.setBackgroundColor(Color.rgb(11, 17, 23));
        setContentView(root);
        InsetUtil.applySafeInsets(root);

        // GeneralsX @bugfix Android port 28/08/2026 Two toolbar rows: the
        // single-row layout crammed the enable switch, the selection label,
        // AND five action buttons into one line, so on narrow landscape
        // phones the label shrank to nothing and buttons got clipped. The
        // status row (switch + what-is-selected) stays fixed; the action
        // row scrolls horizontally when it does not fit.
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        enabledSwitch = new SwitchCompat(this);
        enabledSwitch.setText(R.string.touch_overlay_enabled);
        enabledSwitch.setChecked(config.enabled);
        statusRow.addView(enabledSwitch);

        selectedText = new TextView(this);
        selectedText.setTextColor(Color.WHITE);
        selectedText.setPadding(dp(12), 0, dp(8), 0);
        statusRow.addView(selectedText, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(statusRow);

        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        addToolbarButton(actionsRow, R.string.touch_add_button, this::addNewButton);
        addToolbarButton(actionsRow, R.string.touch_edit_button, this::editSelectedButton);
        addToolbarButton(actionsRow, R.string.touch_delete_button, this::deleteSelectedButton);
        addToolbarButton(actionsRow, R.string.touch_reset_button, this::resetDefaults);
        addToolbarButton(actionsRow, R.string.touch_save_button, this::saveAndFinish);
        addToolbarButton(actionsRow, R.string.touch_export_button, this::exportControls);
        addToolbarButton(actionsRow, R.string.touch_import_button, this::importControls);
        android.widget.HorizontalScrollView actionsScroll = new android.widget.HorizontalScrollView(this);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actionsScroll.addView(actionsRow, new android.widget.HorizontalScrollView.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(actionsScroll);

        editor = new EditorCanvas();
        root.addView(editor, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout sliders = new LinearLayout(this);
        sliders.setOrientation(LinearLayout.HORIZONTAL);
        sliders.setPadding(0, dp(4), 0, 0);
        panValue = addSlider(sliders, R.string.touch_pan_speed, 35, 250,
            Math.round(config.panSensitivity * 100f), value -> config.panSensitivity = value / 100f);
        panSeek = (SeekBar)panValue.getTag();
        sizeValue = addSlider(sliders, R.string.touch_button_size, 65, 175,
            Math.round(config.buttonScale * 100f), value -> {
                config.buttonScale = value / 100f;
                editor.invalidate();
            });
        sizeSeek = (SeekBar)sizeValue.getTag();
        opacityValue = addSlider(sliders, R.string.touch_button_opacity, 25, 100,
            Math.round(config.buttonOpacity * 100f), value -> {
                config.buttonOpacity = value / 100f;
                editor.invalidate();
            });
        opacitySeek = (SeekBar)opacityValue.getTag();
        root.addView(sliders);
        refreshLabels();
    }

    private interface SliderValueListener { void onValue(int value); }

    private TextView addSlider(LinearLayout row, int titleRes, int min, int max,
                               int initial, SliderValueListener listener) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), 0, dp(8), 0);
        TextView label = new TextView(this);
        label.setTextColor(Color.WHITE);
        box.addView(label);
        SeekBar seek = new SeekBar(this);
        seek.setMin(min);
        seek.setMax(max);
        seek.setProgress(initial);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                listener.onValue(value);
                label.setText(getString(titleRes, value));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        box.addView(seek);
        row.addView(box, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        label.setTag(seek);
        label.setText(getString(titleRes, initial));
        return label;
    }

    private void addToolbarButton(LinearLayout row, int textRes, Runnable action) {
        Button button = new Button(this);
        button.setText(textRes);
        button.setTextSize(12);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(9), 0, dp(9), 0);
        button.setOnClickListener(v -> action.run());
        row.addView(button);
    }

    private void addNewButton() {
        if (config.buttons.size() >= 20) {
            Toast.makeText(this, R.string.touch_too_many_buttons, Toast.LENGTH_SHORT).show();
            return;
        }
        // GeneralsX @bugfix Android port 28/08/2026 Spawn at the canvas
        // center: the old (0, 0.5) anchor drew a fresh button HALF OFF the
        // left edge (x is a fraction of the width, so 0 centered the rect on
        // the border), and it stayed there until the user happened to drag
        // it back. The editor's drag clamp (x >= 0.025) only applies to
        // drags, never to this initial placement.
        config.buttons.add(new TouchControlConfig.ButtonSpec("E", android.view.KeyEvent.KEYCODE_E,
            0, 0.5f, 0.5f));
        selectedIndex = config.buttons.size() - 1;
        editor.invalidate();
        refreshLabels();
        editSelectedButton();
    }

    private void deleteSelectedButton() {
        if (selectedIndex < 0 || selectedIndex >= config.buttons.size()) return;
        config.buttons.remove(selectedIndex);
        selectedIndex = config.buttons.isEmpty() ? -1 : Math.min(selectedIndex, config.buttons.size() - 1);
        editor.invalidate();
        refreshLabels();
    }

    private void editSelectedButton() {
        if (selectedIndex < 0 || selectedIndex >= config.buttons.size()) {
            Toast.makeText(this, R.string.touch_select_button_first, Toast.LENGTH_SHORT).show();
            return;
        }
        TouchControlConfig.ButtonSpec spec = config.buttons.get(selectedIndex);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, dp(8), pad, 0);

        EditText label = new EditText(this);
        label.setHint(R.string.touch_button_label_hint);
        label.setText(spec.label);
        label.setSelectAllOnFocus(true);
        form.addView(label);

        Spinner keySpinner = new Spinner(this);
        String[] names = TouchControlConfig.keyNames();
        int[] codes = TouchControlConfig.keyCodes();
        keySpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, names));
        int selectedKey = 0;
        for (int i = 0; i < codes.length; ++i) if (codes[i] == spec.keyCode) selectedKey = i;
        keySpinner.setSelection(selectedKey);
        form.addView(keySpinner);

        LinearLayout modifiers = new LinearLayout(this);
        CheckBox ctrl = new CheckBox(this); ctrl.setText("Ctrl");
        ctrl.setChecked((spec.modifiers & TouchControlConfig.MOD_CTRL) != 0); modifiers.addView(ctrl);
        CheckBox shift = new CheckBox(this); shift.setText("Shift");
        shift.setChecked((spec.modifiers & TouchControlConfig.MOD_SHIFT) != 0); modifiers.addView(shift);
        CheckBox alt = new CheckBox(this); alt.setText("Alt");
        alt.setChecked((spec.modifiers & TouchControlConfig.MOD_ALT) != 0); modifiers.addView(alt);
        form.addView(modifiers);

        TextView widthLabel = new TextView(this);
        widthLabel.setText("Width: " + Math.round(spec.widthDp) + " dp"); form.addView(widthLabel);
        SeekBar widthSeek = new SeekBar(this); widthSeek.setMax(192);
        widthSeek.setProgress(Math.round(spec.widthDp - 28f)); form.addView(widthSeek);
        TextView heightLabel = new TextView(this);
        heightLabel.setText("Height: " + Math.round(spec.heightDp) + " dp"); form.addView(heightLabel);
        SeekBar heightSeek = new SeekBar(this); heightSeek.setMax(192);
        heightSeek.setProgress(Math.round(spec.heightDp - 28f)); form.addView(heightSeek);

        String[] shapeNames = {"Rectangle", "Rounded", "Square", "Circle", "Oval"};
        Spinner shapeSpinner = new Spinner(this);
        shapeSpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, shapeNames));
        shapeSpinner.setSelection(spec.shape);
        form.addView(shapeSpinner);
        shapeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                boolean square = position == TouchControlConfig.SHAPE_SQUARE || position == TouchControlConfig.SHAPE_CIRCLE;
                heightSeek.setEnabled(!square);
                if (square) {
                    heightSeek.setProgress(widthSeek.getProgress());
                    heightLabel.setText("Height: " + (28 + widthSeek.getProgress()) + " dp");
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        EditText fillColor = colorField("Fill color", TouchControlConfig.colorToString(spec.fillColor));
        EditText borderColor = colorField("Border color", TouchControlConfig.colorToString(spec.borderColor));
        EditText textColor = colorField("Text color", TouchControlConfig.colorToString(spec.textColor));
        form.addView(fillColor); form.addView(borderColor); form.addView(textColor);

        SeekBar.OnSeekBarChangeListener sizeListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                widthLabel.setText("Width: " + (28 + widthSeek.getProgress()) + " dp");
                heightLabel.setText("Height: " + (28 + heightSeek.getProgress()) + " dp");
                if (shapeSpinner.getSelectedItemPosition() == TouchControlConfig.SHAPE_SQUARE
                    || shapeSpinner.getSelectedItemPosition() == TouchControlConfig.SHAPE_CIRCLE) {
                    heightSeek.setProgress(widthSeek.getProgress());
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        };
        widthSeek.setOnSeekBarChangeListener(sizeListener);
        heightSeek.setOnSeekBarChangeListener(sizeListener);

        LinearLayout modifiers2 = new LinearLayout(this);
        CheckBox note = new CheckBox(this);
        note.setText("Colors use #RRGGBB or #AARRGGBB");
        note.setEnabled(false);
        modifiers2.addView(note);
        form.addView(modifiers2);

        android.widget.ScrollView formScroll = new android.widget.ScrollView(this);
        formScroll.addView(form);

        new AlertDialog.Builder(this)
            .setTitle(R.string.touch_edit_dialog_title)
            .setView(formScroll)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.touch_apply_button, (dialog, which) -> {
                String newLabel = label.getText().toString().trim();
                if (newLabel.isEmpty()) newLabel = names[keySpinner.getSelectedItemPosition()];
                if (newLabel.length() > 8) newLabel = newLabel.substring(0, 8);
                spec.label = newLabel;
                spec.keyCode = codes[keySpinner.getSelectedItemPosition()];
                spec.modifiers = (ctrl.isChecked() ? TouchControlConfig.MOD_CTRL : 0)
                    | (shift.isChecked() ? TouchControlConfig.MOD_SHIFT : 0)
                    | (alt.isChecked() ? TouchControlConfig.MOD_ALT : 0);
                spec.widthDp = 28f + widthSeek.getProgress();
                spec.heightDp = 28f + heightSeek.getProgress();
                spec.shape = shapeSpinner.getSelectedItemPosition();
                if (spec.shape == TouchControlConfig.SHAPE_SQUARE || spec.shape == TouchControlConfig.SHAPE_CIRCLE) {
                    spec.heightDp = spec.widthDp;
                }
                spec.fillColor = parseColorField(fillColor, spec.fillColor);
                spec.borderColor = parseColorField(borderColor, spec.borderColor);
                spec.textColor = parseColorField(textColor, spec.textColor);
                editor.invalidate();
                refreshLabels();
            })
            .show();
    }

    private EditText colorField(String hint, String value) {
        EditText field = new EditText(this);
        field.setHint(hint + "  #RRGGBB");
        field.setText(value);
        field.setSingleLine(true);
        return field;
    }

    private int parseColorField(EditText field, int fallback) {
        try { return Color.parseColor(field.getText().toString().trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private void resetDefaults() {
        config = TouchControlConfig.defaults();
        selectedIndex = -1;
        enabledSwitch.setChecked(config.enabled);
        panSeek.setProgress(Math.round(config.panSensitivity * 100f));
        sizeSeek.setProgress(Math.round(config.buttonScale * 100f));
        opacitySeek.setProgress(Math.round(config.buttonOpacity * 100f));
        editor.invalidate();
        refreshLabels();
    }

    private void exportControls() {
        config.enabled = enabledSwitch.isChecked();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "GeneralsZH-Controls.json");
        startActivityForResult(intent, REQUEST_EXPORT_CONTROLS);
    }

    private void importControls() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, REQUEST_IMPORT_CONTROLS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == REQUEST_EXPORT_CONTROLS) {
                config.exportTo(this, uri);
                Toast.makeText(this, R.string.touch_exported, Toast.LENGTH_SHORT).show();
            } else if (requestCode == REQUEST_IMPORT_CONTROLS) {
                TouchControlConfig imported = TouchControlConfig.importFrom(this, uri);
                config = imported;
                selectedIndex = -1;
                enabledSwitch.setChecked(config.enabled);
                panSeek.setProgress(Math.round(config.panSensitivity * 100f));
                sizeSeek.setProgress(Math.round(config.buttonScale * 100f));
                opacitySeek.setProgress(Math.round(config.buttonOpacity * 100f));
                config.save(this);
                String gamePath = SetupActivity.getSavedGamePath(this);
                if (gamePath != null) TouchControlConfig.prepareForLaunch(this, new File(gamePath));
                editor.invalidate();
                refreshLabels();
                Toast.makeText(this, R.string.touch_imported, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.touch_transfer_failed,
                e.getMessage() == null ? "Invalid file" : e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void saveAndFinish() {
        config.enabled = enabledSwitch.isChecked();
        config.save(this);
        String gamePath = SetupActivity.getSavedGamePath(this);
        if (gamePath != null) TouchControlConfig.prepareForLaunch(this, new File(gamePath));
        Toast.makeText(this, R.string.touch_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void refreshLabels() {
        if (selectedIndex >= 0 && selectedIndex < config.buttons.size()) {
            TouchControlConfig.ButtonSpec spec = config.buttons.get(selectedIndex);
            selectedText.setText(getString(R.string.touch_selected_button, spec.label));
        } else {
            selectedText.setText(R.string.touch_drag_help_short);
        }
        panValue.setText(getString(R.string.touch_pan_speed, Math.round(config.panSensitivity * 100f)));
        sizeValue.setText(getString(R.string.touch_button_size, Math.round(config.buttonScale * 100f)));
        opacityValue.setText(getString(R.string.touch_button_opacity, Math.round(config.buttonOpacity * 100f)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class EditorCanvas extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<RectF> rects = new ArrayList<>();
        private int dragPointer = -1;

        EditorCanvas() {
            super(TouchControlsActivity.this);
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
            setBackgroundColor(Color.rgb(18, 27, 36));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            paint.setColor(Color.argb(45, 255, 255, 255));
            for (int i = 1; i < 10; ++i) {
                float x = getWidth() * i / 10f;
                canvas.drawLine(x, 0, x, getHeight(), paint);
            }
            for (int i = 1; i < 6; ++i) {
                float y = getHeight() * i / 6f;
                canvas.drawLine(0, y, getWidth(), y, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(9, 13, 18));
            canvas.drawRect(0, getHeight() * 0.82f, getWidth(), getHeight(), paint);
            // GeneralsX @bugfix Android port 28/08/2026 Caption the shaded
            // strip: unexplained, it read as a rendering artifact ("the
            // editor draws a weird black bar"). It is actually a guide
            // showing where the game's own bottom command panel sits.
            text.setColor(Color.argb(170, 255, 255, 255));
            text.setTextSize(dp(12));
            canvas.drawText(getString(R.string.touch_editor_panel_hint),
                getWidth() * 0.5f, getHeight() * 0.82f + dp(17), text);

            rects.clear();
            int alpha = Math.round(255f * config.buttonOpacity);
            for (int i = 0; i < config.buttons.size(); ++i) {
                TouchControlConfig.ButtonSpec spec = config.buttons.get(i);
                float width = spec.widthDp * config.buttonScale * getResources().getDisplayMetrics().density;
                float height = spec.heightDp * config.buttonScale * getResources().getDisplayMetrics().density;
                if (spec.shape == TouchControlConfig.SHAPE_SQUARE || spec.shape == TouchControlConfig.SHAPE_CIRCLE) {
                    float side = Math.min(width, height); width = height = side;
                }
                RectF rect = new RectF(spec.x * getWidth() - width / 2f,
                    spec.y * getHeight() - height / 2f,
                    spec.x * getWidth() + width / 2f,
                    spec.y * getHeight() + height / 2f);
                rects.add(rect);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(i == selectedIndex ? lighten(spec.fillColor, 0.16f) : spec.fillColor);
                paint.setAlpha(i == selectedIndex ? Math.min(255, alpha + 45) : alpha);
                drawShape(canvas, rect, spec, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(i == selectedIndex ? dp(3) : dp(1));
                paint.setColor(spec.borderColor); paint.setAlpha(255);
                drawShape(canvas, rect, spec, paint);
                text.setColor(spec.textColor);
                text.setTextSize(dp(spec.label.length() > 5 ? 12 : 15) * config.buttonScale);
                Paint.FontMetrics metrics = text.getFontMetrics();
                canvas.drawText(spec.label, rect.centerX(),
                    rect.centerY() - (metrics.ascent + metrics.descent) / 2f, text);
            }
        }

        private void drawShape(Canvas canvas, RectF rect, TouchControlConfig.ButtonSpec spec, Paint paint) {
            switch (spec.shape) {
                case TouchControlConfig.SHAPE_CIRCLE:
                    canvas.drawCircle(rect.centerX(), rect.centerY(), Math.min(rect.width(), rect.height()) * 0.5f, paint); break;
                case TouchControlConfig.SHAPE_OVAL:
                    canvas.drawOval(rect, paint); break;
                case TouchControlConfig.SHAPE_SQUARE:
                    float side = Math.min(rect.width(), rect.height());
                    canvas.drawRect(rect.centerX()-side/2f, rect.centerY()-side/2f,
                        rect.centerX()+side/2f, rect.centerY()+side/2f, paint); break;
                case TouchControlConfig.SHAPE_RECTANGLE:
                    canvas.drawRect(rect, paint); break;
                default:
                    float radius = Math.min(dp(12), Math.min(rect.width(), rect.height()) * 0.25f);
                    canvas.drawRoundRect(rect, radius, radius, paint); break;
            }
        }

        private int lighten(int color, float amount) {
            int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
            return Color.argb(Color.alpha(color),
                r + Math.round((255-r)*amount), g + Math.round((255-g)*amount), b + Math.round((255-b)*amount));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    selectedIndex = hit(event.getX(), event.getY());
                    dragPointer = event.getPointerId(0);
                    refreshLabels();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (selectedIndex >= 0 && selectedIndex < config.buttons.size()) {
                        int index = event.findPointerIndex(dragPointer);
                        if (index >= 0) {
                            TouchControlConfig.ButtonSpec spec = config.buttons.get(selectedIndex);
                            spec.x = TouchControlConfig.clamp(event.getX(index) / getWidth(), 0.025f, 0.975f);
                            spec.y = TouchControlConfig.clamp(event.getY(index) / getHeight(), 0.06f, 0.94f);
                            invalidate();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragPointer = -1;
                    return true;
                default:
                    return true;
            }
        }

        private int hit(float x, float y) {
            for (int i = rects.size() - 1; i >= 0; --i) if (rects.get(i).contains(x, y)) return i;
            return -1;
        }
    }
}
