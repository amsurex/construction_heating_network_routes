"""Генератор синтетического входного GeoJSON для тестов ядра.

Сетка кварталов с дорогами, зданиями (oks), трамвайной линией, газопроводом, силовым кабелем,
парком, школой, рекой, ж/д, неизвестным типом (metro); существующая теплосеть вдоль одной улицы
с камерами; точки подключения внутри случайных зданий.

python scripts/make_synthetic.py out.geojson --blocks 8 --points 40 --seed 1
"""
import argparse
import json
import random

from pyproj import Transformer
from shapely.geometry import LineString, Point, Polygon, box, mapping, MultiPolygon
from shapely.ops import transform

TO_WGS = Transformer.from_crs(32637, 4326, always_xy=True).transform
ORIGIN = (413000.0, 6172000.0)   # юго-запад области, UTM 37N (рядом с конкурсным датасетом)


def feat(fid, obj_type, geom, **props):
    p = {"id": fid, "object_type": obj_type}
    p.update(props)
    return {"type": "Feature", "properties": p, "geometry": mapping(transform(TO_WGS, geom))}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--blocks", type=int, default=8, help="кварталов по стороне")
    ap.add_argument("--points", type=int, default=40)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--block", type=float, default=120.0, help="размер квартала, м")
    ap.add_argument("--road", type=float, default=14.0, help="ширина дороги, м")
    ap.add_argument("--cluster", type=float, default=0.0,
                    help="доля стороны (0..1): точки только в квадрате такого размера в северо-восточном углу")
    ap.add_argument("--nasty", action="store_true",
                    help="злой режим: пристроенные и наложенные корпуса, дорога вплотную к фасаду, "
                         "острые пересечения коммуникаций, узкие проходы, точки у самой стены")
    ap.add_argument("--nastier", action="store_true",
                    help="совсем злой: включает --nasty плюс совпадающие спецзоны (дорога и трамвай "
                         "в одном месте), существующая сеть сквозь здание, здание-каре с точкой во дворе, "
                         "MultiPolygon из двух далёких корпусов под одним id, точка с расходом под ДУ1000")
    args = ap.parse_args()
    if args.nastier:
        args.nasty = True
    rnd = random.Random(args.seed)
    ox, oy = ORIGIN
    B, R = args.block, args.road
    n = args.blocks
    size = n * (B + R) + R
    features = []
    fid = [1]

    def nid():
        fid[0] += 1
        return fid[0]

    # дороги: полосы между кварталами (полигоны)
    for i in range(n + 1):
        x = ox + i * (B + R)
        features.append(feat(nid(), "restriction", box(x, oy, x + R, oy + size), restriction_type="road"))
        y = oy + i * (B + R)
        features.append(feat(nid(), "restriction", box(ox, y, ox + size, y + R), restriction_type="road"))

    # здания в кварталах
    buildings = []
    for bi in range(n):
        for bj in range(n):
            x0 = ox + R + bi * (B + R)
            y0 = oy + R + bj * (B + R)
            kind = rnd.random()
            if bi == n // 2 and bj == n // 2:
                features.append(feat(nid(), "restriction", box(x0 + 10, y0 + 10, x0 + B - 10, y0 + B - 10),
                                     restriction_type="park"))
                continue
            if bi == 1 and bj == n - 2:
                features.append(feat(nid(), "restriction", box(x0 + 8, y0 + 8, x0 + B - 8, y0 + B - 8),
                                     restriction_type="social_area", name="школа"))
                continue
            if bi == n - 2 and bj == 1:
                features.append(feat(nid(), "restriction", box(x0 + 20, y0 + 20, x0 + 60, y0 + 60),
                                     restriction_type="metro"))
            # 2–4 здания на квартал, с отступом от дороги 8–15 м
            count = rnd.randint(2, 4)
            for k in range(count):
                w = rnd.uniform(25, 45)
                h = rnd.uniform(15, 35)
                bx = x0 + rnd.uniform(8, B - w - 8)
                by = y0 + rnd.uniform(8, B - h - 8)
                poly = box(bx, by, bx + w, by + h)
                if kind < 0.3 and k == 0:
                    # Г-образное
                    poly = poly.union(box(bx, by, bx + w * 0.4, by + h + 20))
                if any(poly.buffer(6).intersects(q) for q in buildings[-count:] if q is not None):
                    continue
                buildings.append(poly)
                features.append(feat(nid(), "restriction", poly, restriction_type="oks"))

    if args.nasty:
        # 1. пристроенные корпуса (общая стена) и наложенный контур — частый шум реальных данных
        for i, base_poly in enumerate(list(buildings[:6])):
            minx, miny, maxx, maxy = base_poly.bounds
            annex = box(maxx, miny, maxx + 18, miny + (maxy - miny) * 0.6)
            buildings.append(annex)
            features.append(feat(nid(), "restriction", annex, restriction_type="oks"))
            if i % 2 == 0:
                # наложенный дубль контура (территория поверх здания)
                features.append(feat(nid(), "restriction", base_poly.buffer(2), restriction_type="social_area"))
        # 2. дорога вплотную к фасаду: узкая полоса по южной стороне нескольких зданий
        for base_poly in list(buildings[6:9]):
            minx, miny, maxx, maxy = base_poly.bounds
            features.append(feat(nid(), "restriction", box(minx - 30, miny - 8, maxx + 30, miny - 0.2),
                                 restriction_type="road"))
        # 3. коммуникации под острым углом к застройке
        features.append(feat(nid(), "restriction",
                             LineString([(ox, oy + size * 0.15), (ox + size, oy + size * 0.45)]),
                             restriction_type="gas_pipeline"))
        features.append(feat(nid(), "restriction",
                             LineString([(ox, oy + size * 0.75), (ox + size, oy + size * 0.55)]),
                             restriction_type="power_cable"))
        # 4. узкий проход между двумя корпусами (ровно на грани проходимости для DN100)
        gx = ox + R + 2 * (B + R) + 30
        gy = oy + R + 4 * (B + R) + 20
        left = box(gx - 40, gy, gx - 5.3, gy + 60)
        right = box(gx + 5.3, gy, gx + 40, gy + 60)
        buildings.extend([left, right])
        features.append(feat(nid(), "restriction", left, restriction_type="oks"))
        features.append(feat(nid(), "restriction", right, restriction_type="oks"))

    if args.nastier:
        # 5. совпадающие спецзоны: трамвай ровно по оси дороги — §4 требует один спецучасток, Kспец = max
        x_both = ox + 4 * (B + R) + R / 2
        features.append(feat(nid(), "restriction", box(x_both - 2.5, oy, x_both + 2.5, oy + size),
                             restriction_type="tram_tracks"))
        # 6. здание-каре: точка во внутреннем дворе, двор проходим, въезд с севера
        cx = ox + R + 1 * (B + R) + 10
        cy = oy + R + 1 * (B + R) + 10
        ring = box(cx, cy, cx + 90, cy + 90).difference(box(cx + 25, cy + 25, cx + 65, cy + 65))
        ring = ring.difference(box(cx + 36, cy + 65, cx + 54, cy + 95))  # проезд 18 м: проходим с отступом 5 м
        buildings.append(ring)
        features.append(feat(nid(), "restriction", ring, restriction_type="oks"))
        yard = Point(cx + 45, cy + 45)
        features.append(feat(nid(), "oks_connection_point", yard, flow_tph=18.5))
        # 7. два далёких корпуса под одним id (MultiPolygon): отступ действует к обоим
        far_a = box(ox + R + 20, oy + R + 4 * (B + R) + 20, ox + R + 60, oy + R + 4 * (B + R) + 50)
        far_b = box(ox + R + 3 * (B + R) + 20, oy + R + 4 * (B + R) + 20,
                    ox + R + 3 * (B + R) + 60, oy + R + 4 * (B + R) + 50)
        buildings.extend([far_a, far_b])
        features.append(feat(nid(), "restriction", MultiPolygon([far_a, far_b]), restriction_type="oks"))
        # 8. точка с расходом под ДУ1000: отступ к зданиям становится 9 м
        big = box(ox + R + 2 * (B + R) + 20, oy + R + 2 * (B + R) + 20,
                  ox + R + 2 * (B + R) + 70, oy + R + 2 * (B + R) + 60)
        buildings.append(big)
        features.append(feat(nid(), "restriction", big, restriction_type="oks"))
        features.append(feat(nid(), "oks_connection_point", big.representative_point(), flow_tph=8200.0))

    # трамвай — вдоль дороги i=2 (по y), газ — по x между кварталами, кабель — диагонально по улице
    x_tram = ox + 2 * (B + R) + R / 2
    features.append(feat(nid(), "restriction", box(x_tram - 3, oy, x_tram + 3, oy + size), restriction_type="tram_tracks"))
    y_gas = oy + 3 * (B + R) + R / 2 + 4
    features.append(feat(nid(), "restriction", LineString([(ox, y_gas), (ox + size, y_gas)]), restriction_type="gas_pipeline"))
    y_cab = oy + 5 * (B + R) + R / 2 - 4
    features.append(feat(nid(), "restriction", LineString([(ox, y_cab), (ox + size, y_cab)]), restriction_type="power_cable"))
    # река слева, ж/д снизу
    features.append(feat(nid(), "restriction", box(ox - 120, oy - 200, ox - 30, oy + size + 200), restriction_type="water"))
    features.append(feat(nid(), "restriction", box(ox - 200, oy - 80, ox + size + 200, oy - 40), restriction_type="railway"))

    # существующая сеть: магистраль по дороге i = n-1 (вертикальная), камеры каждые 2 квартала
    x_net = ox + (n - 1) * (B + R) + R / 2
    ys = [oy + j * (B + R) + R / 2 for j in range(n + 1)]
    src = Point(x_net, oy + size + 60)
    features.append(feat(nid(), "source", src, name="ТЭЦ синт."))
    prev = src
    for j in reversed(range(len(ys))):
        p = Point(x_net, ys[j])
        features.append(feat(nid(), "heat_network", LineString([prev, p]), diameter=500 if j > n // 2 else 400))
        if j % 2 == 0:
            features.append(feat(nid(), "heat_chamber", p))
        prev = p
    # ответвление по горизонтали в середине
    y_mid = ys[n // 2]
    features.append(feat(nid(), "heat_network", LineString([(x_net, y_mid), (x_net - 3 * (B + R), y_mid)]), diameter=300))
    features.append(feat(nid(), "heat_chamber", Point(x_net - 3 * (B + R), y_mid)))
    if args.nastier:
        # 9. существующая сеть проходит сквозь здание (шум реальных данных): врезка рядом со стеной
        y_thru = ys[1]
        features.append(feat(nid(), "heat_network",
                             LineString([(x_net, y_thru), (x_net - 2 * (B + R), y_thru)]), diameter=300))
        features.append(feat(nid(), "heat_chamber", Point(x_net - 2 * (B + R), y_thru)))
        thru = box(x_net - 1.5 * (B + R) - 20, y_thru - 15, x_net - 1.5 * (B + R) + 20, y_thru + 15)
        buildings.append(thru)
        features.append(feat(nid(), "restriction", thru, restriction_type="oks"))

    # точки подключения внутри зданий
    pool = buildings
    if args.cluster > 0:
        cx0 = ox + size * (1 - args.cluster)
        cy0 = oy + size * (1 - args.cluster)
        pool = [b for b in buildings if b.centroid.x >= cx0 and b.centroid.y >= cy0]
    chosen = rnd.sample(pool, min(args.points, len(pool)))
    for i, b in enumerate(chosen):
        c = b.representative_point()
        features.append(feat(nid(), "oks_connection_point", c, flow_tph=round(rnd.uniform(3, 80), 2)))

    fc = {"type": "FeatureCollection", "name": "synthetic", "features": features}
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(fc, f, ensure_ascii=False)
    print(f"{args.out}: {len(features)} features, {len(buildings)} buildings, {len(chosen)} points")


if __name__ == "__main__":
    main()
