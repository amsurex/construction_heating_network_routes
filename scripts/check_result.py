"""Независимая быстрая проверка результата (shapely): пересечения новых участков, отступы от запретных
ограничений (с исключением собственного полигона для финального отрезка), сводка по вариантам.
python scripts/check_result.py input.geojson result.geojson
"""
import json, sys
from shapely.geometry import shape
from shapely.ops import transform
from pyproj import Transformer

TR = Transformer.from_crs(4326, 32637, always_xy=True).transform
HALF = {50:.2,65:.215,80:.235,100:.255,125:.3,150:.325,200:.44,250:.525,300:.575,400:.685,500:.835,
        600:.925,700:1.025,800:1.125,900:1.225,1000:1.325,1200:1.55,1400:1.725}
FORBIDDEN = {"oks","park","social_area","prohibited_site","water","railway"}

inp = json.load(open(sys.argv[1], encoding="utf-8"))["features"]
res = json.load(open(sys.argv[2], encoding="utf-8"))["features"]
forb = [(f["properties"]["id"], f["properties"].get("restriction_type"), transform(TR, shape(f["geometry"]))) for f in inp
        if f["properties"]["object_type"] == "restriction" and f["properties"].get("restriction_type") in FORBIDDEN
        or (f["properties"]["object_type"] == "restriction" and f["properties"].get("restriction_type") not in
            {"road","tram_tracks","gas_pipeline","power_cable"} and f["properties"].get("restriction_type") not in FORBIDDEN)]
points = {f["properties"]["id"]: transform(TR, shape(f["geometry"])) for f in inp if f["properties"]["object_type"] == "oks_connection_point"}
own = {}   # точка → запретные полигоны, содержащие её (здание + территория) и перекрывающие её здание:
           # отступ к ним на финальном отрезке не действует
for pid, p in points.items():
    cov = {fid for fid, t, g in forb if g.covers(p)}
    bld = [g for fid, t, g in forb if fid in cov and t == "oks"]
    if bld:
        cov |= {fid for fid, t, g in forb if fid not in cov and g.intersection(bld[0]).area > 1.0}
    own[pid] = cov
by = {}
for f in res:
    p = f["properties"]
    if p["object_type"] == "heat_network":
        by.setdefault(p["variant_id"], []).append((p, transform(TR, shape(f["geometry"]))))
    if p["object_type"] == "variant_summary":
        print(f'{p["variant_id"]}: rank {p["rank"]} score {p["score"]} length {p["new_network_length"]} m '
              f'cost {p["construction_cost"]/1e6:.1f} M unconnected {p["unconnected_oks_ids"]}')
# финальный отрезок может быть разбит техузлами (спецпроход у здания): исключение тянем по цепочке
# участков через technical_node до точки ОКС
tech = {f["properties"]["id"] for f in res if f["properties"]["object_type"] == "technical_node"}
leaf_of = {}
for vid, lines in by.items():
    nxt = {}
    for p, l in lines:
        nxt.setdefault(p["start_node_id"], []).append(p["end_node_id"])
    for p, l in lines:
        n = p["end_node_id"]
        hops = 0
        while n in tech and len(nxt.get(n, [])) == 1 and hops < 20:
            n = nxt[n][0]
            hops += 1
        if n in points:
            leaf_of[p["id"]] = n
for vid, lines in by.items():
    cross = 0
    for i in range(len(lines)):
        for j in range(i + 1, len(lines)):
            a, b = lines[i][1], lines[j][1]
            if a.crosses(b) or a.overlaps(b):
                cross += 1
                print(f"  CROSS {lines[i][0]['id']} x {lines[j][0]['id']}")
    viol = 0
    for p, l in lines:
        hw = HALF[p["diameter"]]
        exempt = own.get(leaf_of.get(p["id"]), set())
        for fid, t, g in forb:
            if fid in exempt:
                continue
            off = (5 if p["diameter"] < 500 else 7 if p["diameter"] <= 800 else 9) if t == "oks" else 1.0
            d = g.distance(l)
            if d < off + hw - 0.02:
                viol += 1
                print(f"  NEAR {p['id']} {t} #{fid}: {d:.2f} m, need {off + hw:.2f}")
    print(f"{vid}: sections {len(lines)}, special {sum(1 for p,_ in lines if p['laying_method']=='special')}, "
          f"crossings {cross}, clearance violations {viol}")
