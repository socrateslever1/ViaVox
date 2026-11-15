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
import csv
import io
import datetime as dt
import gzip
import json
import pathlib
import re
import sys
import unicodedata
import zipfile

import ijson

import osmium
from shapely.geometry import LineString, Point, shape
from shapely.strtree import STRtree

POI_KEYS = ("amenity", "tourism", "historic", "leisure",
            "shop", "office", "healthcare", "railway", "public_transport")
# Both Android importers refuse packs above 1.5 million records.
LIMIT = 1_450_000
# Official IBGE street lines are only added while the pack has headroom.
IBGE_ROAD_BUDGET = 1_380_000
# A face whose midpoint is this close to a named OSM road is already mapped.
OSM_COVERED_DEGREES = 30.0 / 111_195.0
DRIVABLE = {
    "motorway", "trunk", "primary", "secondary", "tertiary",
    "residential", "living_street", "unclassified", "service",
    "motorway_link", "trunk_link", "primary_link", "secondary_link",
    "tertiary_link", "pedestrian", "road", "track"
}
LOWERCASE_WORDS = {"de", "da", "do", "das", "dos", "e", "a", "o", "em",
                   "na", "no", "para", "com"}
KEEP_UPPER = re.compile(r"^(BR|AM|UBS|UPA|CMEI|CEI|CICOM|DIP|UFAM|UEA|IFAM|SESC|SENAI|SESI|CAPS|CRAS|CREAS|[IVXLC]+|[A-Z]{1,2}\d+[A-Z]?|\d+[A-Z]?)$")
HIGHWAY_REF = re.compile(r"^(BR|AM)[- ]?(\d{3})$")


# IBGE census files carry no diacritics ("SAO JOAO", "CONSTELACAO"), which a
# speech synthesiser mispronounces. Only unambiguous words are restored.
ACCENTS = {w.translate(str.maketrans("áàâãéêíóôõúç", "aaaaeeiooouc")): w for w in """
são josé joão antônio ministério evangélica evangélico sétimo católica saúde
clínica consultório aliança missionária missionário família apostólico
apostólica odontológico odontológica cristã missão três igarapé vitória
esperança sebastião praça araújo graça graças farmácia espírito mário barão
nazaré gonçalves espírita jeová leão getúlio álvaro glória básica corrêa
solimões tabernáculo depósito união filadélfia salão área paraíso remédio
fátima júlia colégio júlio irmã lírio escritório guimarães bíblica visão fábio
mendonça canaã lázaro águas bênção tapajós santuário capitão comércio água
judá laboratório tefé cláudio paróquia lourenço espaço médico missões criança
sérgio maués última comunitário comunitária paraná rosário magalhães andré
cartório plínio cândido virgílio carismática tomé tucumã águia amazônia
justiça preço moisés cristóvão juruá sião ágape inácio itaúba simões tarumã
manicoré cemitério perpétuo sabiá curió polícia fórum estádio ginásio pública
público técnica técnico mãe pão açaí república município número américa
áfrica belém macapá ceará piauí goiás amapá maranhão rondônia brasília
paraíba méxico japão lábrea humaitá codajás urucará maraã japurá eirunepé
jutaí benjamín conceição gêmeos índio índios índia fé céu luís luíz césar
hélio márcio lúcia lúcio cecília célia cícero dário emília estêvão eugênio
flávio flávia hércules íris lídia lívia marília natália otávio patrícia
plácido rômulo sílvio sílvia tânia tárcio valéria vinícius zélia caetés
tupinambás irmãos alemão capitães pães mães órgão órgãos açúcar café
jacarandá maracujá cajá guaraná jatobá ipê buritizal pajé tucunaré tambaqui
jaraqui pirarucú uirapurú bacurí cupuaçu açaizal piauiense paraense
português portuguesa inglês francês japonês chinês holandês líder vovó vovô
básico eletrônica eletrônico mecânica mecânico ótica óptica estética
fisioterápico pediátrica pediátrico oftalmológica cardiológico diagnóstico
diagnósticos ortopédica psicológica veterinária veterinário agrícola
agropecuária indígena indígenas rodoviária rodoviário ferroviária aeroviário
portuário universitário universitária secundária primária judiciário
previdência assistência ciência ciências independência providência
residência excelência eficiência referência emergência agência conferência
""".split()}
ACCENTS.update({"sao": "são", "nacoes": "nações", "missoes": "missões",
                "divisao": "divisão", "expansao": "expansão",
                "sessao": "sessão", "mansao": "mansão", "comissao": "comissão",
                "ascensao": "ascensão", "paixao": "paixão"})


def restore_accent(word):
    lower = word.lower()
    fixed = ACCENTS.get(lower)
    if fixed is None and lower.isascii() and lower.isalpha() and len(lower) > 4:
        if lower.endswith("coes"):
            fixed = lower[:-4] + "ções"
        elif lower.endswith("cao"):
            fixed = lower[:-3] + "ção"
    if fixed is None:
        return word
    return fixed.upper() if word.isupper() else (
        fixed.capitalize() if word[:1].isupper() else fixed)


def restore_accents(name):
    """Restore diacritics in a name published as plain ASCII."""
    if not name.isascii():
        return name
    return " ".join("-".join(restore_accent(part) for part in word.split("-"))
                    for word in name.split())


def spoken_case(name):
    """IBGE publishes names in capitals; restore readable Portuguese casing."""
    if not name.isupper():
        return name
    name = restore_accents(name)
    words = []
    for index, word in enumerate(name.split()):
        if KEEP_UPPER.match(word):
            words.append(word)
        elif index and word.lower() in LOWERCASE_WORDS:
            words.append(word.lower())
        else:
            words.append("-".join(part.capitalize() for part in word.split("-")))
    return " ".join(words)


# CNEFE "espécie" codes: 4 school, 5 health unit, 6 other purposes,
# 8 religious establishment. Type 6 is free text, so only recognisable
# public-interest places are kept from it.
CNEFE_KINDS = (
    ("igreja", "place_of_worship"), ("assembleia de deus", "place_of_worship"),
    ("paroquia", "place_of_worship"), ("catedral", "place_of_worship"),
    ("templo", "place_of_worship"), ("capela", "place_of_worship"),
    ("mesquita", "place_of_worship"), ("ministerio", "place_of_worship"),
    ("hospital", "hospital"), ("pronto socorro", "hospital"), ("upa ", "clinic"),
    ("ubs ", "clinic"), ("unidade basica", "clinic"), ("policlinica", "clinic"),
    ("posto de saude", "clinic"), ("maternidade", "hospital"),
    ("universidade", "university"), ("faculdade", "university"),
    ("escola", "school"), ("colegio", "school"), ("creche", "kindergarten"),
    ("cmei", "kindergarten"), ("instituto federal", "college"),
    ("delegacia", "police"), ("cicom", "police"), ("batalhao", "police"),
    ("bombeiro", "fire_station"), ("forum", "courthouse"),
    ("tribunal", "courthouse"), ("prefeitura", "townhall"),
    ("secretaria", "government"), ("camara municipal", "townhall"),
    ("cartorio", "government"), ("correios", "post_office"),
    ("terminal", "bus_station"), ("rodoviaria", "bus_station"),
    ("porto ", "ferry_terminal"), ("aeroporto", "attraction"),
    ("supermercado", "supermarket"), ("shopping", "mall"),
    ("mercado municipal", "marketplace"), ("feira ", "marketplace"),
    ("farmacia", "pharmacy"), ("drogaria", "pharmacy"),
    ("posto de gasolina", "fuel"), ("posto de combustivel", "fuel"),
    ("banco ", "bank"), ("caixa economica", "bank"), ("loterica", "bank"),
    ("hotel", "hotel"), ("pousada", "hotel"), ("museu", "museum"),
    ("teatro", "theatre"), ("biblioteca", "library"), ("praca ", "park"),
    ("parque ", "park"), ("estadio", "stadium"), ("ginasio", "sports_centre"),
    ("centro de convivencia", "community_centre"),
    ("centro comunitario", "community_centre"), ("cemiterio", "attraction"),
)
CNEFE_GENERIC = {"igreja", "escola", "comercio", "loja", "bar", "mercearia",
                 "oficina", "igreja evangelica", "igreja catolica", "templo",
                 "posto de saude", "creche", "escola municipal",
                 "escola estadual", "mercadinho", "lanchonete", "salao",
                 "loja de roupa", "consultorio odontologico",
                 "consultorio dentario", "consultorio medico",
                 "clinica odontologica", "reforco escolar", "creche escola",
                 "creche e escola", "posto de gasolina", "casa de oracao",
                 "igreja pentecostal", "igreja petencostal", "igreja crista",
                 "ponto de pregacao", "escola biblica", "escolinha de reforco"}
# Enumerator notes, not names of places.
CNEFE_NOISE = re.compile(
    r"sem nome|s nome|s/n\b|sem denominacao|sem identificacao|nao identific"
    r"|nao informad|ao lado|em frente|proxim|fundos|\banexo\b|\bvago\b"
    r"|desativad|fechad|abandonad|em construcao|em obras|em reforma"
    r"|\bdeposito\b|estacionamento|\bescritorio\b|\bgaragem\b|reforco"
    r"|venda de|loja de|\bcasa\b \d|\bresidencia\b")
CNEFE_HEALTH = re.compile(
    r"hospital|clinica|consultorio|\bubs\b|\bupa\b|saude|odont|laborat"
    r"|policlinica|maternidade|pronto|medic|fisio|\bsamu\b|\bcaps\b|dentar"
    r"|oftalm|cardio|pediatr|diagnost|vacina|\bspa\b|farmac|drogaria")
CNEFE_EDUCATION = re.compile(
    r"escola|colegio|creche|\bcmei\b|educa|universidade|faculdade|instituto"
    r"|ensino|\bcetam\b|\bsenai\b|\bsesi\b|\bsenac\b|\bifam\b|\buea\b"
    r"|\bufam\b|\bcei\b")


def cnefe_kind(species, folded):
    """OSM-style kind for a census establishment, or None to leave it out."""
    padded = folded + " "
    for needle, kind in CNEFE_KINDS:
        if needle in padded:
            return kind
    if species == "8":
        return "place_of_worship"
    if species == "5" and CNEFE_HEALTH.search(folded):
        return "clinic"
    if species == "4" and CNEFE_EDUCATION.search(folded):
        return "school"
    return None


BUMP_VALUES = {"bump", "hump", "table", "cushion"}
ALERT_LIMIT = 90_000


def alert_kind(tags):
    """Road-safety point type from OSM tags, or None.

    Speed cameras are mapped as highway=speed_camera and, in newer data, as
    enforcement=maxspeed on the device. A lombada is traffic_calming.
    """
    if tags.get("highway") == "speed_camera" or tags.get("enforcement") in (
            "maxspeed", "average_speed"):
        return "speed_camera"
    if tags.get("enforcement") == "traffic_signals":
        return "red_light_camera"
    if tags.get("barrier") == "toll_booth":
        return "toll_booth"
    if tags.get("railway") == "level_crossing":
        return "level_crossing"
    if tags.get("traffic_calming") in BUMP_VALUES:
        return "speed_bump"
    return None


def road_name(tags):
    """A usable street name: the mapped name, else a state/federal road ref."""
    name = tags.get("name", "").strip()
    if name:
        return name
    for ref in tags.get("ref", "").split(";"):
        match = HIGHWAY_REF.match(ref.strip().upper())
        if match:
            return f"{match.group(1)}-{match.group(2)}"
    return ""


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
        self.ibge_streets = 0
        self.road_geometry_count = 0
        self.ibge_road_count = 0
        self.osm_road_lines = []
        self.cnefe_pois = 0
        self.poi_cells = set()
        self.alerts = {}
        self.alert_ids = set()
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
            self.poi_cells.add((fold(name), round(lat, 3), round(lon, 3)))
            self.write({"type":"poi", "city_code":city, "name":name,
                        "id":f"osm:{kind}:{id_value}", "kind":category[:40],
                        "lat":round(lat,7), "lon":round(lon,7),
                        "approximate":approximate})
            self.pois += 1

    def ingest_ibge_zip(self, zip_path):
        """Stream official 2022 IBGE face names by municipality."""
        valid_codes=set(self.codes)
        osm_tree=STRtree(self.osm_road_lines) if self.osm_road_lines else None
        with zipfile.ZipFile(zip_path) as archive:
            members=[m for m in archive.infolist() if
                     m.filename.lower().endswith((".json",".geojson"))
                     and not m.is_dir()]
            if not members:
                raise ValueError("IBGE ZIP contains no geographic JSON")
            print("IBGE ZIP files:", [(m.filename,m.file_size) for m in members[:4]])
            import itertools
            with archive.open(members[0]) as sample:
                print("IBGE first JSON tokens:",
                      list(itertools.islice(ijson.parse(sample),18)))
            for member in members:
                if member.file_size>450*1024*1024:
                    raise ValueError("IBGE decompressed file too large")
                filename_match = re.search(r"(13[0-9]{5})_faces", member.filename)
                if filename_match is None:
                    raise ValueError("Unexpected IBGE municipality filename: "+member.filename)
                code=int(filename_match.group(1))
                if code not in valid_codes:
                    continue
                with archive.open(member) as source:
                    for feature in ijson.items(source,"features.item"):
                        props=feature.get("properties") or {}
                        # IBGE JSON is split into one file per municipality.
                        # CD_SETOR can be absent in some features; the municipality
                        # identity comes from the official filename, not the sector.
                        sector=str(props.get("CD_SETOR") or "")
                        if sector and sector[:7].isdigit() and int(sector[:7]) != code:
                            raise ValueError("Sector municipality mismatches IBGE ZIP filename")
                        parts=[str(props.get(key) or "").strip()
                               for key in ("NM_TIP_LOG","NM_TIT_LOG","NM_LOG")]
                        # "RUA CAJUBIA OU 28": keep the first of two names.
                        raw_name=re.split(r"\s+OU\s+"," ".join(x for x in parts if x))[0]
                        name=spoken_case(" ".join(raw_name.split()))
                        if not 2<=len(name)<=140 or fold(name) in {
                            "sem nome","nao identificado","logradouro sem nome"
                        } or fold(name).endswith(" sem denominacao"):
                            continue
                        geom=feature.get("geometry")
                        if not geom:
                            continue
                        try:
                            face=shape(geom)
                            center=face.centroid
                            if center.is_empty or not center.is_valid:
                                continue
                        except (TypeError,ValueError):
                            continue
                        self.ibge_face_roads(face,code,name,osm_tree)
                        key=(code,fold(name))
                        if key in self.streets_seen:
                            continue
                        self.streets_seen.add(key)
                        self.write({"type":"street", "city_code":code,
                            "name":name,"lat":round(center.y,7),
                            "lon":round(center.x,7),
                            "source":"IBGE:faces:2022"})
                        self.ibge_streets+=1

    def ingest_cnefe_zip(self, zip_path, budget=180_000):
        """Named establishments from the 2022 census address register.

        OpenStreetMap has few named places in Amazonas; the census visited
        every address and recorded schools, health units, churches and other
        establishments with coordinates. Any surprise in the file format skips
        this optional source instead of failing the whole pack.
        """
        valid_codes = set(self.codes)
        with zipfile.ZipFile(zip_path) as archive:
            members = [m for m in archive.infolist()
                       if m.filename.lower().endswith(".csv")]
            if not members:
                print("CNEFE: no CSV in archive, skipped")
                return
            for member in members:
                with archive.open(member) as binary:
                    head = binary.read(4096)
                try:
                    head.decode("utf-8")
                    encoding = "utf-8"
                except UnicodeDecodeError:
                    encoding = "latin-1"
                first = head.decode(encoding, "replace").splitlines()[0]
                delimiter = ";" if first.count(";") >= first.count(",") else ","
                with archive.open(member) as binary:
                    reader = csv.reader(io.TextIOWrapper(
                        binary, encoding=encoding, errors="replace", newline=""),
                        delimiter=delimiter)
                    header = [h.strip().upper() for h in next(reader)]
                    def column(*names):
                        return next((header.index(n) for n in names
                                     if n in header), None)
                    c_city = column("COD_MUNICIPIO")
                    c_species = column("COD_ESPECIE")
                    c_name = column("DSC_ESTABELECIMENTO")
                    c_lat = column("LATITUDE")
                    c_lon = column("LONGITUDE")
                    c_level = column("NV_GEO_COORD")
                    c_id = column("COD_UNICO_ENDERECO")
                    if None in (c_city, c_species, c_name, c_lat, c_lon):
                        print("CNEFE: unexpected header, skipped:", header[:40])
                        return
                    width = max(c_city, c_species, c_name, c_lat, c_lon)
                    for number, row in enumerate(reader):
                        if len(row) <= width:
                            continue
                        species = row[c_species].strip()
                        if species not in ("4", "5", "6", "8"):
                            continue
                        # Coordinates estimated from the street face or the
                        # census tract are not good enough for "to your right".
                        if c_level is not None and len(row) > c_level and \
                                row[c_level].strip() not in ("1", "2", ""):
                            continue
                        raw = " ".join(row[c_name].split())
                        if len(raw) >= 40 and " " in raw:
                            # The register cuts names at 40 characters; drop
                            # the half word and any dangling connective.
                            pieces = raw.split()[:-1]
                            while pieces and pieces[-1].lower() in LOWERCASE_WORDS:
                                pieces.pop()
                            raw = " ".join(pieces)
                        name = spoken_case(raw.upper())
                        folded = fold(name)
                        if not 5 <= len(name) <= 75 or folded in CNEFE_GENERIC \
                                or len(folded.split()) < 2 \
                                or CNEFE_NOISE.search(folded) \
                                or re.search(r"https?://|@|[\x00-\x1f]", name):
                            continue
                        kind = cnefe_kind(species, folded)
                        if kind is None:
                            continue
                        try:
                            code = int(row[c_city])
                            lat = float(row[c_lat].replace(",", "."))
                            lon = float(row[c_lon].replace(",", "."))
                        except ValueError:
                            continue
                        if code not in valid_codes or \
                                not (-10.5 < lat < 3.0 and -74.0 < lon < -56.0):
                            continue
                        cell = (folded, round(lat, 3), round(lon, 3))
                        if cell in self.poi_cells:
                            continue
                        self.poi_cells.add(cell)
                        if self.cnefe_pois >= budget or self.count >= LIMIT - 10:
                            print("CNEFE: budget reached")
                            return
                        identifier = row[c_id].strip() if c_id is not None \
                            and len(row) > c_id else str(number)
                        self.write({"type": "poi", "city_code": code,
                                    "name": name,
                                    "id": f"cnefe:{identifier}"[:90],
                                    "kind": kind,
                                    "lat": round(lat, 6), "lon": round(lon, 6),
                                    "approximate": False,
                                    "source": "IBGE:CNEFE:2022"})
                        self.cnefe_pois += 1
                        self.pois += 1

    def ibge_face_roads(self, face, code, name, osm_tree):
        """Emit the official line of a street OpenStreetMap has no name for.

        Without this, a street missing from (or unnamed in) OSM is invisible
        to GPS matching and the app either stays silent or names a neighbour.
        """
        if self.count >= IBGE_ROAD_BUDGET:
            return
        parts = [face] if face.geom_type == "LineString" else (
            list(face.geoms) if face.geom_type == "MultiLineString" else [])
        for part in parts:
            if part.is_empty or len(part.coords) < 2:
                continue
            middle = part.interpolate(0.5, normalized=True)
            if not (-10.5 < middle.y < 3.0 and -74.0 < middle.x < -56.0):
                continue
            if osm_tree is not None:
                near = osm_tree.query(middle.buffer(OSM_COVERED_DEGREES))
                if any(self.osm_road_lines[int(i)].distance(middle)
                       <= OSM_COVERED_DEGREES for i in near):
                    continue
            coords = list(part.simplify(0.00001).coords)[:96]
            if len(coords) < 2 or self.count >= IBGE_ROAD_BUDGET:
                continue
            self.ibge_road_count += 1
            self.write({
                "type": "road_way", "city_code": code,
                "id": f"ibge:{code}:{self.ibge_road_count}",
                "name": name,
                "lat": round(middle.y, 6), "lon": round(middle.x, 6),
                "source": "IBGE:faces:2022",
                "points": [[round(c[1], 6), round(c[0], 6)] for c in coords]
            })

    def maybe_alert(self, tags, osm_type, osm_id, lat, lon):
        """Write a road-safety point (camera, lombada, crossing, toll)."""
        kind = alert_kind(tags)
        if kind is None or sum(self.alerts.values()) >= ALERT_LIMIT:
            return
        ident = f"osm:{osm_type}:{osm_id}"
        if ident in self.alert_ids:
            return
        city = self.municipality(lat, lon)
        if city is None:
            return
        record = {"type": "alert", "kind": kind, "id": ident, "city_code": city,
                  "lat": round(lat, 6), "lon": round(lon, 6)}
        digits = "".join(c for c in tags.get("maxspeed", "") if c.isdigit())
        if digits and 5 <= int(digits) <= 200:
            record["maxspeed"] = int(digits)
        name = " ".join(tags.get("name", "").split())
        if name and not re.search(r"https?://|[\x00-\x1f]", name):
            record["name"] = name[:80]
        self.alert_ids.add(ident)
        self.alerts[kind] = self.alerts.get(kind, 0) + 1
        self.write(record)

    def node(self, obj):
        if not obj.location.valid() or len(obj.tags) == 0:
            return
        tags = {t.k:t.v for t in obj.tags}
        self.maybe_alert(tags, "node", obj.id, obj.location.lat, obj.location.lon)
        if "name" not in tags:
            return
        if not any(k in tags for k in POI_KEYS) and tags.get("highway") != "bus_stop":
            return
        self.process(tags, "node", obj.id, obj.location.lat,
                     obj.location.lon, False)

    def way(self, obj):
        tags = {t.k:t.v for t in obj.tags}
        if alert_kind(tags) == "speed_bump":
            # A raised table mapped as a way: use its middle point.
            middle = [(n.location.lat, n.location.lon)
                      for n in obj.nodes if n.location.valid()]
            if middle:
                lat, lon = middle[len(middle)//2]
                self.maybe_alert(tags, "way", obj.id, lat, lon)
        if tags.get("highway") in DRIVABLE and not tags.get("name", "").strip():
            # Highways mapped with only a reference are still announceable.
            fallback = road_name(tags)
            if fallback:
                tags["name"] = fallback
        if not ("name" in tags and (
            "highway" in tags or any(key in tags for key in POI_KEYS))):
            return
        coords = [(n.location.lat,n.location.lon)
                  for n in obj.nodes if n.location.valid()]
        if not coords:
            return
        lat, lon = coords[len(coords)//2]
        self.process(tags, "way", obj.id, lat, lon, True)
        if tags.get("highway") in DRIVABLE and 2 <= len(coords) and \
                2 <= len(tags.get("name", "").strip()) <= 140:
            # Store real street geometry instead of guessing from a single
            # representative point. Split long ways with an overlapping node
            # to maintain accurate segments across bends.
            for start in range(0, len(coords)-1, 95):
                points = coords[start:min(start+96, len(coords))]
                if len(points) < 2: continue
                code = self.municipality(*points[len(points)//2])
                if code is None: continue
                self.osm_road_lines.append(
                    LineString([(p[1], p[0]) for p in points]))
                self.write({
                    "type": "road_way",
                    "city_code": code,
                    "id": f"osmway:{obj.id}:{start}",
                    "name": tags["name"].strip(),
                    "lat": round(points[len(points)//2][0], 7),
                    "lon": round(points[len(points)//2][1], 7),
                    "points": [
                        [round(p[0], 7), round(p[1], 7)] for p in points
                    ]
                })
                self.road_geometry_count += 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--osm", required=True, type=pathlib.Path)
    parser.add_argument("--municipalities", required=True, type=pathlib.Path)
    parser.add_argument("--ibge-zip", type=pathlib.Path)
    parser.add_argument("--cnefe-zip", type=pathlib.Path)
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
                       "IBGE municipal geometries",
                       "IBGE Faces de Logradouros 2022",
                       "IBGE CNEFE 2022"],
            "license":"ODbL-1.0 for OpenStreetMap derived database"
        },ensure_ascii=False,separators=(",",":")) + "\n")
        collector = Gazetteer(polygons,codes,writer)
        collector.apply_file(str(args.osm), locations=True, idx="flex_mem")
        if args.ibge_zip is not None:
            collector.ingest_ibge_zip(args.ibge_zip)
        if args.cnefe_zip is not None and args.cnefe_zip.is_file():
            try:
                collector.ingest_cnefe_zip(args.cnefe_zip)
            except (zipfile.BadZipFile, csv.Error, OSError, StopIteration) as error:
                print("CNEFE source unusable, continuing without it:", error)
        writer.write(json.dumps({"type":"end","records":collector.count})+"\n")
    print(f"Amazonas pack: municipalities={len(codes)}, "
          f"POIs={collector.pois}, OSM streets={collector.streets}, "
          f"IBGE added streets={collector.ibge_streets}, "
          f"mapped road shapes={collector.road_geometry_count}, "
          f"IBGE road shapes={collector.ibge_road_count}, "
          f"CNEFE places={collector.cnefe_pois}, "
          f"road-safety points={sum(collector.alerts.values())} {collector.alerts}, "
          f"records={collector.count}, file={args.output.stat().st_size} bytes")
    if args.ibge_zip is not None and collector.ibge_streets < 5_000:
        raise RuntimeError(
            "Incomplete official IBGE import: only "
            + str(collector.ibge_streets) + " extra streets"
        )
    if collector.road_geometry_count < 1000:
        raise RuntimeError("Street geometry coverage is insufficient")
    if collector.pois < 1000 or collector.streets < 500:
        raise RuntimeError("Suspiciously empty source extraction")
    if args.output.stat().st_size > 60*1024*1024:
        raise RuntimeError("Compressed pack exceeds Android download limit")


if __name__=="__main__":
    main()
