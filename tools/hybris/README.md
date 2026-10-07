# PowerVR through the existing ARLinux libhybris wrapper

An offscreen Vulkan draw passed on Pixel 11 Pro XL / Tensor G6 / PowerVR
C-Series CXTP-48-1536 MC1 on 2026-10-07, without AVF. This is a tested reuse
candidate; DroidDeck's launch and window presentation paths are not integrated.

## Inputs

- [ARLinux's upstream reuse report](https://github.com/Droid-Deck/DroidDeck/issues/165#issuecomment-6014766410).
- [libhybris source](https://github.com/taowen/libhybris/tree/bc8cb3586b79d208fd21b925fa0209b3e432e008),
  commit `bc8cb3586b79d208fd21b925fa0209b3e432e008` on `arlinux`.
- [Arch runtime release v0.1.3](https://github.com/taowen/arlinux-arch/releases/tag/v0.1.3),
  `arch.zip` SHA-256
  `a2dfcc565a2bbdfdf0743e279c9fd85bb40aee44ca39a8d461c3c72f93135f37`.
  Its generic GPU overlay supplied the Android ABI bridge. Only the five-file
  Vulkan ICD was rebuilt from the source revision above, with Wayland disabled.
- The standalone [triangle probe](render-probe.c), originally built by CI at
  DroidDeck commit `10ab01bc89e2668c132ed35cce1b1274cd931910`. Its runtime library search
  path was adjusted to select the isolated Linux loader and hybris libraries.

## Build the standalone probe

From the repository root:

```sh
python3 tools/hybris/build_render_probe.py "$TMPDIR/droiddeck-hybris-render"
```

The builder uses the installed Termux glibc toolchain, or an AArch64 Linux cross
compiler on Linux. It compiles only the small test executable and two shaders.
Select the isolated hybris ICD with `VK_DRIVER_FILES` when running it:

```sh
render-probe 0x1010 triangle.ppm
```

This directory has no dependency on the AVF kernel, staging tools or workflow.
Those experiments remain on the `wallentx/powervr-avf` branch.

## Compatibility finding

The PowerVR HAL opens successfully and supplies the expected device tag and
all three required Vulkan entry points. It reports device version `0x00000001`,
the `HARDWARE_DEVICE_API_VERSION(0, 1)` encoding. Upstream hybris accepts only
`HWVULKAN_DEVICE_API_VERSION_0_1`, encoded as `0x00010000`.

[powervr-hal-version.patch](powervr-hal-version.patch) accepts both encodings
while retaining the other device validation. Android's own
[Vulkan loader](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android17-release/vulkan/libvulkan/driver.cpp)
does not enforce that exact device-version comparison.

Two otherwise identical offscreen ICD builds produced:

| Check | Unchanged upstream | With version patch |
| --- | --- | --- |
| HAL metadata accepted | No | Yes |
| Vulkan instance and PowerVR device | No | Yes |
| Graphics pipeline and draw | Not reached | Passed |
| Fence and image readback | Not reached | Passed |
| Pixels | No image | 1,152 red; 2,944 blue; zero others |

The image was also counted independently of the C probe. The test ran through
Termux's command service and Aether's glibc launcher, UID 10445, SELinux domain
`runas_app`. It does not establish behavior under DroidDeck's own app identity.
The compatibility layer was disabled: BC emulation and games remain untested.

## Presentation integration still needed

ARLinux's existing `android_wlegl` protocol exchanges Android native handles
and server-allocated buffers. DroidDeck already handles Android Hardware
Buffers, but currently advertises its different `banner_ahb_v1` protocol over
its DMA-BUF buffer path. Those protocols are not interchangeable.

The next integration should adapt the existing buffer protocol and reuse
DroidDeck's compositor, input and lifecycle handling. It should not enable a
PowerVR support claim until a real client window presents and releases buffers
correctly. No new VM transport or GPU driver is required by this offscreen result.

## Local evidence

The test checkout keeps the release archive, source, build commands, patch,
HAL metadata, before/after logs, image, result JSON and rerun instructions under
the ignored `.local/benchmarks/powervr/hybris/` directory. No device libraries,
normal Arch kernel, or DroidDeck runtime were replaced.
