"""Attach read-only checksum-detail observers to the two lab engines."""
import datetime
import json
from pathlib import Path
import subprocess
import threading
import time
import frida

root=Path('/tmp/peerlink-scene-target')
root.mkdir(mode=0o700,parents=True,exist_ok=True)
source=(root/'scene-target-observer.js').read_text()
device=frida.get_device_manager().add_remote_device('127.0.0.1:27042')
sessions=[]
outputs=[]
ready=[]
done=threading.Event()
for label, package in [('game','jp.konami.pesam'),('bot','com.peerlink.app:botengine')]:
    pid=int(subprocess.check_output(['adb','-s','127.0.0.1:15556','shell','pidof','-s',package],text=True).strip())
    out=(root/(label+'.jsonl')).open('a',buffering=1)
    session=device.attach(pid)
    script=session.create_script(source)
    def message_handler(label, out):
        def on_message(message, data):
            event=message.get('payload') if message.get('type')=='send' else {'type':'script_error','description':message.get('description','')}
            if not isinstance(event,dict): event={'type':'invalid_event'}
            event['recordedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
            out.write(json.dumps(event,separators=(',',':'),allow_nan=False)+'\n')
            out.flush()
            if event.get('type')=='scene_target_observer_ready': ready.append(label)
        return on_message
    def detached_handler(out):
        def on_detached(reason, crash):
            out.write(json.dumps({'type':'detached','reason':str(reason)})+'\n')
            out.flush()
        return on_detached
    session.on('detached',detached_handler(out))
    script.on('message',message_handler(label,out))
    script.load()
    sessions.append((session,script))
    outputs.append(out)
    print(label+' observer attached',flush=True)
deadline=time.monotonic()+5
while sorted(ready)!=['bot','game'] and time.monotonic()<deadline:
    time.sleep(0.05)
if sorted(ready)!=['bot','game']:
    raise SystemExit('Both keeper observers did not report ready')
print('Both keeper observers ready',flush=True)
done.wait(1800)
for session,_ in sessions:
    try: session.detach()
    except Exception: pass
for out in outputs: out.close()
