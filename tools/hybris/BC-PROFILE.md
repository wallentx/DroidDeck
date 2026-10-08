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
| Xwayland presentation with new WSI layer | Pending |
| Dark Souls with patched DXVK and BC profile | Pending |

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
