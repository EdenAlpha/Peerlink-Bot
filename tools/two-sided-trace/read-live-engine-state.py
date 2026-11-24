"""Read three scalar/scene samples from each lab process; do not alter game state."""
import json
from pathlib import Path
import subprocess
import threading
import frida

device = frida.get_device_manager().add_remote_device('127.0.0.1:27042')
source = Path('/tmp/peerlink-read-live-engine-state.js').read_text()
for label, package in [('full_game','jp.konami.pesam'),('bot','com.peerlink.app:botengine')]:
    try:
        pid=int(subprocess.check_output(['adb','-s','127.0.0.1:15556','shell','pidof','-s',package],text=True).strip())
        session=device.attach(pid); script=session.create_script(source)
        samples=[]; done=threading.Event()
        def on_message(message, data):
            if message.get('type')=='send': samples.append(message['payload'])
            else: samples.append({'error':message.get('description','script error')})
            if len(samples)>=3 or message.get('type')!='send': done.set()
        script.on('message',on_message); script.load(); done.wait(8); session.detach()
        print(json.dumps({'side':label,'samples':samples},separators=(',',':')),flush=True)
    except Exception as exc:
        print(json.dumps({'side':label,'error':str(exc)},separators=(',',':')),flush=True)
