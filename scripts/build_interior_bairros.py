#!/usr/bin/env python3
"""Neighbourhoods of every Amazonas municipality except Manaus, from IBGE.

Input:  AM_bairros_CD2022.zip from
        geoftp.ibge.gov.br/.../censo_2022/bairros/shp/UF/ (SIRGAS 2000, which
        matches WGS84 to within about a metre).
Output: GeoJSON of plain Polygons (the app's resolver reads only Polygon, so
        multi-part neighbourhoods become one feature per part) with the
        properties name, city and cityCode. Manaus is skipped: the app already
        ships its own layer with zones and colours.

Usage: python scripts/build_interior_bairros.py AM_bairros_CD2022.zip \
           app/src/main/assets/bairros_interior_am.geojson
"""
import json
import sys
import tempfile
import zipfile

import shapefile  # pyshp
from shapely.geometry import shape

MANAUS = "1302603"
SIMPLIFY_DEGREES = 0.00002  # about 2 m: far below GPS accuracy


def main(zip_path, output):
    with tempfile.TemporaryDirectory() as folder:
        with zipfile.ZipFile(zip_path) as archive:
            archive.extractall(folder)
        stem = next(n for n in archive.namelist() if n.endswith(".shp"))[:-4]
        reader = shapefile.Reader(f"{folder}/{stem}")
        features = []
        for record, shp in zip(reader.records(), reader.shapes()):
            data = record.as_dict()
            if data["CD_MUN"] == MANAUS:
                continue
            name = " ".join(str(data["NM_BAIRRO"] or "").split())
            if not name or not shp.points:
                continue
            geometry = shape(shp.__geo_interface__)
            if not geometry.is_valid:
                geometry = geometry.buffer(0)
            geometry = geometry.simplify(SIMPLIFY_DEGREES, preserve_topology=True)
            parts = list(geometry.geoms) if geometry.geom_type == "MultiPolygon" else [geometry]
            for part in parts:
                if part.is_empty or part.geom_type != "Polygon":
                    continue
                rings = [list(part.exterior.coords)] + [list(r.coords) for r in part.interiors]
                features.append({
                    "type": "Feature",
                    "properties": {"name": name, "city": data["NM_MUN"],
                                   "cityCode": int(data["CD_MUN"])},
                    "geometry": {"type": "Polygon", "coordinates": [
                        [[round(x, 6), round(y, 6)] for x, y in ring] for ring in rings]},
                })
    collection = {"type": "FeatureCollection", "features": features}
    with open(output, "w", encoding="utf-8") as handle:
        json.dump(collection, handle, ensure_ascii=False, separators=(",", ":"))
    cities = {f["properties"]["cityCode"] for f in features}
    print(f"{len(features)} polygons, {len(cities)} municipalities, "
          f"{sum(len(r) for f in features for r in f['geometry']['coordinates'])} vertices")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
