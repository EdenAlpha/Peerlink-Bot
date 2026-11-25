"""Check JSONL and legacy bot records against the same verified game data."""
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent
parts_game = [0] * 71
parts_bot = parts_game.copy()
parts_bot[6] = 9


def event(parts, value):
    return {'type': 'match_check', 'frame': 27, 'value': value,
            'snapshot': {'verified': True, 'terms': 3928, 'parts': parts}}


with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    game, bot_json, bot_log = root / 'game.jsonl', root / 'bot.jsonl', root / 'bot.log'
    game.write_text(json.dumps(event(parts_game, 100)) + '\n')
    bot_json.write_text(json.dumps(event(parts_bot, 109)) + '\n')
    bot_log.write_text('MATCH_CHECK_FRAME frame=27 checkValue=109 snapshotVerified=1 '
                       f'terms=3928 parts={json.dumps(parts_bot)}\n')
    command = ['python3', str(ROOT / 'compare-match-checks.py')]
    legacy = subprocess.check_output(command + [str(bot_log), str(game)], text=True)
    observed = subprocess.check_output(command + [str(bot_json), str(game), '--bot-jsonl'], text=True)
    assert legacy == observed
    assert 'First recorded difference at frame 27' in observed
    assert 'side 1, player slot 1, component 2: bot=9, game=0' in observed

print('Checksum comparison fixture passed for native journal and independent bot observer.')
