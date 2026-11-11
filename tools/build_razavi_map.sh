#!/usr/bin/env bash
set -euo pipefail

mkdir -p "$HOME/.openstreetmap/osmosis/plugins" app/src/main/assets/maps tools/osmosis
curl -fL --retry 3 --retry-delay 2 \
  https://github.com/openstreetmap/osmosis/releases/download/0.49.2/osmosis-0.49.2.zip \
  -o tools/osmosis.zip
unzip -q -o tools/osmosis.zip -d tools/osmosis

curl -fL --retry 3 --retry-delay 2 \
  https://github.com/mapsforge/mapsforge/releases/download/0.27.0/mapsforge-map-writer-0.27.0-jar-with-dependencies.jar \
  -o "$HOME/.openstreetmap/osmosis/plugins/mapsforge-map-writer.jar"

curl -fL --retry 3 --retry-delay 2 \
  https://download.geofabrik.de/asia/iran-latest.osm.pbf \
  -o tools/iran-latest.osm.pbf

OSMOSIS_BIN="$(find tools/osmosis -type f -path '*/bin/osmosis' | head -n 1)"
if [[ -z "$OSMOSIS_BIN" ]]; then
  echo "Osmosis executable not found" >&2
  exit 1
fi
chmod +x "$OSMOSIS_BIN"
export JAVACMD_OPTIONS="-Xmx5G"

# Prefer the actual OSM province boundary. If the public API is unavailable,
# use the published Razavi Khorasan bounding box as a safe fallback.
if curl -fL --retry 2 --max-time 60 \
  -A "POYARA-CRM offline map builder" \
  https://api.openstreetmap.org/api/0.6/relation/537657/full \
  -o tools/razavi-boundary.osm; then
  if ! python3 tools/create_razavi_poly.py tools/razavi-boundary.osm tools/razavi.poly; then
    echo "Province polygon assembly failed; using the documented Razavi bounding box."
    rm -f tools/razavi.poly
  fi
fi

OUT="app/src/main/assets/maps/razavi.map"
if [[ -s tools/razavi.poly ]]; then
  "$OSMOSIS_BIN" --rbf file=tools/iran-latest.osm.pbf \
    --bp file=tools/razavi.poly \
    --mw file="$OUT" type=hd threads=2 \
    map-start-position=36.2972,59.6067 map-start-zoom=12 \
    preferred-languages=fa,en
else
  "$OSMOSIS_BIN" --rbf file=tools/iran-latest.osm.pbf \
    --mw file="$OUT" type=hd threads=2 \
    bbox=33.857637,56.229356,37.70523,61.283935 \
    map-start-position=36.2972,59.6067 map-start-zoom=12 \
    preferred-languages=fa,en
fi

test -s "$OUT"
echo "Razavi Khorasan offline map generated: $(du -h "$OUT" | cut -f1)"
