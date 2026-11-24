"""Compare same-frame goalkeeper checksum input details from both lab engines."""
import argparse,collections,json,math
from pathlib import Path

def records(path):
 result=collections.defaultdict(list)
 for line in Path(path).open():
  try:e=json.loads(line)
  except ValueError:continue
  if e.get('type')=='keeper_component' and e.get('part') in (2,3) and e.get('frame'):
   result[(e['frame'],e['part'])].append(e)
 return result

def differences(a,b):
 left,right=a.get('terms',[]),b.get('terms',[])
 changes=[]
 if len(left)!=len(right):changes.append(('term_count',len(left),len(right)))
 for i,(x,y) in enumerate(zip(left,right)):
  if (x.get('call'),x.get('kind'))!=(y.get('call'),y.get('kind')):
   changes.append((f'term_{i}_call',(x.get('call'),x.get('kind')),(y.get('call'),y.get('kind')))); continue
  if x.get('delta')!=y.get('delta') or x.get('value')!=y.get('value'):
   changes.append((f'term_{i}_call_{x.get("call",0):x}', {'value':x.get('value'),'delta':x.get('delta')}, {'value':y.get('value'),'delta':y.get('delta')}))
 return changes

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('game');p.add_argument('bot');p.add_argument('--frame',type=int); args=p.parse_args()
 game,bot=records(args.game),records(args.bot)
 keys=sorted(game.keys()&bot.keys())
 print('Shared frame/part keys:',len(keys))
 for key in keys:
  if args.frame is not None and abs(key[0]-args.frame)>70: continue
  gs,bs=game[key],bot[key]
  # Compare unique totals and term sequences; identical repeated calls are common.
  g,b=gs[-1],bs[-1]
  if g.get('total')!=b.get('total'):
   print('frame',key[0],'part',key[1],'game_total',g.get('total'),'bot_total',b.get('total'))
   print('game_position',g.get('position'),'bot_position',b.get('position'))
   for item in differences(g,b)[:30]:print(' ',*item)
   if args.frame is None:return
if __name__=='__main__':main()
