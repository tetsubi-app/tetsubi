# Third-party notice: Apple Lossless Audio Codec (ALAC)

audioDEK's decoder (`src/main/kotlin/gg/dekiru/audiodek/AudioDekAlacDecoder.kt`) is a Kotlin
implementation of the ALAC format whose structure follows Apple's open-source ALAC reference
decoder: the element parsing (SCE, CPE, LFE, DSE, FIL, END), the adaptive Golomb (rice)
residual decoding, the two-pass adaptive FIR predictor, and the stereo un-mixing.

- Source: the Apple Lossless Audio Codec project (https://github.com/macosforge/alac)
- Copyright (c) 2011 Apple Inc.
- Licence: Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0)

The code is not a line-by-line port of that source; it is written for the JVM (integer arrays,
no per-sample allocation) and checked against its output by decoding files made by two
independent encoders, Apple's `afconvert` and FFmpeg's `alac` encoder (see README.md).
Apple's text is not reproduced here; follow the link above for the licence terms.

This notice is the attribution Apache-2.0 asks for. The app lists it in Settings > About on
Android: "audioDEK: Kotlin ALAC decoder, structure follows Apple's ALAC reference decoder
(Apache-2.0)."

The choice of licence for audioDEK itself has not been made; this module has no LICENSE file.
