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
// A minimal folder browser over plain java.io.File — deliberately NOT the
// system Storage Access Framework picker (ACTION_OPEN_DOCUMENT_TREE): SAF
// hands back a content:// tree the native engine's plain fopen()/chdir()
// calls cannot use directly, which would force copying the ~2-3 GB game
// data into app storage before every launch. Requires the "All files
// access" permission (MANAGE_EXTERNAL_STORAGE, requested by LauncherActivity)
// so the resulting path is a real filesystem path the engine can chdir()
// into wherever the user actually put their files — Downloads, an SD card,
// wherever a normal file manager or USB cable already reaches.
//
// Ported from android/app/.../FolderPickerActivity.java (Zero Hour app),
// trimmed to this app's needs and styled with the shared gzh palette.

package com.generalsx.generals;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class FolderPickerActivity extends Activity {

    static final String EXTRA_SELECTED_PATH = "selected_path";

    private File currentDir;
    private TextView pathLabel;
    private TextView hintLabel;
    private ListView listView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.folderpicker_title);

        File start = Environment.getExternalStorageDirectory();
        currentDir = (start != null && start.isDirectory()) ? start : new File("/storage/emulated/0");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.gzh_background));

        int gutter = dp(20);

        // Mini app bar: title + current path + validity hint.
        TextView title = new TextView(this);
        title.setText(R.string.folderpicker_title);
        title.setTextColor(ContextCompat.getColor(this, R.color.gzh_primary));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20);
        title.setPadding(gutter, dp(24), gutter, dp(12));
        root.addView(title);

        pathLabel = new TextView(this);
        pathLabel.setPadding(gutter, 0, gutter, dp(4));
        pathLabel.setTextIsSelectable(true);
        pathLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        pathLabel.setTextColor(ContextCompat.getColor(this, R.color.gzh_on_surface));
        root.addView(pathLabel);

        hintLabel = new TextView(this);
        hintLabel.setPadding(gutter, 0, gutter, dp(8));
        hintLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        hintLabel.setTextColor(ContextCompat.getColor(this, R.color.gzh_on_surface_variant));
        root.addView(hintLabel);

        listView = new ListView(this);
        listView.setDivider(new android.graphics.drawable.ColorDrawable(
            ContextCompat.getColor(this, R.color.gzh_outline_variant)));
        listView.setDividerHeight(Math.max(1, dp(1)));
        listView.setPadding(dp(8), 0, dp(8), 0);
        listView.setClipToPadding(false);
        listView.setSelector(new android.graphics.drawable.ColorDrawable(
            ContextCompat.getColor(this, R.color.gzh_ripple_light)));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(listView, listParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(gutter, dp(12), gutter, dp(16));
        root.addView(actions, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        com.google.android.material.button.MaterialButton cancel = new com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        cancel.setText(R.string.common_cancel);
        cancel.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cancelParams.rightMargin = dp(8);
        actions.addView(cancel, cancelParams);

        com.google.android.material.button.MaterialButton use = new com.google.android.material.button.MaterialButton(this);
        use.setText(R.string.folderpicker_button_use);
        use.setOnClickListener(v -> finishWithSelection());
        actions.addView(use, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        setContentView(root);

        listView.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            String name = (String) parent.getItemAtPosition(position);
            if (getString(R.string.folderpicker_up_entry).equals(name)) {
                File parentDir = currentDir.getParentFile();
                if (parentDir != null && parentDir.canRead()) {
                    currentDir = parentDir;
                    refresh();
                }
                return;
            }
            File next = new File(currentDir, name);
            if (next.isDirectory()) {
                currentDir = next;
                refresh();
            }
        });

        refresh();
    }

    private void refresh() {
        pathLabel.setText(currentDir.getAbsolutePath());
        hintLabel.setText(LauncherActivity.isValidGameFolder(currentDir)
            ? R.string.folderpicker_hint_valid
            : R.string.folderpicker_hint_invalid);
        hintLabel.setTextColor(ContextCompat.getColor(this,
            LauncherActivity.isValidGameFolder(currentDir)
                ? R.color.gzh_status_ok
                : R.color.gzh_on_surface_variant));

        List<String> entries = new ArrayList<>();
        if (currentDir.getParentFile() != null) {
            entries.add(getString(R.string.folderpicker_up_entry));
        }
        File[] children = currentDir.listFiles();
        if (children != null) {
            List<File> dirs = new ArrayList<>();
            for (File f : children) {
                if (f.isDirectory() && !f.isHidden()) {
                    dirs.add(f);
                }
            }
            Collections.sort(dirs, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File d : dirs) {
                entries.add(d.getName());
            }
        } else {
            // Android 9/10 has no "All files access"; there the fix is the
            // Storage runtime permission (LauncherActivity requests it).
            Toast.makeText(this, android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                    ? R.string.folderpicker_toast_cant_read
                    : R.string.folderpicker_toast_cant_read_legacy, Toast.LENGTH_LONG).show();
        }

        // Framework list rows on a near-black ground default to a light-theme
        // text colour on some OEM builds; style each row explicitly instead.
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, entries) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                row.setTextColor(ContextCompat.getColor(FolderPickerActivity.this, R.color.gzh_on_surface));
                row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
                int padH = dp(16);
                row.setPadding(padH, dp(14), padH, dp(14));
                return row;
            }
        };
        listView.setAdapter(adapter);
    }

    private void finishWithSelection() {
        android.content.Intent result = new android.content.Intent();
        result.putExtra(EXTRA_SELECTED_PATH, currentDir.getAbsolutePath());
        setResult(RESULT_OK, result);
        finish();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
