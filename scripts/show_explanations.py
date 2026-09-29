"""Показать объяснения из результата: сводка лучшего варианта, разбор стоимости, метрики, причины камер.
python scripts/show_explanations.py data/results/dev-2d.geojson
"""
import json
import sys

r = json.load(open(sys.argv[1], encoding="utf-8"))["features"]
best = None
for f in r:
    p = f["properties"]
    if p["object_type"] == "variant_summary" and p["rank"] == 1:
        best = p["variant_id"]
        print("==", p["variant_id"], "==")
        print(p.get("explanation"))
        print(json.dumps(p.get("cost_breakdown"), ensure_ascii=False, indent=1))
        print(json.dumps(p.get("route_metrics"), ensure_ascii=False))
for f in r:
    p = f["properties"]
    if p["object_type"] == "heat_chamber" and p["variant_id"] == best:
        print(p["id"], "|", p.get("reason", "")[:140])
