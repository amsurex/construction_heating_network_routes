#!/usr/bin/env python3
"""Синтетические GeoJSON без внешних библиотек; файл пишется по одной feature.
Координаты — искусственная сетка около меридиана 39°, не конкурсный набор.
--target-mib увеличивает свойства padding, --vertices увеличивает сложность геометрии.
"""
import argparse
import json
import math
from pathlib import Path


def coord(x, y):
    # Только размещение синтетических данных в WGS84; расчёты сервиса выполняет JTS в UTM.
    return [round(39 + x / 63800, 9), round(55 + y / 111300, 9)]


def feature(identifier, kind, geometry, coordinates, **properties):
    return {"type": "Feature", "properties": {"id": identifier, "object_type": kind, **properties},
            "geometry": {"type": geometry, "coordinates": coordinates}}


def rectangle(x1, y1, x2, y2):
    return [[coord(x1, y1), coord(x2, y1), coord(x2, y2), coord(x1, y2), coord(x1, y1)]]


def base():
    yield feature("source", "source", "Point", coord(-100, 0))
    yield feature("existing", "heat_network", "LineString", [coord(-100, 0), coord(100, 0)], diameter=300)
    yield feature("chamber", "heat_chamber", "Point", coord(0, 0))
    yield feature(42, "oks_connection_point", "Point", coord(0, 180), flow_tph=20)


def special():
    yield from base()
    for identifier, y in [("road", 35), ("tram_tracks", 70)]:
        yield feature(identifier, "restriction", "Polygon", rectangle(-60, y, 60, y+10), restriction_type=identifier)
    for identifier, y in [("gas_pipeline", 105), ("power_cable", 140)]:
        yield feature(identifier, "restriction", "LineString", [coord(-60, y), coord(60, y)], restriction_type=identifier)
    yield feature("park", "restriction", "Polygon", rectangle(20, 150, 70, 200), restriction_type="park")
    yield feature("own", "restriction", "Polygon", rectangle(-8, 175, 8, 190), restriction_type="oks")


def large(count, vertices):
    yield from base()
    for i in range(count):
        x, y = 1000 + (i % 1000)*15, 1000 + (i // 1000)*15
        ring = [coord(x+5*math.cos(j*2*math.pi/vertices), y+5*math.sin(j*2*math.pi/vertices)) for j in range(vertices)]
        ring.append(ring[0])
        yield feature(f"polygon_{i}", "restriction", "Polygon", [ring], restriction_type="park")


def write(path, features, target_bytes=0, count=0):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as out:
        out.write(b'{"type":"FeatureCollection","features":[')
        for i, f in enumerate(features):
            if i: out.write(b',')
            value = json.dumps(f, separators=(",", ":")).encode()
            if target_bytes and i >= 4:
                # Равномерные большие properties: они должны пропускаться потоковым reader.
                padding = max(0, (target_bytes-4096)//count-len(value)-32)
                f["properties"]["padding"] = "x"*padding
                value = json.dumps(f, separators=(",", ":")).encode()
            out.write(value)
        out.write(b'],"padding":"')
        remaining = max(0, target_bytes-out.tell()-2)
        while remaining:
            size = min(remaining, 1024*1024); out.write(b'x'*size); remaining -= size
        out.write(b'"}')
    print(json.dumps({"file": str(path), "bytes": path.stat().st_size}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("data/synthetic"))
    parser.add_argument("--large", action="store_true")
    parser.add_argument("--count", type=int, default=100000)
    parser.add_argument("--vertices", type=int, default=4)
    parser.add_argument("--target-mib", type=int, default=0)
    args = parser.parse_args()
    if args.count < 1 or args.vertices < 4 or args.target_mib < 0: parser.error("Некорректный размер/число вершин")
    if args.large:
        write(args.output/"large.geojson", large(args.count, args.vertices), args.target_mib*1024*1024, args.count)
    else:
        write(args.output/"special-crossings.geojson", special())
        write(args.output/"minimal.geojson", base())
        trapped = list(base())
        trapped.append(feature("water", "restriction", "Polygon", rectangle(-20,160,20,200), restriction_type="water"))
        write(args.output/"unreachable.geojson", trapped)


if __name__ == "__main__": main()
