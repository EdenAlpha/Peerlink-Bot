"""Check that real timeline differences and repeated frames are reported."""
import json
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parent


def record(frame, keeper, *, skipped=0, capture='fixture'):
    return {'type': 'frame_state', 'captureId': capture, 'frame': frame,
            'skippedFrames': skipped, 'rewound': False,
            'positions': [[keeper if i == 0 else i, 0, 0, 1] for i in range(22)],
            'ballBits': [1, 2, 3, 0, 0, 0]}


with tempfile.TemporaryDirectory() as tmp:
    game, bot = Path(tmp) / 'game.jsonl', Path(tmp) / 'bot.jsonl'
    game_rows = [record(1, 0), record(2, 0), record(3, 0), record(4, 0)]
    bot_rows = [record(1, 0), record(2, 0), record(3, 0), record(4, 99)]

    def run():
        for path, records in ((game, game_rows), (bot, bot_rows)):
            path.write_text(''.join(json.dumps(row) + '\n' for row in records))
        return subprocess.check_output(['python3', str(ROOT / 'compare-frame-timelines.py'),
                                        str(game), str(bot)], text=True)

    output = run()
    assert 'Unique paired frames: 4; game-only: 0; bot-only: 0; repeated/ambiguous: 0' in output
    assert 'First player-state difference: frame 4; zero-based player indices: [0]' in output
    assert 'No ball-state difference in comparable records' in output

    game_rows.append(record(4, 0))  # A repeated frame must never be treated as the same match.
    bot_rows.append(record(6, 0, skipped=1))
    output = run()
    assert 'Unique paired frames: 3; game-only: 0; bot-only: 1; repeated/ambiguous: 1' in output
    assert 'First repeated/ambiguous frame: 4' in output
    assert 'bot reported skipped frames: 1' in output
    assert 'No player-state difference in comparable records' in output

    game_rows = [record(1, 0), record(2, 0)]
    bot_rows = [record(1, 0), record(2, 0)]
    bot_rows[1]['ballBits'] = [9, 2, 3, 0, 0, 0]
    output = run()
    assert 'First raw ball observation difference: frame 2 (1 of 2 comparable frames)' in output
    assert 'observation timing can differ' in output

print('Frame comparison fixtures passed: first split, repeated frame, missing frame and gap.')
