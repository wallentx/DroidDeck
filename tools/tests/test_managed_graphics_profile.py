import hashlib
import json
import os
from pathlib import Path
import runpy
import tempfile
import unittest


BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
MODULE = runpy.run_path(str(BIN / "droiddeck-game-env"))
CRITICAL = MODULE["PROFILE_CRITICAL"]


class ManagedGraphicsProfileTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.files = Path(self.tmp.name) / "files"
        self.base = self.files / "graphics_profiles/packs"
        self.base.mkdir(parents=True)
        self.hybris = self.files / "system_vulkan" / ("a" * 64)
        self.pack, self.version = self.make_pack("old")
        self.config = {"version": 1, "graphicsProfile": self.profile(self.version)}
        self.command = ["/proton", "waitforexitandrun", "/game.exe"]
        self.env = {"STEAM_COMPAT_DATA_PATH": "/compatdata/42"}

    def make_pack(self, marker):
        stage = self.base / ("stage-" + marker)
        stage.mkdir()
        described = {}
        for index, relative in enumerate(CRITICAL):
            target = stage / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            payload = ("%s:%s:%d" % (marker, relative, index)).encode()
            target.write_bytes(payload)
            if relative == "run-proton.py":
                target.chmod(0o755)
            described[relative] = {
                "path": relative,
                "size": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest(),
            }
        manifest = {
            "format": 1,
            "id": "test-" + marker,
            "wrapper": described["run-proton.py"],
            "archives": [
                {"id": "dxvk", "files": [described[p] for p in CRITICAL if p.startswith("dxvk/")]},
                {"id": "wsi", "files": [described[p] for p in CRITICAL if p.startswith("wsi/")]},
            ],
        }
        manifest_bytes = json.dumps(manifest, sort_keys=True).encode()
        version = hashlib.sha256(manifest_bytes).hexdigest()
        (stage / "pack.json").write_bytes(manifest_bytes)
        (stage / ".complete").write_text(version + "\n")
        pack = self.base / version
        stage.rename(pack)
        return pack, version

    def profile(self, version):
        pack = self.base / version
        managed = {
            "DROIDDECK_PROTON_WRAPPER": str(pack / "run-proton.py"),
            "HYBRIS_BC_TEXTURES": "dxvk",
            "VK_LAYER_PATH": "%s:%s" % (pack / "wsi", self.hybris / "lib"),
            "VK_INSTANCE_LAYERS": "VK_LAYER_window_system_integration:VK_LAYER_HYBRIS_compat",
            "ENABLE_GAMESCOPE_WSI": "0",
            "VK_LOADER_LAYERS_DISABLE": "*gamescope*",
            "DISABLE_WSI_LAYER": None,
            "PROTON_USE_WINED3D": None,
            "DISABLE_VK_LAYER_VALVE_steam_fossilize_1": "1",
            "ENABLE_VK_LAYER_VALVE_steam_fossilize_1": None,
        }
        return {
            "format": 1,
            "packBase": str(self.base),
            "version": version,
            "hybrisRuntime": str(self.hybris),
            "managedEnvironment": managed,
            "validation": {
                "receipt": ".complete",
                "manifest": "pack.json",
                "criticalFiles": list(CRITICAL),
            },
        }

    def test_managed_keys_apply_last_and_unsets_win(self):
        inherited = dict(self.env)
        for key in MODULE["PROFILE_KEYS"]:
            inherited[key] = "user-value"
        result = MODULE["apply_graphics_profile_for_launch"](self.command, inherited, self.config)
        self.assertEqual(result["DROIDDECK_PROTON_WRAPPER"], str(self.pack / "run-proton.py"))
        self.assertEqual(result["HYBRIS_BC_TEXTURES"], "dxvk")
        self.assertNotIn("DISABLE_WSI_LAYER", result)
        self.assertNotIn("PROTON_USE_WINED3D", result)
        self.assertNotIn("ENABLE_VK_LAYER_VALVE_steam_fossilize_1", result)

    def test_profile_is_ignored_for_probes_and_non_games(self):
        broken = {"version": 1, "graphicsProfile": {"format": 999}}
        inherited = dict(self.env, KEEP="yes")
        self.assertEqual(MODULE["apply_graphics_profile_for_launch"](
            ["/proton", "run", "/probe.exe"], inherited, broken), inherited)
        self.assertEqual(MODULE["apply_graphics_profile_for_launch"](
            self.command, dict(inherited, STEAM_COMPAT_DATA_PATH="/compatdata/0"), broken)["KEEP"], inherited["KEEP"])
        self.assertEqual(MODULE["apply_graphics_profile_for_launch"](
            self.command, {"KEEP": "yes"}, broken)["KEEP"], inherited["KEEP"])

    def test_missing_or_corrupt_receipt_is_rejected(self):
        (self.pack / ".complete").write_text("0" * 64)
        with self.assertRaisesRegex(MODULE["GraphicsProfileError"], "receipt"):
            MODULE["apply_graphics_profile_for_launch"](self.command, self.env, self.config)

    def test_corrupt_dll_is_rejected_before_proton(self):
        dll = self.pack / "dxvk/x64/dxgi.dll"
        dll.write_bytes(b"x" * dll.stat().st_size)
        with self.assertRaisesRegex(MODULE["GraphicsProfileError"], "missing or corrupt"):
            MODULE["apply_graphics_profile_for_launch"](self.command, self.env, self.config)

    def test_missing_wsi_library_is_rejected_before_proton(self):
        (self.pack / "wsi/libVkLayer_window_system_integration.so").unlink()
        with self.assertRaisesRegex(MODULE["GraphicsProfileError"], "missing"):
            MODULE["apply_graphics_profile_for_launch"](self.command, self.env, self.config)

    def test_manifest_must_hash_to_selected_version(self):
        with (self.pack / "pack.json").open("ab") as output:
            output.write(b" ")
        with self.assertRaisesRegex(MODULE["GraphicsProfileError"], "manifest does not match"):
            MODULE["apply_graphics_profile_for_launch"](self.command, self.env, self.config)

    def test_selected_old_pack_uses_its_own_manifest_after_an_update(self):
        new_pack, new_version = self.make_pack("new")
        (new_pack / "pack.json").write_text("new app metadata is irrelevant to the old selection")
        result = MODULE["apply_graphics_profile_for_launch"](self.command, self.env, self.config)
        self.assertEqual(result["DROIDDECK_PROTON_WRAPPER"], str(self.pack / "run-proton.py"))
        self.assertNotEqual(self.version, new_version)

    def test_unexpected_python_module_is_rejected_before_wrapper_execution(self):
        (self.pack / "argparse.py").write_text("raise RuntimeError('loaded')\n")
        with self.assertRaisesRegex(MODULE["GraphicsProfileError"], "unexpected entry"):
            MODULE["apply_graphics_profile_for_launch"](self.command, self.env, self.config)

    def test_unexpected_symlink_is_rejected_before_wrapper_execution(self):
        (self.pack / "extra.py").symlink_to(self.pack / "run-proton.py")
        with self.assertRaisesRegex(MODULE["GraphicsProfileError"], "unexpected entry"):
            MODULE["apply_graphics_profile_for_launch"](self.command, self.env, self.config)

    def test_published_alias_paths_stay_lexical_while_files_are_confined_canonically(self):
        alias = Path(self.tmp.name) / "app-data-alias"
        alias.symlink_to(Path(self.tmp.name), target_is_directory=True)
        alias_files = alias / "files"
        config = json.loads(json.dumps(self.config).replace(str(self.files), str(alias_files)))
        result = MODULE["apply_graphics_profile_for_launch"](self.command, self.env, config)
        self.assertEqual(
            result["DROIDDECK_PROTON_WRAPPER"],
            str(alias_files / "graphics_profiles/packs" / self.version / "run-proton.py"),
        )


if __name__ == "__main__":
    unittest.main()
