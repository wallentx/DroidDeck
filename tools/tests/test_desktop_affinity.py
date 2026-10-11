import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest


SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


class DesktopAffinityTest(unittest.TestCase):
    def launch(self, taskset_status, pin=True):
        # Run the real desktop dispatch without starting a compositor or touching CPU affinity.
        source = SESSION.read_text()
        cores = source[source.index("program_cores() {"):source.index('\nif [ -z "${BL_INSIDE:-}" ]; then')]
        start = source.index('  if [ "$mode" = lxqt ]; then')
        dispatch = source[start:source.index('  width=${BL_WIDTH:-1280}', start)]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            desktop = root / "desktop"
            desktop.write_text('#!/bin/sh\nprintf "desktop started\\n"\nexit 23\n')
            desktop.chmod(0o755)
            taskset = root / "taskset"
            taskset.write_text(f'#!/bin/sh\nprintf "%s\\n" "$*" > "$TRACE"\nexit {taskset_status}\n')
            taskset.chmod(0o755)
            script = 'mode=lxqt\nstep() { :; }\n' + cores + dispatch.replace(
                "/usr/local/bin/droiddeck-desktop", shlex.quote(str(desktop)))
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"],
                       BL_GAME_CPUS="2,3,4,5,6", BL_PIN_CORES="1" if pin else "0",
                       TRACE=str(root / "trace"))
            result = subprocess.run(["bash", "-c", script], env=env, text=True,
                                    capture_output=True, timeout=5)
            trace = (root / "trace").read_text() if (root / "trace").exists() else ""
        self.assertEqual(23, result.returncode, result.stderr)
        self.assertEqual(1, result.stdout.count("desktop started"))
        return result, trace

    def test_denied_affinity_still_launches_desktop_once(self):
        result, trace = self.launch(1)
        self.assertIn("core pinning denied", result.stdout)
        self.assertRegex(trace, r"^-cp 2,3,4,5,6 [0-9]+\n$")

    def test_successful_affinity_targets_the_parent_before_exec(self):
        result, trace = self.launch(0)
        self.assertIn("desktop cores: 2,3,4,5,6", result.stdout)
        self.assertRegex(trace, r"^-cp 2,3,4,5,6 [0-9]+\n$")

    def test_disabled_pinning_does_not_invoke_taskset(self):
        result, trace = self.launch(1, pin=False)
        self.assertEqual("", trace)
        self.assertNotIn("desktop cores:", result.stdout)
