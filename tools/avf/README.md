# PowerVR through AVF: prerequisite probe

This is the first validation gate for an AVF session backend. **DroidDeck does
not yet run Steam through AVF.** The existing Adreno runtime remains the only
integrated session backend.

The intended path is:

```text
Arch ARM64 guest -> Mesa Gfxstream -> virtio-gpu -> AVF Gfxstream -> Android PowerVR
```

Use the existing Termux-Aether Arch image build rather than introduce another
distribution. The guest's current kernel disables DRM, so it needs a separate
graphics kernel before Linux can see a virtual GPU. `vulkan-gfxstream` is
available in the Arch Linux ARM package repository; a native PowerVR Mesa driver
inside the guest is not the driver for this path.

## Verified on the development phone

On 2026-10-06, Pixel 11 Pro XL / Tensor G6 / Android 17 build
`DP11.260918.005` reported:

- PowerVR C-Series CXTP-48-1536 MC1, Vulkan 1.4.344, driver 26.1@6967606.
- AVF with non-protected VMs; Terminal's `gfxstream_supported` overlay is true.
- The Android driver successfully created a Vulkan device with DroidDeck's five
  required DMA-BUF/display extensions. RGBA8 and BGRA8 expose linear modifiers.
- A disposable, diskless VM accepted Gfxstream and started. Its old headless
  kernel reached the expected missing-root-filesystem failure. This establishes
  host configuration acceptance, not guest GPU rendering.
- The existing Arch guest boots through `æ`, using `6.18.52-termux-avf`, with
  `CONFIG_DRM` disabled. Reconnecting Termux:API to Shizuku restored access;
  the launcher's “update” message had hidden `binder_not_connected`.

The stock driver reports no BC texture compression. Game compatibility still
depends on the capabilities exposed by Gfxstream and the guest driver.

### Device prerequisite result

The diskless device gate **passed on 2026-10-07 UTC**, using source commit
`4a68a99a9d2fadb4c8a73e84686e5921f5b14fc0` from the
[successful CI run](https://github.com/wallentx/DroidDeck/actions/runs/37554424992).

| Check | Device result |
| --- | --- |
| DRM driver | `virtio_gpu` with a render node |
| Capsets | `0x208`: IDs 3 and 9 |
| Blob resources / context initialization | Both enabled |
| Gfxstream Vulkan context creation | Passed |
| Probe lifetime | Exited; no probe VM remained |

The existing Arch VM remained cleanly stopped with zero sessions. Its kernel
and disk were not replaced. This result verifies the GPU transport prerequisites.

### Vulkan rendering result

On 2026-10-07 UTC, the existing Arch guest booted with the separate graphics
kernel and Termux-Aether API commit `e1b6c2ec4896dc40ff6c6dc3f971d9b7fdc7c41f`.
With `vulkan-gfxstream 26.2.4` selected explicitly, Vulkan reported
`Virtio-GPU GFXStream (PowerVR C-Series CXTP-48-1536 MC1)`, vendor `0x1010`,
device `0x70061042`, and Vulkan 1.4.0.

The offscreen probe created a graphics pipeline, submitted a real triangle draw,
waited for its fence, copied the image into host-visible memory and validated
all pixels. A separate host-side check of the downloaded image agreed:

| Pixel class | Count |
| --- | ---: |
| Red triangle | 1152 |
| Blue background | 2944 |
| Unexpected | 0 |

This verifies rendering and readback through AVF/Gfxstream on this phone.
**DroidDeck's on-screen VM session and Steam are not yet integrated or tested.**

## Build and run

1. Run **AVF GPU prerequisite probe** in GitHub Actions. It uses the pinned
   Termux-Aether kernel builder and `kernel.config` overlay, checks required
   built-in drivers, and boots a diskless QEMU test. Kernel builds stay in CI.
2. Download `avf-gpu-probe-<commit>` from that successful trusted workflow run.
   Extract into private Termux storage. Record the full workflow source commit.
3. With Shizuku running and `rish` authorized, run:

   ```sh
   python tools/avf/probe.py run /private/path/to/artifact \
     --commit FULL_40_CHARACTER_COMMIT \
     --output .local/benchmarks/avf-gpu
   ```

The runner verifies source provenance, checksums and kernel configuration, then
transfers and rechecks files in a new private shell staging directory. It runs
a 512 MiB VM with no disks, shares or networking. It neither starts nor stops
the existing Arch VM and never opens its writable image. Evidence and staging
files are retained. Checksums detect corruption; obtain the artifact from the
trusted repository's successful run, since checksums alone do not authenticate it.

`passed` means a virtio-gpu render node, Gfxstream Vulkan capset, blob resources
and Gfxstream context creation were verified on the phone. The guest powers off
before success. QEMU's 2D test cannot satisfy that device gate.

## Stage the additional graphics kernel

After a passing device run, close Arch's sessions and verify it has stopped
cleanly. Artifacts built with the staging helper can then add the separate
`Image-gfxstream` slot:

```sh
python tools/avf/probe.py stage-kernel /private/path/to/artifact \
  --commit FULL_40_CHARACTER_COMMIT \
  --probe-result .local/benchmarks/avf-gpu/result.json
```

This requires a passing diskless report for that exact artifact. It checks the
device helper's checksum, takes the VM owner's existing POSIX lock without
creating a new lock file, and validates private ownership, permissions, ARM64
Image header and SHA-256. The copy is synced, verified and atomically published
as `Image-gfxstream`. An existing slot is accepted only when its checksum is
identical. The normal `Image` and writable guest disk are never opened for
replacement. Staging does not change the default launch mode or start a VM.

The helper reuses the pinned Termux-Aether kernel-maintenance primitives. The
API's explicit `graphics_mode=gfxstream` launch selects this separate slot;
ordinary launches continue to select `Image`.

## Remaining integration gates

1. Stage the additional graphics kernel after a passing diskless device gate.
2. Enable the GPU configuration in the VM owner and verify rendering with the
   Gfxstream ICD. This gate passed on the development Pixel as recorded above.
3. Connect VM ownership, display, input, audio and session lifetime to DroidDeck;
   verify Steam and a game. AVF launch acceptance is not that validation.

## Local checks

```sh
mkdir -p "$TMPDIR/droiddeck-avf-tests"
# Point this at the pinned Termux-Aether API source used by the workflow.
aether_source=/path/to/termux-aether-api
javac --release 8 -d "$TMPDIR/droiddeck-avf-tests" tools/avf/*.java \
  "$aether_source/guest/arch/kernel-upgrade/KernelUpgrade.java"
java -cp "$TMPDIR/droiddeck-avf-tests" AvfGpuProbeTest
java -cp "$TMPDIR/droiddeck-avf-tests" StageGraphicsKernelTest
python -m unittest discover -s tools/avf -p 'test_*.py'
clang -std=c11 -Wall -Wextra -Werror -fsyntax-only tools/avf/probe-init.c
actionlint .github/workflows/avf-gpu-probe.yml
```

The Java helper supports both primitive and boxed Boolean setters: this phone's
installed AVF framework uses boxed setters. Missing APIs fail the probe.

## Offscreen render probe

CI publishes a separate `avf-render-probe-<commit>` artifact with an ARM64 glibc
executable and checksums. It can also be built with the existing Termux GNU libc
development files, Clang, Vulkan headers and `glslangValidator`:

```sh
python tools/avf/build_render_probe.py "$TMPDIR/droiddeck-render"
```

Copy the executable into the graphics-enabled guest and run it with a bounded
timeout and the expected hardware vendor ID:

```sh
XDG_RUNTIME_DIR=/run/user/0 \
VK_DRIVER_FILES=/usr/share/vulkan/icd.d/gfxstream_vk_icd.json \
  timeout 40 ./render-probe 0x1010 triangle.ppm
```

The runtime directory must exist and belong to the guest user. Exit 0 plus
`status=passed` means the expected non-CPU device rendered the triangle and its
pixels passed validation. The PPM preserves the actual readback, including on
a pixel-validation failure. This is a correctness test, not a benchmark or a
window-system presentation test.

References: [AOSP custom VMs and Gfxstream](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/tags/android-17.0.0_r1/docs/custom_vm.md),
[Termux-Aether Arch builder](https://github.com/wallentx/termux-aether-api/tree/fe2ea2b78ce7701a0144f4532a1aed54e7e6030b/guest/arch).
