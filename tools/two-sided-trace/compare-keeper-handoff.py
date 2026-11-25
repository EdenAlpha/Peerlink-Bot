"""Compare observed movement decisions from one paired lab match."""

import argparse
from collections import defaultdict
import json


STAGES = (
    ('keeper_scene_prepare', 'Scene preparation', ('rolesLoaded', 'loadedRoles', 'availableRoles', 'before', 'after')),
    ('keeper_scene_play', 'Assigned movement', ('preparedAt', 'assignments')),
    ('keeper_state_request', 'Keeper state setter', ('player', 'before', 'requested', 'after', 'callSite')),
    ('keeper_evaluate', 'Motion controller decision', ('player', 'active', 'updateMode', 'gate')),
    ('keeper_evaluate', 'Motion controller raw words', ('controllerWordsBefore', 'controllerWordsAfter')),
    ('keeper_evaluate', 'Motion controller position', ('sourceBefore', 'sourceAfter')),
    ('keeper_position', 'Position copied into the match', ('player', 'positionBefore', 'sourceBefore', 'positionAfter')),
)


def read_runs(path, scene_id):
    runs = defaultdict(lambda: defaultdict(list))
    observed_types = {kind for kind, _, _ in STAGES}
    with open(path, encoding='utf-8') as lines:
        for line in lines:
            event = json.loads(line)
            if (scene_id is not None and event.get('sceneId') != scene_id) or event.get('type') not in observed_types:
                continue
            capture = event.pop('captureId', None)
            if capture is None:
                continue
            event.pop('recordedAt', None)
            key = event['type'], event['frame'], event.get('side', -1)
            runs[capture][key].append(event)
    return runs


def project(records, kind, fields):
    comparable = {}
    ambiguous = 0
    for (event_type, frame, side), entries in records.items():
        if event_type != kind:
            continue
        values = [{field: entry.get(field) for field in fields} for entry in entries]
        if any(value != values[0] for value in values[1:]):
            ambiguous += 1
            continue
        comparable[frame, side] = values[0]
    return comparable, ambiguous


def compare(game, bot):
    for kind, label, fields in STAGES:
        stock, stock_ambiguous = project(game, kind, fields)
        peer, peer_ambiguous = project(bot, kind, fields)
        shared = sorted(stock.keys() & peer.keys())
        print(f'{label} — {len(shared)} paired, {len(stock) - len(shared)} game-only, '
              f'{len(peer) - len(shared)} bot-only, {stock_ambiguous + peer_ambiguous} ambiguous')
        if 'gate' in fields:
            print(f'  Gate decision reached in {sum(stock[k]["gate"] is not None for k in shared)} game rows '
                  f'and {sum(peer[k]["gate"] is not None for k in shared)} bot rows')
        difference = next((key for key in shared if stock[key] != peer[key]), None)
        if difference is None:
            if shared:
                print('  Paired readings agree')
            continue
        frame, side = difference
        team = f' for side {side}' if side >= 0 else ''
        print(f'  First difference at frame {frame}{team}')
        for field in fields:
            left, right = stock[difference][field], peer[difference][field]
            if left != right:
                print(f'    {field} — game {json.dumps(left)}, bot {json.dumps(right)}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('game_jsonl')
    parser.add_argument('bot_jsonl')
    parser.add_argument('--scene', default='197064', help="Scene number, or 'all' to include non-scene frames")
    parser.add_argument('--capture')
    args = parser.parse_args()
    try:
        scene = None if args.scene == 'all' else int(args.scene)
    except ValueError:
        parser.error('--scene must be a scene number or all')
    game = read_runs(args.game_jsonl, scene)
    bot = read_runs(args.bot_jsonl, scene)
    common = sorted(game.keys() & bot.keys())
    if args.capture is None and len(common) == 1:
        args.capture = common[0]
    if args.capture not in common:
        parser.exit(1, f'Scene {args.scene} needs one shared capture. Available pairs {common}\n')
    print(f'Scene {args.scene}, capture {args.capture}')
    compare(game[args.capture], bot[args.capture])


if __name__ == '__main__':
    main()
