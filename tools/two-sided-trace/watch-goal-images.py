"""Save lab-game screens around a possible goal; frame records remain continuous."""
import json
import math
import os
from pathlib import Path
import struct
import subprocess
import threading
import time

TRACE = Path('/tmp/peerlink-scene-target/game.jsonl')
OUT = Path('/tmp/peerlink-goal-screens')
DEVICE = '127.0.0.1:15556'


def likely_goal(ball_bits):
    if not isinstance(ball_bits, list) or len(ball_bits) < 3:
        return False
    try:
        x, height, z = (struct.unpack('<f', struct.pack('<I', v))[0] for v in ball_bits[:3])
        return all(math.isfinite(v) for v in (x, height, z)) and abs(x) >= 52.5 and abs(z) < 3.7 and height < 2.5
    except (TypeError, ValueError, struct.error):
        return False


def capture(frame):
    target = OUT / f'frame-{frame}'
    target.mkdir(mode=0o700, parents=True, exist_ok=True)
    print(f'Possible goal at frame {frame}; recording screen sequence.', flush=True)
    end = time.monotonic() + 15
    count = 0
    while time.monotonic() < end:
        try:
            data = subprocess.check_output(['adb', '-s', DEVICE, 'exec-out', 'screencap', '-p'], timeout=5)
            (target / f'{time.time_ns()}.png').write_bytes(data)
            count += 1
        except (OSError, subprocess.SubprocessError):
            print('Screen capture failed during possible goal.', flush=True)
        time.sleep(0.6)
    print(f'Possible goal frame {frame}: saved {count} screens.', flush=True)


def main():
    os.umask(0o077)
    OUT.mkdir(mode=0o700, parents=True, exist_ok=True)
    deadline, next_trigger = time.monotonic() + 900, 0.0
    print('Goal screen watcher ready.', flush=True)
    with TRACE.open() as source:
        source.seek(0, 2)
        while time.monotonic() < deadline:
            line = source.readline()
            if not line:
                time.sleep(0.05)
                continue
            try:
                event = json.loads(line)
                if (event.get('type') == 'frame_state' and time.monotonic() >= next_trigger and
                        likely_goal(event.get('ballBits'))):
                    threading.Thread(target=capture, args=(event['frame'],), daemon=True).start()
                    next_trigger = time.monotonic() + 20
            except (ValueError, KeyError):
                continue


if __name__ == '__main__':
    main()
