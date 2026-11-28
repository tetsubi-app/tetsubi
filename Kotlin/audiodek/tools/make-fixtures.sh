#!/bin/bash
# Regenerates the audioDEK ALAC test fixtures (macOS: needs afconvert and ffmpeg).
#   Kotlin/audiodek/tools/make-fixtures.sh
# 1. the Gradle test AlacFixtureWavWriter writes every signal in AlacSignals.kt as a WAV
#    (+ manifest.txt with the SHA-256 of each source PCM) into a scratch directory;
# 2. each WAV is encoded to ALAC by Apple's afconvert (".afc") and by FFmpeg's alac
#    encoder (".ff");
# 3. the .m4a files and the manifest are copied to the test resources.
# The tests never need the encoders; this script only runs when signals change.
set -euo pipefail
module="$(cd "$(dirname "$0")/.." && pwd)"
out="$module/src/test/resources/alac"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
(cd "$module/.." && ALAC_FIXTURE_DIR="$scratch/wav" ./gradlew :audiodek:test --rerun --tests '*AlacFixtureWavWriter*' -q)
mkdir -p "$out"
rm -f "$out"/*.m4a
for wav in "$scratch"/wav/*.wav; do
  name="$(basename "$wav" .wav)"
  bits="$(awk -v n="$name" '$1==n {print $3}' "$scratch/wav/manifest.txt")"
  case "$bits" in 16) flag=alac ;; 20) flag=alac/2 ;; 24) flag=alac/3 ;; 32) flag=alac/4 ;; esac
  maps=()
  # afconvert reads the 7.1 WAV's back/front-centre channels differently from ALAC's
  # layout; map them to ALAC's own order (C Lc Rc L R Ls Rs LFE) explicitly.
  case "$name" in *_71_*) maps=(-m 2 6 7 0 1 4 5 3) ;; esac
  afconvert -d "$flag" -f m4af ${maps[@]+"${maps[@]}"} "$wav" "$out/$name.afc.m4a"
  ffmpeg -v error -y -i "$wav" -c:a alac "$out/$name.ff.m4a"
done
cp "$scratch/wav/manifest.txt" "$out/manifest.txt"
du -ck "$out"/*.m4a | tail -1
