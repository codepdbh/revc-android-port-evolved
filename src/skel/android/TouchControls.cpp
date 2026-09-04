//
// JNI bridge for the on-screen virtual gamepad (TouchControlsView.java).
// Just stores whatever the UI thread last reported; CaptureTouchPad() in
// skel/sdl2/sdl2.cpp reads it once per frame on the game thread.
//

#if defined ANDROID

#include <jni.h>
#include "AndroidMain.h"
#include "TouchControls.h"

TouchPadState g_TouchState = {};

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

JAVA_WRAPPER Java_com_revc_game_TouchControlsView_nativeSetButton(JNIEnv *env, jobject obj, jint button, jboolean pressed)
{
	switch (button) {
		case 0: g_TouchState.buttonA = pressed; break;
		case 1: g_TouchState.buttonB = pressed; break;
		case 2: g_TouchState.buttonX = pressed; break;
		case 3: g_TouchState.buttonY = pressed; break;
		case 4: g_TouchState.leftTrigger = pressed; break;
		case 5: g_TouchState.rightTrigger = pressed; break;
		default: break;
	}
}

#endif
