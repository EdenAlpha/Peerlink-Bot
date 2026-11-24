"""Control the real game in a lab match using verified, live native observations.

This is a testing controller for the full-game side, not PeerLink's bot AI.
Inputs stop when observations are stale, the ball leaves play, or a scene starts.
"""
import datetime
import io
import json
import math
import os
from pathlib import Path
import struct
import subprocess
import time

from PIL import Image

TRACE = os.environ.get('PEERLINK_LAB_TRACE', '/tmp/peerlink-observer/fullgame-state.jsonl')
DEVICE = '127.0.0.1:15556'
SCREENS = Path(os.environ.get('PEERLINK_LAB_SCREENS', '/tmp/peerlink-lab-control-screens'))
SCREENS.mkdir(mode=0o700, exist_ok=True)
touch_log = open(os.environ.get('PEERLINK_LAB_TOUCH_LOG', '/tmp/peerlink-lab-touch.log'), 'a', buffering=1)
touch = subprocess.Popen([
    'adb', '-s', DEVICE, 'shell',
    'CLASSPATH=/data/local/tmp/peerlink-touch-injector.jar app_process /system/bin TouchInjector --stdin'
], stdin=subprocess.PIPE, stdout=touch_log, stderr=touch_log, text=True, bufsize=1)


def distance(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def decode_position(bits):
    if not bits or len(bits) != 3:
        return None
    values = [struct.unpack('<f', struct.pack('<I', n))[0] for n in bits]
    if not all(math.isfinite(x) for x in values) or abs(values[0]) > 65 or abs(values[2]) > 48:
        return None
    return values[0], values[2]


def command(start, target, button, action_hold):
    dx, dz = target[0] - start[0], target[1] - start[1]
    length = max(1.0, math.hypot(dx, dz))
    x = round(100 + 45 * dx / length)
    y = round(260 + 45 * dz / length)
    buttons = {'dash': (585, 307), 'pass': (490, 307), 'through': (512, 230),
               'shoot': (590, 210), 'tackle': (590, 210), 'switch': (490, 307)}
    bx, by = buttons[button]
    touch.stdin.write(f'100 260 {x} {y} {bx} {by} 100 {action_hold}\n')
    touch.stdin.flush()


def current_gameplay_screen(frame):
    try:
        data = subprocess.check_output(['adb', '-s', DEVICE, 'exec-out', 'screencap', '-p'], timeout=5)
        image = Image.open(io.BytesIO(data)).convert('RGB')
        if image.size != (640, 360):
            return False
        points = [(590, 185), (590, 195), (560, 210), (620, 210), (585, 278)]
        green = 0
        for point in points:
            r, g, b = image.getpixel(point)
            green += int(50 <= r <= 150 and 95 <= g <= 185 and g > r + 20 and g > b + 20)
        if green < 4:
            return False
        (SCREENS / f'{frame}.png').write_bytes(data)
        return True
    except (OSError, ValueError, subprocess.SubprocessError):
        return False


last_frame = 0
last_action = 0.0
last_event = time.monotonic()
scene_pause_until = 0.0
saw_match = False
deadline = time.monotonic() + 600
print('Lab control waiting for fresh verified match state.', flush=True)
with open(TRACE) as source:
    source.seek(0, 2)
    while time.monotonic() < deadline:
        line = source.readline()
        if not line:
            if saw_match and time.monotonic() - last_event > 8:
                print('No fresh match checks; inputs stopped.', flush=True)
                break
            if touch.poll() is not None:
                print('Touch injector stopped.', flush=True)
                break
            time.sleep(0.15)
            continue
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if event.get('type') == 'scene_start':
            scene = event.get('before', {}).get('sceneId', 0)
            if scene >> 16 == 3:
                scene_pause_until = max(scene_pause_until, time.monotonic() + 15)
                print('Scene detected; pausing controls for the celebration.', flush=True)
            continue
        if event.get('type') != 'match_check' or not event.get('snapshot', {}).get('verified'):
            continue
        frame = event.get('frame', 0)
        if frame <= last_frame:
            continue
        last_frame = frame
        try:
            age = (datetime.datetime.now(datetime.timezone.utc) -
                   datetime.datetime.fromisoformat(event['recordedAt'])).total_seconds()
        except (ValueError, KeyError):
            continue
        if age > 3 or age < -2:
            continue
        snapshot = event['snapshot']
        control = snapshot.get('control') or {}
        position = control.get('position') or []
        side, selected, owner = control.get('side'), control.get('selected'), control.get('owner')
        if side not in (0, 1) or len(position) != 3:
            continue
        ball = (position[0], position[2])
        if not all(isinstance(v, (int, float)) and math.isfinite(v) for v in position):
            continue
        last_event = time.monotonic()
        saw_match = True
        if abs(ball[0]) >= 52.61 and abs(ball[1]) < 4.0 and position[1] < 2.5:
            scene_pause_until = max(scene_pause_until, time.monotonic() + 15)
        if abs(ball[0]) > 54 or abs(ball[1]) > 36 or time.monotonic() < scene_pause_until:
            continue
        points = [decode_position(p) for p in snapshot.get('playerPositionBits', [])]
        if len(points) != 22:
            continue
        own = range(side * 11, side * 11 + 11)
        opponents = range((1 - side) * 11, (2 - side) * 11)
        keeper, opposing_keeper = control.get('ownKeeper', -1), control.get('opposingKeeper', -1)
        if keeper not in own:
            keeper = side * 11
        if opposing_keeper not in opponents:
            opposing_keeper = (1 - side) * 11
        if not points[keeper] or not points[opposing_keeper]:
            continue
        direction = 1 if points[keeper][0] < points[opposing_keeper][0] else -1
        if selected not in own or points[selected] is None:
            available = [i for i in own if i != keeper and points[i] is not None]
            if not available:
                continue
            selected = min(available, key=lambda i: distance(points[i], ball))
        player = points[selected]
        owner_is_own = owner in own if isinstance(owner, int) else False
        owner_valid = owner_is_own and points[owner] is not None and distance(points[owner], ball) < 3.2
        opponent_points = [points[i] for i in opponents if points[i] is not None]
        if not opponent_points or time.monotonic() - last_action < 0.85:
            continue
        if owner_valid and owner == selected:
            goal = (52.5 * direction, 0.0)
            goal_distance = distance(player, goal)
            pressure = min(distance(player, x) for x in opponent_points)
            if goal_distance <= 23 and abs(player[1]) <= 20:
                action, target, hold = 'shoot', goal, 350
            elif pressure < 4.5:
                candidates = [i for i in own if i not in (selected, keeper) and points[i] is not None and
                              4 < distance(player, points[i]) < 30 and
                              (points[i][0] - player[0]) * direction > 1 and
                              min(distance(points[i], x) for x in opponent_points) > 2]
                if candidates:
                    receiver = max(candidates, key=lambda i: (points[i][0] - player[0]) * direction -
                                   0.2 * distance(player, points[i]))
                    target, action, hold = points[receiver], 'pass', 150
                else:
                    target, action, hold = goal, 'dash', 600
            else:
                target, action, hold = goal, 'dash', 600
        else:
            target = ball
            closest = min((i for i in own if i != keeper and points[i] is not None),
                          key=lambda i: distance(points[i], ball), default=selected)
            if selected == keeper or distance(player, ball) > distance(points[closest], ball) + 7:
                action, hold = 'switch', 120
            elif not owner_is_own and distance(player, ball) < 4:
                action, hold = 'tackle', 160
            else:
                action, hold = 'dash', 600
        if not current_gameplay_screen(frame):
            continue
        try:
            command(player, target, action, hold)
            last_action = time.monotonic()
            print(f'frame={frame} action={action} selected={selected} owner={owner}', flush=True)
        except (BrokenPipeError, OSError):
            print('Could not send touch input; stopped.', flush=True)
            break

if touch.stdin:
    touch.stdin.close()
try:
    touch.wait(timeout=3)
except subprocess.TimeoutExpired:
    touch.terminate()
touch_log.close()
