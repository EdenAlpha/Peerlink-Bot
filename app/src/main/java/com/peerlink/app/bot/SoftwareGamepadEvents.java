package com.peerlink.app.bot;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/** Events handed directly to the private bot engine; never injected into Android's input system. */
public final class SoftwareGamepadEvents {
    private SoftwareGamepadEvents() {}
    public static KeyEvent key(int code, int action) {
        if ((code != 96 && code != 97 && code != 99 && code != 100 && code != 102 && code != 103)
                || (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP))
            throw new IllegalArgumentException("Unsupported bot gamepad key");
        long now = SystemClock.uptimeMillis();
        return new KeyEvent(now, now, action, code, 0, 0, 4242, 0, 0, 0x401);
    }
    public static MotionEvent motion(float x, float y, float dash) {
        MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties(); pointer.id = 0;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
        coordinates.setAxisValue(MotionEvent.AXIS_X, x);
        coordinates.setAxisValue(MotionEvent.AXIS_Y, y);
        // This engine accepts both Android right-trigger aliases in axis order.
        coordinates.setAxisValue(MotionEvent.AXIS_RTRIGGER, dash);
        coordinates.setAxisValue(MotionEvent.AXIS_GAS, dash);
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1,
            new MotionEvent.PointerProperties[]{pointer}, new MotionEvent.PointerCoords[]{coordinates},
            0, 0, 1, 1, 4242, 0, InputDevice.SOURCE_JOYSTICK, 0);
    }
}
