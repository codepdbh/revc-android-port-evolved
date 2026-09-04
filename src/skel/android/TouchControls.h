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

	// Everything below mirrors a real SDL_GameController's digital buttons,
	// using the game's actual default PS2-style bindings (see
	// CControllerConfigManager::MapIdToButtonId() / InitDefaultControlConfigJoyPad()
	// in ControllerConfig.cpp -- this is not a guess):
	//   Circle (B)    = fire weapon
	//   Cross (A)     = accelerate (vehicle) / sprint (on foot)
	//   Square (X)    = brake (vehicle) / jump (on foot)
	//   Triangle (Y)  = enter / exit vehicle
	//   L1            = answer phone / change radio station
	//   R1            = handbrake / lock target (aim)
	//   L2            = cycle weapon left / look left
	//   R2            = cycle weapon right / look right
	//   Select (Back) = change camera view
	//   L3            = horn / duck
	//   R3            = look behind / toggle submissions
	//   Start         = pause
	//   D-Pad         = frontend/menu navigation (GO_FORWARD/BACK/LEFT/RIGHT)
	bool circle, cross, square, triangle;
	bool leftShoulder1, rightShoulder1; // L1, R1
	bool leftShoulder2, rightShoulder2; // L2, R2
	bool select, start;
	bool leftStickClick, rightStickClick; // L3, R3
	bool dpadUp, dpadDown, dpadLeft, dpadRight;
};

extern TouchPadState g_TouchState;

#endif

#endif //REVC_TOUCHCONTROLS_H
