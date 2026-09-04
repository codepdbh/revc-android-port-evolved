//
// On-screen virtual gamepad state for Android, fed from TouchControlsView.java
// over JNI and consumed once per frame by CaptureTouchPad() in skel/sdl2/sdl2.cpp.
//

#ifndef REVC_TOUCHCONTROLS_H
#define REVC_TOUCHCONTROLS_H

#if defined ANDROID

struct TouchPadState
{
	// Movement / camera sticks, normalized -1..1 (same convention SDL_GameController
	// axes are converted to before being applied to CControllerState).
	float leftX, leftY;
	float rightX, rightY;

	// Face buttons, matching SDL_CONTROLLER_BUTTON_{A,B,X,Y} indices so they flow
	// through the exact same default gamepad bindings a physical controller uses.
	bool buttonA, buttonB, buttonX, buttonY;

	// L2/R2 analog triggers, treated as digital here (brake / accelerate).
	bool leftTrigger, rightTrigger;
};

extern TouchPadState g_TouchState;

#endif

#endif //REVC_TOUCHCONTROLS_H
