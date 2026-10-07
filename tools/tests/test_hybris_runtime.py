import hashlib
import importlib.util
import io
from pathlib import Path
import subprocess
import tarfile
import unittest

spec = importlib.util.spec_from_file_location(
    "hybris_runtime", Path(__file__).resolve().parents[1] / "hybris/check_runtime.py")
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class HybrisRuntimeTest(unittest.TestCase):
    def fixture(self, missing=None, machine=b"\xb7\x00", stale=False, unsafe=False):
        elf = bytearray(64)
        elf[:6] = b"\x7fELF\x02\x01"
        elf[18:20] = machine
        files = {name: bytes(elf) for name in runtime.REQUIRED if name != missing}
        files["SHA256SUMS"] = "".join(
            hashlib.sha256(data).hexdigest() + "  " + name + "\n"
            for name, data in files.items()).encode()
        files.update({"licenses/libhybris-source.tar.zst": b"source",
                      "licenses/powervr.patch": b"patch", "source-commit": b"pinned revision"})
        if stale:
            files[runtime.REQUIRED[0]] += b"changed after hashing"
        raw = io.BytesIO()
        with tarfile.open(fileobj=raw, mode="w") as archive:
            for name, data in files.items():
                member = tarfile.TarInfo(name)
                member.size = len(data)
                if unsafe and name == runtime.REQUIRED[0]:
                    member.type = tarfile.SYMTYPE
                    member.linkname = "../../outside"
                    member.size = 0
                    archive.addfile(member)
                else:
                    archive.addfile(member, io.BytesIO(data))
        packed = subprocess.run(["zstd", "-cq"], input=raw.getvalue(),
                                capture_output=True, check=True).stdout
        return packed, hashlib.sha256(packed).hexdigest()

    def test_complete_aarch64_payload(self):
        runtime.check(*self.fixture())

    def test_missing_wrapper_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "missing runtime file"):
            runtime.check(*self.fixture(missing=runtime.REQUIRED[0]))

    def test_host_architecture_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "not ELF64 AArch64"):
            runtime.check(*self.fixture(machine=b"\x3e\x00"))

    def test_modified_library_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "library checksum mismatch"):
            runtime.check(*self.fixture(stale=True))

    def test_archive_checksum_is_required(self):
        packed, _ = self.fixture()
        with self.assertRaisesRegex(ValueError, "archive checksum mismatch"):
            runtime.check(packed, "0" * 64)

    def test_symlink_cannot_escape_payload(self):
        with self.assertRaisesRegex(ValueError, "unsafe runtime symlink"):
            runtime.check(*self.fixture(unsafe=True))
