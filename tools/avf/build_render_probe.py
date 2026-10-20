#!/usr/bin/env python3
"""Build the small ARM64 glibc render probe; does not build a kernel or an APK."""
import argparse
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    source = Path(__file__).resolve().parent
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    prefix = Path(os.environ.get("PREFIX", "/usr"))
    termux = str(prefix).startswith("/data/data/com.termux/")
    includes = prefix / "include"
    if not (includes / "vulkan/vulkan.h").is_file():
        raise SystemExit("Vulkan headers are required on the build host")
    # Copy only API headers, never the host's libc headers into a cross compiler.
    for name in ("vulkan", "vk_video"):
        if (includes / name).is_dir():
            shutil.copytree(includes / name, output / "include" / name, dirs_exist_ok=True)
    for stage in ("vert", "frag"):
        subprocess.run(["glslangValidator", "-V", "--target-env", "vulkan1.0", "--vn",
                        "triangle_" + stage, str(source / ("triangle." + stage)),
                        "-o", str(output / ("triangle-" + stage + ".h"))], check=True)
    common = ["-O2", "-std=c11", "-Wall", "-Wextra", "-Werror", "-I" + str(output),
              "-I" + str(output / "include")]
    if termux:
        glibc = prefix / "glibc"
        if not (glibc / "lib/crt1.o").is_file():
            raise SystemExit("The Termux GNU libc development files are required")
        command = ["clang", "--target=aarch64-linux-gnu", "--sysroot=/", "-isystem",
                   str(glibc / "include"), "-fuse-ld=lld", "-nostdlib", *common,
                   str(glibc / "lib/crt1.o"), str(glibc / "lib/crti.o"),
                   str(source / "render-probe.c"), "-L" + str(glibc / "lib"), "-lc",
                   str(glibc / "lib/crtn.o"), "-Wl,--dynamic-linker=/lib/ld-linux-aarch64.so.1"]
    else:
        command = ["aarch64-linux-gnu-gcc", *common, str(source / "render-probe.c"), "-ldl"]
    command += ["-o", str(output / "render-probe")]
    subprocess.run(command, check=True)
    header = (output / "render-probe").read_bytes()[:64]
    if header[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", header, 18)[0] != 183:
        raise SystemExit("Compiler did not produce an ELF64 AArch64 executable")
    (output / "build-command.json").write_text(json.dumps(command, indent=2) + "\n")
    print("Built", output / "render-probe")


if __name__ == "__main__":
    main()
