"""Dev-визуализация: вход (+ опционально результат) в UTM 37N → PNG.
Использование: python scripts/plot_geojson.py input.geojson [result.geojson] out.png
"""
import json, sys
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from shapely.geometry import shape
from shapely.ops import transform
from pyproj import Transformer

TR = Transformer.from_crs(4326, 32637, always_xy=True).transform
COLORS = {"oks": "#c8c8c8", "water": "#9ecae1", "railway": "#bcbddc", "park": "#a1d99b",
          "road": "#fdd0a2", "tram_tracks": "#fdae6b", "gas_pipeline": "#e6550d", "power_cable": "#756bb1",
          "social_area": "#fa9fb5", "prohibited_site": "#e7969c"}

def load(path):
    return json.load(open(path, encoding="utf-8"))["features"]

def draw_poly(ax, g, **kw):
    polys = g.geoms if hasattr(g, "geoms") else [g]
    for p in polys:
        ax.fill(*p.exterior.xy, **kw)
        for h in p.interiors:
            ax.fill(*h.xy, color="white", zorder=kw.get("zorder", 1) + 0.1)

def main():
    inp = sys.argv[1]; res = sys.argv[2] if len(sys.argv) > 3 else None; out = sys.argv[-1]
    fig, ax = plt.subplots(figsize=(16, 13))
    for f in load(inp):
        p = f["properties"]; g = transform(TR, shape(f["geometry"])); t = p["object_type"]
        if t == "restriction":
            rt = p.get("restriction_type", "?")
            c = COLORS.get(rt, "#ff00ff")
            if g.geom_type.endswith("Polygon"):
                draw_poly(ax, g, color=c, alpha=0.8, zorder=1)
            else:
                for l in (g.geoms if hasattr(g, "geoms") else [g]): ax.plot(*l.xy, color=c, lw=2, zorder=2)
        elif t == "heat_network":
            ax.plot(*g.xy, color="#d62728", lw=2.5, zorder=3)
        elif t == "heat_chamber":
            ax.plot(g.x, g.y, "s", color="#d62728", ms=7, zorder=5)
            ax.annotate(str(p["id"]), (g.x, g.y), fontsize=6, color="#d62728", xytext=(3, 3), textcoords="offset points")
        elif t == "source":
            ax.plot(g.x, g.y, "*", color="black", ms=18, zorder=6)
        elif t == "oks_connection_point":
            ax.plot(g.x, g.y, "o", color="#2ca02c", ms=7, zorder=6)
            ax.annotate(f'{p["id"]} ({p["flow_tph"]})', (g.x, g.y), fontsize=7, color="#2ca02c", xytext=(4, 4), textcoords="offset points")
    if res:
        vid = None
        for f in load(res):
            p = f["properties"]; t = p["object_type"]
            if f["geometry"] is None: continue
            if vid is None: vid = p["variant_id"]
            if p["variant_id"] != vid: continue
            g = transform(TR, shape(f["geometry"]))
            if t == "heat_network":
                col = "#ff7f0e" if p.get("laying_method") == "special" else "#1f77b4"
                ax.plot(*g.xy, color=col, lw=2, zorder=4)
                mx, my = g.interpolate(0.5, normalized=True).coords[0]
                ax.annotate(f'DN{p["diameter"]}', (mx, my), fontsize=6, color=col)
            elif t == "heat_chamber":
                ax.plot(g.x, g.y, "D", color="#1f77b4", ms=6, zorder=5)
            elif t == "technical_node":
                ax.plot(g.x, g.y, "^", color="#ff7f0e", ms=5, zorder=5)
    ax.set_aspect("equal"); ax.grid(alpha=0.3)
    fig.tight_layout(); fig.savefig(out, dpi=130)
    print("saved", out)

main()
