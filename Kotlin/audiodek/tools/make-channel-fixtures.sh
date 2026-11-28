#!/bin/bash
# Regenerates the audioDEK channel-order fixtures (macOS: needs afconvert and ffmpeg).
#   Kotlin/audiodek/tools/make-channel-fixtures.sh
# For each ALAC channel count 1..8: 50 ms at 48 kHz/16-bit where channel c (WAVE order) is the constant
# (c+1)*1000 plus a small sine of (c+1)*100 Hz, so every channel is recognisable. The signal is written as a
# WAV in an FFmpeg layout ALAC supports, encoded by FFmpeg's alac encoder (.ff) and by afconvert (.afc), and
# the expected output is FFmpeg DECODING each .m4a to interleaved s16le (FFmpeg's decoder order is the
# reference). Writes the .m4a files and channels-manifest.txt (name, SHA-256 of that decoded PCM, channels).
set -euo pipefail
module="$(cd "$(dirname "$0")/.." && pwd)"
out="$module/src/test/resources/alac/channels"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$out"
rm -f "$out"/*.m4a "$out"/channels-manifest.txt
layouts=(x mono stereo 3.0 4.0 5.0 5.1 "6.1(back)" "7.1(wide)")
echo "# audioDEK channel fixtures: name sha256-of-ffmpeg-decoded-s16le channels" > "$out/channels-manifest.txt"
for n in 1 2 3 4 5 6 7 8; do
  exprs=""
  for c in $(seq 1 "$n"); do exprs+="$((c * 1000))/32768+0.0152587890625*sin(2*PI*$((c * 100))*t)|"; done
  ffmpeg -v error -y -f lavfi -i "aevalsrc=exprs=${exprs%|}:s=48000:d=0.05:c=${layouts[$n]}" -c:a pcm_s16le "$scratch/src.wav"
  maps=()
  # afconvert reads the 6.1 and 7.1 WAV layouts differently from ALAC's: map the WAVE-order channels to ALAC's own order
  # (6.1: C L R Ls Rs Cs LFE; 7.1: C Lc Rc L R Ls Rs LFE).
  case "$n" in 7) maps=(-m 2 0 1 4 5 6 3) ;; 8) maps=(-m 2 6 7 0 1 4 5 3) ;; esac
  ffmpeg -v error -y -i "$scratch/src.wav" -c:a alac "$out/ch${n}_16_48k.ff.m4a"
  afconvert -d alac -f m4af ${maps[@]+"${maps[@]}"} "$scratch/src.wav" "$out/ch${n}_16_48k.afc.m4a"
  for e in ff afc; do
    sha="$(ffmpeg -v error -i "$out/ch${n}_16_48k.$e.m4a" -f s16le - | shasum -a 256 | cut -d' ' -f1)"
    echo "ch${n}_16_48k.$e $sha $n" >> "$out/channels-manifest.txt"
  done
done
du -ck "$out"/*.m4a | tail -1
