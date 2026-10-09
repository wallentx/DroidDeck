#!/usr/bin/env python3
"""Compile the pinned source's real map formatter with fixture-backed inputs."""

import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile


def main():
    source = Path(sys.argv[1]).read_text()
    start = source.index("void CreateMemorymapFile(")
    end = source.index("\nvoid ElfAttachLib(", start)
    function = source[start:end]
    harness = r"""
#define _GNU_SOURCE
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef struct { const char* name; } elfheader_t;
typedef struct { void* stack; } box64context_t;
static box64context_t context = { (void*)0x7ad7600000 };
static box64context_t* my_context = &context;
static elfheader_t image;
static const char* fixture;
static elfheader_t* FindElfAddress(box64context_t* ctx, uintptr_t start)
{
    (void)ctx;
    return (start == 0x10000000 || start == 0x10002000) ? &image : NULL;
}
static FILE* fixture_open(const char* path, const char* mode)
{
    if (strcmp(path, "/proc/self/maps") != 0) abort();
    return fopen(fixture, mode);
}
#define fopen fixture_open
""" + function + r"""
#undef fopen
int main(int argc, char** argv)
{
    if (argc != 3) return 2;
    fixture = argv[1];
    image.name = argv[2];
    CreateMemorymapFile(my_context, STDOUT_FILENO);
    return 0;
}
"""
    rows = [
        "10000000-10001000 r-xp 00000000 fe:1e 42                             /guest/original image",
        "10002000-10013000 rw-p 00000000 00:00 0 ",
        "5560cde000-5560e00000 rw-p 00000000 00:00 0                            [heap]",
        "7ad7600000-7ad7e00000 rw-p 00000000 00:00 0 ",
        "7fded0f000-7fded32000 rw-p 00000000 00:00 0                            [stack]",
        "7fded40000-7fded50000 rw-p 00000000 00:00 0 ",
    ]
    mapping = re.compile(
        r"^([0-9a-f]+-[0-9a-f]+) ([r-][w-][x-][ps]) ([0-9a-f]+) "
        r"([0-9a-f]+:[0-9a-f]+) ([0-9]+)(?:[ \t]+(.*))?$"
    )
    with tempfile.TemporaryDirectory(prefix="box64-map-test-") as temp:
        work = Path(temp)
        (work / "formatter.c").write_text(harness)
        command = shlex.split(os.environ.get("CC", "cc"))
        subprocess.run(command + ["-std=c11", "-Wall", "-Wextra", "-Werror",
                                  "-Wno-unused-parameter", str(work / "formatter.c"),
                                  "-o", str(work / "formatter")], check=True)
        for name in ("/guest/space in name %s.so", "/guest/" + "a" * 2048 + ".so"):
            (work / "maps").write_text("\n".join(rows) + "\n")
            result = subprocess.run([str(work / "formatter"), str(work / "maps"), name],
                                    check=True, text=True, capture_output=True, timeout=5)
            output = result.stdout.splitlines()
            assert len(output) == len(rows), (
                f"one row per mapping required: expected {len(rows)}, got {len(output)}"
            )
            expected_names = ["/guest/original image", name, "[heap]", "[stack]", "", ""]
            for index, (before, after, label) in enumerate(zip(rows, output, expected_names)):
                original = mapping.fullmatch(before)
                actual = mapping.fullmatch(after)
                assert actual, f"malformed output row {index}: {after!r}"
                assert actual.groups()[:5] == original.groups()[:5], (
                    f"mapping fields changed in row {index}"
                )
                assert (actual[6] or "") == label, f"wrong annotation in row {index}"
            for index in (0, 2, 5):
                assert output[index] == rows[index], f"unchanged row {index} was rewritten"
    print("PASS: map rows, annotations, native stack removal, spaces and long paths")


if __name__ == "__main__":
    main()
