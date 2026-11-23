// Load on the ARM64 Android lab with `frida -U -n jp.konami.pesam -l tests/trace_stock_match_end.js`.
// It also works on the lab's :botengine process. Start recording before room entry.
// Keep both streams alongside the APK's bot journal. Compare match_checksum
// records by frame, and use timeMs to place the local events on the device.
'use strict';

const expectedBuildId = '9068c18632a8023ad3e9c6faf3fe92d9';
const library = 'libUE4.so';
const listeners = [];

function record(event, details = {}) {
    console.log(JSON.stringify({ timeMs: Date.now(), pid: Process.id, event, ...details }));
}

function readBuildId(base) {
    const bytes = new Uint8Array(base.add(0x318).readByteArray(16));
    return Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
}

// Android's 64-bit libc++ string with the length and representation in byte 0.
// The two fields observed here are short server status codes.
function readStatus(field) {
    const header = field.readU8();
    const long = (header & 1) !== 0;
    const size = long ? field.add(8).readU64().toNumber() : header >>> 1;
    if (size === 0) return null;
    if (size > 40) return 'UNRECOGNIZED';
    const data = long ? field.add(16).readPointer() : field.add(1);
    const value = data.readUtf8String(size);
    return /^[A-Z0-9_]+$/.test(value) ? value : 'UNRECOGNIZED';
}

function observe(module) {
    const { base } = module;
    const calculations = new Map();
    function attach(offset, callbacks) {
        listeners.push(Interceptor.attach(base.add(offset), callbacks));
    }
    function callerInEngine(address) {
        if (address.compare(base) < 0 || address.compare(base.add(module.size)) >= 0) return null;
        return address.sub(base).toString();
    }

    function commandFrame() {
        const service = base.add(0x9a6a020).readPointer();
        if (service.isNull() || !service.readPointer().equals(base.add(0x98ae9e0))) return null;
        const inner = service.add(8).readPointer();
        if (inner.isNull()) return null;
        const controller = inner.add(0xa0).readPointer();
        if (controller.isNull() || !controller.readPointer().equals(base.add(0x98aedd0))) return null;
        return controller.add(0x30).readU32();
    }

    function readBallBits(ballState) {
        return Array.from({ length: 6 }, (_, index) => ballState.add(index * 4).readU32());
    }

    // Capture the native checksum at creation, before the later comparison or stop.
    attach(0x7053d8c, {
        onEnter(args) {
            this.frameOut = args[2];
            calculations.set(this.threadId, {
                runningSum: 0, parts: { match: null, ball: null, objects: [], players: [] },
                terms: { match: null, ball: null, objects: [], players: [] },
                runningTerms: 0,
                ballBits: null,
            });
        },
        onLeave(result) {
            const calculation = calculations.get(this.threadId);
            calculations.delete(this.threadId);
            const frame = this.frameOut.readU32();
            if (frame === 0 || !calculation) return;
            const { parts, terms, ballBits } = calculation;
            record('match_checksum', { frame, checksum: result.toInt32() & 0xffff, parts, terms, ballBits });
        },
    });

    // Each function adds to the same native sum. The last of the three player
    // functions gives a boundary for one player's combined contribution.
    for (const [offset, category] of [
        [0x6b00048, 'match'], [0x6b00474, 'ball'], [0x6b0050c, 'objects'], [0x6b00908, 'players'],
    ]) {
        attach(offset, {
            onEnter(args) {
                const calculation = calculations.get(this.threadId);
                if (calculation) {
                    this.sum = args[1];
                    this.termCount = args[2];
                    this.calculation = calculation;
                }
            },
            onLeave() {
                if (!this.calculation) return;
                const sum = this.sum.readU32();
                const termCount = this.termCount.readU32();
                const contribution = (sum - this.calculation.runningSum) >>> 0;
                const count = (termCount - this.calculation.runningTerms) >>> 0;
                const { parts, terms } = this.calculation;
                if (Array.isArray(parts[category])) {
                    parts[category].push(contribution);
                    terms[category].push(count);
                } else {
                    parts[category] = contribution;
                    terms[category] = count;
                }
                this.calculation.runningSum = sum;
                this.calculation.runningTerms = termCount;
            },
        });
    }
    // The original checksum reads this canonical ball record, which is
    // distinct from the live position usually exported by the host.
    attach(0x6b004c0, function () {
        const calculation = calculations.get(this.threadId);
        if (calculation) calculation.ballBits = readBallBits(this.context.x0);
    });

    attach(0x7053ea0, {
        onEnter(args) { this.comparison = args[1]; },
        onLeave() {
            const frame = this.comparison.add(12).readU32();
            if (frame === 0 || frame !== this.comparison.add(8).readU32()) return;
            const stored = this.comparison.add(2).readU16();
            const current = this.comparison.add(4).readU16();
            if (stored === current) return;
            const localArrivedSecond = this.comparison.add(1).readU8() === 255;
            record('match_checksum_disagreement', {
                frame,
                local: localArrivedSecond ? current : stored,
                peer: localArrivedSecond ? stored : current,
                verdict: this.comparison.add(16).readU32(),
            });
        },
    });

    // Only report a new move to the parking position. The same native branch
    // can run repeatedly while the ball is already parked.
    attach(0x6af6cdc, function () {
        const { x19, x21, x24, x27, sp } = this.context;
        if (x21.toUInt32() !== x19.add(0xc).readU32()) return;
        const ball = x24.readPointer();
        if (ball.isNull()) return;
        const position = ball.add(0x30);
        if (position.readFloat() === 0 && position.add(8).readFloat() === 100) return;
        const frame = commandFrame();
        if (frame === null || frame === 0) return;
        const model = sp.add(0x20).readPointer();
        record('active_ball_park', {
            frame,
            ballBitsBefore: readBallBits(position),
            transitionFlag: x27.add(0x22e).readU8(),
            matchFlowFlag: model.add(0x33a8).readU8(),
        });
    });

    // jp.konami.AndroidUtil.OnPauseCallback and OnResumeCallback.
    attach(0x6abc648, { onEnter() { record('game_paused'); } });
    attach(0x6abc790, { onEnter() { record('game_resumed'); } });

    // TaskSyncTeamData enables result polling after accepting CmdStartGame.
    attach(0x7a5bc70, {
        onEnter() {
            if (this.returnAddress.equals(base.add(0x8138690))) record('match_start_accepted');
        },
    });

    // CmdGetGameResult stores its two top-level result codes after parsing.
    attach(0x76e2ebc, {
        onEnter(args) { this.command = args[0]; },
        onLeave() {
            try {
                record('server_game_result', {
                    result: readStatus(this.command.add(0x1f0)),
                    getMatchResult: readStatus(this.command.add(0x960)),
                });
            } catch (error) {
                record('server_game_result_unreadable');
            }
        },
    });

    let backgroundLimitExceeded = false;
    // The match-result task checks the configured allowance for background entries.
    attach(0x7eb8c28, {
        onLeave(result) {
            const exceeded = result.toInt32() !== 0;
            if (exceeded && !backgroundLimitExceeded) record('background_entry_limit_exceeded');
            backgroundLimitExceeded = exceeded;
        },
    });

    // The same stop-request and command-controller paths recorded by the bot journal.
    // At 0x701794c the caller distinguishes a local command from a synchronized input.
    attach(0x70165d8, {
        onEnter() { record('request_match_stop', { caller: callerInEngine(this.returnAddress) }); },
    });
    attach(0x701794c, {
        onEnter() { record('apply_match_stop', { caller: callerInEngine(this.returnAddress) }); },
    });

    // Native session closure. The caller distinguishes the result task from other paths.
    attach(0x7a1d454, {
        onEnter() { record('close_match_session', { caller: callerInEngine(this.returnAddress) }); },
    });
    attach(0x7c88dbc, {
        onEnter() { record('close_peer_transport', { caller: callerInEngine(this.returnAddress) }); },
    });
}

Process.attachModuleObserver({
    onAdded(module) {
        if (module.name !== library || listeners.length !== 0) return;
        try {
            if (Process.arch !== 'arm64' || readBuildId(module.base) !== expectedBuildId) {
                record('unsupported_engine');
                return;
            }
            observe(module);
            record('observer_ready');
        } catch (error) {
            for (const listener of listeners.splice(0)) listener.detach();
            record('observer_failed', { error: String(error) });
        }
    },
});
