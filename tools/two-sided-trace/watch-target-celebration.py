"""Privately save both lab traces if the target scene occurs."""
import json,subprocess,time,zipfile
from pathlib import Path
source=Path('/tmp/peerlink-observer/fullgame-state.jsonl')
deadline=time.monotonic()+600
with source.open() as f:
 while time.monotonic()<deadline:
  line=f.readline()
  if not line:time.sleep(.2);continue
  try:event=json.loads(line)
  except ValueError:continue
  if event.get('type')!='scene_start' or event.get('before',{}).get('sceneId')!=197064:continue
  print('Target scene recorded at frame',event['before'].get('commandFrame'),flush=True)
  time.sleep(4)
  command='f=$(ls -t /data/user/0/com.peerlink.app/files/engine-events/*.log 2>/dev/null | head -1); test -n "$f" && cat "$f"'
  botlog=subprocess.check_output(['sudo','docker','exec','redroid-b','sh','-c',command])
  files={'fullgame-state.jsonl':source,'keeper-game.jsonl':Path('/tmp/peerlink-keeper/game.jsonl'),
   'keeper-bot.jsonl':Path('/tmp/peerlink-keeper/bot.jsonl'),
   'scene-game.jsonl':Path('/tmp/peerlink-scene-target/game.jsonl'),
   'scene-bot.jsonl':Path('/tmp/peerlink-scene-target/bot.jsonl'),
   'mesh-game.jsonl':Path('/tmp/peerlink-player-mesh/game.jsonl'),
   'mesh-bot.jsonl':Path('/tmp/peerlink-player-mesh/bot.jsonl')}
  with zipfile.ZipFile('/tmp/peerlink-target-auto.zip','w',compression=zipfile.ZIP_DEFLATED) as z:
   z.writestr('bot-engine.log',botlog)
   for name,path in files.items():
    if path.is_file():z.write(path,name)
  print('Target snapshot saved',flush=True);break
 else:print('No target scene within watcher window',flush=True)
