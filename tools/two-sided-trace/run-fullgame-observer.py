"""Continuously store only the lab game's numeric match-state diagnostics."""
import json
from pathlib import Path
import subprocess
import threading
from datetime import datetime, timezone

import frida

device = frida.get_device_manager().add_remote_device('127.0.0.1:27042')
pid = int(subprocess.check_output([
    'adb', '-s', '127.0.0.1:15556', 'shell', 'pidof', '-s', 'jp.konami.pesam'
], text=True).strip())
if not any(p.pid == pid for p in device.enumerate_processes()):
    raise SystemExit('The full-game process is not visible to the observer')
session = device.attach(pid)
script = session.create_script(Path('/tmp/peerlink-observer/fullgame-observer.js').read_text())
out = Path('/tmp/peerlink-observer/fullgame-state.jsonl').open('a', buffering=1)
done = threading.Event()

def detached(reason, crash):
    out.write(json.dumps({'type': 'detached', 'reason': str(reason)}) + '\n')
    done.set()

def message(msg, data):
    event = msg.get('payload') if msg.get('type') == 'send' else {'type': 'script_error', 'description': msg.get('description', '')}
    event['recordedAt'] = datetime.now(timezone.utc).isoformat()
    out.write(json.dumps(event, separators=(',', ':')) + '\n')
    out.flush()

session.on('detached', detached)
script.on('message', message)
script.load()
done.wait()
out.close()
