package com.revc.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

/**
 * On-screen virtual gamepad, drawn directly over the SDL surface.
 *
 * Feeds everything into the native side (TouchControls.cpp) exactly as a
 * real SDL_GameController would -- a left stick, a right stick, and the
 * standard PS2-style face/shoulder/dpad buttons -- using the game's own
 * default bindings (see CControllerConfigManager::MapIdToButtonId() in
 * ControllerConfig.cpp, the actual source of truth this mirrors):
 *   Circle = fire, Cross = accelerate/sprint, Square = brake/jump,
 *   Triangle = enter/exit vehicle, R1 = handbrake/target, L1 = radio/phone,
 *   L2/R2 = cycle weapon or look left/right, Select = change camera,
 *   L3 = horn/duck, D-Pad = frontend navigation.
 *
 * The frontend menu is entirely D-Pad + Cross/Circle + Start driven (no
 * mouse), so the layout adapts to what nativeGetGameContext() reports:
 * menu, on foot, or in a vehicle.
 */
public class TouchControlsView extends View {

    private static native void nativeSetStick(int stick, float x, float y);
    private static native void nativeSetButton(int button, boolean pressed);
    private static native void nativeSetMenuMouse(float x, float y, boolean down);
    private static native void nativeSkipCutscene();
    private static native int nativeGetGameContext(); // 0 = menu, 1 = on foot, 2 = in vehicle

    private static final int STICK_LEFT = 0;
    private static final int STICK_RIGHT = 1;

    private static final int BTN_CIRCLE = 0;
    private static final int BTN_CROSS = 1;
    private static final int BTN_SQUARE = 2;
    private static final int BTN_TRIANGLE = 3;
    private static final int BTN_L1 = 4;
    private static final int BTN_R1 = 5;
    private static final int BTN_L2 = 6;
    private static final int BTN_R2 = 7;
    private static final int BTN_SELECT = 8;
    private static final int BTN_START = 9;
    private static final int BTN_L3 = 10;
    private static final int BTN_R3 = 11;
    private static final int BTN_DPAD_UP = 12;
    private static final int BTN_DPAD_DOWN = 13;
    private static final int BTN_DPAD_LEFT = 14;
    private static final int BTN_DPAD_RIGHT = 15;

    private static final int CONTEXT_MENU = 0;
    private static final int CONTEXT_ON_FOOT = 1;
    private static final int CONTEXT_VEHICLE = 2;
    private static final int CONTEXT_CUTSCENE = 3;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final class Stick {
        final int id;
        float baseRadius;
        float knobRadius;
        PointF center = new PointF();
        PointF knob = new PointF();
        int pointerId = -1;
        boolean visible = true;

        Stick(int id) {
            this.id = id;
        }
    }

    private static final class Button {
        final int id;
        String label;
        RectF hitRect = new RectF();
        boolean roundedRect = false; // vs circle
        int pointerId = -1;
        boolean pressed = false;
        boolean visible = true;

        Button(int id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private final Stick leftStick = new Stick(STICK_LEFT);
    private final Stick rightStick = new Stick(STICK_RIGHT);

    // A finger that landed on empty menu space (not on the D-Pad/OK/Atras/
    // Start) acts as a direct pointer: menus support real mouse hover/click
    // already, this just feeds it. Independent of the D-Pad, both work at
    // the same time.
    private int menuMousePointerId = -1;

    private final Button[] buttons = new Button[]{
            new Button(BTN_CIRCLE, "O"),
            new Button(BTN_CROSS, "X"),
            new Button(BTN_SQUARE, "□"), // square
            new Button(BTN_TRIANGLE, "△"), // triangle
            new Button(BTN_L1, "L1"),
            new Button(BTN_R1, "R1"),
            new Button(BTN_L2, "L2"),
            new Button(BTN_R2, "R2"),
            new Button(BTN_SELECT, "SELECT"),
            new Button(BTN_START, "START"),
            new Button(BTN_L3, "L3"),
            new Button(BTN_DPAD_UP, "↑"),
            new Button(BTN_DPAD_DOWN, "↓"),
            new Button(BTN_DPAD_LEFT, "←"),
            new Button(BTN_DPAD_RIGHT, "→"),
    };

    private int width, height;
    private int currentContext = CONTEXT_ON_FOOT;
    private float baseLabelSize;

    // Punch-hole/notch safe area, in sensorLandscape this can land on either
    // the left or right edge depending on which way the phone is rotated --
    // controls need to steer clear of it or a real camera cutout eats them.
    private int safeLeft, safeTop, safeRight, safeBottom;

    // Usable area after the safe insets above -- every layout*() method
    // anchors to these instead of raw 0/width/height.
    private float areaLeft, areaTop, areaRight, areaBottom;

    private final Handler contextPoller = new Handler(Looper.getMainLooper());
    private final Runnable pollContext = new Runnable() {
        @Override
        public void run() {
            int ctx;
            try {
                ctx = nativeGetGameContext();
            } catch (UnsatisfiedLinkError e) {
                ctx = currentContext; // native lib not ready yet, keep current layout
            }
            if (ctx != currentContext) {
                currentContext = ctx;
                releaseAllInput();
                layoutControls();
                invalidate();
            }
            contextPoller.postDelayed(this, 200);
        }
    };

    public TouchControlsView(Context context) {
        super(context);
        setWillNotDraw(false);

        fillPaint.setColor(Color.WHITE);
        strokePaint.setColor(Color.WHITE);
        strokePaint.setAlpha(160);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(3f);
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setOnApplyWindowInsetsListener((v, insets) -> {
                applyCutoutInsets(insets);
                return insets;
            });
        }
    }

    private void applyCutoutInsets(WindowInsets insets) {
        DisplayCutout cutout = insets.getDisplayCutout();
        int left = 0, top = 0, right = 0, bottom = 0;
        if (cutout != null) {
            left = cutout.getSafeInsetLeft();
            top = cutout.getSafeInsetTop();
            right = cutout.getSafeInsetRight();
            bottom = cutout.getSafeInsetBottom();
        }
        if (left != safeLeft || top != safeTop || right != safeRight || bottom != safeBottom) {
            safeLeft = left;
            safeTop = top;
            safeRight = right;
            safeBottom = bottom;
            layoutControls();
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        contextPoller.postDelayed(pollContext, 500);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowInsets insets = getRootWindowInsets();
            if (insets != null) applyCutoutInsets(insets);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        contextPoller.removeCallbacks(pollContext);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        width = w;
        height = h;
        layoutControls();
    }

    private Button b(int id) {
        for (Button btn : buttons) if (btn.id == id) return btn;
        return null;
    }

    private void layoutControls() {
        if (width == 0 || height == 0) return;

        areaLeft = safeLeft;
        areaTop = safeTop;
        areaRight = width - safeRight;
        areaBottom = height - safeBottom;

        float margin = Math.min(width, height) * 0.06f;
        float baseRadius = Math.min(width, height) * 0.15f;

        leftStick.baseRadius = baseRadius;
        leftStick.knobRadius = baseRadius * 0.45f;
        leftStick.center.set(areaLeft + margin + baseRadius, areaBottom - margin - baseRadius);
        leftStick.knob.set(leftStick.center.x, leftStick.center.y);

        rightStick.baseRadius = baseRadius;
        rightStick.knobRadius = baseRadius * 0.45f;
        rightStick.center.set(areaRight - margin - baseRadius, areaBottom - margin - baseRadius);
        rightStick.knob.set(rightStick.center.x, rightStick.center.y);

        baseLabelSize = baseRadius * 0.3f;
        labelPaint.setTextSize(baseLabelSize);

        for (Button btn : buttons) {
            btn.visible = false;
            btn.roundedRect = false;
        }

        switch (currentContext) {
            case CONTEXT_MENU:
                layoutMenu(margin, baseRadius);
                break;
            case CONTEXT_VEHICLE:
                layoutVehicle(margin, baseRadius);
                break;
            case CONTEXT_CUTSCENE:
                layoutCutscene();
                break;
            case CONTEXT_ON_FOOT:
            default:
                layoutOnFoot(margin, baseRadius);
                break;
        }
    }

    /** Cutscenes take control away entirely -- just one big, unmistakable skip button. */
    private void layoutCutscene() {
        leftStick.visible = false;
        rightStick.visible = false;

        float w = width * 0.2f;
        float h = height * 0.09f;
        placeRect(BTN_CROSS, areaRight - w - width * 0.04f, areaBottom - h - height * 0.05f, areaRight - width * 0.04f, areaBottom - height * 0.05f);
        b(BTN_CROSS).label = "SALTAR";
    }

    /** Frontend menus are D-Pad + confirm/cancel + Start driven -- no mouse, no sticks. */
    private void layoutMenu(float margin, float baseRadius) {
        leftStick.visible = false;
        rightStick.visible = false;

        // D-Pad cross, bottom-left. In a "+" layout the diagonal neighbors
        // (e.g. UP and LEFT) are the tight fit, not the opposite pair (UP and
        // DOWN) -- their center distance is spread*sqrt(2), so spread needs
        // to clear dR*sqrt(2) (~1.41*dR) for the circles not to overlap.
        float dCx = areaLeft + margin + baseRadius * 1.1f;
        float dCy = areaBottom - margin - baseRadius * 1.1f;
        float dR = baseRadius * 0.36f;
        float spread = dR * 1.75f;
        placeCircle(BTN_DPAD_UP, dCx, dCy - spread, dR);
        placeCircle(BTN_DPAD_DOWN, dCx, dCy + spread, dR);
        placeCircle(BTN_DPAD_LEFT, dCx - spread, dCy, dR);
        placeCircle(BTN_DPAD_RIGHT, dCx + spread, dCy, dR);

        // Confirm / cancel, bottom-right. Cancel is Triangle here, not
        // Circle: this game binds "back" to Triangle (TRIANGLE_BACK_BUTTON
        // in config.h), a Circle press does nothing in the frontend.
        float fCx = areaRight - margin - baseRadius;
        float fCy = areaBottom - margin - baseRadius;
        placeCircle(BTN_CROSS, fCx, fCy + dR * 1.9f, dR * 1.3f);
        placeCircle(BTN_TRIANGLE, fCx, fCy - dR * 1.9f, dR * 1.3f);

        float midX = (areaLeft + areaRight) / 2f;
        placeRect(BTN_START, midX - baseRadius * 0.6f, areaTop + margin, midX + baseRadius * 0.6f, areaTop + margin + baseRadius * 0.5f);

        b(BTN_CROSS).label = "OK";
        b(BTN_TRIANGLE).label = "ATRAS";
    }

    private void layoutOnFoot(float margin, float baseRadius) {
        leftStick.visible = true;
        rightStick.visible = true;

        b(BTN_CROSS).label = "CORRER";
        b(BTN_SQUARE).label = "SALTAR";
        b(BTN_CIRCLE).label = "DISPARAR";
        b(BTN_TRIANGLE).label = "SUBIR";

        // Face buttons, diamond above the right stick.
        float faceCx = rightStick.center.x;
        float faceCy = rightStick.center.y - baseRadius * 2.0f;
        float btnR = baseRadius * 0.34f;
        float spread = btnR * 1.7f;
        placeCircle(BTN_TRIANGLE, faceCx, faceCy - spread, btnR);
        placeCircle(BTN_CROSS, faceCx, faceCy + spread, btnR);
        placeCircle(BTN_SQUARE, faceCx - spread, faceCy, btnR);
        placeCircle(BTN_CIRCLE, faceCx + spread, faceCy, btnR);

        // L1/L2/R1/R2, in a row above the left stick. Centers need to be at
        // least 2*shR apart or the circles themselves overlap -- give them a
        // clear gap on top of that.
        float shR = baseRadius * 0.3f;
        float shGap = shR * 2.5f;
        float rowY = leftStick.center.y - baseRadius * 2.05f;
        placeCircle(BTN_L2, leftStick.center.x - shGap * 1.5f, rowY, shR);
        placeCircle(BTN_L1, leftStick.center.x - shGap * 0.5f, rowY, shR);
        placeCircle(BTN_R1, leftStick.center.x + shGap * 0.5f, rowY, shR);
        placeCircle(BTN_R2, leftStick.center.x + shGap * 1.5f, rowY, shR);

        b(BTN_L1).label = "TEL";
        b(BTN_R1).label = "APUNTAR";

        // Select (camera view) and Start (pause), small, top corners.
        placeRect(BTN_SELECT, areaLeft + margin, areaTop + margin, areaLeft + margin + baseRadius * 0.7f, areaTop + margin + baseRadius * 0.35f);
        placeRect(BTN_START, areaRight - margin - baseRadius * 0.7f, areaTop + margin, areaRight - margin, areaTop + margin + baseRadius * 0.35f);
        b(BTN_SELECT).label = "CAM";
        b(BTN_START).label = "≡";
    }

    private void layoutVehicle(float margin, float baseRadius) {
        leftStick.visible = true;  // steering
        rightStick.visible = true; // camera

        // Big gas/brake pedals, stacked to the right of the right stick area,
        // large enough to hit reliably without looking.
        float pedalW = baseRadius * 0.9f;
        float pedalH = baseRadius * 1.0f;
        float px = rightStick.center.x - baseRadius * 2.1f;
        placeRect(BTN_CROSS, px - pedalW / 2, areaBottom - margin - pedalH, px + pedalW / 2, areaBottom - margin);
        placeRect(BTN_SQUARE, px - pedalW / 2, areaBottom - margin - pedalH * 2.1f, px + pedalW / 2, areaBottom - margin - pedalH * 1.1f);
        b(BTN_CROSS).label = "GAS";
        b(BTN_SQUARE).label = "FRENO";

        float btnR = baseRadius * 0.32f;
        float faceCx = rightStick.center.x;
        float faceCy = rightStick.center.y - baseRadius * 2.1f;
        placeCircle(BTN_TRIANGLE, faceCx, faceCy - btnR * 1.6f, btnR);
        placeCircle(BTN_CIRCLE, faceCx, faceCy + btnR * 1.6f, btnR);
        b(BTN_TRIANGLE).label = "SALIR";
        b(BTN_CIRCLE).label = "DISPARAR";

        float shR = baseRadius * 0.3f;
        float rowY = leftStick.center.y - baseRadius * 1.9f;
        placeCircle(BTN_L1, leftStick.center.x - shR * 1.2f, rowY, shR);
        placeCircle(BTN_R1, leftStick.center.x + shR * 1.2f, rowY, shR);
        b(BTN_L1).label = "RADIO";
        b(BTN_R1).label = "FRENO\nMANO";

        placeCircle(BTN_L3, leftStick.center.x, rowY - shR * 2.2f, shR);
        b(BTN_L3).label = "BOCINA";

        placeRect(BTN_SELECT, areaLeft + margin, areaTop + margin, areaLeft + margin + baseRadius * 0.7f, areaTop + margin + baseRadius * 0.35f);
        b(BTN_SELECT).label = "CAM";
    }

    private void placeCircle(int id, float cx, float cy, float radius) {
        Button btn = b(id);
        btn.visible = true;
        btn.roundedRect = false;
        btn.hitRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
    }

    private void placeRect(int id, float left, float top, float right, float bottom) {
        Button btn = b(id);
        btn.visible = true;
        btn.roundedRect = true;
        btn.hitRect.set(left, top, right, bottom);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (leftStick.visible) drawStick(canvas, leftStick);
        if (rightStick.visible) drawStick(canvas, rightStick);

        for (Button btn : buttons) {
            if (!btn.visible) continue;

            fillPaint.setAlpha(btn.pressed ? 150 : 70);
            if (btn.roundedRect) {
                canvas.drawRoundRect(btn.hitRect, 14f, 14f, fillPaint);
                canvas.drawRoundRect(btn.hitRect, 14f, 14f, strokePaint);
            } else {
                float r = btn.hitRect.width() / 2f;
                canvas.drawCircle(btn.hitRect.centerX(), btn.hitRect.centerY(), r, fillPaint);
                canvas.drawCircle(btn.hitRect.centerX(), btn.hitRect.centerY(), r, strokePaint);
            }
            float maxWidth = (btn.roundedRect ? btn.hitRect.width() : btn.hitRect.width() * 0.82f) - 8f;
            drawFittedLabel(canvas, btn.label, btn.hitRect.centerX(), btn.hitRect.centerY(), maxWidth, baseLabelSize);
        }
    }

    /**
     * Draws (possibly multi-line, via "\n") text centered at (cx, cy), shrinking
     * the font until every line fits within maxWidth -- long words like
     * "DISPARAR" or "APUNTAR" would otherwise spill out of a small button and
     * overlap its neighbors.
     */
    private void drawFittedLabel(Canvas canvas, String label, float cx, float cy, float maxWidth, float startSize) {
        String[] lines = label.split("\n");

        float size = startSize;
        labelPaint.setTextSize(size);
        float widest = 0f;
        for (String line : lines) widest = Math.max(widest, labelPaint.measureText(line));

        float minSize = startSize * 0.4f;
        while (widest > maxWidth && size > minSize) {
            size -= 2f;
            labelPaint.setTextSize(size);
            widest = 0f;
            for (String line : lines) widest = Math.max(widest, labelPaint.measureText(line));
        }

        float lineHeight = labelPaint.descent() - labelPaint.ascent();
        float totalHeight = lineHeight * lines.length;
        float y = cy - totalHeight / 2f - labelPaint.ascent();
        for (String line : lines) {
            canvas.drawText(line, cx, y, labelPaint);
            y += lineHeight;
        }

        labelPaint.setTextSize(startSize); // restore for the next button
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
        // Buttons win ties: a stick's "easy to grab" radius is deliberately
        // bigger than its drawn circle and can reach into a nearby button's
        // hitRect, but landing an exact tap inside a button should always
        // hit that button, not the stick behind it.
        for (Button btn : buttons) {
            if (btn.visible && btn.pointerId == -1 && btn.hitRect.contains(x, y)) {
                btn.pointerId = pointerId;
                setPressed(btn, true);
                if (currentContext == CONTEXT_CUTSCENE && btn.id == BTN_CROSS) {
                    // Skip is a one-shot action -- call it directly instead of
                    // relying on a synthesized Cross press making it through
                    // CPad's edge detection on the right frame (see
                    // TouchControls.cpp for why that was unreliable).
                    nativeSkipCutscene();
                }
                return;
            }
        }
        if (leftStick.visible && leftStick.pointerId == -1 && within(leftStick, x, y)) {
            leftStick.pointerId = pointerId;
            updateStick(leftStick, x, y);
            return;
        }
        if (rightStick.visible && rightStick.pointerId == -1 && within(rightStick, x, y)) {
            rightStick.pointerId = pointerId;
            updateStick(rightStick, x, y);
            return;
        }
        if (currentContext == CONTEXT_MENU && menuMousePointerId == -1) {
            menuMousePointerId = pointerId;
            nativeSetMenuMouse(x, y, true);
        }
    }

    private void handleMove(int pointerId, float x, float y) {
        if (leftStick.pointerId == pointerId) {
            updateStick(leftStick, x, y);
        } else if (rightStick.pointerId == pointerId) {
            updateStick(rightStick, x, y);
        } else if (menuMousePointerId == pointerId) {
            nativeSetMenuMouse(x, y, true);
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
        for (Button btn : buttons) {
            if (btn.pointerId == pointerId) {
                btn.pointerId = -1;
                setPressed(btn, false);
            }
        }
        if (menuMousePointerId == pointerId) {
            menuMousePointerId = -1;
            nativeSetMenuMouse(0f, 0f, false);
        }
    }

    /** Called on a context switch so nothing is left "stuck" pressed from the old layout. */
    private void releaseAllInput() {
        if (leftStick.pointerId != -1) {
            leftStick.pointerId = -1;
            leftStick.knob.set(leftStick.center.x, leftStick.center.y);
            nativeSetStick(STICK_LEFT, 0f, 0f);
        }
        if (rightStick.pointerId != -1) {
            rightStick.pointerId = -1;
            rightStick.knob.set(rightStick.center.x, rightStick.center.y);
            nativeSetStick(STICK_RIGHT, 0f, 0f);
        }
        for (Button btn : buttons) {
            if (btn.pointerId != -1 || btn.pressed) {
                btn.pointerId = -1;
                setPressed(btn, false);
            }
        }
        if (menuMousePointerId != -1) {
            menuMousePointerId = -1;
            nativeSetMenuMouse(0f, 0f, false);
        }
    }

    private void setPressed(Button btn, boolean pressed) {
        btn.pressed = pressed;
        nativeSetButton(btn.id, pressed);
    }

    private boolean within(Stick s, float x, float y) {
        float dx = x - s.center.x;
        float dy = y - s.center.y;
        float r = s.baseRadius * 1.25f; // generous, but buttons above now win ties anyway (see handleDown)
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
        nativeSetStick(s.id, dx / max, dy / max);
    }
}
