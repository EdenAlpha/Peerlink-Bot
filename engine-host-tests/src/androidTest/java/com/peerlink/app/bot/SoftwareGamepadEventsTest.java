package com.peerlink.app.bot;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SoftwareGamepadEventsTest {
    @Test public void botEventsUseTheirOwnDeviceAndLeaveThePhoneInputInventoryUnchanged() {
        int[] before = InputDevice.getDeviceIds();
        KeyEvent down = SoftwareGamepadEvents.key(96, KeyEvent.ACTION_DOWN);
        KeyEvent up = SoftwareGamepadEvents.key(96, KeyEvent.ACTION_UP);
        assertEquals(4242, down.getDeviceId());
        assertEquals(96, down.getKeyCode());
        assertEquals(KeyEvent.ACTION_DOWN, down.getAction());
        assertEquals(KeyEvent.ACTION_UP, up.getAction());
        assertTrue(down.isFromSource(InputDevice.SOURCE_GAMEPAD));
        MotionEvent neutral = SoftwareGamepadEvents.neutral();
        try {
            assertEquals(4242, neutral.getDeviceId());
            assertTrue(neutral.isFromSource(InputDevice.SOURCE_JOYSTICK));
            assertFalse(neutral.isFromSource(InputDevice.SOURCE_TOUCHSCREEN));
            assertEquals(0f, neutral.getAxisValue(MotionEvent.AXIS_X), 0f);
            assertEquals(0f, neutral.getAxisValue(MotionEvent.AXIS_Y), 0f);
        } finally { neutral.recycle(); }
        assertArrayEquals(before, InputDevice.getDeviceIds());
    }
    @Test public void touchAndUnmappedKeysCannotBeSubmittedAsBotButtons() {
        try { SoftwareGamepadEvents.key(KeyEvent.KEYCODE_BACK, 0); fail("Back accepted"); }
        catch (IllegalArgumentException expected) { }
        try { SoftwareGamepadEvents.key(96, 8); fail("Invalid action accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
