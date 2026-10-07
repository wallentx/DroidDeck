import hashlib
from pathlib import Path
import tempfile
import unittest

import probe


class ProbeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.commit = "a" * 40
        for name in probe.FILES:
            (self.root / name).write_bytes(b"fixture")
        (self.root / "kernel.config").write_text(
            "\n".join(f"CONFIG_{key}=y" for key in probe.REQUIRED_CONFIG) + "\n")
        (self.root / "provenance.txt").write_text(
            f"commit={self.commit}\naether_commit={probe.AETHER_REF}\npurpose=diskless-gfxstream-probe\n")
        self.checksums()

    def checksums(self):
        (self.root / "SHA256SUMS").write_text("".join(
            f"{hashlib.sha256((self.root / name).read_bytes()).hexdigest()}  {name}\n"
            for name in probe.FILES))

    def test_complete_artifact(self):
        self.assertEqual(set(probe.FILES), set(probe.validate_artifact(self.root, self.commit)))

    def test_staging_requires_exact_passing_diskless_device_result(self):
        stage = "/data/local/tmp/droiddeck-gpu-" + "1" * 32
        valid = dict(status="passed", source_commit=self.commit, disks=0, device_stage=stage)
        self.assertEqual(stage, probe.verified_device_stage(valid, self.commit))
        for change in (dict(status="failed"), dict(source_commit="b" * 40), dict(disks=1),
                       dict(device_stage=stage + "; true"), dict(device_stage="/data/local/tmp/termux-arch-v2")):
            with self.assertRaises(ValueError):
                probe.verified_device_stage(dict(valid, **change), self.commit)

    def test_rejects_corruption_and_wrong_source(self):
        with self.assertRaisesRegex(ValueError, "source"):
            probe.validate_artifact(self.root, "b" * 40)
        (self.root / "Image").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "Checksum"):
            probe.validate_artifact(self.root, self.commit)

    def test_rejects_headless_kernel_even_with_matching_checksum(self):
        (self.root / "kernel.config").write_text("CONFIG_DRM=n\n")
        self.checksums()
        with self.assertRaisesRegex(ValueError, "kernel driver"):
            probe.validate_artifact(self.root, self.commit)

    def test_rejects_paths_duplicates_and_symlinks(self):
        sums = self.root / "SHA256SUMS"
        original = sums.read_text()
        for bad in (original + original.splitlines()[0] + "\n", original.replace("  Image", "  ../Image")):
            sums.write_text(bad)
            with self.assertRaises(ValueError):
                probe.validate_artifact(self.root, self.commit)
        sums.write_text(original)
        (self.root / "Image").rename(self.root / "real-image")
        (self.root / "Image").symlink_to("real-image")
        with self.assertRaisesRegex(ValueError, "Invalid artifact"):
            probe.validate_artifact(self.root, self.commit)

    def test_ci_2d_result_never_proves_gfxstream(self):
        log = ("DROIDDECK_VIRTGPU_BEGIN_V1\r\nDROIDDECK_VIRTGPU_NODE_V1\r\n"
               "DROIDDECK_VIRTGPU_CI_2D_V1\r\nDROIDDECK_VIRTGPU_PASS_V1\r\n")
        probe.verify_console(log, ci_2d=True)
        with self.assertRaisesRegex(ValueError, "2D enumeration"):
            probe.verify_console(log)

    def test_failures_and_incomplete_results(self):
        log = ("DROIDDECK_VIRTGPU_BEGIN_V1\nDROIDDECK_VIRTGPU_NODE_V1\n"
               "DROIDDECK_GFXSTREAM_CONTEXT_V1\nDROIDDECK_VIRTGPU_PASS_V1\n")
        probe.verify_console(log)
        for bad in (log + "Kernel panic", log + "DROIDDECK_VIRTGPU_FAIL_V1",
                    log + "DROIDDECK_VIRTGPU_PASS_V1\n", log.replace("DROIDDECK_GFXSTREAM_CONTEXT_V1", "")):
            with self.assertRaises(ValueError):
                probe.verify_console(bad)


if __name__ == "__main__":
    unittest.main()
