package com.revc.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
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
    private static native boolean nativeIsControllerConnected();
    private static native void nativeSetRenderScale(int percent);
    private static native int nativeGetRenderScale();

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
        final String prefKey; // "L" / "R" -- stable identity for saved layout customization
        float baseRadius;
        float knobRadius;
        PointF center = new PointF();
        PointF knob = new PointF();
        int pointerId = -1;
        boolean visible = true;

        // Camera stick only: a "floating" look pad instead of a fixed-center
        // joystick -- a finger placed *anywhere* in a wide zone spawns the
        // stick right there (mobile-style camera drag, e.g. Free Fire/PUBG
        // Mobile), instead of requiring you to precisely find the small
        // resting circle. Deflection from that point still behaves exactly
        // like a normal analog stick (proportional, clamped, held = keeps
        // turning), just anchored wherever the drag started.
        boolean isLookPad = false;
        RectF grabZone = new RectF(); // where a finger may land to grab this pad
        PointF dragOrigin = new PointF(); // this drag's floating center, set on touch-down

        Stick(int id, String prefKey) {
            this.id = id;
            this.prefKey = prefKey;
        }
    }

    private static final class Button {
        final int id;
        String label;
        Bitmap icon; // when set, drawn instead of the plain circle+label (see onDraw)
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

    private final Stick leftStick = new Stick(STICK_LEFT, "L");
    private final Stick rightStick = new Stick(STICK_RIGHT, "R");
    {
        rightStick.isLookPad = true;
    }

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

    // Per-control, per-context position offset and size scale, so a control
    // dragged/resized in the on-foot layout doesn't affect the vehicle one
    // (they mostly share the same button IDs but different default spots).
    private final SharedPreferences layoutPrefs;
    private boolean editMode = false;
    private Button selectedButton;
    private Stick selectedStick;
    private final RectF editToggleRect = new RectF();
    private final RectF shrinkRect = new RectF();
    private final RectF growRect = new RectF();
    private final RectF resetRect = new RectF();
    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 1.8f;

    // Internal render resolution (see DEVICE{GET,SET}RENDERSCALE, wired up
    // through librw's GL3/SDL2 backend): a performance/sharpness tradeoff,
    // independent of everything else here. 100 = native. Persisted globally
    // (not per-context like the layout customization above).
    //
    // UI disabled for now: on-device testing showed visible flicker when
    // changing it (still not root-caused -- possibly MIUI's Game Turbo
    // overlay fighting for the same top-right corner, possibly something
    // in our own blit path; needs to be reproduced without that overlay in
    // the way before it's trusted). The native engine plumbing stays in
    // (dead code, only reachable from here) so it doesn't need re-doing
    // once it's actually root-caused -- just flip this back to true.
    private static final boolean RENDER_SCALE_UI_ENABLED = false;
    private static final String PREF_RENDER_SCALE = "render_scale_percent";
    private static final int RENDER_SCALE_MIN = 25;
    private static final int RENDER_SCALE_MAX = 100;
    private static final int RENDER_SCALE_STEP = 5;
    private int renderScalePercent;
    // The native engine may still be mid-init when this view first attaches
    // (it runs on its own thread) -- nativeSetRenderScale() itself is safe
    // to call any time (see Engine::setRenderScale's own guard), so we just
    // keep re-applying the saved value on every poll tick until it reads
    // back correctly, then stop.
    private boolean renderScaleApplied = false;
    private boolean showRenderScalePanel = false;
    private final RectF renderScaleBadgeRect = new RectF();
    private final RectF renderScaleCloseRect = new RectF();
    private final RectF renderScalePlusRect = new RectF();
    private final RectF renderScaleMinusRect = new RectF();

    private final Handler contextPoller = new Handler(Looper.getMainLooper());
    private final Runnable pollContext = new Runnable() {
        @Override
        public void run() {
            int ctx;
            boolean controllerConnected;
            try {
                ctx = nativeGetGameContext();
                controllerConnected = nativeIsControllerConnected();
                if (RENDER_SCALE_UI_ENABLED && !renderScaleApplied) {
                    nativeSetRenderScale(renderScalePercent);
                    if (nativeGetRenderScale() == renderScalePercent) renderScaleApplied = true;
                }
            } catch (UnsatisfiedLinkError e) {
                ctx = currentContext; // native lib not ready yet, keep current layout
                controllerConnected = false;
            }

            // A real gamepad drives pad 0 directly the moment it's connected
            // (see CapturePad() in sdl2.cpp) -- the virtual controls would
            // just be redundant clutter on screen, or worse, fight the
            // physical stick if a finger is still resting on one.
            boolean shouldShow = !controllerConnected;
            if (shouldShow != (getVisibility() == VISIBLE)) {
                if (!shouldShow) {
                    releaseAllInput();
                    editMode = false;
                    selectedButton = null;
                    selectedStick = null;
                }
                setVisibility(shouldShow ? VISIBLE : GONE);
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

    // Custom artwork for the buttons that used to just show a Spanish word --
    // each one is a complete button graphic (its own glossy circle/bezel),
    // so it's drawn in place of our usual fillPaint+strokePaint circle and
    // label, not on top of it (see onDraw). Loaded once here; buttons.icon
    // just points at whichever of these applies for the current context.
    private final Bitmap icRun, icJump, icShoot, icEnterVehicle, icExitVehicle,
            icPhone, icAim, icCamera, icAccelerate, icBrake, icHandbrake, icRadio, icHorn;

    private Bitmap loadIcon(Context context, int resId) {
        return BitmapFactory.decodeResource(context.getResources(), resId);
    }

    public TouchControlsView(Context context) {
        super(context);
        setWillNotDraw(false);

        icRun = loadIcon(context, R.drawable.ic_run);
        icJump = loadIcon(context, R.drawable.ic_jump);
        icShoot = loadIcon(context, R.drawable.ic_shoot);
        icEnterVehicle = loadIcon(context, R.drawable.ic_enter_vehicle);
        icExitVehicle = loadIcon(context, R.drawable.ic_exit_vehicle);
        icPhone = loadIcon(context, R.drawable.ic_phone);
        icAim = loadIcon(context, R.drawable.ic_aim);
        icCamera = loadIcon(context, R.drawable.ic_camera);
        icAccelerate = loadIcon(context, R.drawable.ic_accelerate);
        icBrake = loadIcon(context, R.drawable.ic_brake);
        icHandbrake = loadIcon(context, R.drawable.ic_handbrake);
        icRadio = loadIcon(context, R.drawable.ic_radio);
        icHorn = loadIcon(context, R.drawable.ic_horn);

        layoutPrefs = context.getSharedPreferences("touch_controls_layout", Context.MODE_PRIVATE);
        renderScalePercent = RENDER_SCALE_UI_ENABLED ? layoutPrefs.getInt(PREF_RENDER_SCALE, RENDER_SCALE_MAX) : RENDER_SCALE_MAX;

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
        applyStickCustomization(leftStick);

        rightStick.baseRadius = baseRadius;
        rightStick.knobRadius = baseRadius * 0.45f;
        rightStick.center.set(areaRight - margin - baseRadius, areaBottom - margin - baseRadius);
        applyStickCustomization(rightStick);
        // Grab zone for the look pad: the whole right half of the screen, not
        // just the small visual circle -- a finger can land anywhere there to
        // start dragging. Buttons still win ties (checked first in
        // handleDown), so this doesn't steal taps meant for them.
        float midX = (areaLeft + areaRight) / 2f;
        rightStick.grabZone.set(midX, areaTop, areaRight, areaBottom);

        baseLabelSize = baseRadius * 0.3f;
        labelPaint.setTextSize(baseLabelSize);

        for (Button btn : buttons) {
            btn.visible = false;
            btn.roundedRect = false;
            btn.icon = null; // each context re-sets whichever buttons it wants iconified
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

        layoutEditToolbar(baseRadius);
        layoutRenderScalePanel(baseRadius);
    }

    /** Small badge, top-right, in every context -- tap to reveal +/- steppers. */
    private void layoutRenderScalePanel(float baseRadius) {
        if (!RENDER_SCALE_UI_ENABLED) return;
        float h = baseRadius * 0.32f;
        float badgeW = baseRadius * 0.6f;
        float right = areaRight - 6f;
        float y = areaTop + 6f;
        renderScaleBadgeRect.set(right - badgeW, y, right, y + h);

        if (!showRenderScalePanel) return;

        float gap = h * 0.25f;
        float sideW = h * 1.3f;
        renderScaleCloseRect.set(renderScaleBadgeRect.left - gap - sideW, y, renderScaleBadgeRect.left - gap, y + h);
        renderScalePlusRect.set(renderScaleCloseRect.left - gap - sideW, y, renderScaleCloseRect.left - gap, y + h);
        renderScaleMinusRect.set(renderScalePlusRect.left - gap - sideW, y, renderScalePlusRect.left - gap, y + h);
    }

    /** Small always-on-top toolbar, bottom-center -- clear of every context's own controls. */
    private void layoutEditToolbar(float baseRadius) {
        float midX = (areaLeft + areaRight) / 2f;
        float toggleH = baseRadius * 0.4f;
        float toggleW = baseRadius * (editMode ? 0.75f : 0.55f);
        float y = areaBottom - toggleH - 6f;
        editToggleRect.set(midX - toggleW / 2f, y, midX + toggleW / 2f, y + toggleH);

        if (editMode) {
            float gap = toggleW * 0.35f;
            float sideW = toggleH * 1.3f;
            shrinkRect.set(editToggleRect.left - gap - sideW, y, editToggleRect.left - gap, y + toggleH);
            growRect.set(editToggleRect.right + gap, y, editToggleRect.right + gap + sideW, y + toggleH);
            resetRect.set(growRect.right + gap, y, growRect.right + gap + sideW * 1.6f, y + toggleH);
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
        b(BTN_CROSS).icon = icRun;
        b(BTN_SQUARE).label = "SALTAR";
        b(BTN_SQUARE).icon = icJump;
        b(BTN_CIRCLE).label = "DISPARAR";
        b(BTN_CIRCLE).icon = icShoot;
        b(BTN_TRIANGLE).label = "SUBIR";
        b(BTN_TRIANGLE).icon = icEnterVehicle;

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
        b(BTN_L1).icon = icPhone;
        b(BTN_R1).label = "APUNTAR";
        b(BTN_R1).icon = icAim;

        // Duck (L3 -- CPad::DuckJustDown() reads LeftShock, the left stick
        // click). Was never placed on foot at all, so there was no way to
        // agacharse. Same spot layoutVehicle() already uses for L3/horn:
        // centered above the L1/L2/R1/R2 row.
        placeCircle(BTN_L3, leftStick.center.x, rowY - shR * 2.2f, shR);
        b(BTN_L3).label = "AGACHAR";

        // Select (camera view), top area but clear of the radar (top-left,
        // see RADAR_LEFT/TOP/WIDTH/HEIGHT in Radar.h -- roughly the left 21%
        // of the screen). Round, like every other icon button now, instead
        // of a wide rect the round artwork would get squashed into.
        float camR = baseRadius * 0.32f;
        float camCx = Math.max(areaLeft + margin + camR, areaLeft + (areaRight - areaLeft) * 0.24f);
        placeCircle(BTN_SELECT, camCx, areaTop + margin + camR, camR);
        b(BTN_SELECT).label = "CAM";
        b(BTN_SELECT).icon = icCamera;

        // Start (pause), top-right corner.
        placeRect(BTN_START, areaRight - margin - baseRadius * 0.7f, areaTop + margin, areaRight - margin, areaTop + margin + baseRadius * 0.35f);
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
        b(BTN_CROSS).icon = icAccelerate;
        b(BTN_SQUARE).label = "FRENO";
        b(BTN_SQUARE).icon = icBrake;

        float btnR = baseRadius * 0.32f;
        float faceCx = rightStick.center.x;
        float faceCy = rightStick.center.y - baseRadius * 2.1f;
        placeCircle(BTN_TRIANGLE, faceCx, faceCy - btnR * 1.6f, btnR);
        placeCircle(BTN_CIRCLE, faceCx, faceCy + btnR * 1.6f, btnR);
        b(BTN_TRIANGLE).label = "SALIR";
        b(BTN_TRIANGLE).icon = icExitVehicle;
        b(BTN_CIRCLE).label = "DISPARAR";
        b(BTN_CIRCLE).icon = icShoot;

        float shR = baseRadius * 0.3f;
        float rowY = leftStick.center.y - baseRadius * 1.9f;
        placeCircle(BTN_L1, leftStick.center.x - shR * 1.2f, rowY, shR);
        placeCircle(BTN_R1, leftStick.center.x + shR * 1.2f, rowY, shR);
        b(BTN_L1).label = "RADIO";
        b(BTN_L1).icon = icRadio;
        b(BTN_R1).label = "FRENO\nMANO";
        b(BTN_R1).icon = icHandbrake;

        // Drive-by (L2/R2 -- CPad::GetLookLeft()/GetLookRight(), read
        // straight off LeftShoulder2/RightShoulder2). These were never
        // placed in the vehicle context at all, so there was no way to
        // shoot out either side while driving. Flanking L1/R1, same
        // shGap-style spacing layoutOnFoot() uses for its L1/L2/R1/R2 row.
        float shGap = shR * 2.5f;
        placeCircle(BTN_L2, leftStick.center.x - shGap * 1.5f, rowY, shR);
        placeCircle(BTN_R2, leftStick.center.x + shGap * 1.5f, rowY, shR);
        b(BTN_L2).label = "DISPARAR\nIZQ.";
        b(BTN_R2).label = "DISPARAR\nDER.";

        placeCircle(BTN_L3, leftStick.center.x, rowY - shR * 2.2f, shR);
        b(BTN_L3).label = "BOCINA";
        b(BTN_L3).icon = icHorn;

        float camR = baseRadius * 0.32f;
        float camCx = Math.max(areaLeft + margin + camR, areaLeft + (areaRight - areaLeft) * 0.24f);
        placeCircle(BTN_SELECT, camCx, areaTop + margin + camR, camR);
        b(BTN_SELECT).label = "CAM";
        b(BTN_SELECT).icon = icCamera;

        // Start (pause) -- was never placed in this context at all, so there
        // was simply no way to pause while driving. Same top-right spot as
        // on foot.
        placeRect(BTN_START, areaRight - margin - baseRadius * 0.7f, areaTop + margin, areaRight - margin, areaTop + margin + baseRadius * 0.35f);
        b(BTN_START).label = "≡";
    }

    private void placeCircle(int id, float cx, float cy, float radius) {
        Button btn = b(id);
        btn.visible = true;
        btn.roundedRect = false;
        btn.hitRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        applyButtonCustomization(btn);
    }

    private void placeRect(int id, float left, float top, float right, float bottom) {
        Button btn = b(id);
        btn.visible = true;
        btn.roundedRect = true;
        btn.hitRect.set(left, top, right, bottom);
        applyButtonCustomization(btn);
    }

    // --- Layout customization (edit mode) ------------------------------------

    private String prefKeyBase(String controlKey) {
        return currentContext + "_" + controlKey;
    }

    private void applyButtonCustomization(Button btn) {
        String key = prefKeyBase("btn" + btn.id);
        float dx = layoutPrefs.getFloat(key + "_dx", 0f);
        float dy = layoutPrefs.getFloat(key + "_dy", 0f);
        float scale = layoutPrefs.getFloat(key + "_scale", 1f);
        if (dx == 0f && dy == 0f && scale == 1f) return;

        float cx = btn.hitRect.centerX() + dx;
        float cy = btn.hitRect.centerY() + dy;
        float w = btn.hitRect.width() * scale;
        float h = btn.hitRect.height() * scale;
        btn.hitRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
    }

    private void applyStickCustomization(Stick s) {
        String key = prefKeyBase("stick" + s.prefKey);
        float dx = layoutPrefs.getFloat(key + "_dx", 0f);
        float dy = layoutPrefs.getFloat(key + "_dy", 0f);
        float scale = layoutPrefs.getFloat(key + "_scale", 1f);
        s.center.offset(dx, dy);
        s.baseRadius *= scale;
        s.knobRadius *= scale;
        s.knob.set(s.center.x, s.center.y);
    }

    private void saveButtonOffset(Button btn, float dx, float dy) {
        String key = prefKeyBase("btn" + btn.id);
        float prevDx = layoutPrefs.getFloat(key + "_dx", 0f);
        float prevDy = layoutPrefs.getFloat(key + "_dy", 0f);
        layoutPrefs.edit().putFloat(key + "_dx", prevDx + dx).putFloat(key + "_dy", prevDy + dy).apply();
    }

    private void saveStickOffset(Stick s, float dx, float dy) {
        String key = prefKeyBase("stick" + s.prefKey);
        float prevDx = layoutPrefs.getFloat(key + "_dx", 0f);
        float prevDy = layoutPrefs.getFloat(key + "_dy", 0f);
        layoutPrefs.edit().putFloat(key + "_dx", prevDx + dx).putFloat(key + "_dy", prevDy + dy).apply();
    }

    private void adjustSelectedScale(float delta) {
        if (selectedButton != null) {
            String key = prefKeyBase("btn" + selectedButton.id);
            float scale = clampScale(layoutPrefs.getFloat(key + "_scale", 1f) + delta);
            layoutPrefs.edit().putFloat(key + "_scale", scale).apply();
        } else if (selectedStick != null) {
            String key = prefKeyBase("stick" + selectedStick.prefKey);
            float scale = clampScale(layoutPrefs.getFloat(key + "_scale", 1f) + delta);
            layoutPrefs.edit().putFloat(key + "_scale", scale).apply();
        } else {
            return;
        }
        layoutControls();
        invalidate();
    }

    private float clampScale(float scale) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    private void resetCurrentContextLayout() {
        SharedPreferences.Editor editor = layoutPrefs.edit();
        String prefix = currentContext + "_";
        for (String k : layoutPrefs.getAll().keySet()) {
            if (k.startsWith(prefix)) editor.remove(k);
        }
        editor.apply();
        selectedButton = null;
        selectedStick = null;
        layoutControls();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (leftStick.visible) drawStick(canvas, leftStick);
        if (rightStick.visible) drawStick(canvas, rightStick);

        for (Button btn : buttons) {
            if (!btn.visible) continue;

            if (btn.icon != null) {
                drawIconButton(canvas, btn);
            } else {
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

            if (editMode && btn == selectedButton) {
                drawSelectionRing(canvas, btn.hitRect.centerX(), btn.hitRect.centerY(),
                        btn.roundedRect ? btn.hitRect.width() / 2f + 10f : btn.hitRect.width() / 2f + 10f);
            }
        }

        if (editMode) {
            if (leftStick.visible && selectedStick == leftStick)
                drawSelectionRing(canvas, leftStick.center.x, leftStick.center.y, leftStick.baseRadius + 12f);
            if (rightStick.visible && selectedStick == rightStick)
                drawSelectionRing(canvas, rightStick.center.x, rightStick.center.y, rightStick.baseRadius + 12f);
        }

        drawEditToolbar(canvas);
        drawRenderScalePanel(canvas);
    }

    private void drawRenderScalePanel(Canvas canvas) {
        if (!RENDER_SCALE_UI_ENABLED) return;
        fillPaint.setAlpha(showRenderScalePanel ? 160 : 90);
        canvas.drawRoundRect(renderScaleBadgeRect, 10f, 10f, fillPaint);
        canvas.drawRoundRect(renderScaleBadgeRect, 10f, 10f, strokePaint);
        drawFittedLabel(canvas, renderScalePercent + "%", renderScaleBadgeRect.centerX(), renderScaleBadgeRect.centerY(),
                renderScaleBadgeRect.width() - 6f, baseLabelSize * 0.7f);

        if (!showRenderScalePanel) return;

        fillPaint.setAlpha(140);
        canvas.drawRoundRect(renderScaleCloseRect, 10f, 10f, fillPaint);
        canvas.drawRoundRect(renderScaleCloseRect, 10f, 10f, strokePaint);
        drawFittedLabel(canvas, "✕", renderScaleCloseRect.centerX(), renderScaleCloseRect.centerY(),
                renderScaleCloseRect.width() - 6f, baseLabelSize * 0.7f);

        fillPaint.setAlpha(renderScalePercent < RENDER_SCALE_MAX ? 140 : 60);
        canvas.drawRoundRect(renderScalePlusRect, 10f, 10f, fillPaint);
        canvas.drawRoundRect(renderScalePlusRect, 10f, 10f, strokePaint);
        drawFittedLabel(canvas, "+", renderScalePlusRect.centerX(), renderScalePlusRect.centerY(),
                renderScalePlusRect.width() - 6f, baseLabelSize * 0.7f);

        fillPaint.setAlpha(renderScalePercent > RENDER_SCALE_MIN ? 140 : 60);
        canvas.drawRoundRect(renderScaleMinusRect, 10f, 10f, fillPaint);
        canvas.drawRoundRect(renderScaleMinusRect, 10f, 10f, strokePaint);
        drawFittedLabel(canvas, "-", renderScaleMinusRect.centerX(), renderScaleMinusRect.centerY(),
                renderScaleMinusRect.width() - 6f, baseLabelSize * 0.7f);
    }

    // Reused every frame instead of allocated per button, same as the paints above.
    private final Rect iconSrcRect = new Rect();
    private final RectF iconDstRect = new RectF();
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    /**
     * Custom-art buttons (see icRun etc.) are complete button graphics with
     * their own circular bezel baked in, so this draws the bitmap in place
     * of the usual fillPaint circle + strokePaint ring + text label -- not
     * on top of them.
     */
    private void drawIconButton(Canvas canvas, Button btn) {
        RectF r = btn.hitRect;
        // The art is a complete round button, so it's always drawn as a
        // square -- sized to the hit area's smaller dimension, a hair over
        // so the art's own ring lines up with where a thumb actually expects
        // the edge to be -- never stretched to a non-square hitRect's own
        // aspect ratio (e.g. GAS/FRENO's pedal rects), which would squash it.
        float size = Math.min(r.width(), r.height()) * 1.08f;
        float cx = r.centerX();
        float cy = r.centerY();
        iconDstRect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
        iconSrcRect.set(0, 0, btn.icon.getWidth(), btn.icon.getHeight());
        canvas.drawBitmap(btn.icon, iconSrcRect, iconDstRect, iconPaint);

        if (btn.pressed) {
            fillPaint.setAlpha(90);
            float cr = Math.max(iconDstRect.width(), iconDstRect.height()) / 2f;
            canvas.drawCircle(iconDstRect.centerX(), iconDstRect.centerY(), cr, fillPaint);
        }
    }

    private void drawSelectionRing(Canvas canvas, float cx, float cy, float radius) {
        int prevColor = strokePaint.getColor();
        float prevWidth = strokePaint.getStrokeWidth();
        strokePaint.setColor(Color.YELLOW);
        strokePaint.setStrokeWidth(5f);
        canvas.drawCircle(cx, cy, radius, strokePaint);
        strokePaint.setColor(prevColor);
        strokePaint.setStrokeWidth(prevWidth);
    }

    private void drawEditToolbar(Canvas canvas) {
        fillPaint.setAlpha(editMode ? 160 : 90);
        canvas.drawRoundRect(editToggleRect, 10f, 10f, fillPaint);
        canvas.drawRoundRect(editToggleRect, 10f, 10f, strokePaint);
        drawFittedLabel(canvas, editMode ? "LISTO" : "⚙", editToggleRect.centerX(), editToggleRect.centerY(),
                editToggleRect.width() - 8f, baseLabelSize * 0.8f);

        if (!editMode) return;

        boolean hasSelection = selectedButton != null || selectedStick != null;
        fillPaint.setAlpha(hasSelection ? 140 : 60);
        canvas.drawRoundRect(shrinkRect, 10f, 10f, fillPaint);
        canvas.drawRoundRect(shrinkRect, 10f, 10f, strokePaint);
        drawFittedLabel(canvas, "-", shrinkRect.centerX(), shrinkRect.centerY(), shrinkRect.width() - 8f, baseLabelSize);

        canvas.drawRoundRect(growRect, 10f, 10f, fillPaint);
        canvas.drawRoundRect(growRect, 10f, 10f, strokePaint);
        drawFittedLabel(canvas, "+", growRect.centerX(), growRect.centerY(), growRect.width() - 8f, baseLabelSize);

        fillPaint.setAlpha(110);
        canvas.drawRoundRect(resetRect, 10f, 10f, fillPaint);
        canvas.drawRoundRect(resetRect, 10f, 10f, strokePaint);
        drawFittedLabel(canvas, "RESET", resetRect.centerX(), resetRect.centerY(), resetRect.width() - 8f, baseLabelSize * 0.8f);
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
        boolean active = s.pointerId != -1;
        // A look pad's base "appears" where the drag started while active,
        // instead of always sitting at its resting spot -- that resting
        // circle is just a hint of where to put your thumb.
        PointF base = (s.isLookPad && active) ? s.dragOrigin : s.center;

        fillPaint.setAlpha(50);
        canvas.drawCircle(base.x, base.y, s.baseRadius, fillPaint);
        canvas.drawCircle(base.x, base.y, s.baseRadius, strokePaint);
        fillPaint.setAlpha(active ? 150 : 90);
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

    // Set while a finger drags the currently selected control in edit mode.
    private int editDragPointerId = -1;
    private float dragLastX, dragLastY;

    private void setRenderScalePercent(int percent) {
        percent = Math.max(RENDER_SCALE_MIN, Math.min(RENDER_SCALE_MAX, percent));
        if (percent == renderScalePercent) return;
        renderScalePercent = percent;
        layoutPrefs.edit().putInt(PREF_RENDER_SCALE, renderScalePercent).apply();
        try {
            nativeSetRenderScale(renderScalePercent);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    private void handleDown(int pointerId, float x, float y) {
        if (RENDER_SCALE_UI_ENABLED && renderScaleBadgeRect.contains(x, y)) {
            showRenderScalePanel = !showRenderScalePanel;
            layoutControls();
            return;
        }
        if (RENDER_SCALE_UI_ENABLED && showRenderScalePanel) {
            if (renderScaleCloseRect.contains(x, y)) {
                showRenderScalePanel = false;
                layoutControls();
                return;
            }
            if (renderScalePlusRect.contains(x, y)) {
                setRenderScalePercent(renderScalePercent + RENDER_SCALE_STEP);
                return;
            }
            if (renderScaleMinusRect.contains(x, y)) {
                setRenderScalePercent(renderScalePercent - RENDER_SCALE_STEP);
                return;
            }
        }

        if (editToggleRect.contains(x, y)) {
            editMode = !editMode;
            releaseAllInput();
            if (!editMode) {
                selectedButton = null;
                selectedStick = null;
            }
            layoutControls();
            return;
        }

        if (editMode) {
            if (shrinkRect.contains(x, y)) {
                adjustSelectedScale(-0.1f);
                return;
            }
            if (growRect.contains(x, y)) {
                adjustSelectedScale(0.1f);
                return;
            }
            if (resetRect.contains(x, y)) {
                resetCurrentContextLayout();
                return;
            }
            for (Button btn : buttons) {
                if (btn.visible && btn.hitRect.contains(x, y)) {
                    selectedButton = btn;
                    selectedStick = null;
                    editDragPointerId = pointerId;
                    dragLastX = x;
                    dragLastY = y;
                    return;
                }
            }
            if (leftStick.visible && within(leftStick, x, y)) {
                selectedStick = leftStick;
                selectedButton = null;
                editDragPointerId = pointerId;
                dragLastX = x;
                dragLastY = y;
                return;
            }
            if (rightStick.visible && within(rightStick, x, y)) {
                selectedStick = rightStick;
                selectedButton = null;
                editDragPointerId = pointerId;
                dragLastX = x;
                dragLastY = y;
                return;
            }
            // Tapped empty space in edit mode -- don't fall through to normal
            // gameplay press/menu-mouse handling below.
            return;
        }

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
            if (rightStick.isLookPad) {
                beginLookPad(rightStick, x, y);
            } else {
                updateStick(rightStick, x, y);
            }
            return;
        }
        if (currentContext == CONTEXT_MENU && menuMousePointerId == -1) {
            menuMousePointerId = pointerId;
            nativeSetMenuMouse(x, y, true);
        }
    }

    private void handleMove(int pointerId, float x, float y) {
        if (editDragPointerId == pointerId) {
            float dx = x - dragLastX;
            float dy = y - dragLastY;
            dragLastX = x;
            dragLastY = y;
            if (selectedButton != null) {
                selectedButton.hitRect.offset(dx, dy);
                saveButtonOffset(selectedButton, dx, dy);
            } else if (selectedStick != null) {
                selectedStick.center.offset(dx, dy);
                selectedStick.knob.set(selectedStick.center.x, selectedStick.center.y);
                saveStickOffset(selectedStick, dx, dy);
            }
            return;
        }
        if (leftStick.pointerId == pointerId) {
            updateStick(leftStick, x, y);
        } else if (rightStick.pointerId == pointerId) {
            updateStick(rightStick, x, y);
        } else if (menuMousePointerId == pointerId) {
            nativeSetMenuMouse(x, y, true);
        }
    }

    private void handleUp(int pointerId) {
        if (editDragPointerId == pointerId) {
            editDragPointerId = -1;
            return;
        }
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
        if (s.isLookPad) {
            return s.grabZone.contains(x, y);
        }
        float dx = x - s.center.x;
        float dy = y - s.center.y;
        float r = s.baseRadius * 1.25f; // generous, but buttons above now win ties anyway (see handleDown)
        return dx * dx + dy * dy <= r * r;
    }

    /** Look pads only: called once, on touch-down, to spawn the floating stick at the touch point. */
    private void beginLookPad(Stick s, float x, float y) {
        s.dragOrigin.set(x, y);
        s.knob.set(x, y);
        nativeSetStick(s.id, 0f, 0f);
    }

    private void updateStick(Stick s, float x, float y) {
        PointF origin = s.isLookPad ? s.dragOrigin : s.center;
        float dx = x - origin.x;
        float dy = y - origin.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float max = s.baseRadius;

        if (dist > max) {
            dx = dx / dist * max;
            dy = dy / dist * max;
        }

        s.knob.set(origin.x + dx, origin.y + dy);
        nativeSetStick(s.id, dx / max, dy / max);
    }
}
