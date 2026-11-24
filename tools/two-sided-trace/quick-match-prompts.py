"""Advance the three recognized match prompts on the disposable lab phone.

Run only after entering the room. Every touch is gated by a fresh screenshot
whose button matches the corresponding captured template; no other screen is
acted upon. Screenshots remain on the lab host for later review.
"""
import base64
import io
import json
from pathlib import Path
import subprocess
import sys
import time
import zlib

from PIL import Image

DEVICE = '127.0.0.1:15556'
TEMPLATES = json.loads(Path('/tmp/peerlink-quickmatch-templates.json').read_text())
OUT = Path('/tmp/peerlink-quickmatch')
OUT.mkdir(mode=0o700, exist_ok=True)
BOX = (536, 325, 615, 354)


def mask(image):
    if image.size != (640, 360):
        return set()
    pixels = image.convert('RGB').crop(BOX).getdata()
    return {i for i, (r, g, b) in enumerate(pixels) if r > 200 and g > 200 and b > 200}


def score(actual, expected):
    return len(actual & expected) / max(1, len(actual | expected))


def snapshot():
    data = subprocess.check_output(['adb', '-s', DEVICE, 'exec-out', 'screencap', '-p'], timeout=8)
    return data, Image.open(io.BytesIO(data))


templates = {name: set(indices) for name, indices in TEMPLATES.items()}
stages = [('room_to_match', 'to_match'), ('preview_next', 'next'), ('squad_to_match', 'to_match')]
stage = 0
deadline = time.monotonic() + 110
while stage < len(stages) and time.monotonic() < deadline:
    data, image = snapshot()
    name, button = stages[stage]
    similarity = score(mask(image), templates[button])
    if similarity >= 0.80:
        (OUT / f'{stage + 1}-{name}-before.png').write_bytes(data)
        print(f'Confirmed {name} button (similarity {similarity:.2f}); tapping.', flush=True)
        subprocess.run(['adb', '-s', DEVICE, 'shell', 'input', 'swipe', '555', '339', '555', '339', '700'],
                       check=True, timeout=8)
        stage += 1
        time.sleep(2)
    else:
        time.sleep(0.8)

data, _ = snapshot()
(OUT / 'latest.png').write_bytes(data)
print(f'Match prompts completed: {stage}/{len(stages)}', flush=True)
sys.exit(0 if stage == len(stages) else 1)
