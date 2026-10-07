import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


class SteamPresentationTest(unittest.TestCase):
    def launch(self, enabled=None, inherited=None):
        source = SESSION.read_text()
        begin = source.index("    bl_steam_angle=vulkan")
        end = source.index("\n    # Decky", begin)
        command = source.index('      "$steam_root/steamrtarm64/steam" "${_bl_ui[@]}"')
        command_end = source.index("\n      rc=$?", command)
        script = (source[begin:end] + '\n_bl_ui=(-gamepadui); _bl_cdp=(); _bl_lang=()\n'
                  + source[command:command_end])
        env = dict(os.environ)
        for key in ("BL_STEAM_GL_PRESENT", "DISABLE_GAMESCOPE_WSI", "LIBGL_KOPPER_DISABLE"):
            env.pop(key, None)
        if enabled is not None:
            env["BL_STEAM_GL_PRESENT"] = enabled
        env.update(inherited or {})
        with tempfile.TemporaryDirectory(prefix="droiddeck-steam-gl-") as tmp:
            program = Path(tmp) / "steamrtarm64/steam"
            program.parent.mkdir()
            program.write_text(f"#!{sys.executable}\n" +
                               'import json, os, sys\nprint(json.dumps({"args":sys.argv[1:],'
                               '"disable":os.getenv("DISABLE_GAMESCOPE_WSI"),'
                               '"kopper":os.getenv("LIBGL_KOPPER_DISABLE")}))\n')
            program.chmod(0o755)
            env.update(steam_root=tmp, steam_channel="steamdeck_publicbeta")
            result = subprocess.run(["bash", "-c", script, "test", "steam://open/main"],
                                    env=env, capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 0, result.stderr)
            return json.loads(result.stdout.splitlines()[-1])

    def test_default_keeps_vulkan(self):
        result = self.launch()
        self.assertIn("-cef-use-angle=vulkan", result["args"])
        self.assertIsNone(result["disable"])
        self.assertIsNone(result["kopper"])

    def test_system_powervr_uses_gpu_gl_presentation(self):
        result = self.launch("1")
        self.assertIn("-cef-use-angle=gl", result["args"])
        self.assertIn("-cef-use-gl=angle", result["args"])
        self.assertIn("-cef-force-gpu", result["args"])
        self.assertEqual(result["disable"], "1")
        self.assertEqual(result["kopper"], "true")
        self.assertEqual(result["args"][-1], "steam://open/main")

    def test_disabled_option_preserves_existing_environment(self):
        result = self.launch("0", {"DISABLE_GAMESCOPE_WSI": "0", "LIBGL_KOPPER_DISABLE": "false"})
        self.assertIn("-cef-use-angle=vulkan", result["args"])
        self.assertEqual(result["disable"], "0")
        self.assertEqual(result["kopper"], "false")


if __name__ == "__main__":
    unittest.main()
