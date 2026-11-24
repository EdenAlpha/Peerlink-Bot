"""Smoke-test lab controls using a fake ADB and saved match screenshots."""
import datetime
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import time
from PIL import Image

HERE = Path(__file__).resolve().parent


def bits(x):
    return struct.unpack('<I', struct.pack('<f', x))[0]


def event(frame, verified=True, owner=16):
    positions = [[bits(0.0), bits(0.0), bits(10.0)] for _ in range(22)]
    positions[0] = [bits(-49.0), bits(0.0), bits(0.0)]
    positions[11] = [bits(49.0), bits(0.0), bits(0.0)]
    positions[16] = [bits(-35.0), bits(0.0), bits(0.0)]
    return {'type': 'match_check', 'frame': frame,
            'recordedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'snapshot': {'verified': verified, 'playerPositionBits': positions,
                         'control': {'side': 1, 'selected': 16, 'owner': owner,
                                     'ownKeeper': 11, 'opposingKeeper': 0,
                                     'position': [-35.0, 0.0, 0.0], 'velocity': [0.0, 0.0, 0.0]}}}


with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    trace = root / 'trace.jsonl'
    trace.write_text('')
    def button_mask(name):
        image = Image.open(HERE / name).convert('RGB').crop((462, 291, 530, 327))
        return [i for i, (r, g, b) in enumerate(image.getdata()) if r > 210 and g > 210 and b > 210]
    templates = root / 'templates.json'
    templates.write_text(json.dumps({'attack': button_mask('left-dash.png'), 'defense': button_mask('third-playing.png')}))
    adb = root / 'adb'
    adb.write_text('''#!/usr/bin/env python3
import os,sys
if 'exec-out' in sys.argv:
    sys.stdout.buffer.write(open(os.environ['TEST_SCREEN'],'rb').read())
else:
    for line in sys.stdin:
        with open(os.environ['TEST_ACTIONS'],'a') as out: out.write(line)
        print('Two-finger input injected',flush=True)
''')
    adb.chmod(0o755)
    env = os.environ.copy()
    env.update(PATH=f'{root}:{env["PATH"]}', PEERLINK_LAB_TRACE=str(trace),
               PEERLINK_LAB_SCREENS=str(root / 'screens'), PEERLINK_LAB_TOUCH_LOG=str(root / 'touch.log'),
               PEERLINK_LAB_BUTTON_TEMPLATES=str(templates),
               TEST_SCREEN=str(HERE / 'left-dash.png'), TEST_ACTIONS=str(root / 'actions.txt'))
    output = (root / 'output.txt').open('w+')
    process = subprocess.Popen(['python3', str(HERE / 'play-lab-game.py')], env=env, stdout=output, stderr=output)
    try:
        time.sleep(1)
        with trace.open('a') as f:
            f.write(json.dumps(event(27, False)) + '\n'); f.flush()
            f.write(json.dumps(event(54, owner=0)) + '\n'); f.flush()
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and not (root / 'actions.txt').exists():
            time.sleep(0.1)
        actions = (root / 'actions.txt').read_text().splitlines()
        assert len(actions) == 1 and actions[0].split()[4:6] == ['590', '210'], actions
        with trace.open('a') as f:
            f.write(json.dumps({'type': 'scene_start', 'before': {'sceneId': 0x301c8}}) + '\n')
            f.write(json.dumps(event(81)) + '\n'); f.flush()
        time.sleep(1.5)
        assert len((root / 'actions.txt').read_text().splitlines()) == 1
        assert (root / 'screens' / '54.png').exists()
        print('Lab controller smoke test passed: stale-owner correction, shot, celebration pause.')
    finally:
        process.terminate()
        process.wait(timeout=5)
        output.close()
