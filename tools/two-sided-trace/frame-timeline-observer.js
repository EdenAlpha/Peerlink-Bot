'use strict';

// Read-only, 11.1.1 ARM64 lab probe. It records a player-position and ball
// snapshot at the same player-update boundary on every observed command frame.
// A missing anchor update or a skipped/rewound frame is reported, not filled in.
const lib = Process.findModuleByName('libUE4.so');
if (lib === null || Process.arch !== 'arm64') throw new Error('11.1.1 ARM64 engine unavailable');
const base = lib.base;
const at = offset => base.add(offset);
if (at(0x725b0d0).readU32() !== 0xa9be7bfd) throw new Error('Unexpected player update entry');

let lastFrame = null;
let sequence = 0;
function commandFrame() {
  const service = at(0x9a6a020).readPointer();
  if (service.isNull() || !service.readPointer().equals(at(0x98ae9e0))) return 0;
  const inner = service.add(8).readPointer();
  if (inner.isNull()) return 0;
  const controller = inner.add(0xa0).readPointer();
  if (controller.isNull() || !controller.readPointer().equals(at(0x98aedd0))) return 0;
  return controller.add(0x30).readU32();
}
function ballBits() {
  const match = at(0xa533ec0).readPointer();
  if (match.isNull() || match.add(0x138).readU8() !== 17) return null;
  const core = match.add(0x2f8).readPointer();
  if (core.isNull() || !core.readPointer().equals(at(0x9899d50)) || core.add(8).readU32() !== 54) return null;
  const balls = core.add(0x3c98).readPointer();
  if (balls.isNull() || !balls.readPointer().equals(at(0x989a160))) return null;
  const ball = balls.add(0x30).readPointer(), index = balls.add(0xc).readU32();
  if (ball.isNull() || index >= 8 || !balls.add(0x38 + index * 8).readPointer().equals(ball)) return null;
  return Array.from({length: 6}, (_, i) => ball.add(0x30 + i * 4).readU32());
}
Interceptor.attach(at(0x725b0d0), {
  onEnter(args) {
    try {
      const anchor = at(0xa5909a0 + 0x170).readPointer();
      if (anchor.isNull() || !args[0].equals(anchor)) return;
      const frame = commandFrame();
      if (!frame || frame === lastFrame) return;
      const started = Date.now();
      const positions = [];
      for (let i = 0; i < 22; i++) {
        const player = at(0xa5909a0 + 0x170 + i * 8).readPointer();
        positions.push(player.isNull() ? null : [
          player.add(0x5dc).readU32(), player.add(0x5e0).readU32(),
          player.add(0x5e4).readU32(), player.add(0x798).readU8(),
        ]);
      }
      const previousFrame = lastFrame;
      lastFrame = frame;
      send({type: 'frame_state', sequence: ++sequence, frame, previousFrame,
        skippedFrames: previousFrame !== null && frame > previousFrame ? frame - previousFrame - 1 : null,
        rewound: previousFrame !== null && frame < previousFrame,
        positions, ballBits: ballBits(), captureMs: Date.now() - started});
    } catch (error) {
      send({type: 'frame_observer_error', error: String(error)});
    }
  },
});
send({type: 'frame_timeline_ready', boundary: 'player-zero-update', engine: '11.1.1'});
