"""Verify Frida callback arity and independent reconnect signals."""
import importlib.util
import inspect
from pathlib import Path
import sys
import threading
import types

sys.modules.setdefault('frida', types.ModuleType('frida'))
path = Path(__file__).with_name('run-scene-target-observers.py')
spec = importlib.util.spec_from_file_location('scene_runner', path)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
events1, events2 = [], []
ready1, stopped1, ready2, stopped2 = (threading.Event() for _ in range(4))
message1, detach1 = runner.make_callbacks(ready1, stopped1, events1.append)
message2, detach2 = runner.make_callbacks(ready2, stopped2, events2.append)
for callback in (message1, detach1, message2, detach2):
    assert len(inspect.signature(callback).parameters) == 2
message1({'type': 'send', 'payload': {'type': 'scene_target_observer_ready'}}, None)
assert ready1.is_set() and not ready2.is_set()
detach1('process-terminated', None)
assert stopped1.is_set() and not stopped2.is_set()
assert events1[-1] == {'type': 'detached', 'reason': 'process-terminated'}
message2({'type': 'error', 'description': 'fixture'}, None)
assert events2 == [{'type': 'script_error', 'description': 'fixture'}]
print('Frida reconnect callbacks passed: correct arity and independent signals.')
