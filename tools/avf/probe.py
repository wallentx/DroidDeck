#!/usr/bin/env python3
"""Validate a CI artifact and run its diskless GPU probe through shell Shizuku."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import uuid

FILES = ("Image", "kernel.config", "probe-initramfs.cpio.gz", "gpu-probe.jar", "provenance.txt")
AETHER_REF = "fe2ea2b78ce7701a0144f4532a1aed54e7e6030b"
REQUIRED_CONFIG = ("ARM64_4K_PAGES", "DRM", "DRM_VIRTIO_GPU", "INPUT_EVDEV", "VIRTIO_INPUT",
                   "SND_VIRTIO", "BLK_DEV_INITRD", "RD_GZIP", "VIRTIO_FS")


def verify_console(console, *, ci_2d=False):
    lines = console.splitlines()
    if "Kernel panic" in console or "DROIDDECK_VIRTGPU_FAIL_V1" in console:
        raise ValueError("Guest reported failure")
    markers = ["DROIDDECK_VIRTGPU_BEGIN_V1", "DROIDDECK_VIRTGPU_NODE_V1",
               "DROIDDECK_VIRTGPU_PASS_V1"]
    markers.append("DROIDDECK_VIRTGPU_CI_2D_V1" if ci_2d else "DROIDDECK_GFXSTREAM_CONTEXT_V1")
    if not ci_2d and "DROIDDECK_VIRTGPU_CI_2D_V1" in console:
        raise ValueError("QEMU 2D enumeration does not prove Gfxstream support")
    for marker in markers:
        if lines.count(marker) != 1:
            raise ValueError(f"Missing or duplicate marker: {marker}")


def validate_artifact(directory, commit):
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Use the full source commit from the successful CI run")
    checksums = {}
    for line in (directory / "SHA256SUMS").read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9_.-]+)", line)
        if not match or match[2] in checksums:
            raise ValueError("Invalid or duplicate checksum entry")
        checksums[match[2]] = match[1]
    if set(checksums) != set(FILES):
        raise ValueError("Artifact file set differs from the GPU probe contract")
    for name, expected in checksums.items():
        file = directory / name
        limit = 256 * 1024 * 1024 if name == "Image" else 16 * 1024 * 1024
        if file.is_symlink() or not file.is_file() or not 0 < file.stat().st_size <= limit:
            raise ValueError(f"Invalid artifact file: {name}")
        with file.open("rb") as stream:
            actual = hashlib.file_digest(stream, "sha256").hexdigest()
        if actual != expected:
            raise ValueError(f"Checksum mismatch: {name}")
    provenance = (directory / "provenance.txt").read_text().splitlines()
    expected = {f"commit={commit}", f"aether_commit={AETHER_REF}", "purpose=diskless-gfxstream-probe"}
    if len(provenance) != len(expected) or set(provenance) != expected:
        raise ValueError("Artifact source or purpose does not match")
    config = set((directory / "kernel.config").read_text().splitlines())
    if any(f"CONFIG_{option}=y" not in config for option in REQUIRED_CONFIG):
        raise ValueError("Required built-in kernel driver is missing")
    return checksums


def shell(command, *, timeout=20, stdin=None):
    result = subprocess.run(["rish", "-c", command], input=stdin, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError((result.stdout + result.stderr).decode(errors="replace"))
    return (result.stdout + result.stderr).decode(errors="replace")


def transfer(source, target, expected):
    # Encode a bounded block at a time: a kernel need not be held twice in RAM.
    with subprocess.Popen(["timeout", "--foreground", "120", "rish", "-c",
                           f"umask 077; base64 -d > {shlex.quote(target)}"],
                          stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE) as process:
        try:
            with source.open("rb") as stream:
                while block := stream.read(3 * 16384):
                    process.stdin.write(base64.b64encode(block))
            process.stdin.close()
            process.stdin = None
            _, errors = process.communicate(timeout=120)
        except BaseException:
            process.kill()
            process.communicate()
            raise
        if process.returncode:
            raise RuntimeError(errors.decode(errors="replace"))
    result = shell(f"sha256sum {shlex.quote(target)}").split()
    if not result or result[0] != expected:
        raise ValueError(f"Device transfer checksum mismatch: {source.name}")
    shell(f"chmod 0400 {shlex.quote(target)}")


def verified_device_stage(report, commit):
    if (report.get("status") != "passed" or report.get("source_commit") != commit
            or report.get("disks") != 0):
        raise ValueError("A passing diskless device probe from this exact artifact is required")
    stage = report.get("device_stage", "")
    if not isinstance(stage, str) or not re.fullmatch(r"/data/local/tmp/droiddeck-gpu-[0-9a-f]{32}", stage):
        raise ValueError("Invalid device staging directory")
    return stage


def stage_kernel(artifact, commit, result):
    hashes = validate_artifact(artifact, commit)
    report = json.loads(result.read_text())
    stage = verified_device_stage(report, commit)
    response = subprocess.run(["termux-arch-vm", "--status"], capture_output=True, text=True,
                              check=True, timeout=25)
    state = json.loads(response.stdout)
    if (state.get("status") != "stopped" or state.get("running") is not False
            or state.get("active_sessions") != 0 or state.get("clean_shutdown") is not True):
        raise RuntimeError(f"Arch must be cleanly stopped: {state.get('status')}, {state.get('reason')}")
    helper = f"{stage}/gpu-probe.jar"
    if shell(f"sha256sum {helper}").split()[0] != hashes["gpu-probe.jar"]:
        raise ValueError("Device helper checksum mismatch")
    output = shell(f"CLASSPATH={helper} app_process /system/bin StageGraphicsKernel {stage} {hashes['Image']}",
                   timeout=55)
    (result.parent / "stage-kernel.log").write_text(output)
    records = [json.loads(line) for line in output.splitlines() if line.startswith('{"operation":')]
    if (len(records) != 1 or records[0].get("operation") not in ("staged", "unchanged")
            or records[0].get("image_sha256") != hashes["Image"]
            or records[0].get("normal_kernel_replaced") is not False
            or records[0].get("guest_disk_opened") is not False):
        raise RuntimeError("Kernel staging did not return a verified result")
    records[0]["source_commit"] = commit
    (result.parent / "stage-kernel.json").write_text(json.dumps(records[0], indent=2) + "\n")
    print(output, end="")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="operation", required=True)
    check = sub.add_parser("check-console")
    check.add_argument("console", type=Path)
    check.add_argument("--ci-2d", action="store_true")
    run = sub.add_parser("run")
    run.add_argument("artifact", type=Path)
    run.add_argument("--commit", required=True)
    run.add_argument("--output", type=Path, required=True)
    stage = sub.add_parser("stage-kernel", help="Add a verified Image-gfxstream; preserve Image and the guest disk")
    stage.add_argument("artifact", type=Path)
    stage.add_argument("--commit", required=True)
    stage.add_argument("--probe-result", type=Path, required=True)
    args = parser.parse_args()
    if args.operation == "check-console":
        verify_console(args.console.read_text(), ci_2d=args.ci_2d)
        return
    if args.operation == "stage-kernel":
        stage_kernel(args.artifact, args.commit, args.probe_result)
        return
    hashes = validate_artifact(args.artifact, args.commit)
    if shell("id -u").strip() != "2000":
        raise RuntimeError("This probe requires shell Shizuku (UID 2000)")
    args.output.mkdir(parents=True, exist_ok=True)
    stage = "/data/local/tmp/droiddeck-gpu-" + uuid.uuid4().hex
    shell(f"umask 077; mkdir {stage}")
    record = {"status": "incomplete", "source_commit": args.commit, "device_stage": stage,
              "disks": 0, "vulkan_rendering": "not_tested"}
    result_path = args.output / "result.json"
    result_path.write_text(json.dumps(record, indent=2) + "\n")
    for name in FILES:
        print(f"Transferring {name}", flush=True)
        transfer(args.artifact / name, f"{stage}/{name}", hashes[name])
    command = (f"CLASSPATH={stage}/gpu-probe.jar:/apex/com.android.virt/javalib/framework-virtualization.jar "
               f"app_process /system/bin AvfGpuProbe {stage}")
    result = subprocess.run(["rish", "-c", command], capture_output=True, timeout=55)
    output = (result.stdout + result.stderr).decode(errors="replace")
    (args.output / "host.log").write_text(output)
    # The helper reads the complete console on-device, avoiding rish's large-output
    # stream splitting. It returns success only after the diskless guest is dead.
    passed = result.returncode == 0 and output.splitlines().count("DROIDDECK_GPU_RESULT=passed") == 1
    record["status"] = "passed" if passed else "failed"
    result_path.write_text(json.dumps(record, indent=2) + "\n")
    print(output, end="")
    print(f"Evidence: {result_path}; device console: {stage}/console.txt")
    if not passed:
        raise RuntimeError("GPU prerequisites were not verified; inspect host.log and the device console")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"AVF GPU probe: {error}", file=sys.stderr)
        sys.exit(1)
