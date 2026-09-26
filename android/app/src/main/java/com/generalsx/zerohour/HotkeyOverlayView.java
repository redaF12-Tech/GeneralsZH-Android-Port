/*
** Command & Conquer Generals Zero Hour(tm)
** Copyright 2025 Electronic Arts Inc.
** Licensed under GPL-3.0-or-later. See LICENSE.md.
*/

package com.generalsx.zerohour;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

import java.util.ArrayList;

/** Transparent, touch-through hotkey layer drawn above SDL's game surface. */
final class HotkeyOverlayView extends View {
    private static final long KEY_HOLD_MS = 42L;

    private final ArrayList<TouchControlConfig.ButtonSpec> buttons = new ArrayList<>();
    private final ArrayList<RectF> hitRects = new ArrayList<>();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float scale;
    private final float opacity;
    private final int accentColor;

    private int activeButton = -1;
    private int activePointer = -1;

    private HotkeyOverlayView(GeneralsZHActivity activity, TouchControlConfig config) {
        super(activity);
        buttons.addAll(TouchControlConfig.copyButtons(config.buttons));
        scale = config.buttonScale;
        opacity = config.buttonOpacity;
        accentColor = ThemeHelper.accentColor(activity);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.5f));
        borderPaint.setColor(accentColor);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        textPaint.setShadowLayer(dp(2), 0, dp(1), Color.BLACK);
    }

    static void attach(GeneralsZHActivity activity) {
        TouchControlConfig config = TouchControlConfig.load(activity);
        if (!config.enabled || config.buttons.isEmpty()) return;
        HotkeyOverlayView overlay = new HotkeyOverlayView(activity, config);
        activity.addContentView(overlay, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.bringToFront();
        overlay.setElevation(1000f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        rebuildHitRects();
        int baseAlpha = Math.round(255f * opacity);
        for (int i = 0; i < buttons.size(); ++i) {
            RectF rect = hitRects.get(i);
            TouchControlConfig.ButtonSpec spec = buttons.get(i);
            boolean pressed = i == activeButton;
            fillPaint.setColor(pressed ? lighten(spec.fillColor, 0.16f) : spec.fillColor);
            fillPaint.setAlpha(pressed ? Math.min(255, baseAlpha + 45) : baseAlpha);
            // The interface accent is the single source of truth for the button frame.
            // Keep fill/text colors configurable, but make the frame follow the app theme.
            borderPaint.setColor(accentColor);
            borderPaint.setAlpha(pressed ? 255 : Math.max(120, baseAlpha));
            drawShape(canvas, rect, spec, fillPaint);
            drawShape(canvas, rect, spec, borderPaint);

            textPaint.setColor(spec.textColor);
            float textSize = dp(spec.label.length() > 5 ? 12 : 15) * scale;
            textPaint.setTextSize(textSize);
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float baseline = rect.centerY() - (metrics.ascent + metrics.descent) * 0.5f;
            canvas.drawText(spec.label, rect.centerX(), baseline, textPaint);
        }
    }

    private void rebuildHitRects() {
        hitRects.clear();
        for (TouchControlConfig.ButtonSpec spec : buttons) {
            float width = dp(spec.widthDp) * scale;
            float height = dp(spec.heightDp) * scale;
            if (spec.shape == TouchControlConfig.SHAPE_SQUARE || spec.shape == TouchControlConfig.SHAPE_CIRCLE) {
                float side = Math.min(width, height);
                width = height = side;
            }
            float cx = spec.x * getWidth();
            float cy = spec.y * getHeight();
            hitRects.add(new RectF(cx - width * 0.5f, cy - height * 0.5f,
                cx + width * 0.5f, cy + height * 0.5f));
        }
    }

    private int findButton(float x, float y) {
        if (hitRects.size() != buttons.size()) rebuildHitRects();
        for (int i = hitRects.size() - 1; i >= 0; --i) {
            if (hitRects.get(i).contains(x, y)) return i;
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int hit = findButton(event.getX(), event.getY());
                if (hit < 0) return false; // Let SDL receive normal battlefield gestures.
                activeButton = hit;
                activePointer = event.getPointerId(0);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (activeButton < 0) return false;
                int index = event.findPointerIndex(activePointer);
                if (index >= 0 && !hitRects.get(activeButton).contains(event.getX(index), event.getY(index))) {
                    activeButton = -1;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (activeButton >= 0) {
                    int hit = activeButton;
                    activeButton = -1;
                    activePointer = -1;
                    invalidate();
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    sendKey(buttons.get(hit));
                    performClick();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                activeButton = -1;
                activePointer = -1;
                invalidate();
                return true;
            default:
                return activeButton >= 0;
        }
    }

    private void sendKey(TouchControlConfig.ButtonSpec spec) {
        final int[] modifiers = modifierKeyCodes(spec.modifiers);
        for (int modifier : modifiers) SDLActivity.onNativeKeyDown(modifier);
        SDLActivity.onNativeKeyDown(spec.keyCode);
        handler.postDelayed(() -> {
            SDLActivity.onNativeKeyUp(spec.keyCode);
            for (int i = modifiers.length - 1; i >= 0; --i) SDLActivity.onNativeKeyUp(modifiers[i]);
        }, KEY_HOLD_MS);
    }

    private int[] modifierKeyCodes(int mask) {
        int count = Integer.bitCount(mask & (TouchControlConfig.MOD_CTRL
            | TouchControlConfig.MOD_SHIFT | TouchControlConfig.MOD_ALT));
        int[] result = new int[count];
        int i = 0;
        if ((mask & TouchControlConfig.MOD_CTRL) != 0) result[i++] = KeyEvent.KEYCODE_CTRL_LEFT;
        if ((mask & TouchControlConfig.MOD_SHIFT) != 0) result[i++] = KeyEvent.KEYCODE_SHIFT_LEFT;
        if ((mask & TouchControlConfig.MOD_ALT) != 0) result[i] = KeyEvent.KEYCODE_ALT_LEFT;
        return result;
    }

    private void drawShape(Canvas canvas, RectF rect, TouchControlConfig.ButtonSpec spec, Paint paint) {
        switch (spec.shape) {
            case TouchControlConfig.SHAPE_CIRCLE:
                canvas.drawCircle(rect.centerX(), rect.centerY(), Math.min(rect.width(), rect.height()) * 0.5f, paint);
                break;
            case TouchControlConfig.SHAPE_OVAL:
                canvas.drawOval(rect, paint);
                break;
            case TouchControlConfig.SHAPE_SQUARE:
                float side = Math.min(rect.width(), rect.height());
                RectF square = new RectF(rect.centerX() - side / 2f, rect.centerY() - side / 2f,
                    rect.centerX() + side / 2f, rect.centerY() + side / 2f);
                canvas.drawRect(square, paint);
                break;
            case TouchControlConfig.SHAPE_RECTANGLE:
                canvas.drawRect(rect, paint);
                break;
            default:
                float radius = Math.min(dp(12) * scale, Math.min(rect.width(), rect.height()) * 0.25f);
                canvas.drawRoundRect(rect, radius, radius, paint);
                break;
        }
    }

    private int lighten(int color, float amount) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        r += Math.round((255 - r) * amount);
        g += Math.round((255 - g) * amount);
        b += Math.round((255 - b) * amount);
        return Color.argb(Color.alpha(color), r, g, b);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
