//
// JNI bridge for the on-screen virtual gamepad (TouchControlsView.java).
// Just stores whatever the UI thread last reported; CaptureTouchPad() in
// skel/sdl2/sdl2.cpp reads it once per frame on the game thread.
//

#if defined ANDROID

#include <jni.h>
#include "AndroidMain.h"
#include "TouchControls.h"
#include "Frontend.h"
#include "PlayerPed.h"
#include "PlayerInfo.h"
#include "CutsceneMgr.h"

TouchPadState g_TouchState = {};

// Keep in sync with TouchControlsView.BTN_* constants.
enum {
	BTN_CIRCLE = 0,
	BTN_CROSS,
	BTN_SQUARE,
	BTN_TRIANGLE,
	BTN_L1,
	BTN_R1,
	BTN_L2,
	BTN_R2,
	BTN_SELECT,
	BTN_START,
	BTN_L3,
	BTN_R3,
	BTN_DPAD_UP,
	BTN_DPAD_DOWN,
	BTN_DPAD_LEFT,
	BTN_DPAD_RIGHT,
};

JAVA_WRAPPER Java_com_revc_game_TouchControlsView_nativeSetStick(JNIEnv *env, jobject obj, jint stick, jfloat x, jfloat y)
{
	if (stick == 0) {
		g_TouchState.leftX = x;
		g_TouchState.leftY = y;
	} else {
		g_TouchState.rightX = x;
		g_TouchState.rightY = y;
	}
}

JAVA_WRAPPER Java_com_revc_game_TouchControlsView_nativeSetMenuMouse(JNIEnv *env, jobject obj, jfloat x, jfloat y, jboolean down)
{
	g_TouchState.menuMouseX = x;
	g_TouchState.menuMouseY = y;
	g_TouchState.menuMouseDown = down;
}

JAVA_WRAPPER Java_com_revc_game_TouchControlsView_nativeSetButton(JNIEnv *env, jobject obj, jint button, jboolean pressed)
{
	switch (button) {
		case BTN_CIRCLE:      g_TouchState.circle = pressed; break;
		case BTN_CROSS:       g_TouchState.cross = pressed; break;
		case BTN_SQUARE:      g_TouchState.square = pressed; break;
		case BTN_TRIANGLE:    g_TouchState.triangle = pressed; break;
		case BTN_L1:          g_TouchState.leftShoulder1 = pressed; break;
		case BTN_R1:          g_TouchState.rightShoulder1 = pressed; break;
		case BTN_L2:          g_TouchState.leftShoulder2 = pressed; break;
		case BTN_R2:          g_TouchState.rightShoulder2 = pressed; break;
		case BTN_SELECT:      g_TouchState.select = pressed; break;
		case BTN_START:       g_TouchState.start = pressed; break;
		case BTN_L3:          g_TouchState.leftStickClick = pressed; break;
		case BTN_R3:          g_TouchState.rightStickClick = pressed; break;
		case BTN_DPAD_UP:     g_TouchState.dpadUp = pressed; break;
		case BTN_DPAD_DOWN:   g_TouchState.dpadDown = pressed; break;
		case BTN_DPAD_LEFT:   g_TouchState.dpadLeft = pressed; break;
		case BTN_DPAD_RIGHT:  g_TouchState.dpadRight = pressed; break;
		default: break;
	}
}

// 0 = frontend/menu, 1 = on foot, 2 = in a vehicle, 3 = cutscene playing.
// Polled from Java on a timer to decide which touch layout to show.
extern "C" JNIEXPORT jint JNICALL
Java_com_revc_game_TouchControlsView_nativeGetGameContext(JNIEnv *env, jobject obj)
{
	if (FrontEndMenuManager.GetIsMenuActive())
		return 0;

	// Cutscenes take control away from the player entirely; showing movement/
	// action buttons during one is just confusing. CCutsceneMgr::Update()
	// already skips on CPad::GetPad(0)->GetCrossJustDown() (or Start during
	// the intro) -- we just need a big, obvious button wired to the same
	// Cross press.
	if (CCutsceneMgr::IsRunning())
		return 3;

	CPlayerPed *player = FindPlayerPed();
	if (player != nullptr && player->InVehicle())
		return 2;

	return 1;
}

#endif
