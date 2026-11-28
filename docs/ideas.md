# Ideas and parked work

Things decided to leave for later, with enough context to pick them up. Move an item to `docs/gotchas.md` or `docs/redesign.md` when it is built.

## Local and remote server addresses

**Idea:** let a server have two addresses, a local one (LAN, e.g. `http://<host>:8096`) and a remote one (the public name, here behind a Cloudflare Tunnel), and use the local one automatically whenever it is reachable, falling back to the remote one.

**Why:** streaming at home then never goes out through the proxy and back (the hairpin), which is faster and avoids anything the proxy does to long responses. A Cloudflare Tunnel was resetting HTTP/2 streams of live-converted ALAC mid-track; forcing HTTP/1.1 for converted streams fixed it (see `docs/gotchas.md`, "Converted streams use HTTP/1.1"), but the LAN path was clean from the start.

**Sketch:** a second address field in sign-in and Settings > Server on every platform (Swift and Kotlin); on launch and on network changes probe the local address (short timeout, same server id) and prefer it; keep the logged-in session and device id the same for both addresses; show which one is in use in Settings. Needs care with: the first request after joining Wi-Fi, a VPN (Tailscale) that carries the LAN, `NSAllowsArbitraryLoads`-style plain HTTP on iOS, and Cast (the receiver needs an address it can reach itself).

**Status:** parked on 2026-11-28; not started.

## Qualcomm ALAC decoder on Android

**Idea:** play ALAC directly on devices that list a hardware or software ALAC decoder. On the POCO X8 (`c2.qti.alac.sw.decoder`, `c2.qti.alac.hw.decoder`) the decoder fails at start with `ERROR_CODE_DECODING_FAILED` and the app falls back to the server's FLAC conversion and remembers the failure per build (see `docs/gotchas.md`, "Direct ALAC is best effort").

**What was found (2026-11-28, POCO X8, Android 16, driven over adb with the `decoder input format:` and `codec exception:` log lines):**
- Media3 hands the decoder a valid 24-byte ALAC cookie as `csd-0` (4096-sample frames, 16-bit, 2 channels, 44.1 kHz, about 1 Mb/s, max frame 13927 bytes), mime `audio/alac`, 2 channels, 44100 Hz, PCM 16-bit.
- The decoder configures and starts, then `MediaCodec.queueInputBuffer` throws an empty `CodecException` on the first buffer: `errorCode=1`, `isRecoverable=false`, `isTransient=false`, `diagnosticInfo=android.media.MediaCodec.error_1`. Nothing is logged by the codec service.
- Tried, no change: wrapping the cookie in the 36-byte `alac` box (size, `alac`, version/flags, 24 bytes) through a custom `MediaCodecAudioRenderer.getMediaCodecConfiguration`; putting the hardware decoder (`c2.qti.alac.hw.decoder`) before the software one with a custom `MediaCodecSelector`. Both fail identically, so it is not the cookie layout or one broken decoder. Decoder fallback cannot help: the failure is on the first input buffer, after initialisation.
- Not tried: other `MediaFormat` keys (`max-input-size`, `pcm-encoding`, bits per sample), the platform's own `MediaExtractor` + `MediaCodec` on the same file for comparison, a vendor-specific sample framing.

**Options that were considered:** (1) Media3's `decoder_ffmpeg` extension with only the ALAC decoder (needs the NDK and building FFmpeg; adds a few MB; LGPL); (2) a pure Kotlin ALAC decoder as a Media3 `DecoderAudioRenderer` (Apple's reference decoder is Apache 2.0; testable with files made by ffmpeg and afconvert); (3) keep the server's FLAC conversion.

**Status: resolved by the built-in decoder (2026-11).** Option 2 was built as audioDEK (`Kotlin/audiodek`, wrapped for Media3 in `:shared` androidMain), so ALAC is decoded in the app on every device and plays as the original file; neither the Qualcomm decoder nor the MediaTek tablet's missing one matters any more. The findings above stay as the record of why. See `docs/gotchas.md`, "ALAC is decoded by the app itself". Still to confirm on the POCO X8 and the Xiaomi Pad Mini: playback, seeking, gapless joins, and the `alac decode:` log line.

## Publishing audioDEK as its own open-source library (parked option)

**Why:** Android has no dependable ALAC decoder (the MediaTek tablet has none, the Qualcomm one fails on the first buffer), and audioDEK is a pure Kotlin decoder that needs only the Kotlin standard library, with bit-exact decode tests against Apple's `afconvert` and FFmpeg's `alac` encoder. Other Android and JVM players have the same hole.

**Staging:** (1) prove it on real devices first (POCO X8, Xiaomi Pad Mini: playback, seeking, gapless, 24-bit, battery and speed on slow CPUs); (2) split into its own repo with the decoder module (`:audiodek`), the Media3 renderer (`AudioDekAudioRenderer` and `AudioDekSimpleDecoder`) as a second, optional module, the fixtures and their generator script, the README, and CI that runs the tests (the fixtures mean no encoder is needed); (3) publish to Maven Central or JitPack.

**Open decisions for the owner:** the licence (and keeping Apple's Apache-2.0 attribution, `Kotlin/audiodek/NOTICE-ALAC.md`); a contribution policy; a check that the name audioDEK doesn't clash with anything; fuzz testing beyond the seeded corruption tests (a coverage-guided fuzzer); how to take vulnerability reports.

**Why extraction is cheap:** the module boundary is already there. `:audiodek` has no Android, Media3, coroutines or Compose dependency, a small public API (`AudioDekAlacConfig`, `AudioDekAlacDecoder`, `AudioDekAlacException`, `AudioDekChannels`), its own tests, fixtures, README and notice, and the app depends on it only through `:shared` androidMain.
