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

## Remaining integration gates

1. Run this diskless probe on the graphics kernel, then make a reversible kernel
   upgrade using Termux-Aether's existing maintenance/backup mechanism.
2. Enable the GPU configuration in the VM owner and verify `vulkaninfo --summary`
   and a rendered test with `vulkan-gfxstream` inside Arch. Reject CPU renderers.
3. Connect VM ownership, display, input, audio and session lifetime to DroidDeck;
   verify Steam and a game. AVF launch acceptance is not that validation.

## Local checks

```sh
mkdir -p "$TMPDIR/droiddeck-avf-tests"
javac --release 8 -d "$TMPDIR/droiddeck-avf-tests" tools/avf/*.java
java -cp "$TMPDIR/droiddeck-avf-tests" AvfGpuProbeTest
python -m unittest discover -s tools/avf -p 'test_*.py'
clang -std=c11 -Wall -Wextra -Werror -fsyntax-only tools/avf/probe-init.c
actionlint .github/workflows/avf-gpu-probe.yml
```

The Java helper supports both primitive and boxed Boolean setters: this phone's
installed AVF framework uses boxed setters where the Android 17 release source
uses primitives. Missing APIs fail the probe.

References: [AOSP custom VMs and Gfxstream](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/tags/android-17.0.0_r1/docs/custom_vm.md),
[Termux-Aether Arch builder](https://github.com/wallentx/termux-aether-api/tree/fe2ea2b78ce7701a0144f4532a1aed54e7e6030b/guest/arch).
