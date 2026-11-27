"""Exercise the room cleanup shell with Android activity-manager responses."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


ASSET = Path(__file__).resolve().parents[1] / "app/src/main/assets/prime-room-cleanup.sh"


class PrimeRoomGuardTest(unittest.TestCase):
    def run_guard(self, game="2 (TOP)", peerlink="4 (FGS)", sweep_exit=0,
                  args=("10001", "10002")):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            am = root / "am"
            am.write_text("""#!/bin/sh
printf '%s\\n' "$*" >> "$CALLS"
case "$*" in
  "get-uid-state 10001") printf '%s\\n' "$GAME" ;;
  "get-uid-state 10002") printf '%s\\n' "$PEERLINK" ;;
  "kill-all") exit "$SWEEP_EXIT" ;;
  *) exit 9 ;;
esac
""")
            am.chmod(0o700)
            calls = root / "calls"
            calls.touch()
            env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}", GAME=game,
                       PEERLINK=peerlink, SWEEP_EXIT=str(sweep_exit), CALLS=str(calls))
            result = subprocess.run(["sh", str(ASSET), "guard", *args], env=env,
                                    capture_output=True, text=True, check=False)
            return result, calls.read_text().splitlines()

    def test_active_game_and_bot_use_only_android_cached_process_selection(self):
        result, calls = self.run_guard()
        self.assertEqual(calls, ["get-uid-state 10001", "get-uid-state 10002", "kill-all"])
        self.assertEqual(result.returncode, 0)
        self.assertIn("Android cached-app sweep requested", result.stdout)

    def test_cached_missing_or_unrecognized_state_skips_sweep(self):
        for game, peerlink in [("19 (CACHED_EMPTY)", "4 (FGS)"), ("2 (TOP)", "20 (NONE)"),
                               ("unknown", "4 (FGS)")]:
            with self.subTest(game=game, peerlink=peerlink):
                result, calls = self.run_guard(game=game, peerlink=peerlink)
                self.assertEqual(result.returncode, 0)
                self.assertNotIn("kill-all", calls)
                self.assertIn("skipped", result.stdout)

    def test_invalid_uid_and_rejected_requests_never_report_success(self):
        invalid, calls = self.run_guard(args=("", "10002"))
        self.assertNotEqual(invalid.returncode, 0)
        self.assertEqual(calls, [])
        rejected, calls = self.run_guard(sweep_exit=13)
        self.assertEqual(rejected.returncode, 13)
        self.assertIn("kill-all", calls)
        self.assertNotIn("sweep requested", rejected.stdout)


class SystemReclaimTest(unittest.TestCase):
    def run_reclaim(self, before_count, after_count, enabled=True, status=0):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            def program(name, body):
                path = root / name
                path.write_text("#!/bin/sh\n" + body)
                path.chmod(0o700)

            (root / "ram").write_text("1100\n1150\n1140\n")
            (root / "counts").write_text(f"{before_count}\n{after_count}\n")
            program("dumpsys", """read -r count < counts
sed -i '1d' counts
echo "  use_compaction=$ENABLED"
echo ' System Compaction Memory Stats'
echo "    Compactions Performed: $count"
""")
            program("am", """echo "$*" >> calls
if [ "$STATUS" != 0 ]; then echo 'Permission denied'; fi
exit "$STATUS"
""")
            program("sleep", 'echo "$*" >> sleeps\n')
            program("awk", """if [ "$2" = /proc/meminfo ]; then
  head -1 ram
  sed -i '1d' ram
else
  exec "$REAL_AWK" "$@"
fi
""")
            env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}", REAL_AWK=shutil.which("awk"),
                       ENABLED=str(enabled).lower(), STATUS=str(status))
            result = subprocess.run(["sh", str(ASSET.with_name("prime-system-reclaim.sh"))],
                                    cwd=root, env=env, capture_output=True, text=True, check=False)
            calls = (root / "calls").read_text().splitlines() if (root / "calls").exists() else []
            return result, calls

    def test_measures_actual_available_ram_with_a_counter_increment(self):
        result, calls = self.run_reclaim(7, 8)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(calls, ["compact system"])
        self.assertIn("from 7 to 8", result.stdout)
        self.assertIn("1140 MiB (+40 MiB)", result.stdout)

    def test_device_disables_or_silently_skips_system_compaction(self):
        disabled, calls = self.run_reclaim(0, 0, enabled=False)
        self.assertEqual(calls, [])
        self.assertIn("switched off", disabled.stdout)
        skipped, _ = self.run_reclaim(0, 0)
        self.assertIn("no new system compaction", skipped.stdout)
        self.assertIn("+40 MiB", skipped.stdout)

    def test_handles_missing_counters_and_an_android_rejection(self):
        unverified, _ = self.run_reclaim("missing", "missing")
        self.assertIn("did not expose a counter", unverified.stdout)
        rejected, _ = self.run_reclaim(0, 0, status=1)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("Permission denied", rejected.stdout)
        self.assertNotIn("After 10 seconds", rejected.stdout)


if __name__ == "__main__":
    unittest.main()
