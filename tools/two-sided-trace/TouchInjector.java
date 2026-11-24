import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/** Test-phone shell input: one held joystick finger and a second action finger. */
public final class TouchInjector {
    private final Object input;
    private final Method inject;
    private final Method setDisplay;
    private final long downTime = SystemClock.uptimeMillis();
    private final MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
    private final MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];

    private TouchInjector() throws Exception {
        Object manager = null;
        Method injector = null;
        for (String name : new String[]{"android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager"}) {
            try {
                Class<?> type = Class.forName(name);
                Method singleton = type.getDeclaredMethod("getInstance");
                singleton.setAccessible(true);
                manager = singleton.invoke(null);
                injector = type.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
                injector.setAccessible(true);
                break;
            } catch (ReflectiveOperationException ignored) { manager = null; }
        }
        if (manager == null || injector == null) throw new IllegalStateException("Android input service unavailable");
        input = manager;
        inject = injector;
        Method display = null;
        try { display = InputEvent.class.getDeclaredMethod("setDisplayId", int.class); display.setAccessible(true); }
        catch (ReflectiveOperationException ignored) {}
        setDisplay = display;
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].pressure = 1f;
            coords[i].size = 1f;
        }
    }

    private void point(int i, float x, float y) { coords[i].x = x; coords[i].y = y; }

    private void send(int action, int count) throws Exception {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count,
            properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            if (setDisplay != null) setDisplay.invoke(event, 0);
            if (!Boolean.TRUE.equals(inject.invoke(input, event, 2)))
                throw new IllegalStateException("Android rejected a touch event");
        } finally { event.recycle(); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--stdin".equals(args[0])) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = reader.readLine()) != null) {
                try { perform(line.trim().split("\\s+")); }
                catch (Exception error) { System.err.println("Touch input failed: " + error.getClass().getSimpleName()); }
            }
            return;
        }
        perform(args);
    }

    private static void perform(String[] args) throws Exception {
        if (args.length != 7 && args.length != 8) throw new IllegalArgumentException("Expected stickStartX stickStartY stickEndX stickEndY buttonX buttonY holdMs [actionHoldMs]");
        float x0 = Float.parseFloat(args[0]), y0 = Float.parseFloat(args[1]);
        float x1 = Float.parseFloat(args[2]), y1 = Float.parseFloat(args[3]);
        float buttonX = Float.parseFloat(args[4]), buttonY = Float.parseFloat(args[5]);
        int hold = Integer.parseInt(args[6]);
        int actionHold = args.length == 8 ? Integer.parseInt(args[7]) : 120;
        if (hold < 0 || hold > 5000 || actionHold < 0 || actionHold > 2000)
            throw new IllegalArgumentException("hold duration out of range");
        TouchInjector t = new TouchInjector();
        t.point(0, x0, y0);
        t.send(MotionEvent.ACTION_DOWN, 1);
        for (int i = 1; i <= 5; i++) {
            t.point(0, x0 + (x1 - x0) * i / 5f, y0 + (y1 - y0) * i / 5f);
            t.send(MotionEvent.ACTION_MOVE, 1);
            SystemClock.sleep(40);
        }
        t.point(1, buttonX, buttonY);
        t.send(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2);
        SystemClock.sleep(actionHold);
        t.send(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2);
        SystemClock.sleep(hold);
        t.send(MotionEvent.ACTION_UP, 1);
        System.out.println("Two-finger input injected");
    }
}
