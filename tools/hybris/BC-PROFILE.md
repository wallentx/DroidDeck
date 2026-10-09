# Experimental Direct3D BC profile

`HYBRIS_BC_TEXTURES=dxvk` enables a restricted GPU decoding profile in the
hybris compatibility layer. It is opt-in; the session does not select it yet.
Use the format-checking DXVK built by `tools/dxvk/build.sh` with this profile.
The global `textureCompressionBC` feature remains false.

The profile forces decoding of all fourteen Direct3D BC formats, even where
PowerVR advertises native per-format support. BC1 RGB is excluded because its
absent-alpha border behavior needs separate support. Vulkan device properties
are capped at 1.3. Unsupported host-copy, push-descriptor, descriptor-buffer,
shader-object and generated-command paths remain unavailable. Maintenance6
bind-descriptor and push-constant commands preserve compute state around the
injected decoder. Mutable images support only the corresponding UNORM/sRGB
pair in the BC1, BC2, BC3 and BC7 families, including format-list restrictions.

## Device evidence, 2026-10-08

Pixel 11 Pro XL, PowerVR CXTP-48-1536 MC1, Vulkan driver 1.4.344:

| Check | Result |
| --- | --- |
| GPU decoder golden corpus | 344 readbacks, zero failures |
| BC7 / BC6H corpus size | 512 / 952 blocks |
| BC image versus native uncompressed image | 336 readbacks, zero differences |
| Compressed raw copies / untouched buffer sentinels | Zero differences |
| Maintenance6 plus alternate sRGB views | Same 336 readbacks, zero reference differences |
| Vulkan API and synchronization validation | Zero errors |
| Native sampling versus mathematical precision controls | 110,182 failures; unresolved |
| Hidden Vulkan/Xwayland swapchain creation | XCB and Xlib pass through instance and device dispatch, Vulkan 1.3 |
| Hidden Direct3D presentation | D3D9 x86 and D3D11 x64 pass with Steam Fossilize disabled |
| Dark Souls with patched DXVK and BC profile | In-game rendering observed at approximately 16 FPS after disabling Steam Fossilize; not a performance benchmark |

The image suite still exits with failure because its native precision controls
fail. Their expected values have not been relaxed. Zero differences against
native uncompressed images establish the tested conversion and state-restoration
behavior, not Vulkan conformance or working games. Tests ran through the local
AArch64 glibc launcher; the DroidDeck app execution path remains a separate gate.

The patch extends the upstream baseline image probe with optional environment
flags: `BC_D3D_FORMATS=1` skips the two BC1 RGB formats; `BC_MAINTENANCE6=1`
uses maintenance6 commands; `BC_MUTABLE=1` creates each supported compressed
image with the opposite UNORM/sRGB format and samples through the original
view format. All precision controls and raw-copy checks remain enabled.

Build commands, unmodified and extended probe logs, validation output and exact
local binaries are retained under `.local/benchmarks/powervr/bc-dev/` in the
development checkout. Full runtime builds run in CI. The runtime source archive
and combined downstream patch accompany the packaged libraries.

## Per-game activation

The game-environment launcher accepts `DROIDDECK_PROTON_WRAPPER` as an
executable absolute path. It invokes the wrapper with the selected Proton
command and its original arguments, after synchronization-pack selection.
Only `waitforexitandrun` launches with a nonzero game prefix use the wrapper;
Steam's compatibility probes and installer evaluations retain their ordinary
launch path. Arguments are passed directly, without shell interpolation. A
missing or non-executable wrapper fails explicitly.

This hook allows an installed experimental profile to select its own DXVK
payload while retaining the user's Proton and synchronization choices. It does
not install or enable a graphics profile by itself. Per-game environment entries
must be saved in DroidDeck's app-owned settings: the app regenerates the guest
JSON and launch helper at each session start.

## Steam pipeline-cache layer conflict

A controlled hidden-window D3D9 test reproduced the game's surface failure:
with `VK_LAYER_VALVE_steam_fossilize_arm64` enabled, DXVK logged three
`VK_ERROR_SURFACE_LOST_KHR` errors; with it disabled, the identical test logged
none. Both returned a successful D3D9 `Present` result because DXVK can fall
back to GDI, so checking only that return value misses the failure.

For the experimental game profile, use these per-game environment entries:

```json
{
  "DISABLE_VK_LAYER_VALVE_steam_fossilize_1": "1",
  "ENABLE_VK_LAYER_VALVE_steam_fossilize_1": null
}
```

This leaves the Steam client's own caching settings unchanged. A separate
hidden D3D11 test passed after this change. Dark Souls subsequently reached
actual gameplay on the device without the previous surface-error loop.
This does not establish full Vulkan conformance or sustained performance.

## BattleBit: independent 32-bit mapping and syscall failures

The device exposes neither `VK_EXT_map_memory_placed` nor
`VK_EXT_external_memory_host`. The installed Wine WoW64 path rejected mapped
GPU addresses above 4 GiB, reporting `VK_ERROR_OUT_OF_HOST_MEMORY` for a 4 MiB
buffer. This was an addressability failure, not evidence of exhausted RAM.

An experimental device-backed `mmap` hook produced addresses below 4 GiB.
A standalone 32-bit Windows probe copied 4 MiB through Vulkan and verified every
word with zero mismatches. BattleBit's launcher then passed that mapping step.
The hook is still a local prototype, not part of the packaged runtime.

The launcher next stopped at a direct Linux `int 0x80` instruction in its 32-bit
EasyAntiCheat process. A separate Windows probe reproduced illegal instruction
`0xc000001d` with a benign Linux `getpid` syscall under the installed WoW64/FEX
path. This is independent of the presentation and memory-mapping repairs.
The installed Linux FEX route passed a static ELF probe exercising `getpid`,
`memfd_create` and `close`. With Steam Linux Runtime 4.0.20260805.254769 and
GE-Proton11-7, separate 32-bit Windows probes also passed `getpid`,
`memfd_create`, a three-byte write and `close`, including through the full
Proton launcher. These used isolated Wine prefixes and FEX configuration.

The graphics test exposed a separate compatibility gap:

| Execution mode | Syscall result | Graphics result |
| --- | --- | --- |
| ARM64 Wine with Windows FEX | `int 0x80` raises `0xc000001d` | Existing scoped mapping/presentation fixes work |
| x86 Wine, classic 32-bit Linux process | Windows syscall probes pass | DXVK cannot create a Vulkan instance; installed FEX has only the 64-bit Vulkan guest thunk |
| x86 Wine, `PROTON_USE_WOW64=1` | Process startup reports unsupported 32-bit syscall from a 64-bit process | Probe does not reach rendering |

FEX-2610 still builds its Vulkan guest thunk only for 64-bit guests
([upstream build definition](https://github.com/FEX-Emu/FEX/blob/FEX-2610/ThunkLibs/GuestLibs/CMakeLists.txt#L189)).
The syscall success therefore does not establish a working BattleBit route.

A subsequent static ELF64 A/B test removed Wine, Steam and Vulkan from the
reproduction. The native x64 syscall control passed; adding i386 `int 0x80`
failed with SIGSEGV after the initial x64 `getpid`. Source review at FEX-2610
commit `14c92681f4d62cf84d901460e0358de09c8847a7` found process-wide ABI
selection in syscall dispatch, separate syscall number tables, and
process-wide audit architecture selection in seccomp emulation. Correct
mixed-ABI support must address all of these. Removing only the instruction
rejection can invoke the wrong syscall, as documented by
[upstream PR 3459](https://github.com/FEX-Emu/FEX/pull/3459); its regression
case remains in FEX-2610's known failures. The local standalone reproducer,
raw results and feasibility assessment are under `linux-fex/mixed-abi/` in
the ignored evidence directory. No patched FEX build has been produced or validated.
No BattleBit runtime selection or existing game prefix was changed by these
tests, and no anti-cheat binaries were modified.

The isolated alternative uses the official GE-Proton11-7 x86 package published
2026-09-16, verified against its publisher SHA-512 and release SHA-256 on
2026-10-08. No GE-Proton lifecycle entry was found in endoflife.date, and no
formal support deadline is asserted. The custom Android/FEX arrangement is an
experiment, not an officially supported GE-Proton deployment. The installed
ARM64 Proton and existing game prefixes remain separate from this test.

The subsequent Box64 experiment uses pinned source
`6ece2e87f2c4ef9bf2f759e255d36cf4730a06e3`. The private downstream patch adds
i386 `getpid`, `memfd_create`, file I/O and `open` with native flag conversion.
CI checks native x86-64 reference behavior and ARM64 translation in JIT and
interpreter modes, including error returns and 32-bit pointer truncation.
Those regressions passed in CI, Termux and DroidDeck. The ELF fixtures use a
256 MiB link address: DroidDeck relocated their default 2 MiB image above
4 GiB, invalidating a deliberately truncated pointer.

A Windows 32-bit probe under GE-Proton11-7 Wine WoW64 passed `getpid`,
`memfd_create`, write and close. After obtaining the nested desktop's X11
environment, the hidden D3D9 probe created the PowerVR device and presented
without Vulkan surface/device-loss errors. A separate 4 MiB GPU copy completed
with zero mismatches and both mapped buffers below 4 GiB. Steam SDK testing
uses a private test home with x86 SDK links, preserving the normal native
Steam SDK configuration and existing game prefixes.

Descriptor-backed module testing exposed two independent issues. In the
actual DroidDeck UID/SELinux context, native memfds could be mapped executable
but reopening them through `/proc/self/fd` returned `EACCES`. The same small
native library loaded from disk and an unlinked private temporary file.
Box64 loaded the temporary-file ELF but initially lost its symbol handle.
The patched loader retains the library actually returned by `AddNeededLib`.
An optional, private-directory file tier handles unsealed i386 allocations;
requests involving seals or other flags keep the native implementation.
This tier does not reproduce native seal metadata for unsealed files.

Commit `553cc54` passed native, translated and forced-file-tier loader
regressions in CI, then syscall and module-load regressions inside DroidDeck.
The tests check symbol execution, descriptor offset preservation and rejection
of an unsafe fallback directory. Signed anti-cheat module bytes are unchanged.

Three bounded BattleBit startup trials rendered the EAC splash but did not
reach the menu. The initial trial exposed unsupported i386 `open`; later
trials downloaded `linux32_64` successfully with HTTP 200 and reported EAC
loader callback 507 / launcher result 206, "Failed to load the anti-cheat
module." The relationship between the game's actual loader path and the
controlled unsealed-i386 fixture remains unverified: no confirmed file-tier
activation appeared in the final game log. Further code iteration stopped at
that assumption. The next diagnostic should identify the real ABI, allocation
flags and module-loading operation before another compatibility patch.

Each game trial used a new compatibility prefix and a one-shot wrapper setting
that restored the original per-game key before launch. Final cleanup quit the
game and Steam session, verified idle/no guest PID, restored temporary
stay-awake to zero and returned to Termux. The experimental Box64 component
is not part of the installed APK or the default game configuration.

A targeted trace on 2026-10-09 identified the real EAC allocation: i386
`memfd_create` (356), flags `0x2` (`MFD_ALLOW_SEALING`), successfully returned
fd 110. The optional unsealed-file tier correctly does not handle that request.
The next captured `open` read `/proc/<own-pid>/maps` successfully. No
module-descriptor `dlopen` entry appeared in the wrapped-loader trace before
EAC callback 507 / launcher result 206; calls outside those wrappers are not
excluded. Android's separately reproduced memfd reopening denial is therefore
not established as the immediate cause of this launch failure.

A controlled probe of the same i386 maps-open path reproduced a formatting
defect in Box64's `CreateMemorymapFile`: it appends some annotations after the
existing newline, placing an executable pathname and `[stack]` on separate
lines. This is a concrete candidate for further loader-discovery diagnostics,
not a demonstrated EAC fix. Trace evidence and the read-only reproduction are
in `box64/loader-trace-20261009/` under the ignored evidence directory.

Inspection isolated the newline retained by `getline` as the formatter defect.
Downstream patch `0005` prints only the original line content before appending
the annotation, writes directly to the descriptor to accommodate long names,
and frees the input buffer. A native harness compiles the actual formatter
from the pinned source: six mappings produce eight lines before the patch
and six valid lines after it. Mapping fields and untouched rows are preserved;
guest ELF names, the guest stack, native-stack removal and names longer than
2 KiB pass. The build script runs this regression after applying all patches.
This is local formatter validation; a new full Box64 build and a BattleBit
device trial remain pending. Results are in `box64/maps-fix-20261009/`.

Evidence is retained in the development checkout under
`.local/benchmarks/powervr/{wsi-offscreen,low-map,syscall-probe,linux-fex,box64}/` and
in the device's `Download/DroidDeck/` diagnostic folders. Game screenshots and
account-bearing logs are not committed to the repository.
