"""Compare verified checksum components from the lab game and PeerLink bot.

The full-game input is the observer's JSONL; the bot input is engine_events/*.log.
An unequal component locates a contributor, not necessarily its root cause.
"""
import argparse
import json
import re

line_pattern = re.compile(
    r'MATCH_CHECK_FRAME frame=(\d+) checkValue=(\d+) snapshotVerified=1 terms=(\d+) parts=(\[[^\]]*\])'
)


def component(index):
    if index == 0:
        return 'match'
    if index == 1:
        return 'ball'
    if index < 5:
        return f'object {index - 1}'
    n = index - 5
    return f'side {n // 33 + 1}, player slot {(n % 33) // 3 + 1}, component {n % 3 + 1}'


def bot_records(path):
    records = {}
    with open(path, encoding='utf-8', errors='replace') as lines:
        for line in lines:
            match = line_pattern.search(line)
            if not match:
                continue
            frame, value, terms = map(int, match.group(1, 2, 3))
            parts = json.loads(match.group(4))
            if len(parts) == 71:
                records.setdefault(frame, []).append((value, terms, parts))
    return records


def game_records(path):
    records = {}
    with open(path, encoding='utf-8') as lines:
        for line in lines:
            event = json.loads(line)
            snapshot = event.get('snapshot', {})
            if event.get('type') != 'match_check' or not snapshot.get('verified'):
                continue
            parts = snapshot.get('parts', [])
            if len(parts) == 71:
                records.setdefault(event['frame'], []).append((event['value'], snapshot['terms'], parts))
    return records


def unique(records):
    clean = {}
    ambiguous = 0
    for frame, values in records.items():
        first = values[0]
        if all(value == first for value in values):
            clean[frame] = first
        else:
            ambiguous += 1
    return clean, ambiguous


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bot_log')
    parser.add_argument('game_jsonl')
    args = parser.parse_args()
    bot, bot_ambiguous = unique(bot_records(args.bot_log))
    game, game_ambiguous = unique(game_records(args.game_jsonl))
    common = sorted(bot.keys() & game.keys())
    print(f'Verified shared frames: {len(common)}; ambiguous frames omitted: bot={bot_ambiguous}, game={game_ambiguous}')
    for frame in common:
        bot_value, bot_terms, bot_parts = bot[frame]
        game_value, game_terms, game_parts = game[frame]
        differences = [i for i, pair in enumerate(zip(bot_parts, game_parts)) if pair[0] != pair[1]]
        if bot_value != game_value or bot_terms != game_terms or differences:
            print(f'First recorded difference at frame {frame}: bot checksum={bot_value}, game checksum={game_value}, terms={bot_terms}/{game_terms}')
            for i in differences:
                print(f'  {component(i)}: bot={bot_parts[i]}, game={game_parts[i]}')
            if not differences:
                print('  No individual part difference recorded; inspect term counts and raw capture.')
            return
    print('No difference found in the verified shared frames.' if common else 'No verified shared frames available.')


if __name__ == '__main__':
    main()
