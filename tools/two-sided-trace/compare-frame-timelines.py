"""Compare two recorded match timelines without mixing repeated frames."""

import argparse
from collections import defaultdict
import json


def read_runs(path):
    runs = defaultdict(lambda: defaultdict(list))
    with open(path, encoding='utf-8') as lines:
        for line in lines:
            event = json.loads(line)
            if event.get('type') != 'frame_state':
                continue
            capture, frame = event.get('captureId'), event.get('frame')
            if capture is None or not isinstance(frame, int) or frame <= 0:
                continue
            runs[capture][frame].append(event)
    return runs


def compare(game, bot):
    game_unique = {frame: rows[0] for frame, rows in game.items() if len(rows) == 1}
    bot_unique = {frame: rows[0] for frame, rows in bot.items() if len(rows) == 1}
    ambiguous = {frame for frame, rows in game.items() if len(rows) != 1}
    ambiguous |= {frame for frame, rows in bot.items() if len(rows) != 1}
    shared = sorted((game_unique.keys() & bot_unique.keys()) - ambiguous)
    game_only = sorted(game_unique.keys() - bot.keys())
    bot_only = sorted(bot_unique.keys() - game.keys())
    print(f'Unique paired frames: {len(shared)}; game-only: {len(game_only)}; '
          f'bot-only: {len(bot_only)}; repeated/ambiguous: {len(ambiguous)}')
    if ambiguous:
        print('First repeated/ambiguous frame:', min(ambiguous))
    for label, records in (('game', game_unique), ('bot', bot_unique)):
        gaps = sum(record.get('skippedFrames') or 0 for record in records.values())
        rewinds = sum(bool(record.get('rewound')) for record in records.values())
        print(f'{label} reported skipped frames: {gaps}; rewinds: {rewinds}')

    first_positions = first_ball = None
    position_samples = ball_samples = ball_differences = 0
    for frame in shared:
        left, right = game_unique[frame], bot_unique[frame]
        positions_l, positions_r = left.get('positions'), right.get('positions')
        if (isinstance(positions_l, list) and isinstance(positions_r, list)
                and len(positions_l) == len(positions_r) == 22):
            position_samples += 1
            changed = [index for index, (a, b) in enumerate(zip(positions_l, positions_r)) if a != b]
            if changed and first_positions is None:
                first_positions = frame, changed
        if left.get('ballBits') is not None and right.get('ballBits') is not None:
            ball_samples += 1
            if left['ballBits'] != right['ballBits']:
                ball_differences += 1
                if first_ball is None:
                    first_ball = frame
    if first_positions:
        frame, changed = first_positions
        print(f'First player-state difference: frame {frame}; zero-based player indices: {changed}')
    elif position_samples:
        print('No player-state difference in comparable records')
    else:
        print('No comparable player-state records')
    if first_ball is not None:
        print(f'First raw ball observation difference: frame {first_ball} '
              f'({ball_differences} of {ball_samples} comparable frames); '
              'observation timing can differ')
    elif ball_samples:
        print('No ball-state difference in comparable records')
    else:
        print('No comparable ball-state records')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('game_jsonl')
    parser.add_argument('bot_jsonl')
    parser.add_argument('--capture')
    args = parser.parse_args()
    game, bot = read_runs(args.game_jsonl), read_runs(args.bot_jsonl)
    common = sorted(game.keys() & bot.keys())
    capture = args.capture or (common[0] if len(common) == 1 else None)
    if capture not in common:
        parser.exit(1, f'Choose one shared capture with --capture. Available pairs: {common}\n')
    print(f'Capture: {capture}')
    compare(game[capture], bot[capture])


if __name__ == '__main__':
    main()
