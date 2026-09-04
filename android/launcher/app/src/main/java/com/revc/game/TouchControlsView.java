package com.revc.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/**
 * On-screen virtual gamepad, drawn directly over the SDL surface.
 *
 * Feeds everything into the native side (TouchControls.cpp) as if it were a
 * real SDL_GameController -- a left stick for movement/steering, a right
 * stick for the camera, four face buttons and two triggers -- so every
 * context that already handles a physical gamepad (driving, on foot, menus)
 * picks it up with no extra work on the C++ side.
 */
public class TouchControlsView extends View {

    private static native void nativeSetStick(int stick, float x, float y);
    private static native void nativeSetButton(int button, boolean pressed);

    private static final int STICK_LEFT = 0;
    private static final int STICK_RIGHT = 1;

    private static final int BTN_A = 0; // jump / sprint / enter-exit vehicle
    private static final int BTN_B = 1; // handbrake / cancel
    private static final int BTN_X = 2; // fire
    private static final int BTN_Y = 3; // look behind / change weapon
    private static final int BTN_BRAKE = 4;   // L2
    private static final int BTN_ACCEL = 5;   // R2

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // A stick (movement or camera) tracked while held.
    private static final class Stick {
        final int id;
        final float baseRadius;
        final float knobRadius;
        PointF center;      // resting position, where the base is drawn
        PointF knob;         // current knob position (follows the finger)
        int pointerId = -1;  // MotionEvent pointer currently driving this stick, -1 = free

        Stick(int id, float baseRadius, float knobRadius) {
            this.id = id;
            this.baseRadius = baseRadius;
            this.knobRadius = knobRadius;
        }
    }

    // A momentary button (face button or trigger).
    private static final class Button {
        final int id;
        final String label;
        RectF hitRect;
        int pointerId = -1;
        boolean pressed = false;

        Button(int id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private Stick leftStick;
    private Stick rightStick;
    private final Button[] buttons = new Button[]{
            new Button(BTN_A, "A"),
            new Button(BTN_B, "B"),
            new Button(BTN_X, "X"),
            new Button(BTN_Y, "Y"),
            new Button(BTN_BRAKE, "FRENO"),
            new Button(BTN_ACCEL, "GAS"),
    };

    public TouchControlsView(Context context) {
        super(context);
        setWillNotDraw(false);

        fillPaint.setColor(Color.WHITE);
        fillPaint.setAlpha(70);

        strokePaint.setColor(Color.WHITE);
        strokePaint.setAlpha(160);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(3f);

        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutControls(w, h);
    }

    private void layoutControls(int w, int h) {
        float margin = Math.min(w, h) * 0.06f;
        float baseRadius = Math.min(w, h) * 0.16f;
        float knobRadius = baseRadius * 0.45f;

        leftStick = new Stick(STICK_LEFT, baseRadius, knobRadius);
        leftStick.center = new PointF(margin + baseRadius, h - margin - baseRadius);
        leftStick.knob = new PointF(leftStick.center.x, leftStick.center.y);

        rightStick = new Stick(STICK_RIGHT, baseRadius, knobRadius);
        rightStick.center = new PointF(w - margin - baseRadius, h - margin - baseRadius);
        rightStick.knob = new PointF(rightStick.center.x, rightStick.center.y);

        labelPaint.setTextSize(baseRadius * 0.35f);

        // Face buttons (A/B/X/Y), diamond layout, above the right stick.
        float btnRadius = baseRadius * 0.32f;
        float faceCx = rightStick.center.x;
        float faceCy = rightStick.center.y - baseRadius * 2.1f;
        float spread = btnRadius * 1.7f;
        placeButton(BTN_Y, faceCx, faceCy - spread, btnRadius);
        placeButton(BTN_A, faceCx, faceCy + spread, btnRadius);
        placeButton(BTN_X, faceCx - spread, faceCy, btnRadius);
        placeButton(BTN_B, faceCx + spread, faceCy, btnRadius);

        // Triggers: brake / accelerate, stacked above the left stick.
        float trigW = baseRadius * 1.5f;
        float trigH = baseRadius * 0.55f;
        float trigCx = leftStick.center.x;
        float trigCy = leftStick.center.y - baseRadius * 2.1f;
        findButton(BTN_BRAKE).hitRect = new RectF(trigCx - trigW / 2, trigCy - trigH - 10, trigCx + trigW / 2, trigCy - 10);
        findButton(BTN_ACCEL).hitRect = new RectF(trigCx - trigW / 2, trigCy + 10, trigCx + trigW / 2, trigCy + trigH + 10);
    }

    private void placeButton(int id, float cx, float cy, float radius) {
        findButton(id).hitRect = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
    }

    private Button findButton(int id) {
        for (Button b : buttons) if (b.id == id) return b;
        return null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (leftStick == null) return;

        drawStick(canvas, leftStick);
        drawStick(canvas, rightStick);

        for (Button b : buttons) {
            fillPaint.setAlpha(b.pressed ? 140 : 70);
            if (b.id == BTN_BRAKE || b.id == BTN_ACCEL) {
                canvas.drawRoundRect(b.hitRect, 16f, 16f, fillPaint);
                canvas.drawRoundRect(b.hitRect, 16f, 16f, strokePaint);
            } else {
                float r = b.hitRect.width() / 2f;
                canvas.drawCircle(b.hitRect.centerX(), b.hitRect.centerY(), r, fillPaint);
                canvas.drawCircle(b.hitRect.centerX(), b.hitRect.centerY(), r, strokePaint);
            }
            float textY = b.hitRect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f;
            canvas.drawText(b.label, b.hitRect.centerX(), textY, labelPaint);
        }
    }

    private void drawStick(Canvas canvas, Stick s) {
        fillPaint.setAlpha(50);
        canvas.drawCircle(s.center.x, s.center.y, s.baseRadius, fillPaint);
        canvas.drawCircle(s.center.x, s.center.y, s.baseRadius, strokePaint);
        fillPaint.setAlpha(s.pointerId != -1 ? 150 : 90);
        canvas.drawCircle(s.knob.x, s.knob.y, s.knobRadius, fillPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        int pointerId = event.getPointerId(index);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                handleDown(pointerId, event.getX(index), event.getY(index));
                break;

            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    handleMove(event.getPointerId(i), event.getX(i), event.getY(i));
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                handleUp(pointerId);
                break;
        }

        invalidate();
        return true;
    }

    private void handleDown(int pointerId, float x, float y) {
        if (leftStick.pointerId == -1 && within(leftStick, x, y)) {
            leftStick.pointerId = pointerId;
            updateStick(leftStick, x, y);
            return;
        }
        if (rightStick.pointerId == -1 && within(rightStick, x, y)) {
            rightStick.pointerId = pointerId;
            updateStick(rightStick, x, y);
            return;
        }
        for (Button b : buttons) {
            if (b.pointerId == -1 && b.hitRect.contains(x, y)) {
                b.pointerId = pointerId;
                setPressed(b, true);
                return;
            }
        }
    }

    private void handleMove(int pointerId, float x, float y) {
        if (leftStick.pointerId == pointerId) {
            updateStick(leftStick, x, y);
        } else if (rightStick.pointerId == pointerId) {
            updateStick(rightStick, x, y);
        }
    }

    private void handleUp(int pointerId) {
        if (leftStick.pointerId == pointerId) {
            leftStick.pointerId = -1;
            leftStick.knob.set(leftStick.center.x, leftStick.center.y);
            nativeSetStick(STICK_LEFT, 0f, 0f);
        }
        if (rightStick.pointerId == pointerId) {
            rightStick.pointerId = -1;
            rightStick.knob.set(rightStick.center.x, rightStick.center.y);
            nativeSetStick(STICK_RIGHT, 0f, 0f);
        }
        for (Button b : buttons) {
            if (b.pointerId == pointerId) {
                b.pointerId = -1;
                setPressed(b, false);
            }
        }
    }

    private void setPressed(Button b, boolean pressed) {
        b.pressed = pressed;
        nativeSetButton(b.id, pressed);
    }

    private boolean within(Stick s, float x, float y) {
        // Generous grab area: anywhere within ~1.6x the base radius counts,
        // so a quick stab near the stick still catches it.
        float dx = x - s.center.x;
        float dy = y - s.center.y;
        float r = s.baseRadius * 1.6f;
        return dx * dx + dy * dy <= r * r;
    }

    private void updateStick(Stick s, float x, float y) {
        float dx = x - s.center.x;
        float dy = y - s.center.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float max = s.baseRadius;

        if (dist > max) {
            dx = dx / dist * max;
            dy = dy / dist * max;
        }

        s.knob.set(s.center.x + dx, s.center.y + dy);

        float nx = dx / max;
        float ny = dy / max; // screen Y grows downward; native side expects the same convention SDL axes use (down = positive)
        nativeSetStick(s.id, nx, ny);
    }
}
