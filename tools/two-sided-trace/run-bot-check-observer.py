"""Continuously save the bot's checks independently of its capped journal."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import threading
from uuid import uuid4

import frida

ROOT = Path('/tmp/peerlink-bot-check-observer')
ADB_DEVICE = '127.0.0.1:15556'
FRIDA_DEVICE = '127.0.0.1:27042'
PACKAGE = 'com.peerlink.app:botengine'
CAPTURE_ID = uuid4().hex
STOP = threading.Event()


def process_id():
    try:
        result = subprocess.run(['adb', '-s', ADB_DEVICE, 'shell', 'pidof', '-s', PACKAGE],
                                check=True, capture_output=True, text=True, timeout=5)
        return int(result.stdout.strip())
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


def main():
    os.umask(0o077)
    ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
    source = (ROOT / 'fullgame-observer.js').read_text()
    with (ROOT / 'bot-checks.jsonl').open('a', buffering=1) as output:
        lock = threading.Lock()

        def record(event):
            if not isinstance(event, dict):
                event = {'type': 'invalid_event'}
            event['captureId'] = CAPTURE_ID
            event['recordedAt'] = datetime.now(timezone.utc).isoformat()
            with lock:
                output.write(json.dumps(event, separators=(',', ':'), allow_nan=False) + '\n')
                output.flush()

        last_pid = None
        while not STOP.is_set():
            pid = process_id()
            if pid is None:
                if last_pid is not None:
                    record({'type': 'process_wait'})
                    last_pid = None
                STOP.wait(3)
                continue
            if pid != last_pid:
                record({'type': 'process_found', 'pid': pid})
                last_pid = pid

            detached = threading.Event()
            ready = threading.Event()
            session = None

            def on_message(message, data):
                if message.get('type') == 'send':
                    event = message.get('payload')
                    if isinstance(event, dict) and event.get('type') == 'observer_ready':
                        ready.set()
                else:
                    event = {'type': 'script_error', 'description': message.get('description', '')}
                record(event)

            def on_detached(reason, crash):
                record({'type': 'detached', 'reason': str(reason)})
                detached.set()

            try:
                device = frida.get_device_manager().add_remote_device(FRIDA_DEVICE)
                session = device.attach(pid)
                session.on('detached', on_detached)
                script = session.create_script(source)
                script.on('message', on_message)
                script.load()
                if ready.wait(5):
                    print(f'Bot checksum recorder attached to process {pid}', flush=True)
                    while not STOP.is_set() and not detached.wait(2):
                        pass
                else:
                    record({'type': 'observer_not_ready', 'pid': pid})
            except Exception as error:
                record({'type': 'attach_error', 'class': type(error).__name__})
            finally:
                if session is not None:
                    try:
                        session.detach()
                    except Exception:
                        pass
            STOP.wait(3)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        STOP.set()
