#!/usr/bin/env python3
"""Build an Amazonas-only, verified OSM gazetteer pack.

Input:
  - Geofabrik Norte .osm.pbf (ODbL)
  - 62 IBGE municipality geometries (GeoJSON; source br-geojson/IBGE)
Output:
  - Gzipped streaming JSONL understood by GeoCatalogDatabase.importGeoPack.
  - The pack contains street *names* and named POI coordinates; a road
    representative point is NOT an accurate GPS road snapping geometry.

Install: pip install osmium shapely
Usage: python scripts/build_amazonas_geopack.py --osm norte-latest.osm.pbf \
       --municipalities AM.geojson --output amazonas-geopack.jsonl.gz
"""
import argparse
import datetime as dt
import gzip
import json
import pathlib
import re
import sys
import unicodedata

import osmium
from shapely.geometry import Point, shape
from shapely.strtree import STRtree

POI_KEYS = ("amenity", "tourism", "historic", "leisure",
            "shop", "office", "healthcare", "railway", "public_transport")
LIMIT = 1_200_000


def fold(value):
    return " ".join("".join(
        c for c in unicodedata.normalize("NFD", value.lower())
        if unicodedata.category(c) != "Mn"
    ).split())


def municipality_shapes(path):
    raw = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    polygons = []
    codes = []
    names = []
    for feature in raw["features"]:
        props = feature["properties"]
        code = int(props.get("cd_geocmu") or props.get("id") or 0)
        if not 1300000 <= code <= 1399999:
            continue
        polygon = shape(feature["geometry"])
        if polygon.is_empty or not polygon.is_valid:
            polygon = polygon.buffer(0)
        if polygon.is_empty:
            continue
        polygons.append(polygon)
        codes.append(code)
        names.append(props.get("nome") or props.get("name") or "")
    if len(polygons) != 62 or len(set(codes)) != 62:
        raise ValueError("Expected exactly 62 Amazonas municipality geometries")
    return polygons, codes, names


class Gazetteer(osmium.SimpleHandler):
    def __init__(self, polygons, codes, writer):
        super().__init__()
        self.codes = codes
        self.polygons = polygons
        self.tree = STRtree(polygons)
        self.writer = writer
        self.count = 0
        self.pois = 0
        self.streets = 0
        self.streets_seen = set()

    def municipality(self, lat, lon):
        if not -10.6 < lat < 3.1 or not -74.2 < lon < -56.0:
            return None
        point = Point(lon, lat)
        for index in self.tree.query(point):
            ix = int(index)
            if self.polygons[ix].covers(point):
                return self.codes[ix]
        return None

    def write(self, entry):
        if self.count >= LIMIT:
            raise RuntimeError("Geopack exceeds maximum records")
        self.writer.write(json.dumps(entry, ensure_ascii=False,
                                     separators=(",", ":")) + "\n")
        self.count += 1

    def process(self, tag_map, kind, id_value, lat, lon, approximate):
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            return
        name = tag_map.get("name", "").strip()
        if not 2 <= len(name) <= 140 or re.search(r"https?://|[\x00-\x1f]", name):
            return
        is_street = bool(tag_map.get("highway") and
            tag_map["highway"] not in ("bus_stop", "platform", "elevator"))
        is_poi = any(tag_map.get(key) for key in POI_KEYS) or (
            tag_map.get("highway") == "bus_stop")
        if not is_street and not is_poi:
            return
        city = self.municipality(lat, lon)
        if city is None:
            return
        if is_street:
            key = (city, fold(name))
            if key not in self.streets_seen:
                self.streets_seen.add(key)
                self.write({"type":"street", "city_code":city, "name":name,
                            "lat":round(lat,7), "lon":round(lon,7)})
                self.streets += 1
        if is_poi:
            category = next((tag_map[key] for key in POI_KEYS
                             if tag_map.get(key)), "bus_stop")
            self.write({"type":"poi", "city_code":city, "name":name,
                        "id":f"osm:{kind}:{id_value}", "kind":category[:40],
                        "lat":round(lat,7), "lon":round(lon,7),
                        "approximate":approximate})
            self.pois += 1

    def node(self, obj):
        if not obj.location.valid():
            return
        tags = {t.k:t.v for t in obj.tags}
        if "name" not in tags:
            return
        if not any(k in tags for k in POI_KEYS) and tags.get("highway") != "bus_stop":
            return
        self.process(tags, "node", obj.id, obj.location.lat,
                     obj.location.lon, False)

    def way(self, obj):
        tags = {t.k:t.v for t in obj.tags}
        if not ("name" in tags and (
            "highway" in tags or any(key in tags for key in POI_KEYS))):
            return
        coords = [(n.location.lat,n.location.lon)
                  for n in obj.nodes if n.location.valid()]
        if not coords:
            return
        lat, lon = coords[len(coords)//2]
        self.process(tags, "way", obj.id, lat, lon, True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--osm", required=True, type=pathlib.Path)
    parser.add_argument("--municipalities", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    polygons, codes, names = municipality_shapes(args.municipalities)
    today = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(args.output, "wt", encoding="utf-8",
                   compresslevel=6) as writer:
        writer.write(json.dumps({
            "type":"header", "schema":1,"region":"AM", "version":today,
            "sources":["OpenStreetMap / Geofabrik (ODbL)",
                       "IBGE municipal geometries"],
            "license":"ODbL-1.0 for OpenStreetMap derived database"
        },ensure_ascii=False,separators=(",",":")) + "\n")
        collector = Gazetteer(polygons,codes,writer)
        collector.apply_file(str(args.osm), locations=True, idx="flex_mem")
        writer.write(json.dumps({"type":"end","records":collector.count})+"\n")
    print(f"Amazonas pack: municipalities={len(codes)}, "
          f"POIs={collector.pois}, streets={collector.streets}, "
          f"records={collector.count}, file={args.output.stat().st_size} bytes")
    if collector.pois < 1000 or collector.streets < 500:
        raise RuntimeError("Suspiciously empty source extraction")
    if args.output.stat().st_size > 60*1024*1024:
        raise RuntimeError("Compressed pack exceeds Android download limit")


if __name__=="__main__":
    main()
