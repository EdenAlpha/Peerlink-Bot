"""Read only aggregate UE mesh tick options from the two lab processes."""
import json,subprocess,threading
from pathlib import Path
import frida
D=frida.get_device_manager().add_remote_device('127.0.0.1:27042')
source=Path('/tmp/peerlink-mesh-options-probe.js').read_text()
for label,package in [('game','jp.konami.pesam'),('bot','com.peerlink.app:botengine')]:
 try:
  pid=int(subprocess.check_output(['adb','-s','127.0.0.1:15556','shell','pidof','-s',package],text=True).strip())
  session=D.attach(pid);script=session.create_script(source);done=threading.Event();values=[]
  def on_message(msg,data):
   if msg.get('type')=='send':values.append(msg['payload'])
   else:values.append({'type':'error','description':msg.get('description','')})
   done.set()
  script.on('message',on_message);script.load();done.wait(45);session.detach()
  print(json.dumps({'side':label,'samples':values or [{'type':'timeout'}]},separators=(',',':')),flush=True)
 except Exception as e:print(json.dumps({'side':label,'error':str(e)}),flush=True)
