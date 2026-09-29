"""Картинки мини-кейсов.
python scripts/plot_cases.py    (после ./mvnw test -Dcases=true -Dtest=CasesTest)
"""
import json
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from pyproj import Transformer
from shapely.geometry import shape
from shapely.ops import transform

TR = Transformer.from_crs(4326, 32637, always_xy=True).transform
COLORS = {"oks": "#c8c8c8", "water": "#9ecae1", "railway": "#bcbddc", "park": "#a1d99b", "road": "#fdd0a2",
          "tram_tracks": "#fdae6b", "gas_pipeline": "#e6550d", "power_cable": "#756bb1", "social_area": "#fa9fb5"}
HALF = {50: .2, 65: .215, 80: .235, 100: .255, 125: .3, 150: .325, 200: .44, 250: .525, 300: .575, 400: .685, 500: .835}
CASES = Path("data/cases")
DOCS = Path("docs/cases")


def geoms(path):
    return [(f["properties"], transform(TR, shape(f["geometry"])) if f["geometry"] else None)
            for f in json.load(open(path, encoding="utf-8"))["features"]]


def draw_case(case):
    name = case["name"]
    inp = geoms(CASES / f"{name}.geojson")
    out = geoms(CASES / "out" / f"{name}.geojson")
    best = next(p for p, g in out if p["object_type"] == "variant_summary" and p["rank"] == 1)
    vid = best["variant_id"]
    depth = case["mode"] == "DEPTH_3D"
    fig, axes = plt.subplots(1, 2 if depth else 1, figsize=(13 if depth else 8, 7))
    ax = axes[0] if depth else axes
    for p, g in inp:
        t = p["object_type"]
        if t == "restriction":
            col = COLORS.get(p["restriction_type"], "#ff00ff")
            if g.geom_type.endswith("Polygon"):
                for q in (g.geoms if hasattr(g, "geoms") else [g]):
                    ax.fill(*q.exterior.xy, color=col, alpha=.85, zorder=1)
                    for h in q.interiors:
                        ax.fill(*h.xy, color="white", zorder=1.1)
                    if p["restriction_type"] == "oks":
                        b = q.buffer(5.255)
                        ax.plot(*b.exterior.xy, color="#d62728", lw=.6, ls="--", zorder=1.2)
            else:
                ax.plot(*g.xy, color=col, lw=2.5, zorder=2)
            c = g.centroid
            ax.annotate(p["restriction_type"], (c.x, c.y), fontsize=7, ha="center", color="#555")
        elif t == "heat_network":
            ax.plot(*g.xy, color="#d62728", lw=3, zorder=3)
        elif t == "heat_chamber":
            ax.plot(g.x, g.y, "s", color="#d62728", ms=8, zorder=5)
            ax.annotate(p["id"], (g.x, g.y), fontsize=7, color="#d62728", xytext=(4, -10), textcoords="offset points")
        elif t == "source":
            ax.plot(g.x, g.y, "*", color="black", ms=14, zorder=6)
        elif t == "oks_connection_point":
            ax.plot(g.x, g.y, "o", color="#2ca02c", ms=8, zorder=6)
            ax.annotate(f'{p["id"]} ({p["flow_tph"]} т/ч)', (g.x, g.y), fontsize=7, color="#2ca02c",
                        xytext=(5, 5), textcoords="offset points")
    for p, g in out:
        if g is None or p["variant_id"] != vid:
            continue
        t = p["object_type"]
        if t == "heat_network":
            col = "#ff7f0e" if p["laying_method"] == "special" else "#1f77b4"
            ax.plot(*g.xy, color=col, lw=2.5, zorder=4)
            for x, y in g.coords:
                ax.plot(x, y, ".", color=col, ms=5, zorder=4.5)
            m = g.interpolate(0.5, normalized=True)
            label = f'DN{p["diameter"]} {p["length"]:.0f} м' + (f' K={p["k_special"]}' if p["k_special"] > 1 else "")
            ax.annotate(label, (m.x, m.y), fontsize=7, color=col, xytext=(4, 4), textcoords="offset points")
        elif t == "heat_chamber":
            ax.plot(g.x, g.y, "D", color="#1f77b4", ms=8, zorder=5)
        elif t == "technical_node":
            ax.plot(g.x, g.y, "^", color="#ff7f0e", ms=7, zorder=5)
    ax.set_aspect("equal")
    ax.grid(alpha=.3)
    ax.set_title(f'{name}: {case["title"]}', fontsize=10)
    ax.set_xlabel("м (UTM 37N)")
    if depth:
        ax2 = axes[1]
        # продольный профиль: участки по порядку от корня
        pipes = [(p, g) for p, g in out if p["object_type"] == "heat_network" and p["variant_id"] == vid]
        order = []
        nxt = {p["start_node_id"]: (p, g) for p, g in pipes}
        node = next(p["start_node_id"] for p, g in pipes if p["start_node_id"] not in {q["end_node_id"] for q, _ in pipes})
        s = 0
        while node in nxt:
            p, g = nxt[node]
            ax2.plot([s, s + p["length"]], [-p["depth_start"], -p["depth_end"]],
                     color="#ff7f0e" if p["laying_method"] == "special" else "#1f77b4", lw=2.5)
            s += p["length"]
            node = p["end_node_id"]
        gas = next((g for p, g in inp if p["object_type"] == "restriction" and p["restriction_type"] == "gas_pipeline"), None)
        if gas is not None:
            ax2.add_patch(plt.Rectangle((s / 2 - 0.2, -3.2), 0.4, 0.4, color="#e6550d"))
            ax2.annotate("газ 2.8–3.2 м", (s / 2 + 1, -3.0), fontsize=8, color="#e6550d")
        ax2.axhline(-3.0, color="#999", ls="--", lw=.8)
        ax2.set_xlabel("м вдоль трассы")
        ax2.set_ylabel("глубина верха габарита, м")
        ax2.set_title("Продольный профиль (DEPTH_3D)", fontsize=10)
        ax2.set_ylim(-3.6, 0)
        ax2.grid(alpha=.3)
    fig.tight_layout()
    fig.savefig(DOCS / f"{name}.png", dpi=110)
    plt.close(fig)
    return best


def main():
    DOCS.mkdir(parents=True, exist_ok=True)
    cases = json.load(open(CASES / "cases.json", encoding="utf-8"))
    for case in cases:
        draw_case(case)
    print(f"{len(cases)} cases -> {DOCS}")


if __name__ == "__main__":
    main()
