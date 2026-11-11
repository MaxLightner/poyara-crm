#!/usr/bin/env python3
"""Convert the OSM relation's outer ways to Osmosis .poly format."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

source, destination = map(Path, sys.argv[1:3])
root = ET.parse(source).getroot()
relation = next((item for item in root.findall("relation")
                 if item.attrib.get("id") == "537657"), None)
if relation is None:
    raise SystemExit("Razavi Khorasan OSM relation 537657 not found")

nodes = {
    node.attrib["id"]: (float(node.attrib["lon"]), float(node.attrib["lat"]))
    for node in root.findall("node")
}
ways = {
    way.attrib["id"]: [nd.attrib["ref"] for nd in way.findall("nd")]
    for way in root.findall("way")
}
outer_ids = [
    member.attrib["ref"] for member in relation.findall("member")
    if member.attrib.get("type") == "way" and member.attrib.get("role") == "outer"
]
remaining = [ways[item] for item in outer_ids if item in ways]
if not remaining:
    raise SystemExit("No outer boundary ways found")

rings = []
while remaining:
    ring = remaining.pop(0)[:]
    changed = True
    while ring[0] != ring[-1] and changed:
        changed = False
        for index, candidate in enumerate(remaining):
            if ring[-1] == candidate[0]:
                ring.extend(candidate[1:])
            elif ring[-1] == candidate[-1]:
                ring.extend(reversed(candidate[:-1]))
            elif candidate[-1] == ring[0]:
                ring = candidate[:-1] + ring
            elif candidate[0] == ring[0]:
                ring = list(reversed(candidate[1:])) + ring
            else:
                continue
            remaining.pop(index)
            changed = True
            break
    if ring[0] != ring[-1]:
        raise SystemExit("Could not stitch a closed Razavi Khorasan boundary ring")
    if any(ref not in nodes for ref in ring):
        raise SystemExit("Boundary ring references a missing OSM node")
    rings.append(ring)

with destination.open("w", encoding="utf-8") as output:
    output.write("Razavi-Khorasan\n")
    for index, ring in enumerate(rings, 1):
        output.write(f"{index}\n")
        for ref in ring:
            lon, lat = nodes[ref]
            output.write(f"  {lon:.7f}  {lat:.7f}\n")
        output.write("END\n")
    output.write("END\n")

print(f"Wrote {len(rings)} closed province boundary ring(s) to {destination}")
