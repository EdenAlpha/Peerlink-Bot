"""Keep a narrowly scoped, lab-only initial-diagnostic guard attached."""
import json,subprocess,threading
from pathlib import Path
import frida
D=frida.get_device_manager().add_remote_device('127.0.0.1:27042')
pid=int(subprocess.check_output(['adb','-s','127.0.0.1:15556','shell','pidof','-s','com.peerlink.app:botengine'],text=True).strip())
session=D.attach(pid);script=session.create_script(Path('/tmp/peerlink-lab-initial-guard.js').read_text())
ready=threading.Event();done=threading.Event()
def on_message(msg,data):
 event=msg.get('payload') if msg.get('type')=='send' else {'type':'script_error','description':msg.get('description','')}
 print(json.dumps(event,separators=(',',':')),flush=True)
 if event.get('type')=='lab_guard_ready':ready.set()
 if event.get('type')=='script_error':done.set()
def on_detached(reason,crash):
 print(json.dumps({'type':'detached','reason':str(reason)}),flush=True);done.set()
session.on('detached',on_detached);script.on('message',on_message);script.load()
if not ready.wait(5):raise SystemExit('Lab guard did not attach')
done.wait(1800)
try:session.detach()
except Exception:pass
