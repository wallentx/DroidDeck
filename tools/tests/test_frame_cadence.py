import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class FrameCadenceTest(unittest.TestCase):
    def test_choreographer_timeline_estimator(self):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        if not compiler:
            self.fail("A C compiler is required for the frame cadence regression test")
        tests = Path(__file__).resolve().parent
        source = tests / "frame_cadence_test.c"
        includes = tests.parents[1] / "app/src/main/cpp/waylandcomp/src"
        with tempfile.TemporaryDirectory(prefix="droiddeck-cadence-") as tmp:
            program = Path(tmp) / "frame-cadence"
            built = subprocess.run(
                [compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", "-UNDEBUG",
                 "-I", str(includes), str(source), "-o", str(program)],
                capture_output=True,
                text=True,
            )
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)


if __name__ == "__main__":
    unittest.main()
