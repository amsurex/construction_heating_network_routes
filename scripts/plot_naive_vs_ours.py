import json
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from pyproj import Transformer
from shapely.geometry import shape
from shapely.ops import transform

TR = Transformer.from_crs(4326, 32637, always_xy=True).transform
COLORS = {"oks": "#c8c8c8", "water": "#9ecae1", "railway": "#bcbddc", "park": "#a1d99b",
          "road": "#fdd0a2", "tram_tracks": "#fdae6b", "gas_pipeline": "#e6550d",
          "power_cable": "#756bb1", "social_area": "#fa9fb5", "prohibited_site": "#e7969c"}


def features(path):
    return json.load(open(path, encoding="utf-8"))["features"]


def draw_poly(ax, g, **kw):
    for p in (g.geoms if hasattr(g, "geoms") else [g]):
        ax.fill(*p.exterior.xy, **kw)
        for h in p.interiors:
            ax.fill(*h.xy, color="white", zorder=kw.get("zorder", 1) + 0.1)


def best(path):
    """Сводка лучшего варианта и его id."""
    sums = [f["properties"] for f in features(path)
            if f["properties"].get("object_type") == "variant_summary"]
    return min(sums, key=lambda s: s["score"])


def panel(ax, inp, result, title, summary):
    for f in inp:
        p = f["properties"]
        g = transform(TR, shape(f["geometry"]))
        t = p["object_type"]
        if t == "restriction":
            c = COLORS.get(p.get("restriction_type", "?"), "#ff00ff")
            if g.geom_type.endswith("Polygon"):
                draw_poly(ax, g, color=c, alpha=0.75, zorder=1)
            else:
                for l in (g.geoms if hasattr(g, "geoms") else [g]):
                    ax.plot(*l.xy, color=c, lw=2, zorder=2)
        elif t == "heat_network":
            ax.plot(*g.xy, color="#444444", lw=2.0, zorder=3)
        elif t == "heat_chamber":
            ax.plot(g.x, g.y, "s", color="#444444", ms=5, zorder=4)
        elif t == "oks_connection_point":
            ax.plot(g.x, g.y, "o", color="#1f77b4", ms=6, zorder=5)

    vid = summary["variant_id"]
    unconnected = {str(i) for i in summary.get("unconnected_oks_ids", [])}
    for f in result:
        p = f["properties"]
        if p.get("variant_id") != vid:
            continue
        g = transform(TR, shape(f["geometry"])) if f["geometry"] else None
        if p["object_type"] == "heat_network":
            ax.plot(*g.xy, color="#d62728", lw=2.6, zorder=6)
        elif p["object_type"] == "heat_chamber":
            ax.plot(g.x, g.y, "s", color="#d62728", ms=7, zorder=7)
    for f in inp:
        p = f["properties"]
        if p.get("object_type") == "oks_connection_point" and str(p["id"]) in unconnected:
            g = transform(TR, shape(f["geometry"]))
            ax.plot(g.x, g.y, "x", color="#000000", ms=12, mew=3, zorder=8)

    penalty = summary.get("unconnected_penalty", 0)
    line = "%.0f м · %.1f млн ₽ · score %.2f" % (
        summary["new_network_length"], summary["construction_cost"] / 1e6, summary["score"])
    if penalty:
        line += "\nне подключено точек: %d (штраф %.0f млн ₽)" % (len(unconnected), penalty / 1e6)
    else:
        line += "\nподключены все точки"
    ax.set_title(title + "\n" + line, fontsize=13)
    ax.set_aspect("equal")
    ax.axis("off")


def main():
    naive_path, ours_path, out = sys.argv[1], sys.argv[2], sys.argv[3]
    inp = features("data/samples/dataset.geojson")
    fig, axes = plt.subplots(1, 2, figsize=(18, 9))
    panel(axes[0], inp, features(naive_path), "Каждая точка отдельной трассой", best(naive_path))
    panel(axes[1], inp, features(ours_path), "Общие магистрали (наше решение)", best(ours_path))
    fig.tight_layout()
    fig.savefig(out, dpi=110, bbox_inches="tight")
    print("записано", out)


if __name__ == "__main__":
    main()
