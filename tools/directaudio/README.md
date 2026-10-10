# DirectAudio game-unixlib component

`tools/directaudio/patches/` carries DroidDeck's small downstream change to the
pinned DirectAudio source revision in `source.env`. The component workflow builds
only the game-side Unixlib against the two private Wine `mmdevapi` tables that
DroidDeck supports:

- `linux-wine11` for Valve Proton 11, Proton Experimental, and GE-Proton 11.
- `linux-wine11-systhread` for Proton-CachyOS's system-thread table.

The release still contains all four archive names consumed by `fetch.sh`.
`directaudio-linux-relay.zip` and `directaudio-linux-sink.zip` are checksum-
verified, byte-for-byte copies of upstream `directaudio-linux-v1.1.0`, because
this optimization does not modify Android's relay or PulseAudio sinks.

Run the workflow manually with a nonempty `tag` to publish a component release.
Pushes only build downloadable CI artifacts. After publishing, copy the four
SHA-256 values in `SHA256SUMS.txt` to `release.env` as one atomic pin update.


## Mixer change and validation

The downstream patch specializes 48 kHz packed stereo F32 and signed 16-bit PCM.
It splits a wrapped input ring into contiguous spans and moves format, channel,
and gain selection outside the per-frame loop. AArch64 uses baseline NEON;
other targets retain a portable scalar implementation. Other formats, channel
layouts, resampling, and the limiter keep their original paths.

The regression runner extracts the actual production mixer from `directaudio.c`
and compares it with the pinned original mixer. It checks exact finite sample
bits, signed zero, infinities, NaN classification, channel gains, ring wrapping,
1-3-frame vector tails, underruns, fallback formats, and resampling. CI runs the
normal and forced-scalar variants plus mandatory ASan/UBSan on x86_64 and AArch64.

For a source checkout at `DIRECTAUDIO_SOURCE_REVISION`, apply the patch and run:

```sh
git apply /absolute/path/to/DroidDeck/tools/directaudio/patches/0001-stereo-mixer-neon.patch
sh tests/run-mixer-semantics.sh
sh tools/bench-directaudio-mixer.sh --randomized-lanes --seed 7 \
  --frames 48 --voices 8 --iterations 10000 --trials 21 --warmup 1000 \
  --format f32 --gain on --cpu 6 --json
```

Choose an available CPU for the last command. Full Wine builds belong in the
component workflow; the host-side tests and benchmark compile only the mixer.

## Tensor G6 measurements (2026-10-10)

On the Pixel 11 Pro XL, the optimized mixer was **4.80-18.11x faster than the
original generic mixer** across 72 cases (median case speedup 8.43x). Comparing
with the specialized forced-scalar implementation isolates NEON's additional
benefit: **1.80-3.99x**, with a median case speedup of 2.52x.

Representative medians below use 48-frame blocks, eight voices, and channel
gains enabled. Times include buffer clearing, ring/accounting updates, and
uncontended mutex calls.

| CPU | Samples | Original (us/block) | Specialized scalar (us/block) | NEON (us/block) | Original / NEON |
| --- | --- | ---: | ---: | ---: | ---: |
| 0 | F32 | 12.446 | 4.592 | 1.776 | 7.01x |
| 0 | S16 | 15.213 | 5.139 | 2.277 | 6.68x |
| 2 | F32 | 9.167 | 4.229 | 1.736 | 5.28x |
| 2 | S16 | 18.712 | 5.151 | 2.306 | 8.11x |
| 6 | F32 | 4.712 | 1.356 | 0.717 | 6.57x |
| 6 | S16 | 6.145 | 1.515 | 0.756 | 8.13x |

At 48 kHz, 48-frame blocks occur 1,000 times per second. The CPU 6 F32 example
therefore saves approximately 4.0 ms of CPU time per second for eight voices.
This is an isolated native mixer measurement; game FPS and complete Wine audio
latency were not measured.

Method: Clang 21.1.8 at `-O2`, affinity to CPUs 0/2/6, F32/S16, 48/480 frames,
1/8/64 voices, gains on/off, and a 1,021-frame ring. The baseline retains the
original dynamic format/channel dispatch and modulo addressing. Each case has
21 trials per implementation, with seeded randomized case and implementation
order (4,536 trial records total). Iteration counts target at least 1.5 ms of
NEON work per trial, bounded to 100-100,000 iterations; frequency scaling remains
under Android's control. These are native Bionic host tests of the extracted
mixer, not timings of the GCC-built glibc Wine driver. Only the forced-scalar
control disables vectorization. Raw commands, compiler/binary hashes, thermal
snapshots, and JSON/CSV results are retained locally under
`.local/benchmarks/powervr/directaudio-simd-20261010/measurements/`.
