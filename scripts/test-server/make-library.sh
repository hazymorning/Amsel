#!/usr/bin/env bash
# Synthesised with ffmpeg (sine tones, solid-colour covers), so the library holds no third-party
# copyright; the bitexact flags keep the output the same for a given ffmpeg version.
# Without ffmpeg on the PATH, FFMPEG_IMAGE runs it: the server image start.sh pulled has one, so CI
# installs nothing.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:-$HERE/build/test-library}"

rm -rf "$OUT"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

if command -v ffmpeg >/dev/null; then
  FF=(ffmpeg)
elif [[ -n "${FFMPEG_IMAGE:-}" ]]; then
  FF=(docker run --rm -u "$(id -u):$(id -g)" -v "$OUT:$OUT" --entrypoint ffmpeg "$FFMPEG_IMAGE")
else
  echo "ffmpeg is required: install it, or name a Docker image that has it in FFMPEG_IMAGE" >&2
  exit 1
fi
FF+=(-hide_banner -loglevel error -nostdin -y)
BITEXACT=(-fflags +bitexact -flags:a +bitexact)

cover() {
  "${FF[@]}" -f lavfi -i "color=c=0x$2:s=600x600:d=1" -frames:v 1 -q:v 4 \
    -fflags +bitexact -flags:v +bitexact "$1"
}

tone() {
  echo "sine=frequency=$1:sample_rate=22050:duration=$2"
}

flac_track() {
  local dir=$1 file=$2 freq=$3 secs=$4 title=$5 artist=$6 aa=$7 album=$8 n=$9 total=${10} date=${11} genre=${12}
  "${FF[@]}" -f lavfi -i "$(tone "$freq" "$secs")" -af "volume=0.3" -ac 1 -ar 22050 \
    -sample_fmt s16 -c:a flac -compression_level 8 "${BITEXACT[@]}" \
    -metadata TITLE="$title" -metadata ARTIST="$artist" -metadata ALBUMARTIST="$aa" \
    -metadata ALBUM="$album" -metadata TRACKNUMBER="$n" -metadata TRACKTOTAL="$total" \
    -metadata DISCNUMBER=1 -metadata DATE="$date" -metadata GENRE="$genre" \
    "$dir/$file"
}

mp3_track() {
  local dir=$1 file=$2 freq=$3 secs=$4 title=$5 artist=$6 aa=$7 album=$8 n=$9 total=${10} date=${11} genre=${12} art=${13}
  "${FF[@]}" -f lavfi -i "$(tone "$freq" "$secs")" -i "$art" \
    -map 0:a -map 1:v -af "volume=0.3" -ac 1 -ar 22050 -c:a libmp3lame -b:a 48k \
    -c:v copy -disposition:v attached_pic -id3v2_version 3 "${BITEXACT[@]}" \
    -metadata:s:v title="Album cover" -metadata:s:v comment="Cover (front)" \
    -metadata title="$title" -metadata artist="$artist" -metadata album_artist="$aa" \
    -metadata album="$album" -metadata track="$n/$total" -metadata disc="1/1" \
    -metadata date="$date" -metadata genre="$genre" \
    "$dir/$file"
}

ogg_track() {
  local dir=$1 file=$2 freq=$3 secs=$4 title=$5 artist=$6 aa=$7 album=$8 n=$9 total=${10} date=${11} genre=${12}
  "${FF[@]}" -f lavfi -i "$(tone "$freq" "$secs")" -af "volume=0.3" -ac 1 -ar 22050 \
    -c:a libvorbis -q:a 0 "${BITEXACT[@]}" \
    -metadata TITLE="$title" -metadata ARTIST="$artist" -metadata ALBUMARTIST="$aa" \
    -metadata ALBUM="$album" -metadata TRACKNUMBER="$n" -metadata TRACKTOTAL="$total" \
    -metadata DISCNUMBER=1 -metadata DATE="$date" -metadata GENRE="$genre" \
    "$dir/$file"
}

A="Aurora Tone"
mkdir -p "$OUT/$A"
cover "$OUT/$A/artist.jpg" "F2A65A"

D="$OUT/$A/First Light (2021)"
mkdir -p "$D"
cover "$D/folder.jpg" "E07A5F"
flac_track "$D" "01 - Dawn Chorus.flac"  440 12 "Dawn Chorus"  "$A" "$A" "First Light" 1 3 2021 Ambient
flac_track "$D" "02 - Morning Haze.flac" 494 15 "Morning Haze" "$A" "$A" "First Light" 2 3 2021 Ambient
flac_track "$D" "03 - Sunrise.flac"      523 18 "Sunrise"      "$A" "$A" "First Light" 3 3 2021 Ambient

D="$OUT/$A/Second Wind (2023)"
mkdir -p "$D"
cover "$OUT/.second-wind.jpg" "3D405B"
mp3_track "$D" "01 - Tailwind.mp3"    587 14 "Tailwind"    "$A" "$A" "Second Wind" 1 3 2023 Electronic "$OUT/.second-wind.jpg"
mp3_track "$D" "02 - Updraft.mp3"     659 16 "Updraft"     "$A" "$A" "Second Wind" 2 3 2023 Electronic "$OUT/.second-wind.jpg"
mp3_track "$D" "03 - Still Air.mp3"   698 20 "Still Air"   "$A" "$A" "Second Wind" 3 3 2023 Electronic "$OUT/.second-wind.jpg"
rm -f "$OUT/.second-wind.jpg"

B="Basso Continuo"
mkdir -p "$OUT/$B"
cover "$OUT/$B/artist.jpg" "81B29A"
D="$OUT/$B/Low End (2022)"
mkdir -p "$D"
cover "$D/folder.jpg" "2A9D8F"
ogg_track "$D" "01 - Ground Bass.ogg"  110 13 "Ground Bass"  "$B" "$B" "Low End" 1 3 2022 Classical
ogg_track "$D" "02 - Pedal Point.ogg"  131 17 "Pedal Point"  "$B" "$B" "Low End" 2 3 2022 Classical
ogg_track "$D" "03 - Drone Lullaby.ogg" 147 19 "Drone Lullaby" "$B" "$B" "Low End" 3 3 2022 Classical

C="Cirrus"
mkdir -p "$OUT/$C"
cover "$OUT/$C/artist.jpg" "F4F1DE"
D="$OUT/$C/High Clouds (2024)"
mkdir -p "$D"
cover "$OUT/.high-clouds.jpg" "8ECAE6"
mp3_track "$D" "01 - Altitude.mp3"      784 12 "Altitude"      "$C"                 "$C" "High Clouds" 1 3 2024 Pop "$OUT/.high-clouds.jpg"
mp3_track "$D" "02 - Jet Stream.mp3"    880 15 "Jet Stream"    "$C feat. $A"        "$C" "High Clouds" 2 3 2024 Pop "$OUT/.high-clouds.jpg"
mp3_track "$D" "03 - Thin Air.mp3"      988 18 "Thin Air"      "$C"                 "$C" "High Clouds" 3 3 2024 Pop "$OUT/.high-clouds.jpg"
rm -f "$OUT/.high-clouds.jpg"

mkdir -p "$OUT/Playlists"
cat > "$OUT/Playlists/Amsel Mix.m3u" <<'EOF'
#EXTM3U
#EXTINF:12,Aurora Tone - Dawn Chorus
../Aurora Tone/First Light (2021)/01 - Dawn Chorus.flac
#EXTINF:13,Basso Continuo - Ground Bass
../Basso Continuo/Low End (2022)/01 - Ground Bass.ogg
#EXTINF:15,Cirrus feat. Aurora Tone - Jet Stream
../Cirrus/High Clouds (2024)/02 - Jet Stream.mp3
#EXTINF:16,Aurora Tone - Updraft
../Aurora Tone/Second Wind (2023)/02 - Updraft.mp3
#EXTINF:18,Cirrus - Thin Air
../Cirrus/High Clouds (2024)/03 - Thin Air.mp3
EOF

cat > "$OUT/LICENSE.txt" <<'EOF'
All audio and images in this folder were synthesised by make-library.sh with ffmpeg
(sine tones and solid colours). They are dedicated to the public domain (CC0 1.0).
EOF

echo "Library written to $OUT ($(du -sh "$OUT" | cut -f1), $(find "$OUT" -type f \( -name '*.flac' -o -name '*.mp3' -o -name '*.ogg' \) | wc -l) tracks)" >&2
