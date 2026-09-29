package ru.lct.heat.routing;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heat.geometry.GeomUtil;
import ru.lct.heat.geometry.ObstacleMap;
import ru.lct.heat.geometry.SegmentCheck;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.Restriction;
import ru.lct.heat.model.ref.CostConstants;
import ru.lct.heat.model.ref.RestrictionRules;
import ru.lct.heat.model.ref.RuleProfile;
import ru.lct.heat.routing.network.NetEdge;
import ru.lct.heat.routing.network.NetNode;
import ru.lct.heat.routing.network.NewNetwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Решётчатое выпрямление готовой сети: каждое ребро перекладывается так, чтобы все его участки шли
 * по восьми направлениям θ + k·45° (θ — ориентация здания для подводящего ребра, иначе доминирующая
 * ориентация застройки рядом), а все повороты были ровно 45° или 90°. Так трасса выглядит как
 * проектная (вдоль стен и улиц, без мелких изломов — Техприложение §2.1, протокол: нестандартные
 * углы нежелательны) при удлинении не более {@link RoutingParams#getRectifyMaxExtraFrac()}.
 *
 * <p>Поиск — A* по решётке с шагом {@link RoutingParams#getRectifyStepM()}, привязанной к концу ребра
 * (точке входа в здание или узлу); от начала ребра до решётки — один-два отрезка по тем же направлениям.
 * Узлы решётки внутри зон отступа запрещены, внутри спецзон — только прямой проход. Результат проверяется
 * точной проверкой отрезков ({@link ObstacleMap#check}) и на зазор к остальным рёбрам; при любой
 * неудаче ребро остаётся как было.
 */
@Slf4j
public final class LatticeRectifier {

    private static final int[] DI = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DJ = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final double SQRT2 = Math.sqrt(2);
    private static final double MIN_SEGMENT_M = 1.0;
    private static final double START_WINDOW_STEPS = 3;
    private static final double ORIENTATION_RADIUS_M = 80.0;
    /** Допуск к лимиту удлинения, м: выпрямление не должно стоить длины (длина весит 0.3 в score). */
    private static final double RECTIFY_SLACK_M = 0.5;

    private final MapCache maps;
    private final ApproachPlanner approach;
    private final RoutingParams params;
    private final RuleProfile profile;
    private final STRtree buildings = new STRtree();

    public LatticeRectifier(MapCache maps, ApproachPlanner approach, RoutingParams params, InputModel input,
                            RuleProfile profile) {
        this.maps = maps;
        this.approach = approach;
        this.params = params;
        this.profile = profile;
        for (Restriction r : input.getRestrictions()) {
            if (RestrictionRules.OKS.equals(profile.canonical(r.getRestrictionType()))
                    && r.getGeometry().getDimension() == 2) {
                buildings.insert(r.getGeometry().getEnvelopeInternal(), r.getGeometry());
            }
        }
        buildings.build();
    }

    /** @return число переложенных рёбер */
    public int rectify(NewNetwork net) {
        if (params.getRectifyMaxExtraFrac() <= 0) {
            return 0;
        }
        long t0 = System.currentTimeMillis();
        int done = 0;
        double before = net.totalLength();
        for (NetEdge e : new ArrayList<>(net.getEdges())) {
            if (rectifyEdge(net, e)) {
                done++;
            }
        }
        if (done > 0) {
            log.info("Выпрямление по решётке: переложено рёбер {}, длина {} → {} м, {} мс", done,
                    Math.round(before), Math.round(net.totalLength()), System.currentTimeMillis() - t0);
        }
        return done;
    }

    private boolean rectifyEdge(NewNetwork net, NetEdge e) {
        List<Coordinate> cs = e.getCoords();
        boolean leafWithApproach = e.getTo().getKind() == NetNode.Kind.OKS_POINT
                && approach.ownPolygonId(e.getTo().getId()).isPresent() && cs.size() >= 3;
        int goalIdx = leafWithApproach ? cs.size() - 2 : cs.size() - 1;
        Coordinate start = cs.get(0);
        Coordinate goal = cs.get(goalIdx);
        if (goalIdx < 1 || start.distance(goal) < 2 * MIN_SEGMENT_M) {
            return false;
        }
        Coordinate after = leafWithApproach ? cs.get(cs.size() - 1) : null;
        double theta = after != null ? Math.atan2(after.y - goal.y, after.x - goal.x) : dominantOrientation(cs);
        if (isStandard(cs, goalIdx, theta)) {
            return false;
        }
        double oldLength = 0;
        for (int i = 0; i < goalIdx; i++) {
            oldLength += cs.get(i).distance(cs.get(i + 1));
        }

        ObstacleMap map = maps.map(e.getDiameter());
        Others others = new Others(net, e);
        Lattice lattice = new Lattice(goal, theta, params.getRectifyStepM());
        Envelope bounds = GeomUtil.line(cs.subList(0, goalIdx + 1)).getEnvelopeInternal();
        bounds.expandBy(Math.max(40.0, 0.3 * oldLength));
        int goalDir = after == null ? -1 : 0; // θ задана направлением goal→after, т.е. направление 0
        Set<Object> exemptLast = leafWithApproach ? approach.exemptIds(e.getTo().getId()) : Collections.emptySet();

        List<Coordinate> path = search(map, others, lattice, start, goal, goalDir, bounds, oldLength);
        if (path == null) {
            log.debug("Ребро {}→{}: решётка не нашла путь (старт {}, цель {}, θ={}°)", e.getFrom(), e.getTo(), start, goal,
                    Math.round(Math.toDegrees(theta)));
            return false;
        }
        double newLength = 0;
        for (int i = 0; i + 1 < path.size(); i++) {
            newLength += path.get(i).distance(path.get(i + 1));
        }
        // прямой косой отрезок лучше «лесенки»: принимаем только без роста числа поворотов (§2.1)
        if (turns(path) > turns(cs.subList(0, goalIdx + 1))) {
            log.debug("Ребро {}→{}: выпрямление добавляет повороты ({} → {}) — оставляем", e.getFrom(), e.getTo(),
                    turns(cs.subList(0, goalIdx + 1)), turns(path));
            return false;
        }
        if (newLength > oldLength * (1 + params.getRectifyMaxExtraFrac()) + RECTIFY_SLACK_M) {
            log.debug("Ребро {}→{}: выпрямление удлиняет {} → {} м — оставляем", e.getFrom(), e.getTo(),
                    Math.round(oldLength), Math.round(newLength));
            return false;
        }
        // точная проверка отрезков + зазор к остальным рёбрам + повороты
        List<Coordinate> full = new ArrayList<>(path);
        if (after != null) {
            full.add(after);
        }
        int oldCrossings = 0;
        for (SegmentCheck c : e.getChecks()) {
            oldCrossings += c.getCrossings().size();
        }
        int newCrossings = 0;
        List<SegmentCheck> checks = new ArrayList<>();
        for (int i = 0; i + 1 < full.size(); i++) {
            boolean last = i == full.size() - 2;
            SegmentCheck chk = map.check(full.get(i), full.get(i + 1), last ? exemptLast : Collections.emptySet(),
                    false, last && e.getTo().getKind() == NetNode.Kind.OKS_POINT);
            if (!chk.isFree() || others.blocked(full.get(i), full.get(i + 1))) {
                log.debug("Ребро {}→{}: отрезок {} выпрямленной трассы не прошёл проверку: {}", e.getFrom(), e.getTo(), i,
                        chk.isFree() ? "зазор к другому участку" : chk.getReason());
                return false;
            }
            checks.add(chk);
            newCrossings += chk.getCrossings().size();
            if (i >= 1 && GeomUtil.turnAngleDeg(full.get(i - 1), full.get(i), full.get(i + 1))
                    > CostConstants.MAX_TURN_ANGLE_DEG + PathFinder.ANGLE_TOL_DEG) {
                return false;
            }
        }
        if (newCrossings > oldCrossings) {
            log.debug("Ребро {}→{}: выпрямление добавляет спецпересечения ({} → {})", e.getFrom(), e.getTo(),
                    oldCrossings, newCrossings);
            return false; // новые спецпересечения — другая стоимость/топология
        }
        cs.clear();
        cs.addAll(full);
        e.getChecks().clear();
        e.getChecks().addAll(checks);
        return true;
    }

    private static int turns(List<Coordinate> cs) {
        int n = 0;
        for (int i = 1; i + 1 < cs.size(); i++) {
            if (GeomUtil.turnAngleDeg(cs.get(i - 1), cs.get(i), cs.get(i + 1)) > 0.5) {
                n++;
            }
        }
        return n;
    }

    /** Все повороты до goalIdx уже кратны 45° и направления совпадают с семейством θ. */
    private static boolean isStandard(List<Coordinate> cs, int goalIdx, double theta) {
        for (int i = 0; i < goalIdx; i++) {
            double dir = Math.atan2(cs.get(i + 1).y - cs.get(i).y, cs.get(i + 1).x - cs.get(i).x);
            double k = (dir - theta) / (Math.PI / 4);
            if (Math.abs(k - Math.round(k)) > 0.005) {
                return false;
            }
        }
        return true;
    }

    /** Доминирующая ориентация застройки рядом с ломаной (взвешенная по длине сторон, по модулю 90°). */
    @SuppressWarnings("unchecked")
    double dominantOrientation(List<Coordinate> cs) {
        Envelope env = GeomUtil.line(cs).getEnvelopeInternal();
        env.expandBy(ORIENTATION_RADIUS_M);
        List<double[]> sides = new ArrayList<>(); // {угол mod 90 в градусах, длина}
        double[] bins = new double[90];
        for (Geometry g : (List<Geometry>) buildings.query(env)) {
            for (int n = 0; n < g.getNumGeometries(); n++) {
                Coordinate[] ring = g.getGeometryN(n).getBoundary().getCoordinates();
                for (int i = 0; i + 1 < ring.length; i++) {
                    double len = ring[i].distance(ring[i + 1]);
                    if (len < 3) {
                        continue;
                    }
                    double deg = Math.toDegrees(Math.atan2(ring[i + 1].y - ring[i].y, ring[i + 1].x - ring[i].x));
                    double mod = ((deg % 90) + 90) % 90;
                    sides.add(new double[]{mod, len});
                    bins[(int) Math.floor(mod) % 90] += len;
                }
            }
        }
        if (sides.isEmpty()) {
            // нет застройки рядом — направление самого длинного отрезка ломаной
            double best = 0;
            double dir = 0;
            for (int i = 0; i + 1 < cs.size(); i++) {
                double len = cs.get(i).distance(cs.get(i + 1));
                if (len > best) {
                    best = len;
                    dir = Math.atan2(cs.get(i + 1).y - cs.get(i).y, cs.get(i + 1).x - cs.get(i).x);
                }
            }
            return dir;
        }
        int bestBin = 0;
        double bestW = -1;
        for (int b = 0; b < 90; b++) {
            double w = bins[(b + 89) % 90] + bins[b] + bins[(b + 1) % 90];
            if (w > bestW) {
                bestW = w;
                bestBin = b;
            }
        }
        // точное значение: взвешенное круговое среднее сторон в ±3° от доминирующего бина (период 90°)
        double sx = 0;
        double sy = 0;
        double center = bestBin + 0.5;
        for (double[] side : sides) {
            double d = side[0] - center;
            d -= 90 * Math.round(d / 90);
            if (Math.abs(d) <= 3) {
                double a = Math.toRadians(side[0] * 4);
                sx += side[1] * Math.cos(a);
                sy += side[1] * Math.sin(a);
            }
        }
        double mean = Math.toDegrees(Math.atan2(sy, sx)) / 4;
        return Math.toRadians(((mean % 90) + 90) % 90);
    }

    /** Решётка: узел (i, j) → goal + step·(i·u + j·v), u = направление θ. */
    private static final class Lattice {
        final Coordinate origin;
        final double step;
        final double ux;
        final double uy;

        Lattice(Coordinate origin, double theta, double step) {
            this.origin = origin;
            this.step = step;
            this.ux = Math.cos(theta);
            this.uy = Math.sin(theta);
        }

        Coordinate at(int i, int j) {
            return new Coordinate(origin.x + step * (i * ux - j * uy), origin.y + step * (i * uy + j * ux));
        }

        /** Единичный вектор направления k (0..7). */
        double[] dir(int k) {
            double len = Math.hypot(DI[k], DJ[k]);
            double dx = DI[k] / len;
            double dy = DJ[k] / len;
            return new double[]{dx * ux - dy * uy, dx * uy + dy * ux};
        }

        /** Координаты решётки (вещественные) для точки. */
        double[] toLattice(Coordinate c) {
            double dx = c.x - origin.x;
            double dy = c.y - origin.y;
            return new double[]{(dx * ux + dy * uy) / step, (-dx * uy + dy * ux) / step};
        }
    }

    private static int turnSteps(int d1, int d2) {
        int d = Math.abs(d1 - d2) % 8;
        return Math.min(d, 8 - d);
    }

    private static long key(int i, int j, int dir) {
        return (((long) (i + 1_000_000)) << 36) | (((long) (j + 1_000_000)) << 4) | (dir & 0xF);
    }

    private static final class State {
        final int i;
        final int j;
        final int dir;
        /** Стоимость (метры + штрафы за повороты). */
        final double g;
        final double f;
        /** Чистая длина, м (для лимита удлинения). */
        final double len;
        final State prev;

        State(int i, int j, int dir, double g, double f, double len, State prev) {
            this.i = i;
            this.j = j;
            this.dir = dir;
            this.g = g;
            this.f = f;
            this.len = len;
            this.prev = prev;
        }
    }

    /**
     * A* по решётке от стартовых состояний (1–2 отрезка от start до узлов решётки рядом) до узла (0,0) = goal.
     * Стоимость — метры плюс штраф за поворот (как в основном поиске). Возвращает ломаную start…goal или null.
     */
    private List<Coordinate> search(ObstacleMap map, Others others, Lattice lat, Coordinate start, Coordinate goal,
                                    int goalDir, Envelope bounds, double oldLength) {
        double turnPenalty = params.getTurnPenaltyMPer45Deg();
        double limit = oldLength * (1 + params.getRectifyMaxExtraFrac()) + RECTIFY_SLACK_M;
        Map<Long, Integer> status = new HashMap<>();
        PriorityQueue<State> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<Long, Double> bestG = new HashMap<>();
        Map<State, List<Coordinate>> prefix = new HashMap<>();

        // стартовые состояния: от start к узлам решётки в окне вокруг него одним или двумя отрезками
        double[] sl = lat.toLattice(start);
        int si = (int) Math.round(sl[0]);
        int sj = (int) Math.round(sl[1]);
        int w = (int) START_WINDOW_STEPS;
        for (int i = si - w; i <= si + w; i++) {
            for (int j = sj - w; j <= sj + w; j++) {
                Coordinate q = lat.at(i, j);
                if (!bounds.contains(q) || nodeStatus(map, lat, status, i, j) != 0) {
                    continue;
                }
                if (q.distance(start) < 1e-3) {
                    // старт совпал с узлом решётки
                    State s = new State(i, j, -1, 0, h(lat, i, j), 0, null);
                    prefix.put(s, Collections.emptyList());
                    open.add(s);
                    continue;
                }
                for (int k = 0; k < 8; k++) {
                    // один отрезок по направлению k
                    double[] d = lat.dir(k);
                    double dx = q.x - start.x;
                    double dy = q.y - start.y;
                    double along = dx * d[0] + dy * d[1];
                    double across = Math.abs(-dx * d[1] + dy * d[0]);
                    if (along > MIN_SEGMENT_M && across < 1e-3 && segmentOk(map, others, start, q)) {
                        State s = new State(i, j, k, along, along + h(lat, i, j), along, null);
                        prefix.put(s, Collections.singletonList(start));
                        open.add(s);
                    }
                    // два отрезка: k, затем k2 (поворот 45°/90°)
                    for (int k2 = 0; k2 < 8; k2++) {
                        int t = turnSteps(k, k2);
                        if (t == 0 || t > 2) {
                            continue;
                        }
                        double[] d2 = lat.dir(k2);
                        double det = d[0] * d2[1] - d[1] * d2[0];
                        if (Math.abs(det) < 1e-9) {
                            continue;
                        }
                        double a = (dx * d2[1] - dy * d2[0]) / det;
                        double b = (d[0] * dy - d[1] * dx) / det;
                        if (a < MIN_SEGMENT_M || b < MIN_SEGMENT_M) {
                            continue;
                        }
                        Coordinate m = new Coordinate(start.x + d[0] * a, start.y + d[1] * a);
                        if (!bounds.contains(m) || map.pointStatus(m, Collections.emptySet()) != 0
                                || !segmentOk(map, others, start, m) || !segmentOk(map, others, m, q)) {
                            continue;
                        }
                        double g = a + b + turnPenalty * t;
                        State s = new State(i, j, k2, g, g + h(lat, i, j), a + b, null);
                        prefix.put(s, List.of(start, m));
                        open.add(s);
                    }
                }
            }
        }
        if (open.isEmpty()) {
            log.debug("Решётка: нет стартовых состояний (окно {} шагов, статус узлов у старта: {})", w,
                    nodeStatus(map, lat, status, si, sj));
            return null;
        }
        int startStates = open.size();

        int expansions = 0;
        State found = null;
        while (!open.isEmpty()) {
            State s = open.poll();
            if (s.len + h(lat, s.i, s.j) > limit) {
                continue;
            }
            long k = key(s.i, s.j, s.dir);
            Double known = bestG.get(k);
            if (known != null && known <= s.g - 1e-9) {
                continue;
            }
            bestG.put(k, s.g);
            if (s.i == 0 && s.j == 0) {
                if (goalDir < 0 || s.dir < 0 || turnSteps(s.dir, goalDir) <= 2) {
                    found = s;
                    break;
                }
                continue;
            }
            if (++expansions > params.getRectifyMaxExpansions()) {
                break;
            }
            int hereStatus = nodeStatus(map, lat, status, s.i, s.j);
            for (int d = 0; d < 8; d++) {
                int t = s.dir < 0 ? 0 : turnSteps(s.dir, d);
                if (t > 2) {
                    continue;
                }
                if (t > 0 && hereStatus == 1) {
                    continue; // поворот внутри спецзоны запрещён
                }
                int ni = s.i + DI[d];
                int nj = s.j + DJ[d];
                Coordinate q = lat.at(ni, nj);
                if (!bounds.contains(q)) {
                    continue;
                }
                int st = nodeStatus(map, lat, status, ni, nj);
                if (st == 2) {
                    continue;
                }
                if (others.contains(q) && q.distance(start) > 3 * others.clearance
                        && q.distance(goal) > 3 * others.clearance) {
                    continue; // вдоль другого нового участка ближе зазора
                }
                if (DI[d] != 0 && DJ[d] != 0) {
                    // диагональ: без срезания углов
                    if (nodeStatus(map, lat, status, s.i + DI[d], s.j) == 2
                            || nodeStatus(map, lat, status, s.i, s.j + DJ[d]) == 2) {
                        continue;
                    }
                }
                double stepLen = lat.step * (DI[d] != 0 && DJ[d] != 0 ? SQRT2 : 1);
                double g = s.g + stepLen + turnPenalty * t;
                long nk = key(ni, nj, d);
                Double kn = bestG.get(nk);
                if (kn != null && kn <= g) {
                    continue;
                }
                open.add(new State(ni, nj, d, g, g + h(lat, ni, nj), s.len + stepLen, s));
            }
        }
        if (found == null) {
            log.debug("Решётка: путь не найден — стартовых состояний {}, раскрытий {}, лимит {} м, цель статус {}",
                    startStates, expansions, Math.round(limit), nodeStatus(map, lat, status, 0, 0));
            return null;
        }
        // восстановление: узлы решётки от старта к цели, слияние коллинеарных
        List<State> chain = new ArrayList<>();
        for (State s = found; s != null; s = s.prev) {
            chain.add(s);
        }
        Collections.reverse(chain);
        List<Coordinate> out = new ArrayList<>(prefix.get(chain.get(0)));
        if (out.isEmpty()) {
            out.add(start);
        }
        List<Coordinate> nodes = new ArrayList<>();
        for (State s : chain) {
            nodes.add(lat.at(s.i, s.j));
        }
        for (int i = 0; i < nodes.size(); i++) {
            boolean last = i == nodes.size() - 1;
            boolean turn = i > 0 && i < nodes.size() - 1 && chain.get(i).dir != chain.get(i + 1).dir;
            if ((i == 0 && chain.get(0).dir >= 0) || last || turn) {
                out.add(nodes.get(i));
            }
        }
        // убираем вырожденные/коллинеарные вершины (старт мог совпасть с узлом)
        List<Coordinate> clean = new ArrayList<>();
        for (Coordinate c : out) {
            if (clean.isEmpty() || clean.get(clean.size() - 1).distance(c) > 1e-6) {
                clean.add(c);
            }
        }
        for (int i = 1; i + 1 < clean.size(); i++) {
            if (GeomUtil.turnAngleDeg(clean.get(i - 1), clean.get(i), clean.get(i + 1)) < 1e-6) {
                clean.remove(i);
                i--;
            }
        }
        clean.set(clean.size() - 1, goal);
        return clean.size() >= 2 ? clean : null;
    }

    private static double h(Lattice lat, int i, int j) {
        int a = Math.abs(i);
        int b = Math.abs(j);
        return lat.step * (Math.max(a, b) + (SQRT2 - 1) * Math.min(a, b));
    }

    private static int nodeStatus(ObstacleMap map, Lattice lat, Map<Long, Integer> cache, int i, int j) {
        long k = key(i, j, 0);
        Integer s = cache.get(k);
        if (s == null) {
            s = map.pointStatus(lat.at(i, j), Collections.emptySet());
            cache.put(k, s);
        }
        return s;
    }

    private static boolean segmentOk(ObstacleMap map, Others others, Coordinate a, Coordinate b) {
        return map.check(a, b, Collections.emptySet()).isFree() && !others.blocked(a, b);
    }

    /** Остальные рёбра сети как препятствия (как в {@link GeometryPolisher}). */
    private final class Others {
        final STRtree index = new STRtree();
        final double clearance;

        Others(NewNetwork net, NetEdge self) {
            clearance = params.getNewPipeClearanceM() + self.getDiameter().getWidthM();
            for (NetEdge o : net.getEdges()) {
                if (o == self) {
                    continue;
                }
                LineString l = GeomUtil.line(o.getCoords());
                Geometry buf = l.buffer(clearance);
                index.insert(buf.getEnvelopeInternal(), new Object[]{l, PreparedGeometryFactory.prepare(buf)});
            }
            index.build();
        }

        @SuppressWarnings("unchecked")
        boolean contains(Coordinate c) {
            org.locationtech.jts.geom.Point p = GeomUtil.point(c);
            for (Object[] o : (List<Object[]>) index.query(new Envelope(c))) {
                if (((PreparedGeometry) o[1]).intersects(p)) {
                    return true;
                }
            }
            return false;
        }

        @SuppressWarnings("unchecked")
        boolean blocked(Coordinate a, Coordinate b) {
            LineString seg = GeomUtil.segment(a, b);
            for (Object[] o : (List<Object[]>) index.query(seg.getEnvelopeInternal())) {
                LineString line = (LineString) o[0];
                PreparedGeometry buf = (PreparedGeometry) o[1];
                if (!buf.intersects(seg)) {
                    continue;
                }
                Geometry inter = line.intersection(seg);
                boolean junction = inter.getDimension() == 0 && inter.getNumGeometries() == 1
                        && (inter.getCoordinate().distance(a) < 1e-6 || inter.getCoordinate().distance(b) < 1e-6);
                if (!junction || seg.intersection(buf.getGeometry()).getLength() > clearance * 4) {
                    return true;
                }
            }
            return false;
        }
    }
}
