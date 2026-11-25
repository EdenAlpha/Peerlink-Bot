"""Continuously save both engines' frame timeline on the disposable lab phone."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import threading
import time
from uuid import uuid4
import frida

ROOT = Path('/tmp/peerlink-frame-timeline')
SOURCE = ROOT / 'frame-timeline-observer.js'
CAPTURE_ID = uuid4().hex
STOP = threading.Event()


def pid_of(package):
    try:
        result = subprocess.run(['adb', '-s', '127.0.0.1:15556', 'shell', 'pidof', '-s', package],
                                check=True, capture_output=True, text=True, timeout=5)
        return int(result.stdout.strip())
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


def observe(label, package, source):
    with (ROOT / (label + '.jsonl')).open('a', buffering=1) as output:
        lock = threading.Lock()

        def record(event):
            event = event if isinstance(event, dict) else {'type': 'invalid_event'}
            event['captureId'] = CAPTURE_ID
            event['recordedAt'] = datetime.now(timezone.utc).isoformat()
            event['monotonicNs'] = time.monotonic_ns()
            with lock:
                output.write(json.dumps(event, separators=(',', ':'), allow_nan=False) + '\n')
                output.flush()

        previous_pid = None
        while not STOP.is_set():
            pid = pid_of(package)
            if pid is None:
                if previous_pid is not None:
                    record({'type': 'process_wait'})
                    previous_pid = None
                STOP.wait(2)
                continue
            if pid != previous_pid:
                record({'type': 'process_found', 'pid': pid})
                previous_pid = pid
            session = None
            attached, detached = threading.Event(), threading.Event()

            def on_message(message, data):
                event = message.get('payload') if message.get('type') == 'send' else {
                    'type': 'script_error', 'description': message.get('description', '')}
                if isinstance(event, dict) and event.get('type') == 'frame_timeline_ready':
                    attached.set()
                record(event)

            def on_detached(reason, crash):
                record({'type': 'detached', 'reason': str(reason)})
                detached.set()

            try:
                device = frida.get_device_manager().add_remote_device('127.0.0.1:27042')
                session = device.attach(pid)
                session.on('detached', on_detached)
                script = session.create_script(source)
                script.on('message', on_message)
                script.load()
                if attached.wait(5):
                    print(f'{label} frame timeline attached to {pid}', flush=True)
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
            STOP.wait(2)


def main():
    os.umask(0o077)
    ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
    source = SOURCE.read_text()
    print('Frame capture ' + CAPTURE_ID, flush=True)
    threads = [threading.Thread(target=observe, args=(label, package, source), daemon=True)
               for label, package in [('game', 'jp.konami.pesam'), ('bot', 'com.peerlink.app:botengine')]]
    for thread in threads:
        thread.start()
    try:
        for thread in threads:
            thread.join()
    except KeyboardInterrupt:
        STOP.set()
        for thread in threads:
            thread.join(timeout=10)


if __name__ == '__main__':
    main()
