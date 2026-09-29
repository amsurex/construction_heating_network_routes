package ru.lct.heat.routing;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import ru.lct.heat.geometry.GeomUtil;
import ru.lct.heat.geometry.ObstacleMap;
import ru.lct.heat.geometry.SegmentCheck;
import ru.lct.heat.model.ref.CostConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Резервный поиск по мелкому растру для стеснённой застройки. Граф видимости строится по вершинам
 * контуров зон отступа: в коридоре шириной 1–2 м между двумя зонами таких вершин не оказывается, и
 * путь, который по правилам существует, не находится (A* упирается в старт). Тогда запускается этот
 * поиск: окно вокруг старта и цели растеризуется с шагом {@code stepM}, клетка свободна, если её центр
 * вне зон отступа и вне спецзон, затем A* по восьми направлениям и «натягивание нити» — ломаная
 * упрощается до минимума вершин, каждый отрезок проверяется точной проверкой {@link ObstacleMap#check}.
 *
 * <p>Применяется только когда обычный поиск не дал пути: иначе трассы стали бы длиннее (растр грубее
 * графа видимости), а время — больше.
 */
@Slf4j
public final class FallbackGridFinder {

    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DY = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final double SQRT2 = Math.sqrt(2);

    private final ObstacleMap map;
    private final RoutingParams params;

    public FallbackGridFinder(ObstacleMap map, RoutingParams params) {
        this.map = map;
        this.params = params;
    }

    /**
     * Путь от start до ближайшей достижимой цели.
     *
     * @param exempt полигоны, отступ к которым не действует (собственные полигоны точки ОКС)
     * @return ломаная от start до цели включительно, либо null
     */
    public List<Coordinate> find(Coordinate start, List<Coordinate> goals, Set<Object> exempt) {
        if (goals.isEmpty() || params.getFallbackGridStepM() <= 0) {
            return null;
        }
        double step = params.getFallbackGridStepM();
        // окно строим вокруг старта и ближайших целей: дальние цели заведомо хуже по стоимости
        List<Coordinate> near = new ArrayList<>(goals);
        near.sort(java.util.Comparator.comparingDouble(g -> g.distance(start)));
        double radius = Math.min(params.getFallbackGridMaxExtentM() / 2,
                near.get(0).distance(start) * 1.6 + params.getFallbackGridMarginM());
        Envelope env = new Envelope(start);
        List<Coordinate> inWindow = new ArrayList<>();
        for (Coordinate g : near) {
            if (g.distance(start) <= radius) {
                inWindow.add(g);
                env.expandToInclude(g);
            }
        }
        if (inWindow.isEmpty()) {
            inWindow.add(near.get(0));
            env.expandToInclude(near.get(0));
        }
        env.expandBy(params.getFallbackGridMarginM());
        double extent = Math.max(env.getWidth(), env.getHeight());
        if (extent > params.getFallbackGridMaxExtentM()) {
            log.debug("Резервный растр: окно {} м больше предела — пропускаем", Math.round(extent));
            return null;
        }
        int nx = (int) Math.ceil(env.getWidth() / step) + 1;
        int ny = (int) Math.ceil(env.getHeight() / step) + 1;
        long cells = (long) nx * ny;
        if (cells > params.getFallbackGridMaxCells()) {
            log.debug("Резервный растр: {} клеток больше предела — пропускаем", cells);
            return null;
        }
        long t0 = System.currentTimeMillis();

        int si = index(start.x, env.getMinX(), step, nx);
        int sj = index(start.y, env.getMinY(), step, ny);
        if (si < 0 || sj < 0) {
            return null;
        }
        // целевые клетки: в них можно войти, даже если центр клетки занят (цель лежит на трубе)
        java.util.Map<Integer, Coordinate> goalCells = new java.util.HashMap<>();
        for (Coordinate g : inWindow) {
            int gi = index(g.x, env.getMinX(), step, nx);
            int gj = index(g.y, env.getMinY(), step, ny);
            if (gi >= 0 && gj >= 0) {
                goalCells.putIfAbsent(gj * nx + gi, g);
            }
        }
        if (goalCells.isEmpty()) {
            return null;
        }

        byte[] free = new byte[nx * ny]; // 0 — не вычислено, 1 — свободна, 2 — занята
        double[] dist = new double[nx * ny];
        Arrays.fill(dist, Double.MAX_VALUE);
        int[] parent = new int[nx * ny];
        Arrays.fill(parent, -1);
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Double.compare(
                Double.longBitsToDouble(a[1]), Double.longBitsToDouble(b[1])));
        int startCell = sj * nx + si;
        dist[startCell] = 0;
        open.add(new long[]{startCell, Double.doubleToLongBits(heuristic(si, sj, goalCells.keySet(), nx, step))});
        int foundCell = -1;
        int expansions = 0;
        while (!open.isEmpty()) {
            long[] cur = open.poll();
            int cell = (int) cur[0];
            if (goalCells.containsKey(cell)) {
                foundCell = cell;
                break;
            }
            if (++expansions > params.getFallbackGridMaxExpansions()) {
                log.debug("Резервный растр: лимит раскрытий {}", expansions);
                break;
            }
            int ci = cell % nx;
            int cj = cell / nx;
            for (int d = 0; d < 8; d++) {
                int ni = ci + DX[d];
                int nj = cj + DY[d];
                if (ni < 0 || nj < 0 || ni >= nx || nj >= ny) {
                    continue;
                }
                int next = nj * nx + ni;
                boolean goalCell = goalCells.containsKey(next);
                int status = goalCell ? 0 : status(free, next, ni, nj, env, step, exempt);
                if (status == 2) {
                    continue;
                }
                int steps = 1;
                if (status == 1) {
                    // спецзона: внутри неё нельзя ни поворачивать, ни идти вдоль — пересекаем одним
                    // прямым отрезком до первой свободной клетки в том же направлении (§4)
                    int k = 2;
                    int limit = (int) Math.ceil(params.getFallbackJumpMaxM() / step);
                    int ji = ni;
                    int jj = nj;
                    boolean landed = false;
                    while (k <= limit) {
                        ji = ci + DX[d] * k;
                        jj = cj + DY[d] * k;
                        if (ji < 0 || jj < 0 || ji >= nx || jj >= ny) {
                            break;
                        }
                        int cand = jj * nx + ji;
                        int st = goalCells.containsKey(cand) ? 0 : status(free, cand, ji, jj, env, step, exempt);
                        if (st == 2) {
                            break;
                        }
                        if (st == 0) {
                            landed = true;
                            break;
                        }
                        k++;
                    }
                    if (!landed) {
                        continue;
                    }
                    Coordinate from = new Coordinate(env.getMinX() + ci * step, env.getMinY() + cj * step);
                    Coordinate to = new Coordinate(env.getMinX() + ji * step, env.getMinY() + jj * step);
                    if (!map.check(from, to, exempt).isFree()) {
                        continue;
                    }
                    ni = ji;
                    nj = jj;
                    next = jj * nx + ji;
                    steps = k;
                } else if (DX[d] != 0 && DY[d] != 0
                        && (status(free, cj * nx + ni, ni, cj, env, step, exempt) == 2
                        || status(free, nj * nx + ci, ci, nj, env, step, exempt) == 2)) {
                    continue; // диагональ без срезания углов
                }
                double nd = dist[cell] + steps * step * (DX[d] != 0 && DY[d] != 0 ? SQRT2 : 1);
                if (nd < dist[next] - 1e-9) {
                    dist[next] = nd;
                    parent[next] = cell;
                    open.add(new long[]{next,
                            Double.doubleToLongBits(nd + heuristic(ni, nj, goalCells.keySet(), nx, step))});
                }
            }
        }
        if (foundCell < 0) {
            log.debug("Резервный растр: путь не найден, раскрытий {}, окно {}x{} клеток, целей {}",
                    expansions, nx, ny, goalCells.size());
            return null;
        }
        List<Coordinate> cellPath = new ArrayList<>();
        for (int cell = foundCell; cell != -1; cell = parent[cell]) {
            int ci = cell % nx;
            int cj = cell / nx;
            cellPath.add(new Coordinate(env.getMinX() + ci * step, env.getMinY() + cj * step));
            if (cell == startCell) {
                break;
            }
        }
        Collections.reverse(cellPath);
        cellPath.set(0, start);
        cellPath.set(cellPath.size() - 1, goalCells.get(foundCell));
        List<Coordinate> simplified = pull(cellPath, exempt);
        if (simplified == null) {
            log.debug("Резервный растр: путь найден ({} клеток), но натягивание нити не прошло проверки",
                    cellPath.size());
            return null;
        }
        log.info("Резервный растр: путь {} м, {} вершин, {} раскрытий, {} мс",
                Math.round(GeomUtil.line(simplified).getLength()), simplified.size(), expansions,
                System.currentTimeMillis() - t0);
        return simplified;
    }

    /**
     * Натягивание нити: из ломаной по клеткам оставляем минимум вершин. Вершина может стоять только вне
     * спецзон (внутри них поворот запрещён, §4); каждый отрезок проходит точную проверку. Жадный проход
     * здесь не годится — «самый дальний допустимый» сосед часто заводит в тупик перед дорогой, поэтому
     * считаем динамику: минимальное число вершин до каждой точки пути.
     */
    private List<Coordinate> pull(List<Coordinate> path, Set<Object> exempt) {
        int n = path.size();
        int reach = Math.min(n - 1, params.getFallbackPullReachCells());
        boolean[] usable = new boolean[n];
        for (int i = 0; i < n; i++) {
            usable[i] = i == 0 || i == n - 1 || map.pointStatus(path.get(i), exempt) == 0;
        }
        int[] steps = new int[n];
        int[] prev = new int[n];
        Arrays.fill(steps, Integer.MAX_VALUE);
        Arrays.fill(prev, -1);
        steps[0] = 0;
        for (int i = 0; i < n; i++) {
            if (steps[i] == Integer.MAX_VALUE || !usable[i]) {
                continue;
            }
            // от дальних к ближним: первый допустимый даёт минимум вершин
            for (int j = Math.min(n - 1, i + reach); j > i; j--) {
                if (!usable[j] || steps[j] <= steps[i] + 1) {
                    continue;
                }
                if (!segmentOk(path.get(i), path.get(j), exempt)) {
                    continue;
                }
                if (prev[i] >= 0 && GeomUtil.turnAngleDeg(path.get(prev[i]), path.get(i), path.get(j))
                        > CostConstants.MAX_TURN_ANGLE_DEG) {
                    continue;
                }
                steps[j] = steps[i] + 1;
                prev[j] = i;
            }
        }
        if (steps[n - 1] == Integer.MAX_VALUE) {
            int reached = 0;
            for (int i = 0; i < n; i++) { if (steps[i] != Integer.MAX_VALUE) { reached = i; } }
            int usableCount = 0;
            for (boolean u : usable) { if (u) { usableCount++; } }
            log.debug("Натягивание: дошли до вершины {} из {} (годных вершин {})", reached, n, usableCount);
            return null;
        }
        List<Coordinate> out = new ArrayList<>();
        for (int i = n - 1; i >= 0; i = prev[i]) {
            out.add(path.get(i));
            if (i == 0) {
                break;
            }
        }
        Collections.reverse(out);
        return out.size() >= 2 ? out : null;
    }

    private boolean segmentOk(Coordinate a, Coordinate b, Set<Object> exempt) {
        return a.distance(b) >= 1e-9 && map.check(a, b, exempt).isFree();
    }

    /** Статус клетки: 0 — свободна, 1 — в спецзоне (только сквозное пересечение), 2 — запрет. */
    private int status(byte[] cache, int cell, int i, int j, Envelope env, double step, Set<Object> exempt) {
        if (cache[cell] == 0) {
            Coordinate c = new Coordinate(env.getMinX() + i * step, env.getMinY() + j * step);
            cache[cell] = (byte) (map.pointStatus(c, exempt) + 1);
        }
        return cache[cell] - 1;
    }

    /** Октильное расстояние до ближайшей целевой клетки. */
    private static double heuristic(int i, int j, java.util.Set<Integer> goalCells, int nx, double step) {
        double best = Double.MAX_VALUE;
        for (int cell : goalCells) {
            int dx = Math.abs(i - cell % nx);
            int dy = Math.abs(j - cell / nx);
            best = Math.min(best, step * (Math.max(dx, dy) + (SQRT2 - 1) * Math.min(dx, dy)));
        }
        return best;
    }

    private static int index(double v, double min, double step, int n) {
        int i = (int) Math.round((v - min) / step);
        return i < 0 || i >= n ? -1 : i;
    }
}
