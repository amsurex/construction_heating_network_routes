#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Корпус наборов для фаззинга ядра: 20 обычных + 22 «злых».

Наборы не хранятся в репозитории (генерируются детерминированно по сидам), поэтому отчёт
Отчёт фаззинга воспроизводится в две команды:

    python3 scripts/make_fuzz_sets.py
    ./mvnw test -Dfuzz=true -Dtest=FuzzTest

Обычные наборы — регулярная застройка с дорогами и коммуникациями. «Злые» (`--nasty`) добавляют то,
чего нет в конкурсном датасете, но что есть в реальных данных: пристроенные и наложенные корпуса,
дорогу вплотную к фасаду, коммуникации под острым углом, проход ровно на грани проходимости.
Варьируются размер квартала и ширина дороги, часть наборов — с кучным расположением точек.
"""
import argparse
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, "make_synthetic.py")


def plan():
    """(имя, аргументы) для каждого набора; порядок и значения фиксированы — это и есть корпус."""
    out = []
    for i in range(1, 21):
        blocks = (i % 6) + 4
        points = ((7 * (i - 1) + 6) % 30) + 9
        out.append(("s%02d" % i, ["--blocks", blocks, "--points", points, "--seed", i]))
    for i in range(21, 37):
        blocks = (i % 6) + 4
        points = (i % 11) + 9
        args = ["--blocks", blocks, "--points", points, "--seed", i, "--nasty",
                "--block", 70 + (i % 5) * 25, "--road", 10 + (i % 4) * 6]
        out.append(("n%02d" % i, args))
    for i in range(37, 43):
        points = (i % 9) + 12
        args = ["--blocks", 6, "--points", points, "--seed", i, "--nasty",
                "--cluster", 0.7, "--block", 90]
        out.append(("c%02d" % i, args))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="data/synthetic/fuzz", help="куда складывать наборы")
    ap.add_argument("--clean", action="store_true", help="сначала удалить старые наборы из каталога")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    if args.clean:
        for f in os.listdir(args.out):
            if f.endswith(".geojson"):
                os.remove(os.path.join(args.out, f))

    sets = plan()
    for name, extra in sets:
        blocks = extra[extra.index("--blocks") + 1]
        points = extra[extra.index("--points") + 1]
        dst = os.path.join(args.out, "%s_b%s_p%s.geojson" % (name, blocks, points))
        cmd = [sys.executable, GEN, dst] + [str(a) for a in extra]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0:
            sys.stderr.write(r.stdout + r.stderr)
            return r.returncode
        print(r.stdout.strip().splitlines()[-1])
    print("готово: %d наборов в %s" % (len(sets), args.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
