#!/usr/bin/env python3
"""Квази-реальный тестовый набор той же структуры, что конкурсный, из OpenStreetMap (только для тестов,
сервис остаётся офлайн): реальные здания, дороги, трамвайные пути, железная дорога, парки, вода,
школы/детсады (доп. типы) Москвы + синтетическая существующая теплосеть вдоль улиц, газопроводы и
кабели, точки подключения внутри зданий.

python tools/make_osm_dataset.py --center 55.796 37.715 --size 1400 --points 25 --seed 1 \
    --out data/synthetic/osm-preobrazhenka.geojson
"""
import argparse
import json
import math
import random

import requests
from pyproj import Transformer
from shapely.geometry import LineString, MultiPolygon, Point, Polygon, mapping, shape
from shapely.ops import linemerge, transform, unary_union

TO_UTM = Transformer.from_crs(4326, 32637, always_xy=True).transform
TO_WGS = Transformer.from_crs(32637, 4326, always_xy=True).transform
ROAD_WIDTH = {"motorway": 20, "trunk": 18, "primary": 16, "secondary": 12, "tertiary": 9, "residential": 7,
              "unclassified": 6, "living_street": 5, "service": 4}


def overpass(bbox):
    s, w, n, e = bbox
    q = f"""[out:json][timeout:120];
(
  way["building"]({s},{w},{n},{e});
  relation["building"]({s},{w},{n},{e});
  way["highway"~"^(motorway|trunk|primary|secondary|tertiary|residential|unclassified|living_street)$"]({s},{w},{n},{e});
  way["railway"="tram"]({s},{w},{n},{e});
  way["railway"="rail"]({s},{w},{n},{e});
  way["leisure"~"^(park|garden)$"]({s},{w},{n},{e});
  relation["leisure"~"^(park|garden)$"]({s},{w},{n},{e});
  way["natural"="water"]({s},{w},{n},{e});
  relation["natural"="water"]({s},{w},{n},{e});
  way["waterway"="river"]({s},{w},{n},{e});
  way["amenity"~"^(school|kindergarten)$"]({s},{w},{n},{e});
);
out body; >; out skel qt;"""
    last = None
    for url in ("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter",
                "https://overpass.private.coffee/api/interpreter"):
        for attempt in range(2):
            try:
                r = requests.post(url, data={"data": q}, headers={"User-Agent": "lct-2026-dataset-builder/1.0"},
                                  timeout=300)
                r.raise_for_status()
                return r.json()["elements"]
            except Exception as e:  # noqa: BLE001 — перебираем зеркала
                last = e
    raise SystemExit(f"Overpass недоступен: {last}")


def build_geoms(elements):
    nodes = {e["id"]: (e["lon"], e["lat"]) for e in elements if e["type"] == "node"}
    ways = {e["id"]: e for e in elements if e["type"] == "way"}
    out = []  # (tags, shapely geom in UTM)

    def way_coords(w):
        return [nodes[n] for n in w["nodes"] if n in nodes]

    for w in ways.values():
        cs = way_coords(w)
        if len(cs) < 2:
            continue
        tags = w.get("tags", {})
        closed = cs[0] == cs[-1] and len(cs) >= 4
        if closed and ("building" in tags or "leisure" in tags or "natural" in tags or "amenity" in tags):
            g = Polygon(cs)
        else:
            g = LineString(cs)
        if not g.is_valid:
            g = g.buffer(0)
        out.append((tags, transform(TO_UTM, g)))
    for e in elements:
        if e["type"] != "relation":
            continue
        tags = e.get("tags", {})
        outers, inners = [], []
        for m in e.get("members", []):
            if m["type"] != "way" or m["ref"] not in ways:
                continue
            cs = way_coords(ways[m["ref"]])
            if len(cs) < 2:
                continue
            (outers if m.get("role") != "inner" else inners).append(LineString(cs))
        try:
            rings = [r for r in getattr(linemerge(outers), "geoms", [linemerge(outers)]) if r.is_ring]
            holes = [r for r in getattr(linemerge(inners), "geoms", [linemerge(inners)]) if r.is_ring] if inners else []
            polys = [Polygon(r.coords, [h.coords for h in holes if Polygon(r.coords).contains(Polygon(h.coords))]) for r in rings]
            if polys:
                g = unary_union([p.buffer(0) for p in polys])
                out.append((tags, transform(TO_UTM, g)))
        except Exception:
            pass
    return out


def classify(tags, g):
    if "building" in tags:
        return "oks", g
    if tags.get("amenity") in ("school", "kindergarten"):
        return tags["amenity"], g
    hw = tags.get("highway")
    if hw in ROAD_WIDTH and g.geom_type == "LineString":
        return "road", g.buffer(ROAD_WIDTH[hw] / 2, cap_style=2)
    if tags.get("railway") == "tram" and g.geom_type == "LineString":
        return "tram_tracks", g.buffer(1.6, cap_style=2)
    if tags.get("railway") == "rail" and g.geom_type == "LineString":
        return "railway", g.buffer(3.0, cap_style=2)
    if tags.get("leisure") in ("park", "garden"):
        return "park", g
    if tags.get("natural") == "water":
        return "water", g
    if tags.get("waterway") == "river" and g.geom_type == "LineString":
        return "water", g.buffer(8, cap_style=2)
    return None, None


def feature(fid, kind, geom, **props):
    return {"type": "Feature", "properties": {"id": fid, "object_type": kind, **props},
            "geometry": mapping(transform(TO_WGS, geom))}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--center", nargs=2, type=float, default=[55.796, 37.715])
    ap.add_argument("--size", type=float, default=1400, help="сторона квадрата, м")
    ap.add_argument("--points", type=int, default=25)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    rnd = random.Random(a.seed)
    lat, lon = a.center
    dlat = a.size / 2 / 111300
    dlon = a.size / 2 / (111300 * math.cos(math.radians(lat)))
    bbox = (lat - dlat, lon - dlon, lat + dlat, lon + dlon)
    cx, cy = TO_UTM(lon, lat)
    area = Polygon([(cx - a.size / 2, cy - a.size / 2), (cx + a.size / 2, cy - a.size / 2),
                    (cx + a.size / 2, cy + a.size / 2), (cx - a.size / 2, cy + a.size / 2)])

    elements = overpass(bbox)
    raw = build_geoms(elements)
    features = []
    fid = 1000
    restrictions = []
    roads_lines = []
    buildings = []
    for tags, g in raw:
        kind, geom = classify(tags, g)
        if kind is None or geom.is_empty:
            continue
        geom = geom.intersection(area)
        if geom.is_empty or geom.geom_type not in ("Polygon", "MultiPolygon"):
            continue
        if kind == "road":
            roads_lines.append((tags, g.intersection(area)))
        if kind == "oks" and geom.area >= 60:
            buildings.append(geom)
        fid += 1
        restrictions.append((fid, kind, geom))

    # дороги как полигоны могут перекрываться друг с другом — это норма (перекрёстки)
    for rid, kind, geom in restrictions:
        features.append(feature(rid, "restriction", geom, restriction_type=kind))

    # существующая теплосеть: вдоль самых длинных улиц (со сдвигом от оси дороги на ширину/2 + 4 м)
    roads_lines.sort(key=lambda t: -t[1].length)
    trunk = None
    pipes, chambers = [], []
    pid, chid = 5001, 6001
    source = None
    used = []
    for tags, line in roads_lines[:6]:
        if line.geom_type != "LineString" or line.length < 200:
            continue
        off = line.parallel_offset(ROAD_WIDTH[tags["highway"]] / 2 + 4, "left", join_style=2)
        if off.geom_type != "LineString" or off.length < 150:
            continue
        # без пересечений с уже уложенными
        if any(off.distance(u) < 3 for u in used) and not any(off.intersects(u) for u in used):
            continue
        dn = 500 if trunk is None else rnd.choice([300, 400])
        if trunk is None:
            trunk = off
            source = Point(off.coords[0])
        used.append(off)
        # делим на участки в вершинах и ставим камеры на концах
        cs = list(off.coords)
        step = max(1, len(cs) // 3)
        for i in range(0, len(cs) - 1, step):
            seg = LineString(cs[i:min(i + step + 1, len(cs))])
            if seg.length < 5:
                continue
            pipes.append((pid, seg, dn))
            pid += 1
        for c in (cs[0], cs[-1]):
            chambers.append((chid, Point(c)))
            chid += 1
        if len(used) >= 4:
            break
    if source is None:
        raise SystemExit("нет улиц для существующей сети")
    features.append(feature("source", "source", source))
    for pid_, seg, dn in pipes:
        features.append(feature(pid_, "heat_network", seg, diameter=dn))
    for chid_, pt in chambers:
        features.append(feature(chid_, "heat_chamber", pt))

    # газопровод и кабель — вдоль двух других улиц (линии)
    lines_for_utils = [l for t, l in roads_lines[6:14] if l.geom_type == "LineString" and l.length > 150]
    for i, line in enumerate(lines_for_utils[:4]):
        kind = "gas_pipeline" if i % 2 == 0 else "power_cable"
        off = line.parallel_offset(3, "right", join_style=2)
        if off.geom_type == "LineString":
            fid += 1
            features.append(feature(fid, "restriction", off, restriction_type=kind))

    # точки подключения: внутри зданий на 2–6 м от стены, не ближе 30 м к существующей сети
    net_union = unary_union([p for _, p, _ in pipes])
    cands = [b for b in buildings if b.area > 400 and b.distance(net_union) > 30 and b.distance(net_union) < 600]
    rnd.shuffle(cands)
    n = 0
    for b in cands:
        if n >= a.points:
            break
        poly = max(b.geoms, key=lambda p: p.area) if b.geom_type == "MultiPolygon" else b
        inner = poly.buffer(-3)
        if inner.is_empty:
            continue
        inner = max(inner.geoms, key=lambda p: p.area) if inner.geom_type == "MultiPolygon" else inner
        # точка у границы внутреннего контура
        d = rnd.uniform(0, inner.exterior.length)
        pt = inner.exterior.interpolate(d)
        if not poly.contains(pt):
            continue
        n += 1
        flow = round(rnd.choice([5, 8, 12, 18, 25, 35, 50, 70]) * rnd.uniform(0.8, 1.2), 2)
        features.append(feature(n, "oks_connection_point", pt, flow_tph=flow))

    with open(a.out, "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": features}, f, ensure_ascii=False)
    kinds = {}
    for ft in features:
        k = ft["properties"].get("restriction_type", ft["properties"]["object_type"])
        kinds[k] = kinds.get(k, 0) + 1
    print(a.out, len(features), "features", kinds)


if __name__ == "__main__":
    main()
