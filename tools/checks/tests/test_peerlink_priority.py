"""Exercise the downloaded Prime Shell diagnostic using Android command fixtures."""

from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import os
import subprocess
import tempfile
import time

here = Path(__file__).resolve().parent
source = (here.parent / "PeerLink-priority.sh").read_text()
cases = {
    "stock_launcher": "stronger protection while",
    "dynamic_launcher": "stronger protection while",
    "resolver_other": "stronger protection while",
    "resolver_stale": "stronger protection while",
    "resume_task": "stronger protection while",
    "manual_open": "stronger protection while",
    "never_open": "could not be verified in the foreground",
    "system_input": "stronger protection while",
    "different_input": "Different windows held input",
    "oem_window_title": "stronger protection while",
    "focus_interrupted": "stronger protection while",
    "screen_off": '\"Before\" phase did not get three stable readings',
    "binder_fds": "stronger protection while",
    "wrapper_failure": "stronger protection while",
    "list_has_value": "already configured",
    "list_empty_value": "stronger protection while",
    "error_as_success": "stronger protection while",
    "both_reads_denied": "Android did not confirm the display setting",
    "invalid_list": "Android did not confirm the display setting",
    "list_has_no_output": "Android did not confirm the display setting",
    "put_denied": "could not enable the simulated display",
    "better": "stronger protection while",
    "unchanged": "no measured difference",
    "weaker": "weaker protection while",
    "no_reversal": "change did not reverse",
    "wrong_focus": "could not be verified in the foreground",
    "wrong_app": "could not be verified in the foreground",
    "no_window": "did not get three stable readings",
    "wrong_display": "did not get three stable readings",
    "no_score": "valid bot process score",
    "no_display": "did not create the simulated display",
    "bot_restarted": "bot process exited or changed",
    "game_restarted": "eFootball restarted",
    "launch_denied": "could not open PeerLink",
    "already_set": "already configured",
    "original_empty": "stronger protection while",
    "user_changed": "Kept your current selection",
    "restore_denied": "Display restore needs attention",
    "no_bot": "First tap Create room",
    "match_bot_exit": "process=com.peerlink.app:botengine reason=3 (LOW_MEMORY)",
    "match_game_exit": "process=jp.konami.pesam reason=3 (LOW_MEMORY)",
    "match_cancel": "Recording stopped from Prime Shell",
    "match_no_window": "The window could not be verified on the second display",
}

def run(case):
    with tempfile.TemporaryDirectory(prefix="priority-") as temp:
        base = Path(temp)
        exe = base / "bin"
        exe.mkdir()
        shell = os.environ.get("PRIORITY_TEST_SHELL", "/bin/sh")
        (exe / 'sh').symlink_to(shell)
        fixture = base / 'android-command'
        fixture.write_bytes((here / 'priority-android.sh').read_bytes())
        fixture.chmod(0o700)
        for command in ('ps', 'am', 'settings', 'dumpsys', 'cmd', 'sleep'):
            (exe / command).symlink_to(fixture)
        lock = base / 'lock'
        download = base / 'emulated/0/Download'
        downloaded = base / 'PeerLink-priority.sh'
        downloaded.write_text(source.replace('/data/local/tmp/peerlink-bot-priority', str(lock))
                              .replace('/storage/emulated/', str(base / 'emulated') + '/'))
        initial = {'already_set':'640x480/160','list_has_value':'640x480/160','list_empty_value':'','original_empty':''}.get(case,'null')
        (base / 'setting').write_text(initial + '\n')
        env = dict(os.environ, PATH=str(exe) + ':' + os.environ['PATH'], FIX=temp, CASE=case)
        live = case.startswith("match_")
        command = ["sh", str(downloaded)]
        start = subprocess.run(
            command + (["match"] if live else []),
            text=True, capture_output=True, env=env, timeout=4,
        )
        if case == 'no_bot':
            assert start.returncode != 0 and cases[case] in start.stdout and not lock.exists(), start
            return
        expected_start = "Recording started" if live else "Check started"
        assert start.returncode == 0 and expected_start in start.stdout, start
        report = next(download.glob('PeerLink-priority-*.txt'))
        stop_requested = False
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            contents = report.read_text()
            if case == "match_cancel" and not stop_requested and (base / "recording").exists():
                stopped = subprocess.run(command + ["restore"], text=True, capture_output=True, env=env, timeout=4)
                assert stopped.returncode == 0 and "Cancellation requested" in stopped.stdout, stopped
                stop_requested = True
            if cases[case] in contents and not lock.exists():
                break
            if case == 'restore_denied' and 'Display restore needs attention' in contents:
                break
            time.sleep(0.02)
        result = report.read_text()
        assert cases[case] in result, (case,result)
        assert 'DO_NOT_EXPORT' not in result, (case,result)
        calls = (base/'am_calls').read_text() if (base/'am_calls').exists() else ''
        assert '-p jp.konami.pesam' not in calls and 'force-stop' not in calls, (case,calls)
        if live:
            assert "Before sample" not in result and "--display 37" in calls, (case, result, calls)
            if case == "match_no_window":
                assert "Window ready" not in result, result
            else:
                assert "Window ready" in result and "MemAvailable:" in result, result
            assert "Restored the original simulated-display setting" in result, result
        if case in ("match_bot_exit", "match_game_exit"):
            assert "Process changed" in result and "pid=17099" not in result, result
        if case in ('stock_launcher', 'resolver_other', 'resolver_stale'):
            assert calls.count('-n jp.konami.pesam/com.epicgames.ue4.SplashActivity') == 1, (case,calls)
        if case == 'resolver_other':
            assert '-n android/' not in calls, calls
        if case == 'resolver_stale':
            assert '-n jp.konami.pesam/.Removed' in calls, calls
        if case == 'dynamic_launcher':
            assert '-n jp.konami.pesam/.NewLauncher' in calls and 'SplashActivity' not in calls, calls
        if case == 'resume_task':
            assert calls.count('task focus 76') == 2 and '-n jp.konami.pesam/' not in calls, calls
        if case in ('manual_open', 'never_open'):
            assert 'Waiting up to one minute' in result, result
        if case == 'never_open':
            assert 'outside the comparison' in result and 'Before sample ' not in result, result
        if case == 'screen_off':
            assert 'screen on=false, awake=false' in result and 'Before sample ' not in result, result
            assert '--display 37' not in calls, calls
        if case == 'different_input':
            assert 'RESULT.' not in result and 'input notification-shade' in result, result
        if case == 'system_input':
            assert 'input held by notification-shade' in result, result
        if case == 'oem_window_title':
            assert 'input=jp.konami.pesam' in result and 'input=unknown' not in result, result
        if case == 'focus_interrupted':
            assert 'input=none, foreground app=jp.konami.pesam' in result, result
        if case in ('system_input', 'different_input', 'oem_window_title', 'focus_interrupted'):
            assert result.count('  bot PID 800002 score') == 9, result
        if case in ('stock_launcher','dynamic_launcher','resolver_other','resolver_stale','resume_task','manual_open'):
            assert result.count('  bot PID 800002 score') == 9
        if case in ('both_reads_denied','invalid_list','list_has_no_output'):
            assert result.count('Before sample') == 3 and 'With PeerLink visible sample' not in result
            assert 'exit 7' in result and 'diagnostic settings get unavailable' in result
        if case == 'both_reads_denied':
            assert 'exit 13' in result and 'Permission denied reading settings' in result
        if case == 'put_denied':
            assert 'exit 12' in result and 'vendor refused write' in result
        expected = {'user_changed':'800x600/160','restore_denied':'320x480/160,own_content_only'}.get(case,initial)
        assert (base / 'setting').read_text().strip() == expected, (case,result)
        if case != 'restore_denied':
            assert not lock.exists(), (case,result)
        if case in ('better','unchanged','weaker','no_reversal','original_empty','binder_fds','wrapper_failure','error_as_success','list_empty_value'):
            assert result.count('  bot PID 800002 score') == 9, (case,result)
            assert result.count('eFootball PID 800003 score 0') == 9, (case,result)
        if case == 'restore_denied':
            env['CASE'] = 'better'
            done = subprocess.run(['sh',str(downloaded),'restore'],env=env,text=True,capture_output=True)
            assert done.returncode == 0 and not lock.exists() and (base/'setting').read_text().strip()=='null', (case,done)

def test_priority_check():
    with ThreadPoolExecutor(max_workers=5) as pool:
        list(pool.map(run,cases))


if __name__ == '__main__':
    test_priority_check()
    print(f'{len(cases)} priority and recovery fixtures passed')
