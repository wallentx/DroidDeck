# Experimental Box64 syscall component

This standalone diagnostic build tests x86 Wine WoW64 as an alternative to
FEX for programs that issue Linux i386 `int 0x80` calls. It is not included in
the APK and does not select a compatibility tool or modify game prefixes.

The pinned upstream Android/glibc build recognized the instruction but returned
`ENOSYS` for `getpid` (20) and `memfd_create` (356). The downstream patch enables
those two existing syscall-table mappings and implements `read`, `write`, and
`close` with libc calls and Linux negative-errno results. Existing syscall-user-
dispatch handling remains before these handlers. All other unsupported calls
retain their existing behavior. This build uses `BOX32=0`; its patch does not
cover the separate Box32 implementation.

The patch and diagnostic tooling were AI-assisted, explicitly authorized for
this downstream experiment. They are not submitted to Box64 upstream.

## Build and validation

The `Build experimental Box64` workflow first runs the static ELF64 probes on
native x86-64 Linux. Its ARM64 job builds the pinned source and checks both the
native-syscall control and mixed-ABI test through Box64. The mixed-ABI test also
runs with the JIT disabled. Compilation belongs in CI, not on the phone.

On an ARM64 Linux build host with CMake, Ninja, GCC, Python, Clang, LLD and zstd:

```sh
bash tools/box64/build.sh out/box64
bash tools/box64/tests/build.sh out/probes
BOX64_NORCFILES=1 out/box64/box64 out/probes/int80
```

The probes are linked at 256 MiB: DroidDeck relocated the default 2 MiB
image above 4 GiB, which invalidated its deliberate 32-bit pointer test.
The syscall regression passes inside DroidDeck at the explicit low address.

`int80` returns the failing stage as its exit status: 1 PID agreement; 2 memory
file creation and pointer truncation; 3 write; 4 seek/read and content; 5 close
and `EBADF`; 6 invalid flags/`EINVAL`; 7 invalid pointer/`EFAULT`; 8 invalid-FD
read/write; 9 unknown syscall/`ENOSYS`. Zero means all stages passed.

Artifacts contain the binary, patched source archive, exact upstream revision,
downstream patch, license, checksums and probes. A successful CPU test does not
establish Wine, PowerVR Vulkan, Steam integration or game/anti-cheat compatibility;
those require separate device tests. Preserve existing game settings until then.
