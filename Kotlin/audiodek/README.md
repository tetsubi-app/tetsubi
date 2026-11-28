# audioDEK

A pure Kotlin ALAC (Apple Lossless) decoder for the JVM and Android. Kotlin standard library only:
no Android, Media3, coroutines or Compose. It is a module of the Tetsubi build (`:audiodek`); the
Media3 renderer that uses it lives in `:shared` (androidMain, `AudioDekAudioRenderer`).

Licence: not chosen yet (the owner decides; there is no LICENSE file). The decoder's structure follows
Apple's Apache-2.0 reference decoder; see `NOTICE-ALAC.md`.

## API

```kotlin
val config = AudioDekAlacConfig.parse(cookie)          // 24-byte ALACSpecificConfig, or the 36-byte form
val decoder = AudioDekAlacDecoder(config)
val pcm = IntArray(decoder.maxSamples)                  // frameLength * channels
val frames = decoder.decode(sample, 0, sample.size, pcm) // one MP4 sample -> interleaved PCM, frames decoded
```

- Supports bit depths 16, 20, 24 and 32, 1 to 8 channels (mono, stereo, 3.0, 4.0, 5.0, 5.1, 6.1, 7.1),
  mixed stereo, adaptive Golomb residuals, both predictor passes, shifted low bytes, uncompressed
  (escape) frames, partial last frames, DSE and FIL elements.
- Output: interleaved Ints, right-justified, sign-extended to the file's bit depth (20-bit audio gives 20-bit
  values), channels in ALAC's order. `AudioDekChannels.toWave` reorders to WAVE order (what Android expects).
- Corrupt, truncated or unsupported input throws `AudioDekAlacException`, nothing else. The decoder can
  be used again after an error. Not thread safe; no allocation per sample.
- Not supported: channel coupling elements (CCE) and program config elements (PCE), which ALAC files do not use.

## What is claimed: bit-exact decode

Decoding a file gives exactly the samples that were encoded. This is tested, not assumed: signals are generated
deterministically (`src/test/kotlin/.../AlacSignals.kt`: silence, full-scale square, impulses, sine, seeded white
noise, a music-like mix, correlated and uncorrelated stereo, mono; 16, 20, 24 and 32 bits; 44.1, 48 and 96 kHz; 5.1
and 7.1; a 3-sample file; lengths that are not a multiple of 4096), encoded to ALAC by Apple's own encoder
(`afconvert`) and by FFmpeg's `alac` encoder, and decoded here. Every sample is compared with the source
(`AudioDekBitExactTest`; the test names say signal, encoder and the bit depth the encoder stored).

Encoder limits, not decoder limits: FFmpeg's encoder stores 20-bit audio in 24 bits (left-justified) and 32-bit
audio as 24 bits, so its 32-bit pair is not an exact 32-bit comparison (checked as "top 24 bits", named
`..._SKIPPED_ffmpeg_stores_32bit_as_24bit`). `afconvert` writes true 20-bit and 32-bit files.

Frames built by hand cover paths the encoders rarely take (`AudioDekFrameTest`: order 0, order 31, the two-pass
mode, wraparound, mix fields, DSE/FIL, escape frames), and `AudioDekRobustnessTest` truncates every fixture frame
at many lengths, flips bytes at random and feeds garbage: only a decoded frame or `AudioDekAlacException` may come
out. `AudioDekSpeedTest` decodes 60 s of 44.1 kHz/16-bit stereo at hundreds of times real time on a laptop JVM.

## What is not claimed

"Bit-exact decode" is about this decoder's output. It is not "bit-perfect" playback end to end: what an app
does after decoding (Android's mixer and resampler, volume, an equaliser, visualiser or normalisation, Bluetooth,
a DAC) is outside it. Fuzzing beyond the seeded tests above has not been done.

## Fixtures

`src/test/resources/alac/*.m4a` (about 410 KB for both encoders) with `manifest.txt` (SHA-256 of each source
PCM, so the tests prove the generator still produces what the encoders were given). The tests do not need
ffmpeg or afconvert. To regenerate (macOS, with both installed): `tools/make-fixtures.sh`. It has the Gradle
test `AlacFixtureWavWriter` write the signals as WAV files, encodes each with both encoders (`afconvert -d alac`,
`alac/2`, `alac/3`, `alac/4` for 16, 20, 24 and 32 bit; 7.1 mapped to ALAC's own channel order with `-m`), and copies the
results here.

## Tests

`./gradlew :audiodek:test`. (`:shared:allTests` does not run them.)
