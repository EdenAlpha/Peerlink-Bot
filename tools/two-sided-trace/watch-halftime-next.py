"""Advance the real lab game's halftime Next button, never goal scenes."""
import io
import json
import os
from pathlib import Path
import subprocess
import time

from PIL import Image

DEVICE = '127.0.0.1:15556'
TRACE = Path('/tmp/peerlink-observer/fullgame-state.jsonl')
TEMPLATES = json.loads(Path('/tmp/peerlink-quickmatch-templates.json').read_text())
CLOCK = set(json.loads(Path('/tmp/peerlink-halftime-clock-template.json').read_text()))
NEXT = set(TEMPLATES['next'])
OUT = Path('/tmp/peerlink-halftime-watcher')


def mask(image, box, predicate):
    pixels = image.convert('RGB').crop(box)
    return {i for i, rgb in enumerate(pixels.getdata()) if predicate(*rgb)}


def similarity(actual, expected):
    return len(actual & expected) / max(1, len(actual | expected))


def halftime_next(image):
    if image.size != (640, 360):
        return False
    clock = mask(image, (295, 12, 345, 32), lambda r, g, b: r > 180 and g > 180 and b < 100)
    button = mask(image, (536, 325, 615, 354), lambda r, g, b: r > 200 and g > 200 and b > 200)
    return similarity(clock, CLOCK) >= 0.8 and similarity(button, NEXT) >= 0.8


def main():
    os.umask(0o077)
    OUT.mkdir(mode=0o700, parents=True, exist_ok=True)
    deadline = time.monotonic() + 900
    active, presses, next_press = False, 0, 0.0
    print('Halftime watcher ready; waits for match frames before checking the screen.', flush=True)
    with TRACE.open() as source:
        source.seek(0, 2)
        while time.monotonic() < deadline:
            line = source.readline()
            if line:
                try:
                    event = json.loads(line)
                    if event.get('type') == 'match_check' and event.get('frame', 0) >= 4000:
                        active = True
                    if presses and event.get('type') == 'match_check' and event.get('frame', 0) > 6500:
                        print('Second-half frames observed; halftime watcher finished.', flush=True)
                        return
                except ValueError:
                    pass
                continue
            if not active:
                time.sleep(0.3)
                continue
            if time.monotonic() < next_press:
                time.sleep(0.4)
                continue
            try:
                data = subprocess.check_output(['adb', '-s', DEVICE, 'exec-out', 'screencap', '-p'], timeout=8)
                image = Image.open(io.BytesIO(data))
                if halftime_next(image):
                    (OUT / f'halftime-before-{presses + 1}.png').write_bytes(data)
                    print('Confirmed 45:00 halftime screen and Next button; advancing.', flush=True)
                    subprocess.run(['adb', '-s', DEVICE, 'shell', 'input', 'swipe',
                                    '555', '339', '555', '339', '700'], check=True, timeout=8)
                    presses += 1
                    next_press = time.monotonic() + 10
                else:
                    time.sleep(1.5)
            except (OSError, ValueError, subprocess.SubprocessError) as error:
                print('Halftime screen check failed:', type(error).__name__, flush=True)
                time.sleep(2)
    print('Halftime watcher ended without a confirmed second-half frame.', flush=True)


if __name__ == '__main__':
    main()
